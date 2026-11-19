package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `acpSession` payload types are durable state (PKH-135): the journal row outlives the
 * artifact that wrote it, so the codecs round-trip with the step's own Json settings and no
 * SDK class on the other end. The second rule is totality: every refusal kind of the sealed
 * hierarchy — the five the session adds included — lands in exactly one outcome of the
 * closed vocabulary. A new refusal without a home here is a compile error, which is what
 * keeps AC#4's typed branch points from ever degrading into an unclassified failure.
 */
class AcpSessionCodecTest {

    @Test
    fun `input round-trips with turns, content blocks and every declared operation`() {
        val input = AcpSessionInput(
            command = listOf("python3", "-I", "agent.py"),
            turns = listOf(
                AcpTurnInput("first"),
                AcpTurnInput(
                    "second",
                    content = listOf(
                        AcpContentInput.Image("aGk=", "image/png"),
                        AcpContentInput.Audio("aGk=", "audio/ogg"),
                        AcpContentInput.Resource("file:///x", "text"),
                        AcpContentInput.ResourceLink("file:///y", "y"),
                    ),
                ),
            ),
            cwd = "/tmp",
            timeoutSeconds = 42,
            mcpServers = listOf(
                AcpMcpInput("local", "stdio", command = listOf("mcp")),
                AcpMcpInput("remote", "http", url = "https://h/rpc",
                    headers = listOf(AcpMcpHeader("authorization", "by-reference")),
                ),
            ),
            modeId = "plan",
            configOptions = listOf(AcpConfigInput("verbosity", "high")),
            loadSessionId = "agent-1",
            listSessions = true,
            logout = true,
            deleteOnExit = true,
        )
        val encoded = AcpSessionStepDefinition.InputCodec.encode(input)
        assertEquals(input, AcpSessionStepDefinition.InputCodec.decode(encoded))
        // the sealed variants keep their identity through the wire form
        assertTrue(encoded.value.contains("\"image\""))
        assertTrue(encoded.value.contains("embeddedContext") || encoded.value.contains("\"resource\""))
    }

    @Test
    fun `output round-trips and its outcome field is closed vocabulary`() {
        val output = AcpSessionOutput(
            ok = false,
            sessionRef = "c3Jr",
            turns = listOf(AcpTurnOutput("hello", "END_TURN")),
            historicEvents = listOf("h1"),
            sessionsListed = listOf("s1"),
            appliedMode = "plan",
            appliedConfig = listOf("verbosity=high"),
            logoutOk = true,
            outcome = AcpSessionOutcome.TRANSPORT_FAILURE,
            failureKind = "ACP_TRANSPORT_CLOSED",
            detail = "the transport closed",
        )
        val codec = AcpSessionStepDefinition.OutputCodec
        assertEquals(output, codec.decode(codec.encode(output)))
        assertEquals(
            setOf(
                "COMPLETED", "CAPABILITY_MISSING", "SESSION_REFUSED",
                "TRANSPORT_FAILURE", "BUDGET_EXCEEDED", "UNKNOWN",
            ),
            AcpSessionOutcome.VOCABULARY,
            "AC#4 names the classes; the vocabulary may only change with the criterion",
        )
    }

    @Test
    fun `every refusal kind maps to exactly one session outcome`() {
        val refusals: List<Pair<AcpRefusal, String>> = listOf(
            AcpRefusal.VersionMismatch(1, 2, setOf(1)) to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.MissingCapability("image") to AcpSessionOutcome.CAPABILITY_MISSING,
            AcpRefusal.SessionCapabilityMissing("session list") to AcpSessionOutcome.CAPABILITY_MISSING,
            AcpRefusal.ForeignSessionRef("ref") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.ConfigInvalid("bad mode") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.ActivePromptInProgress("busy") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.SessionOperationFailed("load", "boom") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.TransportClosed("eof") to AcpSessionOutcome.TRANSPORT_FAILURE,
            AcpRefusal.TransportFailure("io") to AcpSessionOutcome.TRANSPORT_FAILURE,
            AcpRefusal.FramingError("garbage") to AcpSessionOutcome.TRANSPORT_FAILURE,
            AcpRefusal.AgentExited(3, "tail") to AcpSessionOutcome.TRANSPORT_FAILURE,
            AcpRefusal.AgentUnreachable("no exec") to AcpSessionOutcome.TRANSPORT_FAILURE,
            AcpRefusal.AgentTimeout(30) to AcpSessionOutcome.BUDGET_EXCEEDED,
            AcpRefusal.AgentRefusedRequest("m", "no") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.ProfileRefusal("p") to AcpSessionOutcome.SESSION_REFUSED,
            AcpRefusal.CredentialSeamAbsent("c", "x") to AcpSessionOutcome.UNKNOWN,
            AcpRefusal.CredentialNotResolved("c", "x") to AcpSessionOutcome.UNKNOWN,
            AcpRefusal.PluginInternal("smoke") to AcpSessionOutcome.UNKNOWN,
        )
        for ((refusal, expected) in refusals) {
            assertEquals(expected, AcpSessionOutcome.of(refusal), refusal.kindToken)
            // and the output mapping carries the class all the way to durable form
            val out = AcpSessionOutcome.toOutput(refusal = refusal)
            assertFalse(out.ok)
            assertEquals(expected, out.outcome)
            assertEquals(refusal.kindToken, out.failureKind)
            assertEquals(refusal.detail, out.detail)
        }
    }

    @Test
    fun `the five session kinds carry the tokens AC#4 names`() {
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", AcpRefusal.SessionCapabilityMissing("x").kindToken)
        assertEquals("ACP_FOREIGN_SESSION_REF", AcpRefusal.ForeignSessionRef("x").kindToken)
        assertEquals("ACP_CONFIG_INVALID", AcpRefusal.ConfigInvalid("x").kindToken)
        assertEquals("ACP_ACTIVE_PROMPT_IN_PROGRESS", AcpRefusal.ActivePromptInProgress("x").kindToken)
        assertEquals("ACP_SESSION_OPERATION_FAILED", AcpRefusal.SessionOperationFailed("o", "x").kindToken)
    }

    @Test
    fun `a successful mapping never says failure and keeps the evidence fields`() {
        val out = AcpSessionOutcome.toOutput(
            refusal = null,
            turns = listOf(AcpTurnOutput("r", "END_TURN")),
            sessionRef = "abc",
            historicEvents = listOf("h1"),
            sessionsListed = listOf("s1"),
            appliedMode = "plan",
            appliedConfig = listOf("v=1"),
            logoutOk = true,
        )
        assertTrue(out.ok)
        assertEquals(AcpSessionOutcome.COMPLETED, out.outcome)
        assertEquals("", out.failureKind)
        assertEquals(listOf("h1"), out.historicEvents)
    }
}
