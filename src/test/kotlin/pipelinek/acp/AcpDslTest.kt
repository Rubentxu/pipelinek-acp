package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `acpTask lowers to one registryStep naming the task key with its full profile encoded`() {
        val emitter = RecordingEmitter()
        emitter.acpTask(
            listOf("my-agent", "--acp"), "fix the test",
            profile = AcpTaskProfile.READONLY, cwd = "work", budgetSeconds = 90, credentialRef = "ci",
        )
        assertEquals(AcpTaskStepDefinition.KEY, emitter.calledKey)
        assertEquals(0, emitter.blockCalls)
        val decoded = AcpTaskStepDefinition.InputCodec.decode(emitter.calledInput!!)
        assertEquals(
            AcpTaskInput(
                command = listOf("my-agent", "--acp"),
                prompt = "fix the test",
                profile = AcpTaskProfile.READONLY,
                cwd = "work",
                budgetSeconds = 90,
                credentialRef = "ci",
            ),
            decoded,
        )
    }

    @Test
    fun `acpTask defaults to the least-privilege profile the omitted arguments could mean`() {
        val emitter = RecordingEmitter()
        emitter.acpTask(listOf("agent"), "p")
        val decoded = AcpTaskStepDefinition.InputCodec.decode(emitter.calledInput!!)
        // READONLY by default: a script that says nothing about writing must not be
        // delegated under a profile that promises confined mutation.
        assertEquals(AcpTaskProfile.READONLY, decoded.profile)
        assertEquals("", decoded.cwd)
        assertEquals("", decoded.credentialRef)
        assertEquals(300L, decoded.budgetSeconds)
    }

    @Test
    fun `acpSession lowers to one registryStep naming the session key with every turn encoded`() {
        val emitter = RecordingEmitter()
        emitter.acpSession(
            command = listOf("agent", "--acp"),
            turns = listOf(
                acpTurn("one"),
                acpTurn("two", content = listOf(acpImage("aGk=", "image/png"))),
            ),
            modeId = "plan",
            configOptions = listOf(AcpConfigInput("verbosity", "high")),
            loadSessionId = "agent-1",
            listSessions = true,
        )
        assertEquals(AcpSessionStepDefinition.KEY, emitter.calledKey)
        assertEquals(0, emitter.blockCalls)
        val decoded = AcpSessionStepDefinition.InputCodec.decode(emitter.calledInput!!)
        assertEquals(2, decoded.turns.size)
        assertEquals("two", decoded.turns[1].text)
        assertEquals(listOf("image"), decoded.turns[1].content.map { it.capability })
        assertEquals("plan", decoded.modeId)
        assertEquals("agent-1", decoded.loadSessionId)
        assertTrue(decoded.listSessions)
    }

    @Test
    fun `acpSession defaults to a plain new session that never deletes on exit`() {
        val emitter = RecordingEmitter()
        emitter.acpSession(listOf("agent"), turns = listOf(acpTurn("p")))
        val decoded = AcpSessionStepDefinition.InputCodec.decode(emitter.calledInput!!)
        // deleteOnExit=false is AC#3's release rule lowered: closing the scope is not
        // erasing history, and a script must SAY it to get it.
        assertEquals(false, decoded.deleteOnExit)
        assertEquals(false, decoded.logout)
        assertEquals(false, decoded.listSessions)
        assertEquals("", decoded.loadSessionId)
        assertEquals("", decoded.resumeSessionId)
        assertEquals(300L, decoded.timeoutSeconds)
    }
}
