import pipelinek.acp.acpSession
import pipelinek.acp.acpTurn

// PKH-143 example (a): a READ-ONLY review over one composed session.
// The agent is the in-repo session fixture — no tokens, no network. The two turns run
// against the SAME session: the second reply must echo BOTH questions (the agent-side
// history carried the context), and the witness file proves the normal scope exit says
// CLOSE and never DELETE.
pipeline {
    stages {
        stage("review, read-only") {
            acpSession(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "full",
                    "build/pkh143-examples/01-review.witness"),
                turns = listOf(
                    acpTurn("review module A: list its exports"),
                    acpTurn("now summarize what you saw"),
                ),
                cwd = ".",
                timeoutSeconds = 60,
            )
        }
    }
}
