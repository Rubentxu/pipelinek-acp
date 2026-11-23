// =============================================================================
// example: a read-only review delegation (PKH-143 AC#1)
// =============================================================================
//
// A reviewer that must not touch the tree: the delegation runs under the READONLY profile,
// and the pipeline declares the ONLY effect the reviewer's own kind needs — READ. The fixture
// agent asks for a permission mid-prompt (it is a second implementation of the wire, and it
// asks like a real agent does): the read request follows the declared authority, and the edit
// request is refused by the fence BEFORE anything runs. The agent's intentions change nothing:
// the profile and the authority decide.
//
// Run it (from the repository root, with the built operator jar):
//
//   pipelinek run --workspace . --db <state>/run.sqlite --control-root <state>/control \
//     --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
//     examples/readonly-review/review.kts
//
// examples/readonly-review/run.sh asserts the outcome on the durable journal.
import pipelinek.acp.AcpAuthority
import pipelinek.acp.AcpTaskProfile
import pipelinek.acp.AcpToolEffect
import pipelinek.acp.acpTask

pipeline {
    stages {
        stage("read-only review") {
            // The reviewer reads the diff: the permission it asks for is inside the
            // declaration, so the delegation can do its job over the real wire.
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "read"),
                prompt = "review the diff of PR 123 (read-only pass)",
                profile = AcpTaskProfile.READONLY,
                budgetSeconds = 60,
                authority = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ)),
            )
        }
        stage("write attempt stays fenced") {
            // The same pipeline, the same agent, an edit request: absent from the declared
            // effects, so the answer is denial — the absence IS the rule (PKH-132/134).
            acpTask(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_task_fixture_agent.py", "permission", "edit"),
                prompt = "review the diff of PR 123 (does it want to change files?)",
                profile = AcpTaskProfile.READONLY,
                budgetSeconds = 60,
                authority = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ)),
            )
        }
    }
}
