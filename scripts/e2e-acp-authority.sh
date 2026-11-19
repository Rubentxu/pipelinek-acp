#!/usr/bin/env bash
# The PKH-134 row: the authority declared through the published DSL changes what the
# installed host's delegation actually answers on the reverse leg, and the durable journal
# carries the typed events. Three runs, all assertive on operation_journal, not on the
# exit code:
#
#   1. a READONLY delegation whose authority declares READ, against the `permission read`
#      fixture — the SAME request PKH-132's malicious row saw denied unconditionally must
#      now come back granted with the optionId the agent offered (opt-once), and
#      reverseEvents must name the grant;
#   2. the identical request under an authority that does not declare it (and under no
#      authority at all) — PERM=denied: the declaration is the only door, absence still
#      denies, which is PKH-132's default preserved;
#   3. a READONLY delegation declaring MUTATE authority — the seam must refuse the
#      contradiction BEFORE launch (PROFILE_REFUSED / ACP_PROFILE_REFUSED), the
#      ACP_PROFILE_REFUSED precedent applied to the authority declaration.
#
# No network, no credentials: the agent is the in-repo python fixture over a pipe.
set -eu
cd "$(dirname "$0")/.."
# The host that admits the artifact is the one of the SDK the plugin was compiled
# against: scripts/host-sdk.sh resolves the pair and puts that host in front of the
# PATH, so this row and the build stage measure the same executable.
. scripts/host-sdk.sh
acp_select_host "$(acp_sdk_version)" > /dev/null
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-gate"
mkdir -p "$state"
for tag in grant deny-ceiling contradict; do
  rm -f "$state/run-authority-$tag.sqlite" "$state/run-authority-$tag.sqlite-wal" \
        "$state/run-authority-$tag.sqlite-shm"
  rm -rf "$state/control-authority-$tag"
done

# One script per run; the only difference is the declared authority (or its absence),
# so the comparison the journal rows make is exact.
cat > "$state/acp-authority-grant.kts" <<'SCRIPT'
import pipelinek.acp.acpTask
import pipelinek.acp.AcpAuthority
import pipelinek.acp.AcpToolEffect
import pipelinek.acp.AcpTaskProfile

pipeline {
    stages {
        stage("permission with declared read authority") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "read"),
                prompt = "ask to read",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
                authority = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ)),
            )
        }
    }
}
SCRIPT

cat > "$state/acp-authority-deny-ceiling.kts" <<'SCRIPT'
import pipelinek.acp.acpTask
import pipelinek.acp.AcpAuthority
import pipelinek.acp.AcpToolEffect
import pipelinek.acp.AcpTaskProfile

pipeline {
    stages {
        stage("permission under an authority that does not declare it") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "edit"),
                prompt = "ask to edit",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
                authority = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ)),
            )
        }
        stage("the same request with no authority at all") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "read"),
                prompt = "ask again",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
            )
        }
    }
}
SCRIPT

cat > "$state/acp-authority-contradict.kts" <<'SCRIPT'
import pipelinek.acp.acpTask
import pipelinek.acp.AcpAuthority
import pipelinek.acp.AcpToolEffect
import pipelinek.acp.AcpTaskProfile

pipeline {
    stages {
        stage("readonly profile declaring mutate authority") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "edit"),
                prompt = "must never launch",
                profile = AcpTaskProfile.READONLY,
                cwd = ".",
                budgetSeconds = 60,
                authority = AcpAuthority(allowedEffects = listOf(AcpToolEffect.MUTATE)),
            )
        }
    }
}
SCRIPT

run_host() { # tag
  pipelinek run --workspace "$repo" \
    --db "$state/run-authority-$1.sqlite" --control-root "$state/control-authority-$1" \
    --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
    "$state/acp-authority-$1.kts"
}
run_host grant
run_host deny-ceiling
run_host contradict

# -I: isolated interpreter — nothing on sys.path but stdlib.
python3 -I - "$state" <<'PY'
import json, os, sqlite3, sys
state = sys.argv[1]

def outputs(tag):
    db = os.path.join(state, "run-authority-%s.sqlite" % tag)
    con = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
    rows = [
        json.loads(out)["result"]
        for (out,) in con.execute("select output from operation_journal where output is not null")
        if json.loads(out).get("result")
    ]
    con.close()
    return [json.loads(r) for r in rows]

granted = outputs("grant")[-1]
assert granted.get("ok") is True, "the declared READ authority must complete: %r" % granted
assert granted.get("reply") == "PERM=granted:opt-once", (
    "the grant must select the optionId the agent offered, allow-once preferred: %r" % granted.get("reply"))
assert "perm granted (read)" in granted.get("reverseEvents", []), (
    "the typed journal must ride out in the durable output: %r" % granted.get("reverseEvents"))

denied = outputs("deny-ceiling")
undeclared, no_authority = denied[0], denied[1]
assert undeclared.get("ok") is True and undeclared.get("reply") == "PERM=denied", (
    "an edit-kind request under a READ-only authority stays denied: %r" % undeclared)
assert no_authority.get("reply") == "PERM=denied", (
    "deny-by-default is preserved as the ABSENCE of authority: %r" % no_authority)
assert "perm denied (misc)" not in no_authority.get("reverseEvents", [])
assert any(e.startswith("perm denied") for e in no_authority.get("reverseEvents", [])), (
    "the denial is journaled too: %r" % no_authority.get("reverseEvents"))

refused = outputs("contradict")[-1]
assert refused.get("ok") is False, "a wider-than-ceiling authority must never run: %r" % refused
assert refused.get("outcome") == "PROFILE_REFUSED", refused
assert refused.get("failureKind") == "ACP_PROFILE_REFUSED", refused
assert "authority" in refused.get("detail", "").lower(), refused.get("detail")

print("e2e authority evidence (grant):", granted["reply"], granted["reverseEvents"])
print("e2e authority evidence (deny):", undeclared["reply"], no_authority["reply"])
print("e2e authority evidence (contradiction):", refused["failureKind"])
PY
