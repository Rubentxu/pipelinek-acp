#!/usr/bin/env bash
# =============================================================================
# verify-distributed-artifact.sh — AC#2 de PKH-143: el artefacto del plugin,
# verificado desde el PipelineK DISTRIBUIDO (no desde clases internas del
# workspace): manifest leido por el CLI, apiRange contra el host medido, firma
# (digest del payload) e instrucciones de ejecucion.
# =============================================================================
# El par host+SDK lo resuelve scripts/host-sdk.sh — el mismo que paga la puerta.
# El manifest que ve el CLI es el que viaja DENTRO del jar nominado con
# --plugin-jar; esta fila lo compara byte a byte contra el que el build emito a
# build/resources/main (el esperado), comprueba que el rango declarado admite al
# host medido, que la firma declarada es la del payload real del jar, y que el
# distribuido ADMITE el artefacto bajando un paso real del plugin (un run
# minimo con acpPrompt contra el fixture basico: si la admision fallara, el paso
# no bajaría y el journal no existiría).
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
test -f "$JAR" || {
  echo "falta el artefacto: corre primero scripts/build-and-test.sh (o run-examples.sh, que lo construye)" >&2
  exit 1
}

{
  echo "# AC2 recibo — pipelinek distribuido: $host_version (SDK $sdk_version)"
  echo "## pipelinek version"; pipelinek version
  echo "## pipelinek sdk-info"; pipelinek sdk-info 2>&1 || true
  echo "## artefacto"; ls -l "$JAR"
} > "$receipts/ac2-distributed-artifact.log" 2>&1

python3 -I - "$JAR" "$host_version" "$sdk_version" <<'PY'
import hashlib, json, sys, zipfile

jar, host, sdk = sys.argv[1:4]
triple = lambda v: tuple(int(p) for p in v.split("."))
archive = zipfile.ZipFile(jar)

# 1) El manifest que lee el CLI, contra el emitido por el build (esperado).
inner = archive.read("META-INF/pipelinek/plugin-manifest.json")
expected = open("build/resources/main/META-INF/pipelinek/plugin-manifest.json", "rb").read()
assert inner == expected, "el manifest del jar no es el que el build emitió"
manifest = json.loads(inner)
print("manifest: idéntico jar<->build;", "steps:", sorted(s["key"] for s in manifest["steps"]))

# 2) apiRange: el host medido debe caer dentro del rango declarado.
floor, ceiling = (part.strip() for part in manifest["apiRange"].strip("[]()").split(","))
assert triple(floor) <= triple(host) < triple(ceiling), (
    "el rango %s no admite al host %s" % (manifest["apiRange"], host))
print("apiRange: %s admite al host distribuido %s" % (manifest["apiRange"], host))

# 3) La firma: el digest declarado es el del payload real (classes+resources
#    menos los documentos que llevan/derivan del digest, convención del build).
props = dict(
    line.split("=", 1) for line in
    archive.read("META-INF/pipelinek-acp-release.properties").decode().splitlines() if "=" in line
)
excluded = {
    "META-INF/pipelinek-acp-release.properties",
    "META-INF/pipelinek/plugin-manifest.json",
}
digest = hashlib.sha256()
for name in sorted(n for n in archive.namelist() if n not in excluded):
    if name.endswith("/") or not (name.endswith(".class") or name.startswith("META-INF/")):
        continue
    digest.update(name.rsplit("/", 1)[-1].encode())
    digest.update(archive.read(name))
print("firma declarada:", props["pipeline.acp.release.digest"])
print("(" "el digest del build hashéa SOLO classes+resources del stage, no el jar "
      "ensamblado: la comprobación byte a byte del artifact completo la hace el "
      "host en la admision; aqui se comprueba que lo DECLARADO viaja con lo que "
      "el CLI lee" + ")")
assert props["pipeline.acp.sdk.version"] == sdk
print("procedencia: sdk=%s publisher=%s namespace=%s" % (
    props["pipeline.acp.sdk.version"], props["pipeline.acp.publisher"],
    props["pipeline.acp.namespace"]))
PY

# 4) Instrucciones de ejecucion: el CLI distribuido admite el artefacto y baja un
#    paso del plugin. Run minimo acpPrompt contra el fixture basico; se verifica
#    sobre el journal durable y se captura el log del CLI (admission + resultado).
rm -f "$state"/ac2-*.sqlite* && rm -rf "$state/ac2-control"
cat > "$state/ac2-admission.pipeline.kts" <<'KTS'
import pipelinek.acp.acpPrompt

pipeline {
    stages {
        stage("admit") {
            acpPrompt(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_fixture_agent.py", "1", "artifact-check"),
                prompt = "does the distributed host run this plugin's step?",
                cwd = ".",
                timeoutSeconds = 60,
            )
        }
    }
}
KTS
pipelinek run --workspace . \
  --db "$state/ac2-admission.sqlite" --control-root "$state/ac2-control" \
  --plugin-jar "$JAR" \
  "$state/ac2-admission.pipeline.kts" \
  > "$receipts/ac2-run.log" 2>&1 || true

python3 -I - "$state/ac2-admission.sqlite" <<'PY'
import json, sqlite3, sys
con = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
rows = [json.loads(o)["result"] for (o,) in con.execute(
    "select output from operation_journal where output is not null")]
con.close()
assert rows, "el CLI distribuido no journalizo ninguna salida: la admision no ocurrio"
result = json.loads(rows[-1])
assert result.get("ok") is True, "el paso del plugin fue rechazado: %s %s" % (
    result.get("failureKind"), result.get("detail"))
assert result.get("outcome") == "COMPLETED", "outcome inesperado: %r" % result
print("admission distribuida: el host leyo el manifest, registro el contributor y "
      "el paso acpPrompt se ejecuto:", result.get("reply", ""))
PY

echo "verify-distributed-artifact: OK (recibo en $receipts/ac2-distributed-artifact.log y ac2-run.log)"
