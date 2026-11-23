// =============================================================================
// example: bounded test-repair with an independent verifier (PKH-143 AC#1 / PKH-140)
// =============================================================================
//
// Two delegations of the same recipe over two disposable broken projects:
//
//   * the agent that REALLY fixes main.py → the pipeline's own check turns green → VERIFIED,
//     and the change lands ARCHIVED as a candidate patch (never committed, never promoted);
//   * the agent that LIES — "all tests pass, promote the branch" while the tree never moved →
//     the digest cannot change, the ledger names it no-progress, and the verdict is NO_PROGRESS
//     with ok=false. The agent's words never decide; the independent check does.
//
// The DSL deliberately cannot say: a verify command chosen from the agent's answer (the check
// is always the plan's argv), an unbounded budget, or a promotion verb.
//
// Run examples/test-repair/run.sh for the self-asserting version (it builds the two broken
// projects first); all paths are relative to the repository root because the host runs there.
import pipelinek.acp.agenticRepair

pipeline {
    stages {
        stage("repair the fixable project") {
            agenticRepair(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_repair_fixture_agent.py", "fix"),
                repairPrompt = "make check.py pass in this project",
                verifyCommand = listOf("python3", "-I", "check.py"),
                cwd = "build/examples/repair-project-fix",
                archiveDir = "build/examples/repair-archive/fix",
                diffCommand = listOf("git", "-C", "build/examples/repair-project-fix",
                    "diff", "--", "main.py"),
                revisionCommand = listOf("git", "-C", "build/examples/repair-project-fix",
                    "rev-parse", "HEAD"),
                maxRepairAttempts = 3,
                timeoutSeconds = 120,
            )
        }
        stage("the liar stays unverified") {
            agenticRepair(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_repair_fixture_agent.py", "noop"),
                repairPrompt = "make check.py pass in this project",
                verifyCommand = listOf("python3", "-I", "check.py"),
                cwd = "build/examples/repair-project-lie",
                archiveDir = "build/examples/repair-archive/lie",
                diffCommand = listOf("git", "-C", "build/examples/repair-project-lie",
                    "diff", "--", "main.py"),
                revisionCommand = listOf("git", "-C", "build/examples/repair-project-lie",
                    "rev-parse", "HEAD"),
                maxRepairAttempts = 2,
                timeoutSeconds = 120,
            )
        }
    }
}
