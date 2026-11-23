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
#      (ok=false, outcome=PROFILE_REFUSED) before the fixture process ever starts;
#   3. (PKH-136) an `observe` delegation whose agent streams every update shape plus
#      canaries in the fields the whitelist must never read — the run's events table must
#      carry the projected pipelinek.acp.* kinds and NO canary, proving the events reach
#      the durable observability API through the published seam without leaking the wire;
#   4. (PKH-136) a `flood` delegation: a chunk avalanche cut short by the budget. Its output
#      is the typed timeout refusal, and its events must carry the lifecycle facts the plugin
#      establishes itself — started, cancelled, refused — proving the CONTROL lane holds its
#      promise that lifecycle facts survive the avalanche.
#
# No network, no credentials: the agent is the in-repo python fixture over a pipe.
set -eu
cd "$(dirname "$0")/.."
# The host that admits the artifact is the one of the SDK the plugin was compiled
# against: scripts/host-sdk.sh resolves the pair and puts that host in front of the
# PATH, so this row and the build stage measure the same executable.
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
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
        stage("delegate observed") {
            // PKH-136: the agent streams thought, tool calls (pending -> completed), a plan
            // and an unknown variant, with canaries in rawInput/_meta/raw JSON. The events
            // table must show the projection and must not show a single canary.
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py",
                    "observe", "task-ok"),
                prompt = "delegate observed",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
            )
        }
        stage("delegate flooded then cancelled") {
            // PKH-136 LIFECYCLE row: 1200 chunks bury the bounded lane and the agent never
            // answers; the budget cancels. The control-lane facts of this exchange — started,
            // cancelled, refused — must still reach the events table. The stage fails the
            // exchange on purpose (typed timeout refusal), never the run.
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "flood"),
                prompt = "delegate flood",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 1,
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
assert len(happy) >= 4, "four task stages must leave four typed outputs, got %d" % len(happy)
for answer in happy[:3]:
    assert answer.get("ok") is True, "the delegation was refused: %s %s" % (
        answer.get("failureKind"), answer.get("detail"))
    assert answer.get("outcome") == "COMPLETED", "unexpected outcome: %r" % answer.get("outcome")
    assert answer.get("stopReason") == "END_TURN", "unexpected stop: %r" % answer.get("stopReason")
assert "task-ok" in happy[2].get("reply", ""), "unexpected observed reply: %r" % happy[2].get("reply")
# The flooded delegation ends by budget: a typed refusal in the SAME durable journal.
flood = happy[3]
assert flood.get("ok") is False, "the flooding agent must not complete: %r" % flood
assert flood.get("failureKind") == "ACP_AGENT_TIMEOUT", (
    "the avalanche must end classified as a budget timeout, got %r" % flood.get("failureKind"))
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

# --- PKH-136: the projected ACP events, in the same durable database, via the host's
# --- own events table. The host persists a plugin emission as a PluginEventEmitted row
# --- carrying the registry kind and the fact's payload; this row only reads what landed.
con = sqlite3.connect("file:%s?mode=ro" % os.path.join(state, "run-task.sqlite"), uri=True)
envelopes = [json.loads(p) for (p,) in con.execute(
    "select payload from events where kind='PluginEventEmitted'")]
con.close()
emitted = [e for batch in envelopes for e in (batch if isinstance(batch, list) else [batch])]
assert emitted, "the observed exchange projected no plugin event at all"

kinds = {e.get("registryKind", "") for e in emitted}
expected = {
    "pipelinek.acp.agent_message_chunk",
    "pipelinek.acp.agent_thought_chunk",
    "pipelinek.acp.tool_call",
    "pipelinek.acp.tool_call_changed",
    "pipelinek.acp.tool_call_completed",
    "pipelinek.acp.plan",
    "pipelinek.acp.unknown_update",
    # LIFECYCLE row (PKH-136): the facts the plugin establishes about its own exchanges.
    # started/turn_ended come from the completed delegations; cancelled/refused from the
    # flooded one the budget cut short — the CONTROL lane's promise under an avalanche.
    "pipelinek.acp.exchange_started",
    "pipelinek.acp.turn_ended",
    "pipelinek.acp.exchange_cancelled",
    "pipelinek.acp.exchange_refused",
}
missing = expected - kinds
assert not missing, "the projection owes these kinds and none arrived: %s" % sorted(missing)

# The redaction gate: every field the whitelist must never read carried a canary on the
# wire. A canary here means the payload, _meta or the unknown raw JSON reached the log.
canaries = [
    "CANARY-RAWINPUT-8842", "CANARY-META-9931", "CANARY-RAWINPUT2-7753",
    "CANARY-RAWOUTPUT-6674", "CANARY-RAWJSON-5592",
]
all_payloads = " ".join(json.dumps(e) for e in emitted)
for banned in canaries + ["rawInput", "rawOutput", "_meta", "jsonrpc"]:
    assert banned not in all_payloads, (
        "the events table leaked %r — the redaction whitelist did not hold" % banned)
print("e2e observation evidence: %d plugin events, kinds %s" % (
    len(emitted), sorted(kinds)))
print("e2e task evidence (happy):", happy[2])
print("e2e task evidence (flood):", flood)
print("e2e task evidence (escape):", refusal)
PY
