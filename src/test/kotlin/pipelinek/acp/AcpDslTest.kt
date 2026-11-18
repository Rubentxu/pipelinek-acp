package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The DSL lowers to the ONE primitive the published seam offers.
 *
 * A fake emitter records what a compiled script would push into the registry; the guarantee
 * this guards is that `acpPrompt` names THIS plugin's step key and encodes the input with THIS
 * plugin's codec — a drift there is a step that resolves to no handler at execution time.
 */
class AcpDslTest {

    private class RecordingEmitter : StepEmitter<RecordingEmitter> {
        var calledKey: PluginStepId? = null
        var calledInput: EncodedStepValue? = null
        var blockCalls = 0

        override fun registryStep(stepKey: PluginStepId, encodedInput: EncodedStepValue) {
            calledKey = stepKey
            calledInput = encodedInput
        }

        override fun registryBlock(
            stepKey: PluginStepId,
            encodedInput: EncodedStepValue,
            block: RecordingEmitter.() -> Unit,
        ) {
            blockCalls++
        }
    }

    @Test
    fun `acpPrompt lowers to a single registryStep with the plugin key and its own encoding`() {
        val emitter = RecordingEmitter()
        emitter.acpPrompt(listOf("agent", "--acp"), "do the thing", cwd = "/w", timeoutSeconds = 7)

        assertEquals(AcpPromptStepDefinition.KEY, emitter.calledKey)
        // Step 2 (blocks) is PKH-133; the contract route must not silently declare a body.
        assertEquals(0, emitter.blockCalls)
        val decoded = AcpPromptStepDefinition.InputCodec.decode(emitter.calledInput!!)
        assertEquals(
            AcpPromptInput(listOf("agent", "--acp"), "do the thing", "/w", 7),
            decoded,
        )
    }

    @Test
    fun `defaults land on the input the script omitted`() {
        val emitter = RecordingEmitter()
        emitter.acpPrompt(listOf("agent"), "p")
        val decoded = AcpPromptStepDefinition.InputCodec.decode(emitter.calledInput!!)
        assertEquals("", decoded.cwd)
        assertEquals(300L, decoded.timeoutSeconds)
    }
}
