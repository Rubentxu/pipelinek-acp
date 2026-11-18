#!/usr/bin/env bash
# The one row that starts the published product: the installed host admits the operator jar,
# the script calls the plugin's own DSL, and the step runs the whole ACP exchange against the
# deterministic fixture agent.
#
# The handler never throws for an agent-side refusal (the execution boundary would
# re-classify it as ENGINE and the declared kind would be lost), so a run that ends SUCCESS
# says only that the step RAN. The evidence of what the agent ANSWERED is the step's typed
# output, and the durable journal of the run carries it; this gate asserts on that row, not
# on the exit code — a refusal-shaped ok=false must leave this stage red.
#
# No network, no credentials: the agent is the in-repo python fixture over a pipe.
set -eu
cd "$(dirname "$0")/.."
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-gate"
mkdir -p "$state"
rm -f "$state/run.sqlite" "$state/run.sqlite-wal" "$state/run.sqlite-shm"

cat > "$state/acp-e2e.kts" <<'SCRIPT'
import pipelinek.acp.acpPrompt

pipeline {
    stages {
        stage("acp") {
            acpPrompt(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_fixture_agent.py", "1", "hola-agente"),
                prompt = "ping",
            )
        }
    }
}
SCRIPT

pipelinek run --workspace "$repo" \
  --db "$state/run.sqlite" --control-root "$state/control" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  "$state/acp-e2e.kts"

# -I: isolated interpreter — nothing on sys.path but stdlib; the fixture and this assertion
# are the only python the run sees.
python3 -I - "$state/run.sqlite" <<'PY'
import json, sqlite3, sys
db = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
results = [
    json.loads(out)["result"]
    for (out,) in db.execute(
        "select output from operation_journal where output is not null"
    )
    if json.loads(out).get("result")
]
assert results, "the durable journal carries no step output at all"
answer = json.loads(results[-1])
# The fixture echoes this exact reply with END_TURN: anything else — a refusal, an empty
# turn, a wrong agent — lands here and fails the gate.
assert answer.get("ok") is True, "the exchange was refused: %s %s" % (
    answer.get("failureKind"), answer.get("detail"))
assert answer.get("reply") == "hola-agente", "unexpected reply: %r" % answer
assert answer.get("stopReason") == "END_TURN", "unexpected stop: %r" % answer
print("e2e evidence:", answer)
PY
