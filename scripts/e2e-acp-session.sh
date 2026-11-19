#!/usr/bin/env bash
# The PKH-135 row: the installed host admits the jar, a published script lowers `acpSession`
# against the deterministic session fixture, and the DURABLE journal carries the composed
# evidence. Two runs, both assertive on the journal, not on the exit code:
#
#   1. one session with TWO sequential turns — the journal must carry ok=true,
#      outcome=COMPLETED, two END_TURN turns, and the second reply must ECHO BOTH questions:
#      the agent-side session history carried the context across the turns the host ran
#      (AC#1 through the installed product, not just through the unit test);
#   2. the same plan under the `minimal` caps profile declaring list+load+logout — every
#      unannounced capability must answer as the typed ACP_SESSION_CAPABILITY_MISSING with
#      outcome=CAPABILITY_MISSING, before the session is even asked (AC#2/AC#3/AC#4).
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
rm -f "$state/run-session.sqlite" "$state/run-session.sqlite-wal" "$state/run-session.sqlite-shm"
rm -f "$state/run-session-refusal.sqlite" "$state/run-session-refusal.sqlite-wal" "$state/run-session-refusal.sqlite-shm"
rm -rf "$state/control-session" "$state/control-session-refusal"
rm -f "$state/session-witness.txt"

cat > "$state/acp-session-e2e.kts" <<SCRIPT
import pipelinek.acp.acpSession
import pipelinek.acp.acpTurn

pipeline {
    stages {
        stage("composed session") {
            acpSession(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "full",
                    "$state/session-witness.txt"),
                turns = listOf(
                    acpTurn("first question"),
                    acpTurn("second question"),
                ),
                timeoutSeconds = 60,
            )
        }
    }
}
SCRIPT

cat > "$state/acp-session-refusal.kts" <<'SCRIPT'
import pipelinek.acp.acpSession
import pipelinek.acp.acpTurn

pipeline {
    stages {
        stage("unannounced capabilities") {
            acpSession(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "minimal"),
                turns = listOf(acpTurn("hi")),
                listSessions = true,
                logout = true,
                timeoutSeconds = 60,
            )
        }
    }
}
SCRIPT

pipelinek run --workspace "$repo" \
  --db "$state/run-session.sqlite" --control-root "$state/control-session" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  "$state/acp-session-e2e.kts"

pipelinek run --workspace "$repo" \
  --db "$state/run-session-refusal.sqlite" --control-root "$state/control-session-refusal" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  "$state/acp-session-refusal.kts"

# -I: isolated interpreter — nothing on sys.path but stdlib.
python3 -I - "$state" <<'PY'
import json, os, sqlite3, sys
state = sys.argv[1]

def outputs(db):
    con = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
    rows = [
        json.loads(out)["result"]
        for (out,) in con.execute("select output from operation_journal where output is not null")
        if json.loads(out).get("result")
    ]
    con.close()
    return rows

happy = [json.loads(r) for r in outputs(os.path.join(state, "run-session.sqlite"))]
assert happy, "the durable journal carries no session output at all"
answer = happy[-1]
assert answer.get("ok") is True, "the session was refused: %s %s" % (
    answer.get("failureKind"), answer.get("detail"))
assert answer.get("outcome") == "COMPLETED", "unexpected outcome: %r" % answer.get("outcome")
turns = answer.get("turns", [])
assert len(turns) == 2, "two declared turns must answer twice, got %r" % turns
assert all(t.get("stopReason") == "END_TURN" for t in turns), "unexpected stop: %r" % turns
# AC#1 through the installed product: turn 2's reply ECHOES BOTH questions — the context the
# agent's own session history carried across the turns the host ran sequentially.
assert "first question;second question" in turns[1]["reply"], (
    "sequential turns must conserve context, turn2 said %r" % turns[1]["reply"])
assert answer.get("sessionRef"), "the session must answer with its opaque ref"
# AC#3's release rule as the agent SAW it: close said, delete NOT said by the normal exit.
witness = open(os.path.join(state, "session-witness.txt")).read()
assert "CLOSE" in witness, "a normal exit says close when announced: %r" % witness
assert "DELETE" not in witness, "normal close must never delete: %r" % witness

refused = [json.loads(r) for r in outputs(os.path.join(state, "run-session-refusal.sqlite"))]
assert refused, "the refusal run left no typed output"
refusal = refused[-1]
assert refusal.get("ok") is False, "unannounced capabilities must never complete: %r" % refusal
assert refusal.get("failureKind") == "ACP_SESSION_CAPABILITY_MISSING", (
    "the gate must name the missing negotiation, got %r" % refusal.get("failureKind"))
assert refusal.get("outcome") == "CAPABILITY_MISSING", "outcome/failureKind must agree: %r" % refusal
assert refusal.get("sessionRef", "") == "", "a gated plan opens no session: %r" % refusal
print("e2e session evidence (happy):", answer)
print("e2e session evidence (refusal):", refusal)
PY
