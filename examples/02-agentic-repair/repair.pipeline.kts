import pipelinek.acp.agenticRepair

// PKH-143 example (b): bounded test-repair with the PKH-140 recipe.
// The repair fixture agent rewrites main.py in the session cwd (mode "fix"), the
// INDEPENDENT check (check.py, the plan's own argv) decides the verdict, and every
// change is archived as a CANDIDATE patch plus a JSON report — never committed,
// never auto-promoted. The budget is maxRepairAttempts; the PKH-138 ledger is the
// attempt control (its rows ride in the step output).
pipeline {
    stages {
        stage("repair the broken total()") {
            agenticRepair(
                command = listOf("python3", "-I",
                    System.getProperty("user.dir") + "/src/test/resources/acp_repair_fixture_agent.py",
                    "fix"),
                repairPrompt = "make check.py pass: fix total() in main.py",
                verifyCommand = listOf("python3", "-I", "check.py"),
                cwd = "examples/02-agentic-repair/project",
                archiveDir = "build/pkh143-examples/repair-archive",
                // El diff lo pide el PLAN, no el agente: sin diffCommand no hay candidato.
                diffCommand = listOf("git", "diff", "--", "main.py"),
                maxRepairAttempts = 2,
                timeoutSeconds = 120,
            )
        }
    }
}
