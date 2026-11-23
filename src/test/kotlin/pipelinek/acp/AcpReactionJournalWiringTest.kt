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
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-138 over the EXECUTOR: what happens when [AcpReactionRunner] dispatches through the
 * durable ledger, and when [AcpReactiveStepDefinition] ships those rows in its own output.
 *
 * The port laws themselves (crash phases, origin classification, identity minting) are measured
 * in [AcpJournalReconciliationTest]; these rows prove the port is not dead code: the runner's
 * dispatch path is where the intent-before-effect / ack-after-effect order, the renumbered-
 * cursor dedupe, the fail-closed identity refusal, the operator gates and the abort's unknown-
 * marking actually run.
 */
class AcpReactionJournalWiringTest {

    private class SlotProbe(
        val park: CompletableDeferred<Unit>? = null,
        val succeed: Boolean = true,
    ) : BodySlotInvoker {
        var calls = 0
        val entered = CompletableDeferred<Unit>()

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            calls++
            entered.complete(Unit)
            park?.await()
            return if (succeed) BodyOutcome.Completed(StepOutcome.Success)
            else BodyOutcome.Completed(
                StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "body failed")),
            )
        }
    }

    /** A durable store the tests break on purpose: [failOn] throws at the chosen phase. */
    private class DurableStore(val failOn: AcpEffectPhase? = null) {
        val rows = mutableListOf<AcpReactionLedgerRow>()
        val sink: (AcpReactionLedgerRow) -> Unit = { row ->
            if (row.phase == failOn) throw IllegalStateException("simulated crash at ${row.phase}")
            rows += row
        }
    }

    private fun chunk(text: String): SessionUpdate =
        SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

    private fun compiled(
        id: String,
        selector: AcpEventSelector = AcpEventSelector.AnyUpdate,
        operator: AcpReactionOperator? = null,
        effectful: Boolean = false,
        once: Boolean = false,
    ) = AcpCompiledReaction(id, PluginStepId(id), selector, once, false, operator, effectful)

    @Test
    fun `the runner never invokes the slot when the intent write fails`() = runTest {
        val store = DurableStore(failOn = AcpEffectPhase.INTENT)
        val slot = SlotProbe()
        val ledger = AcpReactionLedger(persist = store.sink)
        val runner = AcpReactionRunner(
            slot, listOf(compiled("alert", effectful = true)),
            connectionNonce = "c1", journal = ledger,
        )
        runner.start(backgroundScope)
        runner.submit(chunk("hello"))
        runner.finish()
        assertEquals(0, slot.calls, "no intent in the journal, no effect on the world")
        assertEquals(0, runner.invocations.size)
        assertTrue(runner.skipped.any { it.reason.contains("intent not journalled") })
    }

    @Test
    fun `replay of an unknown effect refuses to re-drive it automatically`() = runTest {
        val store = DurableStore(failOn = AcpEffectPhase.ACKED)
        // The body never confirms (it failed) and the ack never arrives either: the row is the
        // honest UNKNOWN and an unconfirmed invocation — exactly the pair replay must NOT re-drive.
        val slot = SlotProbe(succeed = false)
        val ledger = AcpReactionLedger(persist = store.sink)
        val runner = AcpReactionRunner(
            slot, listOf(compiled("alert", effectful = true)),
            connectionNonce = "c1", journal = ledger,
        )
        runner.start(backgroundScope)
        runner.submit(chunk("hello"))
        runner.finish()
        assertEquals(1, slot.calls)
        val replayed = runner.replay(1)
        assertEquals(1, slot.calls, "an unknown effect is never re-driven automatically")
        assertEquals(0, replayed.size, "the owed attempt stays blocked until the unknown is decided")
        assertTrue(runner.skipped.any { it.reason.contains("unknown effect") })
    }

    @Test
    fun `a reconnect that renumbers cursors does not re-fire a durably acknowledged reaction`() =
        runTest {
            // The prior journal proves reaction "ping" paid for external event e-7 (cursor 5).
            val priorRows = listOf(
                AcpReactionLedgerRow(
                    "ping", EventKey.External("agent", "e-7"), 5, 1, AcpEffectPhase.ACKED,
                ),
            )
            val slot = SlotProbe()
            val ledger = AcpReactionLedger(priorRows = priorRows)
            // The reconnect replays the SAME external ids with fresh cursors.
            val runner = AcpReactionRunner(
                slot, listOf(compiled("ping", effectful = true)),
                connectionNonce = "c2", journal = ledger,
            )
            runner.start(backgroundScope)
            runner.submit(
                chunk("replayed"), externalEventId = "e-7",
                externalEventSource = "agent", externalTrustworthy = true,
            )
            runner.finish()
            assertEquals(0, slot.calls, "dedupe compares EventKeys, never the renumbered cursors")
            assertTrue(runner.skipped.any { it.reason.contains("durably acknowledged") })
        }

    @Test
    fun `an unaddressable event on a journalled run fails closed instead of dispatching`() =
        runTest {
            val slot = SlotProbe()
            // No connection nonce and no external id: no key exists, so no durable row could
            // ever own the effect — the fail-closed answer is a recorded skip, not a dispatch.
            val runner = AcpReactionRunner(
                slot, listOf(compiled("alert", effectful = true)),
                journal = AcpReactionLedger(),
            )
            runner.start(backgroundScope)
            runner.submit(chunk("hello"))
            runner.finish()
            assertEquals(0, slot.calls)
            assertTrue(runner.skipped.any { it.reason.contains("unaddressable event") })
        }

    @Test
    fun `take dispatches its budget and records every later match as a skip`() = runTest {
        val slot = SlotProbe()
        val runner = AcpReactionRunner(
            slot, listOf(compiled("alert", operator = AcpReactionOperator.Take(2))),
            clock = AcpClock { 0L },
        )
        runner.start(backgroundScope)
        repeat(4) { runner.submit(chunk("event-$it")) }
        runner.finish()
        assertEquals(2, runner.invocations.size)
        val suppressed = runner.skipped.filter { it.reason.startsWith("take(") }
        assertEquals(listOf(3L, 4L), suppressed.map { it.eventCursor })
    }

    @Test
    fun `a permission request is never discarded even when the operator budget is spent`() =
        runTest {
            val slot = SlotProbe()
            val runner = AcpReactionRunner(
                slot, listOf(compiled("alert", operator = AcpReactionOperator.Take(1))),
                clock = AcpClock { 0L },
            )
            runner.start(backgroundScope)
            runner.submit(chunk("first")) // spends the take
            runner.submit(chunk("second")) // suppressed by the budget
            runner.submit(chunk("may i"), permissionRequest = true) // protected: never dropped
            runner.finish()
            assertEquals(2, runner.invocations.size)
            assertTrue(runner.skipped.any { it.reason.startsWith("take(") })
        }

    @Test
    fun `abort closes the subscription marks unacked intents unknown and leaves no worker`() =
        runTest {
            val store = DurableStore()
            val slot = SlotProbe(park = CompletableDeferred())
            val ledger = AcpReactionLedger(persist = store.sink)
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val runner = AcpReactionRunner(
                slot, listOf(compiled("alert", effectful = true)),
                connectionNonce = "c1", journal = ledger,
            )
            runner.start(scope)
            runner.submit(chunk("hello"))
            slot.entered.await() // the body is in flight, its intent written, no ack yet
            runner.abort()
            // The in-flight intent never acked: it lands UNKNOWN, the honest recovery verdict.
            assertTrue(ledger.rows().any { it.phase == AcpEffectPhase.UNKNOWN })
            assertTrue(store.rows.none { it.phase == AcpEffectPhase.ACKED })
            // Zero orphan workers: the control loop died with the abort and joined before return.
            assertTrue(scope.coroutineContext.job.children.toList().isEmpty(), "no worker survives")
            // A late event after the abort is dropped at the door, never dispatched.
            runner.submit(chunk("too late"))
            assertEquals(0, runner.invocations.size)
            // And the durable truth (intent, no ack) recovers as unknown.
            assertTrue(
                AcpReactionLedger(priorRows = store.rows)
                    .isUnknown("alert", EventKey.ConnectionPersisted("c1", 1)),
            )
            scope.cancel()
        }

    @Test
    fun `a confirmed dispatch journals its intent before the body and its ack after`() = runTest {
        val order = mutableListOf<String>()
        val slot = object : BodySlotInvoker {
            override suspend fun invoke(s: PluginStepId, c: BodyInvocationContext): BodyOutcome {
                order += "effect"
                return BodyOutcome.Completed(StepOutcome.Success)
            }
        }
        val ledger = AcpReactionLedger(persist = { row -> order += "persist:${row.phase}" })
        val runner = AcpReactionRunner(
            slot, listOf(compiled("alert", effectful = true)),
            connectionNonce = "c1", journal = ledger,
        )
        runner.start(backgroundScope)
        runner.submit(chunk("hello"))
        runner.finish()
        assertEquals(listOf("persist:INTENT", "effect", "persist:ACKED"), order)
        val invocation = runner.invocations.single()
        assertNotNull(invocation.eventKey)
        assertInstanceOf(AcpDriveVerdict.Executed::class.java, invocation.driveVerdict)
        assertEquals(1, runner.journalRows.count { it.phase == AcpEffectPhase.ACKED })
    }

    @Test
    fun `a body that failed keeps its intent unacked so recovery calls it unknown`() = runTest {
        val store = DurableStore()
        val slot = SlotProbe(succeed = false)
        val ledger = AcpReactionLedger(persist = store.sink)
        val runner = AcpReactionRunner(
            slot, listOf(compiled("alert", effectful = true)),
            connectionNonce = "c1", journal = ledger,
        )
        runner.start(backgroundScope)
        runner.submit(chunk("hello"))
        runner.finish()
        // The body ran and said no: whether its external effect half-happened is unknowable,
        // so the durable row stops at intent and this ledger calls it unknown immediately.
        assertEquals(listOf(AcpEffectPhase.INTENT), store.rows.map { it.phase })
        assertTrue(runner.journalRows.any { it.phase == AcpEffectPhase.UNKNOWN })
        val recovered = AcpReactionLedger(priorRows = store.rows)
        assertInstanceOf(
            AcpReconciledDecision.Suspended::class.java,
            AcpReactionReconciler(recovered, "c2")
                .decide("alert", EventKey.ConnectionPersisted("c1", 1), AcpStreamOrigin.LIVE, effectful = true),
        )
    }

    @Test
    fun `cursor and result round-trip through the step's own durable output codec`() {
        val output = AcpReactiveOutput(
            ok = true,
            reactionLedger = listOf(
                AcpReactionLedgerRow("ping", EventKey.External("agent", "e1"), 5, 1, AcpEffectPhase.INTENT),
                AcpReactionLedgerRow("ping", EventKey.External("agent", "e1"), 5, 1, AcpEffectPhase.ACKED),
                AcpReactionLedgerRow(
                    "notify", EventKey.ConnectionPersisted("c1", 6), 6, 1, AcpEffectPhase.UNKNOWN,
                ),
            ),
        )
        val decoded = AcpReactiveStepDefinition.OutputCodec.decode(
            AcpReactiveStepDefinition.OutputCodec.encode(output),
        )
        assertEquals(output, decoded, "the journal row outlives the artifact that wrote it")
    }

    @Test
    fun `a pre-PKH-138 output row still decodes with an empty ledger`() {
        // The format extension is MINIMAL: one defaulted field. Yesterday's encoding — the shape
        // shipped by PKH-137 — must keep decoding, or the durable journal would lie to old runs.
        val legacy = """{"ok":true,"reply":"r","stopReason":"END_TURN","reactions":2,""" +
            """"failureKind":"","detail":"","reactionSummary":[],"awaitVerdicts":[]}"""
        val decoded = AcpReactiveStepDefinition.OutputCodec.decode(EncodedStepValue(legacy))
        assertTrue(decoded.reactionLedger.isEmpty())
        assertEquals(2, decoded.reactions)
    }

    @Test
    fun `the ledger survives the output encoding the journal stores`() {
        // What the step ships is what a recovered run reads back: the row's key, phase and
        // cursor keep their names through the step's own codec, SDK-free (the AC#3 authority is
        // this encoding, not an in-memory copy).
        val output = AcpReactiveOutput(ok = true, reactionLedger = listOf(
            AcpReactionLedgerRow("a", EventKey.ConnectionPersisted("c", 1), 1, 1, AcpEffectPhase.ACKED),
        ))
        val json = AcpReactiveStepDefinition.OutputCodec.encode(output).value
        assertTrue(json.contains("\"reactionLedger\""))
        assertTrue(json.contains("ConnectionPersisted"))
        assertTrue(json.contains("\"cursor\":1"))
    }
}
