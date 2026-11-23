#!/usr/bin/env bash
# =============================================================================
# run-examples.sh — AC#1/AC#2 de PKH-143: los cuatro ejemplos agenticos corren
# contra el PipelineK DISTRIBUIDO con el artefacto del plugin nominado, y se
# verifican sobre el journal durable (no sobre el exit code).
# =============================================================================
# El host es el del SDK con el que el plugin compilo (scripts/host-sdk.sh): el
# mismo par que paga la puerta del repo. El artefacto es el operator jar; si no
# existe, esta fila lo construye (es el mismo `operatorJar` que emite el manifest).
# Las capturas de cada run (journal volcado + observaciones del CLI) se escriben
# en PKH143_RECEIPTS para que el recibo sobreviva al arbol efimero de la sesion.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh

sdk_version="$(acp_sdk_version)"
acp_use_host "$sdk_version"
host_version="$(acp_host_version)"
JAR="build/libs/pipelinek-acp-0.1.0-operator.jar"
receipts="${PKH143_RECEIPTS:-/var/tmp/pkh143-recibos}"
state="build/pkh143-examples"
mkdir -p "$state" "$receipts"
if [ ! -f "$JAR" ]; then
  gradle_bin="${GRADLE_BIN:-$HOME/.asdf/installs/gradle/8.14.5/bin/gradle}"
  export JAVA_HOME="${ACP_JAVA_HOME:-$HOME/.asdf/installs/java/temurin-21.0.8+9.0.LTS}"
  systemd-run --user --scope --slice=dev-jvm.slice \
    "$gradle_bin" -q operatorJar -PsdkRepo="$ACP_SDK_BUNDLE" -PsdkVersion="$sdk_version" \
    -PpipelinekHostVersion="$host_version"
fi
test -f "$JAR"

# AC#2: el CLI distribuido admite el artefacto; la version del host se mide, no se
# declara. `sdk-info` dice contra que SDK se compila el distribuido del que dispone
# la maquina; `capabilities` confirma que el servidor del que habla este ejecutable
# conoce las calles de servicio que despues va a pagar AC#3.
{
  echo "== pipelinek version =="; pipelinek version
  echo "== pipelinek capabilities (schema) =="; pipelinek capabilities | head -c 400; echo
  echo "== pipelinek sdk-info =="; pipelinek sdk-info 2>&1 | head -c 600; echo
} > "$receipts/ac2-cli-distribuido.log" 2>&1

rm -f "$state"/0*.sqlite "$state"/0*.sqlite-wal "$state"/0*.sqlite-shm
rm -rf "$state"/0*-control
rm -f "$state"/01-review.witness "$state"/03-a.witness "$state"/03-b.witness
rm -rf "$state/repair-archive"
# El proyecto del ejemplo 02 debe estar ROTO: es el punto de partida del repair.
if ! grep -q "sum(xs) - 1" examples/02-agentic-repair/project/main.py; then
  cat > examples/02-agentic-repair/project/main.py <<'PY'
def total(xs):
    # Broken on purpose (PKH-143 example): the repair agent must fix exactly this.
    return sum(xs) - 1
PY
fi

run_example() {
  local n="$1" script="$2"
  pipelinek run --workspace . \
    --db "$state/$n.sqlite" --control-root "$state/$n-control" \
    --plugin-jar "$JAR" \
    "$script" > "$receipts/ac1-$n-run.log" 2>&1 || true
}

run_example 01 examples/01-readonly-review/review.pipeline.kts
run_example 02 examples/02-agentic-repair/repair.pipeline.kts
run_example 03 examples/03-two-reviewers/two-reviewers.pipeline.kts
run_example 04 examples/04-mcp-gateway/mcp-gateway.pipeline.kts

python3 -I - "$state" "$receipts" <<'PY'
import json, os, sqlite3, sys

state, receipts = sys.argv[1], sys.argv[2]

def outputs(db):
    path = os.path.join(state, db)
    con = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    rows = [
        json.loads(out)["result"]
        for (out,) in con.execute("select output from operation_journal where output is not null")
        if json.loads(out).get("result")
    ]
    con.close()
    return [json.loads(r) for r in rows]

def dump(name, payload):
    with open(os.path.join(receipts, name), "w") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)

# --- (a) revision de SOLO LECTURA: dos turnos, una sesion, contexto que viaja.
review = outputs("01.sqlite")
assert review, "01: el journal no lleva ninguna salida tipada"
turns = review[-1]
assert turns.get("ok") is True, "01: la sesion fue rechazada: %s %s" % (
    turns.get("failureKind"), turns.get("detail"))
assert turns.get("outcome") == "COMPLETED", "01: outcome inesperado %r" % turns.get("outcome")
replies = [t.get("reply", "") for t in turns.get("turns", [])]
assert len(replies) == 2, "01: se debieron responder dos turnos: %r" % replies
assert "review module A" in replies[1] and "summarize what you saw" in replies[1], (
    "01: el segundo turno debe ecoar AMBAS preguntas (la historia de sesion viajo): %r" % replies[1])
witness = open(os.path.join(state, "01-review.witness")).read()
assert "DELETE" not in witness, "01: la salida normal nunca borra historial: %r" % witness
dump("ac1-01-readonly.json", turns)
print("example 01 (read-only review): OK")

# --- (b) test-repair acotado: verificacion independiente, candidato + patch.
repair = outputs("02.sqlite")[-1]
assert repair.get("ok") is True, "02: el repair no cerro: %s %s" % (
    repair.get("outcome"), repair.get("detail"))
ev = [e.get("phase") or e.get("status") or json.dumps(e) for e in repair.get("evidence", [])]
assert repair.get("agentTurns", 0) >= 1, "02: el agente nunca intervino: %r" % repair
patch = repair.get("patchPath", "")
assert patch and os.path.exists(patch), "02: sin patch candidato archivado no hay recipe completa (%r)" % patch
report = repair.get("reportPath", "")
assert report, "02: el informe JSON no esta en la salida"
main_now = open("examples/02-agentic-repair/project/main.py").read()
assert "sum(xs) - 1" not in main_now, "02: main.py sigue roto tras un repair ok"
dump("ac1-02-repair.json", repair)
print("example 02 (bounded test-repair): OK (patch=%s report=%s)" % (
    repair.get("patchPath"), report))

# --- (c) dos revisores independientes: sin contexto ni efectos compartidos.
rev = outputs("03.sqlite")
a, b = rev[0], rev[1]
assert a.get("ok") and b.get("ok"), "03: ambos turnos deben completar"
seen_a = a.get("reply", "")
seen_b = b.get("reply", "")
assert "review A" in seen_a and "review B" not in seen_a, (
    "03: el revisor A no debe ver la pregunta de B: %r" % seen_a)
assert "review B" in seen_b and "review A" not in seen_b, (
    "03: el revisor B no debe ver la pregunta de A: %r" % seen_b)
keys_a = [r["eventKey"]["canonical"] for r in a.get("reactionLedger", [])]
keys_b = [r["eventKey"]["canonical"] for r in b.get("reactionLedger", [])]
assert keys_a and keys_b, "03: cada sesion debo journalizar su ledger de reaccion"
assert not (set(keys_a) & set(keys_b)), (
    "03: dos sesiones independientes no pueden compartir EventKey: %r %r" % (keys_a, keys_b))
assert len(set(keys_a + keys_b)) == len(keys_a) + len(keys_b), (
    "03: ni un efecto duplicado dentro ni entre sesiones")
tokens_a = {k.split(":")[1] for k in keys_a}
tokens_b = {k.split(":")[1] for k in keys_b}
assert tokens_a.isdisjoint(tokens_b), "03: el nonce es por conexion: %r vs %r" % (tokens_a, tokens_b)
dump("ac1-03-two-reviewers.json", {"a": a, "b": b})
print("example 03 (two independent reviewers): OK (%d+%d ledger rows, claves disjuntas)" % (
    len(keys_a), len(keys_b)))

# --- (d) gateway MCP: el fixture llama una herramienta de sesion.
mcp = outputs("04.sqlite")[-1]
assert mcp.get("ok") is True, "04: el turno con gateway fue rechazado: %s %s" % (
    mcp.get("failureKind"), mcp.get("detail"))
reply = mcp.get("reply", "")
assert "tools:runTests" in reply, "04: la lista del gateway debe llevar la herramienta: %r" % reply
assert "first:executed" in reply, "04: la llamada valida debe despacharse: %r" % reply
assert "bad:-32602" in reply, "04: el valor fuera de schema se niega en el alambre: %r" % reply
assert "again:already_acknowledged" in reply, "04: la repeticion con la misma clave no reinvoca: %r" % reply
statuses = [c.get("status") for c in mcp.get("exposedToolCalls", [])]
assert statuses == ["executed", "schema_denied", "already_acknowledged"], (
    "04: cada llamada quedo en la evidencia tipada: %r" % statuses)
tool_rows = [r for r in mcp.get("reactionLedger", []) if r.get("reactionId") == "runTests"]
assert [r.get("phase") for r in tool_rows] == ["INTENT", "ACKED"], (
    "04: una invocacion, un par intent/ack: %r" % tool_rows)
port = mcp.get("mcpGatewayUrl", "")
assert port.startswith("http://127.0.0.1:"), "04: la URL del gateway viajo en la sesion: %r" % port
dump("ac1-04-mcp-gateway.json", mcp)
print("example 04 (MCP session gateway): OK (gateway %s, caducado tras el turno)" % port)
PY

echo "run-examples: los cuatro ejemplos pasaron contra pipelinek $host_version (SDK $sdk_version)"
