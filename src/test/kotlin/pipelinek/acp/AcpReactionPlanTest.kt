package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-137 AC#3: the reaction plan is DECLARED as typed, serializable data — an `on(id,
 * selector)` list that compiles to static slot addresses before any agent is launched.
 *
 * The law this guards is the one the contract states as "never lambdas runtime arbitrarias":
 * everything a reaction needs travels in the encoded step input (durable state the journal
 * keeps), so a plan read back from the journal routes the SAME events to the SAME slots. A
 * selector that was a lambda could not survive that round trip, and a slot address the plugin
 * had to invent at runtime would split the identity the engine builds from the child's ADDRESS.
 */
class AcpReactionPlanTest {

    private val json = Json { encodeDefaults = true }

    private fun chunk(text: String): SessionUpdate =
        SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

    @Test
    fun `the selector vocabulary is durable data - the whole plan round-trips without any SDK class`() {
        val plan = listOf(
            acpOn("alert", AcpEventSelector.TextContains("ERROR")),
            acpOn("thoughts", AcpEventSelector.UpdateKind("agent_thought_chunk"), once = true),
            acpOn("everything", AcpEventSelector.AnyUpdate),
        )

        val encoded = json.encodeToString(ListSerializer(AcpReactionDeclaration.serializer()), plan)
        assertEquals(plan, json.decodeFromString(ListSerializer(AcpReactionDeclaration.serializer()), encoded))
        // The serialized document is the proof: each selector appears as a JSON object with a
        // type tag. A lambda-backed "selector" would be absent here and unpicklable there.
        assertTrue("\"TextContains\"" in encoded, encoded)
        assertTrue("\"UpdateKind\"" in encoded, encoded)
        assertTrue("\"AnyUpdate\"" in encoded, encoded)
    }

    @Test
    fun `each typed selector matches exactly the updates its name says and no others`() {
        val thought = SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking hard"))
        val planRemoved = SessionUpdate.PlanRemoved("dropped")

        assertTrue(AcpEventSelector.AnyUpdate.matches(planRemoved))
        assertTrue(AcpEventSelector.UpdateKind("plan_removed").matches(planRemoved))
        assertFalse(AcpEventSelector.UpdateKind("plan_removed").matches(chunk("x")))
        assertTrue(AcpEventSelector.TextContains("ERR").matches(chunk("boom ERROR here")))
        assertFalse(AcpEventSelector.TextContains("ERR").matches(thought))
        assertTrue(AcpEventSelector.TextContains("hard", inReasoning = true).matches(thought))
        // The unknown variant keeps the agent's own token: a selector matches what the WIRE
        // said, not what this SDK's model happens to know.
        val foreign = SessionUpdate.UnknownSessionUpdate(
            "custom_update",
            kotlinx.serialization.json.JsonObject(emptyMap()),
            null,
        )
        assertTrue(AcpEventSelector.UpdateKind("custom_update").matches(foreign))
        assertFalse(AcpEventSelector.UpdateKind("custom_update").matches(thought))
    }

    @Test
    fun `compiling a plan addresses each reaction by its own name, statically`() {
        val compilation = compileAcpReactionPlan(
            reactions = listOf(
                acpOn("pipelinek.acp.reaction.alert", AcpEventSelector.TextContains("ERROR")),
                acpOn("pipelinek.acp.reaction.thoughts", AcpEventSelector.AnyUpdate, once = true),
            ),
            awaits = listOf(acpAwait(AcpEventSelector.AnyUpdate, 1_000)),
        )

        val compiled = compilation as AcpPlanCompilation.Compiled
        assertEquals(
            listOf(
                PluginStepId("pipelinek.acp.reaction.alert"),
                PluginStepId("pipelinek.acp.reaction.thoughts"),
            ),
            compiled.reactions.map { it.slot },
            "the slot address is the declaration's id — the name the DSL gave the child",
        )
        assertEquals(
            listOf(false, true),
            compiled.reactions.map { it.once },
            "once travels with the compiled reaction; the runner enforces it, not the plan author's memory",
        )
        assertEquals(1, compiled.awaits.size)
    }

    @Test
    fun `a plan the engine could not route is refused at compile time, not at the agent`() {
        val duplicate = compileAcpReactionPlan(
            reactions = listOf(
                acpOn("same", AcpEventSelector.AnyUpdate),
                acpOn("same", AcpEventSelector.TextContains("x")),
            ),
            awaits = emptyList(),
        )
        assertTrue(
            duplicate is AcpPlanCompilation.Rejected,
            "two reactions named alike are an ambiguous slot address; the seam refuses to guess, so the plan must not compile either",
        )

        val blank = compileAcpReactionPlan(
            reactions = listOf(
                acpOn("", AcpEventSelector.AnyUpdate),
                acpOn("empty-match", AcpEventSelector.TextContains("")),
            ),
            awaits = listOf(acpAwait(AcpEventSelector.AnyUpdate, 0)),
        )
        val rejected = blank as AcpPlanCompilation.Rejected
        assertEquals(
            3,
            rejected.reasons.size,
            "a nameless slot, an always-true empty fragment and a zero await timeout are each refused: ${rejected.reasons}",
        )
        assertTrue(rejected.reasons.any { "empty-match" in it }, "the reason names the declaration it rejects: ${rejected.reasons}")
        assertTrue(rejected.reasons.any { "await" in it }, "the await timeout is refused as the await's own problem: ${rejected.reasons}")
    }

    @Test
    fun `an empty plan compiles to exactly the single unnamed reaction the step has always had`() {
        // PKH-136 shipped acpReactive dispatching ONE body child addressed by its kind. A plan
        // that declares nothing must compile to that same address, or the already-published
        // law (one slot, every update, own attempt identity) would change meaning.
        val compilation = compileAcpReactionPlan(emptyList(), emptyList()) as AcpPlanCompilation.Compiled
        assertEquals(
            listOf(AcpReactiveStepDefinition.REACTION_SLOT),
            compilation.reactions.map { it.slot },
        )
        assertTrue(compilation.reactions.single().selector is AcpEventSelector.AnyUpdate)
    }
}
