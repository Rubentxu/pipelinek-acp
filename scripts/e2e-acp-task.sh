#!/usr/bin/env bash
# The PKH-133 row: the installed host admits the jar, a published script lowers `acpTask`,
# and the delegated exchange runs through the isolation posture over the deterministic task
# fixture. Two runs, both assertive on the durable journal, not on the exit code:
#
#   1. a READONLY delegation inside the fence — the journal must carry ok=true,
#      outcome=COMPLETED, stopReason=END_TURN, and the session counter file must read 2:
#      two sequential task steps opened two fresh sessions against the same agent kind,
#      proving no connection was parked between steps;
#   2. the same delegation with an escaping cwd — the profile must refuse it BEFORE launch
#      (ok=false, outcome=PROFILE_REFUSED) before the fixture process ever starts.
#
# No network, no credentials: the agent is the in-repo python fixture over a pipe.
set -eu
cd "$(dirname "$0")/.."
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-gate"
mkdir -p "$state"
rm -f "$state/run-task.sqlite" "$state/run-task.sqlite-wal" "$state/run-task.sqlite-shm"
rm -rf "$state/control-task" "$state/control-task-escape"
rm -f "$state/run-task-escape.sqlite" "$state/run-task-escape.sqlite-wal" "$state/run-task-escape.sqlite-shm"
# The agent's cwd IS the workspace root (the repo): the counter file it writes lands there.
rm -f "$repo/task-sessions.counter"

cat > "$state/acp-task-e2e.kts" <<'SCRIPT'
import pipelinek.acp.acpTask
import pipelinek.acp.AcpTaskProfile

pipeline {
    stages {
        stage("delegate read") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py",
                    "reply", "task-ok", "task-sessions.counter"),
                prompt = "delegate one",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
            )
        }
        stage("delegate read again") {
            // The counter file is shared on purpose: session/new must have advanced it to 2.
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py",
                    "reply", "task-ok", "task-sessions.counter"),
                prompt = "delegate two",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
            )
        }
    }
}
SCRIPT

cat > "$state/acp-task-escape.kts" <<'SCRIPT'
import pipelinek.acp.acpTask
import pipelinek.acp.AcpTaskProfile

pipeline {
    stages {
        stage("escape attempt") {
            acpTask(
                // argv[0] is python3 (a shell-free launch the validator honours); the
                // escaping cwd is what the fence must refuse before the process starts.
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "reply", "must-not-run"),
                prompt = "delegate outside",
                profile = AcpTaskProfile.READONLY,
                cwd = "../../../../tmp",
                budgetSeconds = 60,
            )
        }
    }
}
SCRIPT

pipelinek run --workspace "$repo" \
  --db "$state/run-task.sqlite" --control-root "$state/control-task" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  "$state/acp-task-e2e.kts"

pipelinek run --workspace "$repo" \
  --db "$state/run-task-escape.sqlite" --control-root "$state/control-task-escape" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  "$state/acp-task-escape.kts"

# -I: isolated interpreter — nothing on sys.path but stdlib.
python3 -I - "$state" "$repo" <<'PY'
import json, os, sqlite3, sys
state, repo = sys.argv[1], sys.argv[2]

def outputs(db):
    con = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
    rows = [
        json.loads(out)["result"]
        for (out,) in con.execute("select output from operation_journal where output is not null")
        if json.loads(out).get("result")
    ]
    con.close()
    return rows

happy = [json.loads(r) for r in outputs(os.path.join(state, "run-task.sqlite"))]
assert happy, "the durable journal carries no step output at all"
assert len(happy) >= 2, "two task stages must leave two typed outputs, got %d" % len(happy)
for answer in happy:
    assert answer.get("ok") is True, "the delegation was refused: %s %s" % (
        answer.get("failureKind"), answer.get("detail"))
    assert answer.get("outcome") == "COMPLETED", "unexpected outcome: %r" % answer.get("outcome")
    assert answer.get("stopReason") == "END_TURN", "unexpected stop: %r" % answer.get("stopReason")
assert "task-ok" in happy[-1].get("reply", ""), "unexpected reply: %r" % happy[-1].get("reply")
# Two delegations, one agent kind, shared counter: session/new ran twice — no parked session.
counter = open(os.path.join(repo, "task-sessions.counter")).read().strip()
assert counter == "2", "delegations must open one fresh session each, counter reads %r" % counter

escaped = [json.loads(r) for r in outputs(os.path.join(state, "run-task-escape.sqlite"))]
assert escaped, "the escaping run left no typed output"
refusal = escaped[-1]
assert refusal.get("ok") is False, "an escaping cwd must never complete: %r" % refusal
assert refusal.get("failureKind") == "ACP_PROFILE_REFUSED", (
    "the fence must classify the escape as a profile refusal, got %r" % refusal.get("failureKind"))
assert refusal.get("outcome") == "PROFILE_REFUSED", "outcome/failureKind must agree: %r" % refusal
print("e2e task evidence (happy):", happy[-1])
print("e2e task evidence (escape):", refusal)
PY
