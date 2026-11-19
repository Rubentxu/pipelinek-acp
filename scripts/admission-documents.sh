#!/usr/bin/env bash
# Cheap and mechanical, and it can fail: admission refuses a jar whose manifest
# is missing or whose SPI registration does not name the contributor — and it
# refuses a jar whose declared api range does not admit the host that will run
# it, which is the refusal this repo's own version mismatch would have caused.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
host_version="$(acp_host_version)"
JAR=build/libs/pipelinek-acp-0.1.0-operator.jar
test -f "$JAR"
unzip -p "$JAR" META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor \
  | grep -qx 'pipelinek.acp.AcpContributor'
unzip -p "$JAR" META-INF/pipelinek/plugin-manifest.json \
  | grep -q '"pipelinek.acp.prompt"'
unzip -p "$JAR" META-INF/pipelinek/plugin-manifest.json \
  | grep -q '"pipelinek.acp.session"'
unzip -p "$JAR" META-INF/pipelinek/plugin-manifest.json \
  | grep -q '"pipelinek.acp.task"'
# The range the artifact declares, against the host that will admit it. The
# range is DERIVED at build time from the SDK compiled and the host measured, so
# a range that excludes the host is not a stale document: it is an artifact that
# admission will refuse, and this row is where that shows up.
python3 -I - "$JAR" "$host_version" <<'PY'
import json
import sys
import zipfile

jar, host = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as archive:
    manifest = json.loads(archive.read("META-INF/pipelinek/plugin-manifest.json"))
declared = manifest["apiRange"]
floor, ceiling = (part.strip() for part in declared.strip("[]()").split(","))
triple = lambda v: tuple(int(part) for part in v.split("."))
if not (triple(floor) <= triple(host) < triple(ceiling)):
    raise SystemExit(
        "the artifact declares the api range %s, which does not admit the host %s"
        % (declared, host)
    )
print("api range %s admits the host %s" % (declared, host))
PY
