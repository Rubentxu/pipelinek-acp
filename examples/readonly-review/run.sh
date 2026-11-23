#!/usr/bin/env bash
# examples/readonly-review — a review delegation that cannot touch the tree (PKH-143 AC#1).
#
# Two delegations against the real host and the deterministic fixture agent (no tokens, no
# network): a READ permission the declared authority grants, and an EDIT permission the same
# authority fences. The evidence is the durable journal, not the exit code.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-examples"
mkdir -p "$state"
rm -f "$state/readonly-review.sqlite" "$state/readonly-review.sqlite-wal" "$state/readonly-review.sqlite-shm"
rm -rf "$state/control-readonly-review"

pipelinek run --workspace "$repo" \
  --db "$state/readonly-review.sqlite" --control-root "$state/control-readonly-review" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  examples/readonly-review/review.kts

python3 -I - "$state/readonly-review.sqlite" <<'PY'
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
assert len(replies) == 2, "two delegations must leave two typed replies, got %r" % replies
assert replies[0] == "PERM=granted:opt-once", (
    "the declared READ effect must follow the grant, option id verbatim: %r" % replies[0])
assert replies[1] == "PERM=denied", (
    "an effect the authority does not declare is denied — the read-only review stays read-only: %r"
    % replies[1])
print("readonly-review evidence:", replies)
PY
