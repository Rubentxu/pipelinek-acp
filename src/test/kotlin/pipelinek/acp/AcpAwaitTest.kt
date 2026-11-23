package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * PKH-137 AC#3: `await(condition, timeout)` inside a live session.
 *
 * Await observes the SAME event stream the reactions route on, so "wait for" and "react to"
 * cannot drift into two event languages. The two halves of its contract: it must see events
 * that arrived BEFORE it asked (current state, not just the future), and it must not lose an
 * event that lands between its snapshot and its subscription. And it must give up on its
 * budget — an await whose timeout never fires is the unbounded wait the plan compiler refused.
 */
class AcpAwaitTest {

    private class OkSlot : BodySlotInvoker {
        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome =
            BodyOutcome.Completed(StepOutcome.Success)
    }

    private class ParkingSlot : BodySlotInvoker {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val park = CompletableDeferred<Unit>()
        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            started.trySend(Unit)
            park.await()
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private fun chunk(text: String): SessionUpdate = SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

    private val errorHere = AcpEventSelector.TextContains("ERROR")

    @Test
    fun `await sees an update that arrived before it asked`() = runTest {
        val runner = AcpReactionRunner(OkSlot(), listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        runner.submit(chunk("boom ERROR here"))

        val verdict = runner.await(errorHere, timeoutMillis = 1_000)

        assertEquals(AcpAwaitVerdict.Satisfied(1L), verdict, "the already-observed event IS the current state await must honour")
        runner.finish()
    }

    @Test
    fun `await waits for an update that arrives after it asked`() = runTest {
        val runner = AcpReactionRunner(OkSlot(), listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        val waiting = CompletableDeferred<AcpAwaitVerdict>()
        launch { waiting.complete(runner.await(errorHere, timeoutMillis = 1_000)) }

        runner.submit(chunk("later ERROR"))
        runner.finish()

        assertEquals(AcpAwaitVerdict.Satisfied(1L), waiting.await())
    }

    @Test
    fun `await gives up on its budget and says so`() = runTest {
        val runner = AcpReactionRunner(OkSlot(), listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        val late = launch {
            delay(5_000)
            runner.submit(chunk("too late ERROR"))
        }

        val verdict = runner.await(errorHere, timeoutMillis = 1_000)

        assertEquals(AcpAwaitVerdict.TimedOut, verdict, "the budget expired at 1s, four seconds before the event existed")
        assertEquals(0, runner.invocations.size, "nothing was dispatched for an event the agent had not sent yet")
        late.cancel()
        runner.finish()
    }

    @Test
    fun `await skips the updates that do not match`() = runTest {
        val runner = AcpReactionRunner(OkSlot(), listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        runner.submit(chunk("all quiet"))
        val waiting = CompletableDeferred<AcpAwaitVerdict>()
        launch { waiting.complete(runner.await(errorHere, timeoutMillis = 1_000)) }

        runner.submit(chunk("boom ERROR here"))
        val verdict = waiting.await()

        assertEquals(AcpAwaitVerdict.Satisfied(2L), verdict, "the cursor await reports is the matching event, not its predecessor")
        runner.finish()
    }

    @Test
    fun `a parked body cannot starve await - the event stream flows past the dispatch loop`() = runTest {
        val slot = ParkingSlot()
        val runner = AcpReactionRunner(slot, listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        // Event 2 lands while the control loop is stuck inside event 1's body. Await reads the
        // OBSERVED stream, not the dispatched one, so it is answered from under the parked body.
        val awaited = AcpEventSelector.TextContains("second")
        runner.submit(chunk("first ERROR, body parked")) // control loop enters the body and parks
        slot.started.receive()
        runner.submit(chunk("second ERROR"))
        val verdict = runner.await(awaited, timeoutMillis = 1_000)

        assertEquals(AcpAwaitVerdict.Satisfied(2L), verdict)
        slot.park.complete(Unit)
        runner.finish()
    }

    @Test
    fun `await over a burst reports the first event that matched`() = runTest {
        val runner = AcpReactionRunner(OkSlot(), listOf(AcpCompiledReaction("alert", PluginStepId("alert"), errorHere, false)))
        runner.start(backgroundScope)
        val waiting = CompletableDeferred<AcpAwaitVerdict>()
        launch { waiting.complete(runner.await(errorHere, timeoutMillis = 1_000)) }
        repeat(10) { runner.submit(chunk("quiet $it")) }
        runner.submit(chunk("ERROR at eleven"))
        repeat(10) { runner.submit(chunk("quiet again $it")) }

        assertEquals(AcpAwaitVerdict.Satisfied(11L), waiting.await())
        runner.finish()
    }

    @Test
    fun `an await without positive budget is refused, not turned into an infinite wait`() {
        val runner = AcpReactionRunner(OkSlot(), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runner.await(AcpEventSelector.AnyUpdate, timeoutMillis = 0L) }
        }
    }
}
