package pipelinek.acp

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `acpTask` payload types are durable state (PKH-133).
 *
 * Two rules are guarded here: the codec is symmetric against the same Json settings the step
 * declares (a silent ignoreUnknownKeys drift would let a stale journal row decode into a
 * shape the handler never produced), and the credential field survives the round trip as a
 * NAME only — secret material has no representation in these types, so a secret in an encoded
 * input is a compile-level impossibility, not a convention.
 */
class AcpTaskCodecTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun `input round-trips through the step codec with every field preserved`() {
        val input = AcpTaskInput(
            command = listOf("python3", "-I", "agent.py"),
            prompt = "summarize",
            profile = AcpTaskProfile.WRITE_RESTRICTED,
            cwd = "build/out",
            budgetSeconds = 42,
            credentialRef = "acp-demo",
        )
        val encoded = AcpTaskStepDefinition.InputCodec.encode(input)
        assertEquals(input, AcpTaskStepDefinition.InputCodec.decode(encoded))
        // The encoded form carries the reference, never a secret field of any kind.
        assertTrue(encoded.value.contains("acp-demo"))
        assertFalse(encoded.value.contains("password"), "the input schema must have no secret slot")
    }

    @Test
    fun `output round-trips and its outcome field is closed vocabulary`() {
        val output = AcpTaskOutput(
            ok = false,
            outcome = AcpTaskOutcome.PROFILE_REFUSED,
            failureKind = "ACP_PROFILE_REFUSED",
            detail = "the profile demands confinement the target cannot attest",
        )
        assertEquals(output, AcpTaskStepDefinition.OutputCodec.decode(AcpTaskStepDefinition.OutputCodec.encode(output)))
        assertEquals(
            setOf(
                "COMPLETED", "TRANSPORT_FAILURE", "CANCELLED", "BUDGET_EXCEEDED",
                "PROFILE_REFUSED", "HANDSHAKE_REFUSED", "UNKNOWN",
            ),
            AcpTaskOutcome.VOCABULARY,
            "AC#3 names the classes; the vocabulary may only change with the criterion",
        )
    }

    @Test
    fun `every refusal kind maps to exactly one outcome and nothing lands unclassified`() {
        val refusals: List<Pair<AcpRefusal, String>> = listOf(
            AcpRefusal.AgentTimeout(30) to AcpTaskOutcome.BUDGET_EXCEEDED,
            AcpRefusal.ProfileRefusal("no attestation") to AcpTaskOutcome.PROFILE_REFUSED,
            AcpRefusal.VersionMismatch(1, 99, setOf(1)) to AcpTaskOutcome.HANDSHAKE_REFUSED,
            AcpRefusal.MissingCapability("image") to AcpTaskOutcome.HANDSHAKE_REFUSED,
            AcpRefusal.AgentRefusedRequest("session/prompt", "nope") to AcpTaskOutcome.HANDSHAKE_REFUSED,
            AcpRefusal.TransportClosed("EOF") to AcpTaskOutcome.TRANSPORT_FAILURE,
            AcpRefusal.TransportFailure("pipe") to AcpTaskOutcome.TRANSPORT_FAILURE,
            AcpRefusal.AgentExited(3, "tail") to AcpTaskOutcome.TRANSPORT_FAILURE,
            AcpRefusal.FramingError("not json") to AcpTaskOutcome.TRANSPORT_FAILURE,
            AcpRefusal.AgentUnreachable("missing binary") to AcpTaskOutcome.TRANSPORT_FAILURE,
            AcpRefusal.CredentialSeamAbsent("acp-demo", "not offered") to AcpTaskOutcome.UNKNOWN,
            AcpRefusal.PluginInternal("mechanism") to AcpTaskOutcome.UNKNOWN,
        )
        for ((refusal, expected) in refusals) {
            assertEquals(expected, AcpTaskOutcome.of(refusal), "misclassification for ${refusal.kindToken}")
            val output = AcpTaskOutcome.toOutput(AcpExchange.Refused(refusal))
            assertFalse(output.ok, "a refusal is never ok: ${refusal.kindToken}")
            assertTrue(output.outcome in AcpTaskOutcome.VOCABULARY)
            assertEquals(refusal.kindToken, output.failureKind)
        }
    }

    @Test
    fun `a completed exchange keeps its stop reason and a cancelled turn is its own outcome`() {
        val done = AcpTaskOutcome.toOutput(AcpExchange.Completed("text", "END_TURN"))
        assertTrue(done.ok)
        assertEquals(AcpTaskOutcome.COMPLETED, done.outcome)
        assertEquals("END_TURN", done.stopReason)

        val cancelled = AcpTaskOutcome.toOutput(AcpExchange.Completed("", "CANCELLED"))
        assertTrue(cancelled.ok, "the agent answered — the answer is that the turn was cancelled")
        assertEquals(AcpTaskOutcome.CANCELLED, cancelled.outcome, "AC#3: cancellation is said apart from completion")

        val maxTokens = AcpTaskOutcome.toOutput(AcpExchange.Completed("partial", "MAX_TOKENS"))
        assertEquals(AcpTaskOutcome.COMPLETED, maxTokens.outcome)
        assertEquals("MAX_TOKENS", maxTokens.stopReason, "AC#3: stop reason travels, it is not business verification")
    }

    @Test
    fun `the json settings the step documents are the settings the codec uses`() {
        // ignoreUnknownKeys=false is deliberate: a journal row shaped by a future schema must
        // fail loudly at decode, not silently lose the field a gate is asserting on.
        val encoded = AcpTaskStepDefinition.OutputCodec.encode(AcpTaskOutput(ok = true, outcome = "COMPLETED"))
        val tampered = json.parseToJsonElement(encoded.value).let {
            (it as kotlinx.serialization.json.JsonObject).let { o ->
                kotlinx.serialization.json.JsonObject(o + ("surprise" to kotlinx.serialization.json.JsonPrimitive("x")))
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
            AcpTaskStepDefinition.OutputCodec.decode(
                dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(tampered.toString()),
            )
        }
    }
}
