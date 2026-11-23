package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-137 AC#1 and AC#4: the runner that EXECUTES a compiled plan.
 *
 * The roles are separate by construction (AC#4): the reader hands events in through
 * [AcpReactionRunner.submit] and never touches the slot; one control loop routes each event to
 * the reactions its selector matches and dispatches them SEQUENTIALLY (invocation order equals
 * event order, one body in flight); the queue between them is bounded, so an agent that talks
 * faster than the pipeline reacts feels backpressure instead of growing the run without limit.
 * Deadlock is what this shape refuses: a body that waits on the agent (a permission, a tool)
 * suspends the control loop, not the reader, so the agent's answer still arrives.
 *
 * The identity law (AC#1): two distinct events fire the same slot twice, each invocation with
 * its own attempt index and its own event cursor; replaying a CONFIRMED event re-runs nothing —
 * the recorded invocation comes back — while a body that did not confirm is owed another attempt.
 */
class AcpReactionRunnerTest {

    /** The engine's side of the seam: records calls, optionally parks the body. */
    private class SlotProbe(
        /** when set, every body waits on it before completing — the test releases it. */
        val park: CompletableDeferred<Unit>? = null,
        val failFirstCall: Boolean = false,
    ) : BodySlotInvoker {
        private var calls = 0
        val started = Channel<Int>(Channel.UNLIMITED)
        val slots = mutableListOf<PluginStepId>()
        val callCount: Int get() = calls

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            calls++
            slots += slot
            started.send(calls)
            park?.await()
            if (failFirstCall && calls == 1) {
                return BodyOutcome.Completed(StepOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "boom")))
            }
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private fun chunk(text: String): SessionUpdate =
        SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

    private fun thought(text: String): SessionUpdate =
        SessionUpdate.AgentThoughtChunk(ContentBlock.Text(text))

    private fun compiled(
        vararg pairs: Pair<String, AcpEventSelector>,
        onceIds: Set<String> = emptySet(),
    ): List<AcpCompiledReaction> = pairs.map { (id, selector) ->
        AcpCompiledReaction(id, PluginStepId(id), selector, once = id in onceIds)
    }

    @Test
    fun `two distinct events fire the same slot twice with distinct identities`() = runTest {
        val slot = SlotProbe()
        val runner = AcpReactionRunner(slot, compiled("alert" to AcpEventSelector.AnyUpdate))
        runner.start(backgroundScope)
        runner.submit(chunk("one"))
        runner.submit(chunk("two"))
        runner.finish()

        assertEquals(
            listOf("acp-reaction.alert" to 1, "acp-reaction.alert" to 2),
            runner.invocations.map { it.attempt.key.value to it.attempt.index },
            "each invocation of the same reaction carries its own attempt identity",
        )
        assertEquals(
            listOf(1L, 2L),
            runner.invocations.map { it.eventCursor },
            "the event each reaction answered is part of the invocation's identity",
        )
        assertEquals(listOf(PluginStepId("alert"), PluginStepId("alert")), slot.slots)
    }

    @Test
    fun `selectors route each event to exactly the reactions that match`() = runTest {
        val slot = SlotProbe()
        val runner = AcpReactionRunner(
            slot,
            compiled(
                "alert" to AcpEventSelector.TextContains("ERROR"),
                "thoughts" to AcpEventSelector.UpdateKind("agent_thought_chunk"),
            ),
        )
        runner.start(backgroundScope)
        runner.submit(chunk("all quiet"))
        runner.submit(thought("hmm"))
        runner.submit(chunk("boom ERROR here"))
        runner.finish()

        assertEquals(
            listOf("thoughts" to 2L, "alert" to 3L),
            runner.invocations.map { it.reactionId to it.eventCursor },
            "the quiet chunk fired nothing; thought and error fired their own slots in event order",
        )
    }

    @Test
    fun `replaying a confirmed event does not re-invoke a confirmed body`() = runTest {
        val slot = SlotProbe()
        val runner = AcpReactionRunner(slot, compiled("alert" to AcpEventSelector.AnyUpdate))
        runner.start(backgroundScope)
        runner.submit(chunk("first"))
        runner.finish()
        val confirmed = runner.invocations.map { Triple(it.reactionId, it.eventCursor, it.attempt.index) }

        val replayed = runner.replay(1L)

        assertEquals(1, slot.callCount, "the confirmed body ran once; replay must not run it again")
        assertEquals(
            confirmed,
            replayed.map { Triple(it.reactionId, it.eventCursor, it.attempt.index) },
            "replay answers with the recorded invocation, not a fresh dispatch",
        )
    }

    @Test
    fun `a replayed event whose body never confirmed is dispatched again`() = runTest {
        val slot = SlotProbe(failFirstCall = true)
        val runner = AcpReactionRunner(slot, compiled("alert" to AcpEventSelector.AnyUpdate))
        runner.start(backgroundScope)
        runner.submit(chunk("first"))
        runner.finish()
        assertEquals(
            true,
            runner.invocations.single().confirmed.not(),
            "a body that failed is not confirmed",
        )

        val replayed = runner.replay(1L)

        assertEquals(2, slot.callCount, "a body that did not confirm is owed another attempt on replay")
        assertEquals(2, replayed.maxOf { it.attempt.index }, "the replay's dispatch carries the NEXT attempt identity")
        assertEquals(2, runner.invocations.size, "both the failed and the replayed attempt are on the record")
    }

    @Test
    fun `once fires the first matching event and records every later match as skipped`() = runTest {
        val slot = SlotProbe()
        val runner = AcpReactionRunner(
            slot,
            compiled("kick" to AcpEventSelector.AnyUpdate, onceIds = setOf("kick")),
        )
        runner.start(backgroundScope)
        runner.submit(chunk("a"))
        runner.submit(chunk("b"))
        runner.finish()

        assertEquals(1, runner.invocations.size, "once means one dispatch, whatever the agent says after")
        assertEquals(listOf(2L), runner.skipped.map { it.eventCursor }, "the skip is observed, not silently dropped")
    }

    @Test
    fun `the reader never invokes the slot itself - dispatch lives on the control loop`() = runTest {
        val park = CompletableDeferred<Unit>()
        val slot = SlotProbe(park = park)
        val runner = AcpReactionRunner(slot, compiled("alert" to AcpEventSelector.AnyUpdate))
        runner.start(backgroundScope)

        val arrived = CompletableDeferred<Unit>()
        launch {
            runner.submit(chunk("slow body"))
            arrived.complete(Unit)
        }
        slot.started.receive()

        // The body has BEGUN and still the reader already returned: what the body waits on
        // cannot block what feeds the runner.
        assertTrue(arrived.isCompleted, "submit must not wait for the body the dispatch started")
        assertEquals(0, runner.invocations.size, "the invocation is not recorded while its body is in flight")

        park.complete(Unit)
        runner.finish()
        assertEquals(1, runner.invocations.size)
    }

    @Test
    fun `a full queue backpressures the reader instead of growing without bound`() = runTest {
        val park = CompletableDeferred<Unit>()
        val slot = SlotProbe(park = park)
        val runner = AcpReactionRunner(
            slot,
            compiled("alert" to AcpEventSelector.AnyUpdate),
            queueCapacity = 1,
        )
        runner.start(backgroundScope)

        runner.submit(chunk("being dispatched")) // taken by the control loop, body parks
        runner.submit(chunk("queued")) // fits the bounded queue
        val overflow = CompletableDeferred<Unit>()
        launch {
            runner.submit(chunk("overflow"))
            overflow.complete(Unit)
        }

        slot.started.receive()
        while (slot.callCount < 1) yield()
        assertFalse(overflow.isCompleted, "with the queue full the reader must WAIT, not buffer a third event")

        park.complete(Unit)
        overflow.await()
        runner.finish()
        assertEquals(
            listOf(1L, 2L, 3L),
            runner.invocations.map { it.eventCursor },
            "sequential execution: bodies ran in event order even though the reader raced ahead",
        )
    }

    @Test
    fun `the empty plan keeps the shipped address so the published law is unchanged`() = runTest {
        val slot = SlotProbe()
        val plan = compileAcpReactionPlan(emptyList(), emptyList()) as AcpPlanCompilation.Compiled
        val runner = AcpReactionRunner(slot, plan.reactions)
        runner.start(backgroundScope)
        runner.submit(chunk("x"))
        runner.finish()

        assertEquals(
            listOf(AcpReactiveStepDefinition.REACTION_SLOT to AcpReactiveStepDefinition.ATTEMPT_KEY),
            runner.invocations.map { it.slot to it.attempt.key },
            "an undeclared plan is today's step: one unnamed slot, the shipped attempt key",
        )
    }
}
