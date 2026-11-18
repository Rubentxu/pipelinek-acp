package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The codec seam the durable log depends on: what a step wrote must read back identical. */
class AcpCodecTest {

    @Test
    fun `input survives a round trip through the encoded form`() {
        val value = AcpPromptInput(
            command = listOf("my-agent", "--acp", "--verbose"),
            prompt = "summarize\nthe diff",
            cwd = "/tmp/work",
            timeoutSeconds = 42,
        )
        assertEquals(value, AcpPromptStepDefinition.InputCodec.decode(
            AcpPromptStepDefinition.InputCodec.encode(value),
        ))
    }

    @Test
    fun `output survives a round trip and a refusal keeps its kind token`() {
        val value = AcpPromptOutput(
            ok = false,
            failureKind = AcpRefusal.VersionMismatch(1, 2, setOf(1)).kindToken,
            detail = "agent offered protocol version 2",
        )
        val decoded = AcpPromptStepDefinition.OutputCodec.decode(
            AcpPromptStepDefinition.OutputCodec.encode(value),
        )
        assertEquals(value, decoded)
        assertEquals("ACP_VERSION_MISMATCH", decoded.failureKind)
    }

    @Test
    fun `the encoded form is the plugin's own JSON and nothing else leaked into it`() {
        val encoded = AcpPromptStepDefinition.InputCodec.encode(
            AcpPromptInput(command = listOf("a"), prompt = "p"),
        )
        // The wire form is durable state: a reader outside the JVM must be able to parse it,
        // so it is JSON text with the plugin's field names.
        assertEquals(
            AcpPromptInput(command = listOf("a"), prompt = "p"),
            AcpPromptStepDefinition.InputCodec.decode(encoded),
        )
        val text = encoded.value
        assertEquals(true, text.startsWith("{") && text.endsWith("}"), "not a JSON object: $text")
        assertEquals(true, "\"command\"" in text)
    }
}
