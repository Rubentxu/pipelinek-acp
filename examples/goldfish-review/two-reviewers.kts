// =============================================================================
// example: two independent goldfish reviewers (PKH-143 AC#1)
// =============================================================================
//
// The goldfish rule: a reviewer sees ONLY the work, never the other reviewer's reasoning —
// independence is a property of the SESSIONS, not of the prompts. `acpTask` is the machinery
// that buys it: each task opens its own fresh ACP session (no parked connection is reused) and
// closes it, so neither agent can carry context into the other. Both delegations below share
// ONE session-counter file: after the run it reads "2", which is the proof that the engine
// opened one session per reviewer rather than one session seen twice.
//
// The verdict texts here are canned per persona — that is the point of a fixture agent: the
// independence being demonstrated is the pipeline's, and it holds identically when the personas
// are real agents (see examples/README.md, opt-in rows).
//
// Run it (from the repository root, with the built operator jar):
//
//   pipelinek run --workspace . --db <state>/run.sqlite --control-root <state>/control \
//     --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
//     examples/goldfish-review/two-reviewers.kts
import pipelinek.acp.AcpTaskProfile
import pipelinek.acp.acpTask

pipeline {
    stages {
        stage("goldfish 1: hawk") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "reply",
                    "REVIEW[hawk]:verdict=block;risk=high",
                    "build/examples/goldfish-sessions.txt"),
                prompt = "review the auth change in PR 123 (persona: hawk)",
                profile = AcpTaskProfile.READONLY,
                budgetSeconds = 60,
            )
        }
        stage("goldfish 2: dove") {
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "reply",
                    "REVIEW[dove]:verdict=approve;risk=low",
                    "build/examples/goldfish-sessions.txt"),
                prompt = "review the auth change in PR 123 (persona: dove)",
                profile = AcpTaskProfile.READONLY,
                budgetSeconds = 60,
            )
        }
    }
}
