package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole client lifecycle against a deterministic agent: launch → initialize →
 * session/new → session/prompt → typed reply.
 *
 * The fixture is a second, independent implementation of the wire (a ~60-line NDJSON-RPC
 * responder), which is what makes this an integration test of the REAL seams rather than a
 * rehearsal of our own mocks: framing, request-id correlation, notification handling and the
 * Flow-shaped prompt turn all have to agree across two codebases. No network, no
 * credentials — the pipe is the only transport.
 */
class AcpFixtureRoundTripTest {

    private fun fixture(argv: String): List<String> {
        val url = checkNotNull(javaClass.getResource("/acp_fixture_agent.py")) {
            "the fixture agent is missing from the test resources"
        }
        val script = checkNotNull(url.toURI().let { java.nio.file.Paths.get(it) }).toString()
        // -I: isolated interpreter — no user site, no cwd on sys.path. The fixture lives in
        // test resources precisely so nothing else can be imported by accident.
        return listOf("python3", "-I", script) + argv.split(" ").filter { it.isNotEmpty() }
    }

    private fun input(command: List<String>) = AcpPromptInput(
        command = command,
        prompt = "summarize the diff",
        cwd = System.getProperty("java.io.tmpdir"),
        timeoutSeconds = 60,
    )

    @Test
    fun `a full turn against the fixture returns the agent reply and END_TURN`() {
        val exchange = AcpConnection(pluginVersion = "0.0.0-test").run(input(fixture("1 hola-agente")))
        when (exchange) {
            is AcpExchange.Completed -> {
                assertEquals("hola-agente", exchange.reply)
                assertEquals("END_TURN", exchange.stopReason)
            }
            is AcpExchange.Refused ->
                throw AssertionError("the exchange should have completed, refused: ${exchange.refusal.detail}")
        }
    }

    @Test
    fun `an agent answering an unsupported protocol version is a typed refusal, not a crash`() {
        val exchange = AcpConnection(pluginVersion = "0.0.0-test").run(input(fixture("99 never-reached")))
        val refusal = (exchange as AcpExchange.Refused).refusal
        assertTrue(refusal is AcpRefusal.VersionMismatch, "expected a version refusal, got $refusal")
        assertEquals("ACP_VERSION_MISMATCH", refusal.kindToken)
        assertEquals(99, (refusal as AcpRefusal.VersionMismatch).offered)
    }

    @Test
    fun `an agent that exits mid-handshake is reported as an exit, never as success`() {
        // `sh -c 'exit 4'` produces an EOF on stdout with no JSON-RPC answer: the classic way
        // a broken transport pretends to be an empty success.
        val exchange = AcpConnection(pluginVersion = "0.0.0-test").run(
            input(listOf("sh", "-c", "exit 4")),
        )
        val refusal = (exchange as AcpExchange.Refused).refusal
        assertTrue(
            refusal.kindToken == "ACP_AGENT_EXITED" || refusal.kindToken == "ACP_TRANSPORT_CLOSED",
            "an agent that died must classify as an exit or a closed transport, got ${refusal.kindToken}",
        )
    }

    @Test
    fun `a garbage stream is refused and never a completed exchange`() {
        // Emits one non-JSON-RPC line and then dies. Measured behaviour of the SDK transport:
        // a line it cannot parse does NOT fail the protocol eagerly — the pending prompt is
        // cancelled only when the pipe closes with the process. The honest guarantee is the
        // one AC#3 asks for: garbage never reads as success. It arrives here as the typed
        // refusal of the process that produced it.
        val exchange = AcpConnection(pluginVersion = "0.0.0-test").run(
            input(listOf("sh", "-c", "echo 'this is not json-rpc'; exit 7")),
        )
        assertTrue(exchange is AcpExchange.Refused, "a garbage stream must never complete, got $exchange")
        val refusal = (exchange as AcpExchange.Refused).refusal
        assertTrue(
            refusal.kindToken in setOf(
                "ACP_FRAMING_ERROR", "ACP_TRANSPORT_CLOSED", "ACP_TRANSPORT_FAILURE", "ACP_AGENT_EXITED",
            ),
            "garbage on stdout must classify as a transport-level refusal, " +
                "got ${refusal.kindToken}: ${refusal.detail}",
        )
    }
}
