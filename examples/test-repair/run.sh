#!/usr/bin/env bash
# examples/test-repair — the PKH-140 recipe over the real host (PKH-143 AC#1).
#
# Two disposable broken projects (committed to throwaway git repos so the CANDIDATE patch is a
# real diff), the deterministic repair agent (no tokens), and the pipeline's own argv as the
# independent verifier. The fixable project must land VERIFIED with an archived patch; the
# lying agent must land NOT verified, because the check — not the words — decides.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-examples"
mkdir -p "$state"
rm -f "$state/test-repair.sqlite" "$state/test-repair.sqlite-wal" "$state/test-repair.sqlite-shm"
rm -rf "$state/control-test-repair"
rm -rf "$repo/build/examples/repair-project-fix" "$repo/build/examples/repair-project-lie"
rm -rf "$repo/build/examples/repair-archive"

# The authentic git: skip any shim that declares the commit-policy marker in its own text —
# a policy shim must not take over the disposable repositories of this example.
git_bin="git"
export GIT_BIN
# Y config global aislada: los ganchos de política del usuario (hook.* en ~/.gitconfig) tampoco
# deciden sobre los repositorios desechables del ejemplo — AcpRepairFixtureTest hace lo mismo.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
IFS=':' read -ra path_dirs <<< "$PATH"
for d in "${path_dirs[@]}"; do
  cand="$d/git"
  if [ -x "$cand" ] && [ -f "$cand" ] && ! grep -q "git-control" "$cand" 2>/dev/null; then
    git_bin="$cand"
    GIT_BIN="$cand"
    break
  fi
done

make_broken_project() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/main.py" <<'PYPROJECT'
def total(xs):
    return sum(xs) - 1
PYPROJECT
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
  "$git_bin" -C "$dir" init -q
  "$git_bin" -C "$dir" add -A
  "$git_bin" -C "$dir" -c user.email=example@pipelinek.dev -c user.name=pipelinek \
    commit -q -m "the bug: total is off by one"
}

make_broken_project "$repo/build/examples/repair-project-fix"
make_broken_project "$repo/build/examples/repair-project-lie"

pipelinek run --workspace "$repo" \
  --db "$state/test-repair.sqlite" --control-root "$state/control-test-repair" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  examples/test-repair/repair.kts

python3 -I - "$state/test-repair.sqlite" "$repo/build/examples" <<'PY'
import json, os, sqlite3, sys
db = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
examples_dir = sys.argv[2]
verdicts = []
for (out,) in db.execute("select output from operation_journal where output is not null"):
    doc = json.loads(out)
    result = doc.get("result")
    if not result:
        continue
    answer = json.loads(result)
    if "outcome" in answer and "agentTurns" in answer:
        verdicts.append(answer)
assert len(verdicts) == 2, "two repair runs must leave two verdicts, got %r" % len(verdicts)

fixed, lied = verdicts
assert fixed.get("ok") is True and fixed["outcome"] == "VERIFIED", (
    "the project the agent really repaired must verify: %r" % fixed)
assert fixed["patchPath"] and os.path.isfile(fixed["patchPath"]), (
    "the candidate patch must be archived, not applied: %r" % fixed["patchPath"])
assert fixed["reportPath"] and os.path.isfile(fixed["reportPath"]), (
    "the JSON report must be archived: %r" % fixed["reportPath"])
patch = open(fixed["patchPath"]).read()
assert "+    return sum(xs)" in patch and "-    return sum(xs) - 1" in patch, (
    "the archived patch must carry exactly the repair: %r" % patch[:200])
# The recipe verifies FIRST: the first evidence row is the pre-agent check, red on the bug.
assert fixed["evidence"][0]["passed"] is False, (
    "the pre-agent check must have seen the broken tree: %r" % fixed["evidence"][0])
assert fixed["evidence"][-1]["passed"] is True, "the final check must be green"
assert fixed["agentTurns"] >= 1, "at least one repair turn ran"
# NOTHING was promoted: the project's HEAD still carries the bug commit; the fix is in the
# working tree only, and the diff is the offer.
head = os.popen(
    "'%s' -C '%s/repair-project-fix' log -1 --format=%%s"
    % (os.environ.get("GIT_BIN", "git"), examples_dir)).read()
assert "the bug" in head, "the candidate must never be committed: HEAD is %r" % head

assert lied.get("ok") is False, (
    "an agent's confident words never verify the build: %r" % lied)
assert lied["outcome"] in ("NO_PROGRESS", "BUDGET_EXHAUSTED"), (
    "the liar must land a typed negative, not a silent one: %r" % lied)
assert not lied.get("patchPath"), (
    "nothing changed, so nothing may be archived as a candidate: %r" % lied.get("patchPath"))
print("test-repair evidence: VERIFIED after %d turn(s); liar -> %s"
      % (fixed["agentTurns"], lied["outcome"]))
PY
