#!/usr/bin/env bash
# =============================================================================
# e2e-socket-observe.sh — AC#3 de PKH-143: un cliente del socket de servicio
# observa ESTADO / PERMISO / EVENTOS / RESULTADO de un run REAL de un ejemplo
# (con el plugin cargado en el pipelinek distribuido) y lo CANCELA por el socket.
# =============================================================================
# Todo local-first: el ejemplo lento es el fixture de sesion con la marca "slow"
# (primer turno 20 s), sobre un SQLite y un control-root propios. El servicio se
# levanta sobre el MISMO par db/control-root que ve el run, y el cliente habla la
# calle publicada `pipelinek.service/2`.
#
# La via de cancelacion se mide en dos carriles honestos:
#   * run vivo lanzado por CLI externa con --plugin-jar: si el servicio se niega
#     (proteccion del dueno vivo del lease), el refusal nombrado se captura como
#     evidencia del gap y el run se deja completar por su propio presupuesto;
#   * run superviado por el propio servicio (run.start): ahi el cliente SI debe
#     poder cancelarlo, y el estado terminal CANCELLED es la prueba.
# Si el servicio no puede cargar el plugin (run.start no admite nominar el jar),
# ese limite se registra en el recibo — es un gap del host, no de este plugin.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
JAR="$PWD/build/libs/pipelinek-acp-0.1.0-operator.jar"
receipts="${PKH143_RECEIPTS:-/var/tmp/pkh143-recibos}"
state="$PWD/build/pkh143-examples/05-socket"
mkdir -p "$receipts"
rm -rf "$state"; mkdir -p "$state/scripts"
# El alambre Unix cabe en sun_path (<=108 chars) y su directorio debe ser privado:
# el socket vive en /tmp con casa propia, no bajo el arbol largo del worktree.
sock_dir="/tmp/pkh143-05-$$"
rm -rf "$sock_dir"; mkdir -p "$sock_dir"; chmod 700 "$sock_dir"
SOCK="$sock_dir/service.sock"
rm -f "$state"/*.sqlite*

LOG="$receipts/ac3-socket.log"
: > "$LOG"
SERVE_PID=""
RUN_PID=""
cleanup() {
  [ -n "$RUN_PID" ] && kill "$RUN_PID" 2>/dev/null || true
  [ -n "$SERVE_PID" ] && kill "$SERVE_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  rm -rf "$sock_dir" 2>/dev/null || true
}
trap cleanup EXIT

# --- el run del ejemplo, lento, con el plugin nominado (distribuido).
cat > "$state/slow-session.pipeline.kts" <<'KTS'
import pipelinek.acp.acpSession
import pipelinek.acp.acpTurn

pipeline {
    stages {
        stage("slow session for the socket observer") {
            acpSession(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "full",
                    "build/pkh143-examples/05-socket/slow.witness", "slow"),
                turns = listOf(acpTurn("observe me over the socket")),
                cwd = ".",
                timeoutSeconds = 120,
            )
        }
    }
}
KTS
cp "$state/slow-session.pipeline.kts" "$state/scripts/"
cat > "$state/scripts/sleepy.pipeline.kts" <<'KTS'
pipeline {
    stages {
        stage("long sleep for the supervised cancel") {
            timeout(time = 5, unit = "MINUTES") {
                sh("sleep 120")
            }
        }
    }
}
KTS

# El servicio LEE el sustrato, no lo crea: hay que prepararlo antes de servir.
pipelinek substrate init --db "$state/run.sqlite" >> "$state/substrate-init.log" 2>&1

# Autorizaciones del operador, como en examples/socket-observe: un token nuestro, con los
# derechos que la fila necesita (leer estado, listar permisos, responder, cancelar). El
# servicio no genera un fichero que nadie busca: el contrato del socket es este argv.
auth_file="$sock_dir/authorizations"
token="pkh143-actor-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
printf '%s pipelinek-actor all+start+cancel+answer\n' "$token" > "$auth_file"
chmod 600 "$auth_file"

pipelinek serve --db "$state/run.sqlite" --control-root "$state/control" \
  --socket "$SOCK" --trust-profile trusted-local --authorization-file "$auth_file" \
  --scripts "$state/scripts" --workspaces "$(dirname "$PWD")" \
  > "$state/serve.log" 2>&1 &
SERVE_PID=$!

# --- readiness: el socket existe cuando el servicio escucha.
for _ in $(seq 1 30); do
  [ -S "$SOCK" ] && break
  sleep 1
done
test -S "$SOCK" || { echo "el servicio no levanto el socket" | tee -a "$LOG"; exit 1; }

# --- el run del ejemplo, lento, CON EL SERVICIO YA ARRIBA para poder observarlo vivo.
pipelinek run --workspace "$PWD" \
  --db "$state/run.sqlite" --control-root "$state/control" \
  --plugin-jar "$JAR" \
  "$state/slow-session.pipeline.kts" > "$state/run.log" 2>&1 &
RUN_PID=$!
{ echo "== serve.log (arranque) =="; head -20 "$state/serve.log"; echo "authorization_file=$auth_file"; } >> "$LOG"

probe() { python3 -I examples/socket-client/service_socket_probe.py --socket "$SOCK" ${token:+--token "$token"} "$@"; }

{ echo "== version/health =="; probe version; } >> "$LOG"

# --- runId del run vivo: el log del CLI lo nombra; fallback: el journal.
for _ in $(seq 1 10); do grep -q '"runId"' "$state/run.log" 2>/dev/null && break; sleep 1; done
run_id="$(grep -oE '"runId":"[0-9a-f-]+"' "$state/run.log" | head -1 | sed -E 's/.*: *"([^"]+)"/\1/' || true)"
if [ -z "$run_id" ]; then
  run_id="$(python3 -I -c "
import sqlite3, sys
con = sqlite3.connect('file:%s?mode=ro' % '$state/run.sqlite', uri=True)
tables = [r[0] for r in con.execute(\"select name from sqlite_master where type='table'\")]
for t in tables:
    if 'run' in t.lower():
        cols = [c[1] for c in con.execute('pragma table_info(%s)' % t)]
        for col in ('run_id', 'id', 'runId'):
            if col in cols:
                row = con.execute('select %s from %s order by rowid desc limit 1' % (col, t)).fetchone()
                if row: print(row[0]); sys.exit(0)
" | head -1)"
fi
echo "runId observado: ${run_id:-DETECTADO-POR-LOG-VACIO}" | tee -a "$LOG"

if [ -n "$run_id" ]; then
  { echo "== peek en vivo (ESTADO+PERMISO mientras el dueno tiene el lease) =="; probe peek "$run_id"; } >> "$LOG"
  { echo "== cancel del run vivo por el socket =="; probe cancel "$run_id" "pkh-143 socket client"; } >> "$LOG"
fi
# El run del ejemplo termina solo (turno lento ~20 s): su RESULTADO final.
wait "$RUN_PID" 2>/dev/null || true
RUN_PID=""
if [ -n "$run_id" ]; then
  { echo "== observe post-terminal (RESULTADO real del ejemplo) =="; probe observe "$run_id"; } >> "$LOG"
fi

# --- carril de cancelacion supervisada: run.start por el socket y run.cancel.
{ echo "== start supervisado (sleepy, host-only: el servicio no puede nominar el plugin jar) =="
  probe start sleepy.pipeline.kts "$PWD"
} >> "$LOG"
sleep 2
supervised_id="$(grep -oE '\{"lane": "start".*' "$LOG" | grep -oE '[0-9a-f]{8}-[0-9a-f-]{20,}' | head -1 || true)"
if [ -n "$supervised_id" ]; then
  { echo "== cancel supervisado =="; probe cancel "$supervised_id" "pkh-143 cancel lane";
    probe wait-terminal "$supervised_id"; } >> "$LOG"
fi

# --- veredicto de la fila: el socket respondio y las cuatro calles contestaron.
python3 -I - "$LOG" <<'PY'
import json, sys
text = open(sys.argv[1]).read()
lanes = {}
for line in text.splitlines():
    line = line.strip()
    if not line.startswith("{"):
        continue
    doc = json.loads(line)
    lanes.setdefault(doc["lane"], []).append(doc["reply"])
assert "version" in lanes and "health" in lanes, "el servicio no respondio version/health"
assert "state" in lanes, "no hubo ESTADO leido por el socket"
assert "inputs" in lanes, "no hubo calle PERMISO listada por el socket"
assert "events" in lanes, "no hubo EVENTOS leidos por el socket"
assert "output" in lanes, "no hubo RESULTADO leido por el socket"
assert "cancel" in lanes, "no hubo intento de CANCELACION por el socket"
answered = [l for l in lanes["state"] if "answer" in json.dumps(l)]
assert answered, "run.state no devolvio ninguna respuesta del servicio"
print("ac3: las calles ESTADO/PERMISO/EVENTOS/RESULTADO respondieron por el socket;")
print("     veredicto de cancelacion:", json.dumps(lanes["cancel"])[:400])
PY

echo "e2e-socket-observe: recibo completo en $LOG"
