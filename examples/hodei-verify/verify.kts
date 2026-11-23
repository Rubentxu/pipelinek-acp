// =============================================================================
// example: the verification pipeline an EXTERNAL orchestrator (Hodei) invokes (PKH-143 AC#4)
// =============================================================================
//
// This is the whole contract between Hodei and the verification: a plan that lives in THIS
// repository, run by the published `pipelinek` CLI against the operator jar, over a project
// directory named here. The orchestrator owns the invocation, the budget it waits for and
// where it reads the evidence; the pipeline owns the verdict. No step of this plan reaches
// into the caller: there is no Hodei memory, scheduler or backlog access in any direction —
// the agent is a fixture without credentials, and the only loopback traffic is the engine's
// own journal.
//
// The verification half is the PKH-140 recipe with a NON-repairing agent ("refuse"): the
// independent check (the plan's own argv) decides, and the outcome says whether the candidate
// already passes. ok=true happens exactly when the check is green before any agent runs.
//
// Consumed by examples/hodei-verify/verify.sh, which materializes the candidate under
// build/examples/hodei-candidate — the path the [cwd] names, relative to the repository root
// the host runs from.
import pipelinek.acp.agenticRepair

pipeline {
    stages {
        stage("verify the candidate") {
            agenticRepair(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_repair_fixture_agent.py", "refuse"),
                repairPrompt = "this is a verification: change nothing, report only",
                verifyCommand = listOf("python3", "-I", "check.py"),
                cwd = "build/examples/hodei-candidate",
                archiveDir = "build/examples/hodei-evidence",
                maxRepairAttempts = 1,
                timeoutSeconds = 60,
            )
        }
    }
}
