import pipelinek.acp.AcpEventSelector
import pipelinek.acp.acpOn
import pipelinek.acp.acpReactive
import pipelinek.acp.acpTake

// PKH-143 example (c): two INDEPENDENT reviewers (Goldfish pattern).
// Two stages, two fresh session-fixture processes, two witness files: no connection,
// no session and no context is shared (reviewer B's reply echoes ONLY its own question).
// Each turn dispatches one effectful-marked reaction; the PKH-138 ledger rows carried in
// the outputs address every event by EventKey `conn:<per-connection-nonce>:<cursor>`, so
// the two sessions' keys can never collide and no event is ever journalled twice.
pipeline {
    stages {
        stage("reviewer A") {
            acpReactive(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "full",
                    "build/pkh143-examples/03-a.witness"),
                prompt = "review A: list the risky spots",
                reactions = listOf(
                    acpOn("note", AcpEventSelector.UpdateKind("agent_message_chunk"),
                        operator = acpTake(1), effectful = true),
                ),
                cwd = ".",
                timeoutSeconds = 60,
            )
        }
        stage("reviewer B") {
            // Same agent kind, fresh process: the "seen:" half of the reply proves the
            // context did not travel from A, and the ledger proves the effects did not.
            acpReactive(
                command = listOf("python3", "-I",
                    "src/test/resources/acp_session_fixture_agent.py", "full",
                    "build/pkh143-examples/03-b.witness"),
                prompt = "review B: propose the next test",
                reactions = listOf(
                    acpOn("note", AcpEventSelector.UpdateKind("agent_message_chunk"),
                        operator = acpTake(1), effectful = true),
                ),
                cwd = ".",
                timeoutSeconds = 60,
            )
        }
    }
}
