#!/usr/bin/env bash
# examples/goldfish-review — two reviewers, two fresh sessions, no shared context
# (PKH-143 AC#1). The independence is measured, not claimed: both delegations share one
# session-counter file, so the counter reading "2" proves one session PER reviewer, and the
# journal carries a reply from each persona that names nothing of the other's exchange.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-examples"
mkdir -p "$state" "$repo/build/examples"
rm -f "$state/goldfish.sqlite" "$state/goldfish.sqlite-wal" "$state/goldfish.sqlite-shm"
rm -rf "$state/control-goldfish"
rm -f "$repo/build/examples/goldfish-sessions.txt"

pipelinek run --workspace "$repo" \
  --db "$state/goldfish.sqlite" --control-root "$state/control-goldfish" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  examples/goldfish-review/two-reviewers.kts

python3 -I - "$state/goldfish.sqlite" "$repo/build/examples/goldfish-sessions.txt" <<'PY'
import json, sqlite3, sys
db = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
replies = []
for (out,) in db.execute("select output from operation_journal where output is not null"):
    doc = json.loads(out)
    result = doc.get("result")
    if not result:
        continue
    answer = json.loads(result)
    if "stopReason" in answer and "reply" in answer:
        assert answer.get("ok") is True, "the delegation was refused: %s %s" % (
            answer.get("failureKind"), answer.get("detail"))
        replies.append(answer["reply"])
assert len(replies) == 2, "two goldfish must leave two typed replies, got %r" % replies
# The fixture prefixes each reply with the session it answered on: the numbers being 1 and 2
# is the wire-side witness that the second reviewer did not ride the first one's session.
assert replies[0] == "task-session-1:REVIEW[hawk]:verdict=block;risk=high", (
    "the hawk must answer once, on its own fresh session: %r" % replies[0])
assert replies[1] == "task-session-2:REVIEW[dove]:verdict=approve;risk=low", (
    "the dove must answer on the NEXT session, not the hawk's: %r" % replies[1])
assert "hawk" not in replies[1] and "dove" not in replies[0], (
    "no reviewer's answer may carry the other's context: %r" % replies)
counter = open(sys.argv[2]).read().strip()
assert counter == "2", (
    "acpTask must open one FRESH session per reviewer; the shared counter reads %r" % counter)
print("goldfish evidence:", replies, "| sessions:", counter)
PY
