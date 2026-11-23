#!/usr/bin/env bash
# examples/hodei-verify — the contract an external orchestrator (Hodei) runs (PKH-143 AC#4).
#
# Hodei-side, end to end, WITHOUT Hodei: this script plays the role of any external scheduler —
# it owns a candidate directory, invokes the verification pipeline through the published
# `pipelinek` CLI, waits, and CONSUMES the evidence (the typed verdict from the durable journal
# plus the archived report). Nothing crosses in the other direction: the pipeline never touches
# the caller's memory, scheduler or backlog, and the whole exchange is argv, exit status and
# files.
#
# usage: verify.sh [candidate-dir]     (default: build a two-file fixture candidate)
#
# Two rows are measured when no candidate is given: a GREEN candidate (VERIFIED without ever
# waking the agent) and a RED one (the check stays red, the refusing agent never gets to
# pretend, ok=false with a typed outcome). The verdict comes from the check, not the agent.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-examples"
mkdir -p "$state" "$repo/build/examples"

make_candidate() {
  # $1 = dir, $2 = good|broken
  local dir="$1"
  rm -rf "$dir"
  mkdir -p "$dir"
  cat > "$dir/check.py" <<'PYCHECK'
import sys
sys.path.insert(0, ".")
from main import total
got = total([1, 2, 3])
if got != 6:
    print("FAIL: total([1, 2, 3]) == %r, expected 6" % got)
    sys.exit(1)
print("OK")
PYCHECK
  if [ "$2" = "good" ]; then
    printf 'def total(xs):\n    return sum(xs)\n' > "$dir/main.py"
  else
    printf 'def total(xs):\n    return sum(xs) - 1\n' > "$dir/main.py"
  fi
}

run_verification() {
  local db="$1"
  rm -f "$db" "$db-wal" "$db-shm"
  rm -rf "$state/control-hodei" "$repo/build/examples/hodei-evidence"
  pipelinek run --workspace "$repo" \
    --db "$db" --control-root "$state/control-hodei" \
    --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
    examples/hodei-verify/verify.kts
}

consume_verdict() {
  # The ONLY thing an orchestrator reads back: one JSON line, typed, from the journal.
  python3 -I - "$1" <<'PY'
import json, sqlite3, sys
db = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
verdicts = []
for (out,) in db.execute("select output from operation_journal where output is not null"):
    doc = json.loads(out)
    result = doc.get("result")
    if result:
        answer = json.loads(result)
        if "outcome" in answer and "agentTurns" in answer:
            verdicts.append(answer)
assert verdicts, "the verification left no typed verdict"
v = verdicts[-1]
print(json.dumps({
    "ok": v["ok"], "outcome": v["outcome"], "agentTurns": v["agentTurns"],
    "evidence": v.get("evidence", []), "reportPath": v.get("reportPath", ""),
}, sort_keys=True))
PY
}

if [ "$#" -ge 1 ]; then
  # External caller: it owns the candidate; this script is the invocation contract for it.
  rm -rf "$repo/build/examples/hodei-candidate"
  cp -r "$1" "$repo/build/examples/hodei-candidate"
  run_verification "$state/hodei.sqlite"
  consume_verdict "$state/hodei.sqlite"
  exit 0
fi

echo "== row 1: the green candidate verifies without waking any agent =="
make_candidate "$repo/build/examples/hodei-candidate" good
run_verification "$state/hodei-green.sqlite"
line="$(consume_verdict "$state/hodei-green.sqlite")"
echo "$line"
python3 -I - <<PY
import json
verdict = json.loads('''$line''')
assert verdict["ok"] is True, "a green candidate must verify: %r" % verdict
assert verdict["outcome"] == "VERIFIED", "unexpected outcome: %r" % verdict
assert verdict["agentTurns"] == 0, (
    "verify-FIRST means an already-green candidate never launches an agent: %r" % verdict)
PY

echo "== row 2: the red candidate stays red, the words of the agent change nothing =="
make_candidate "$repo/build/examples/hodei-candidate" broken
run_verification "$state/hodei-red.sqlite" || true   # the RUN may end red; the verdict is the evidence
line="$(consume_verdict "$state/hodei-red.sqlite")"
echo "$line"
python3 -I - <<PY
import json
verdict = json.loads('''$line''')
assert verdict["ok"] is False, "a red candidate may never claim verification: %r" % verdict
assert verdict["outcome"] in ("DENIED", "NO_PROGRESS", "BUDGET_EXHAUSTED"), (
    "the refusal must be typed, not silent: %r" % verdict)
assert verdict["evidence"][0]["passed"] is False, "the check ran FIRST and saw the red tree"
PY
echo "hodei-verify: contract rows measured (argv + journal; no orchestrator state touched)"
