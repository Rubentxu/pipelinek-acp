package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * PKH-138 (PKACP-07): the RECONCILIATION PORT over the existing durable journal — the data
 * laws of [AcpReactionReconciliation], measured where they live.
 *
 * The rows here are the port's own guarantees:
 *
 *  * AC#1 — the three crash windows around an effect (before the intent write, after it, after
 *    the effect but before the ack) are injected by a persist sink that THROWS, and every
 *    unknown outcome answers with suspension, never an automatic resend.
 *  * AC#2 — history never re-fires effects and an origin the run cannot prove suspends them.
 *  * AC#3 — the (run, operation, session, reaction, event) tuple cannot collide, and an
 *    unprovable identity fails closed instead of guessing.
 *  * AC#4 — take/distinct/debounce carry limits and clock rules and replay as a pure fold; the
 *    compiler refuses the unlimited and the misdeclared, and a permission request is never
 *    discarded.
 *
 * What the runner and the step's output do with this port — dispatch order, dedupe on a live
 * reconnect, the abort's unknown-marking, the codec round-trip — is measured in
 * [AcpReactionJournalWiringTest].
 */
class AcpJournalReconciliationTest {

    /** A durable store the tests break on purpose: [failOn] throws at the chosen phase. */
    private class DurableStore(val failOn: AcpEffectPhase? = null) {
        val rows = mutableListOf<AcpReactionLedgerRow>()
        val sink: (AcpReactionLedgerRow) -> Unit = { row ->
            if (row.phase == failOn) throw IllegalStateException("simulated crash at ${row.phase}")
            rows += row
        }
    }

    // ------------------------------------------------------------- AC#1 crashes

    @Test
    fun `crash before the intent write means the effect never ran and redrive is allowed`() {
        val store = DurableStore(failOn = AcpEffectPhase.INTENT)
        val ledger = AcpReactionLedger(persist = store.sink)
        var effectRan = false
        val verdict = runBlocking {
            ledger.drive("ping", EventKey.ConnectionPersisted("c1", 1), 1, 1) {
                effectRan = true
                true
            }
        }
        assertInstanceOf(AcpDriveVerdict.IntentNotPersisted::class.java, verdict)
        assertFalse(effectRan, "an effect whose intent the journal never saw must not run")
        assertTrue(store.rows.isEmpty())
        // Nothing durable exists, so a reconciler on the empty journal may dispatch again.
        val recovered = AcpReactionLedger(priorRows = store.rows)
        val decision = AcpReactionReconciler(recovered, "c1")
            .decide("ping", EventKey.ConnectionPersisted("c1", 1), AcpStreamOrigin.LIVE, effectful = true)
        assertEquals(AcpReconciledDecision.Fire, decision)
    }

    @Test
    fun `crash after the intent and before the ack marks the effect unknown and never re-sends`() {
        val store = DurableStore(failOn = AcpEffectPhase.ACKED)
        val ledger = AcpReactionLedger(persist = store.sink)
        var effectRan = false
        val verdict = runBlocking {
            ledger.drive("ping", EventKey.ConnectionPersisted("c1", 3), 3, 1) {
                effectRan = true
                true
            }
        }
        assertTrue(effectRan)
        assertEquals(AcpDriveVerdict.EffectUnknown, verdict)
        // Only the intent survived into durability — exactly what a recovery would read back.
        assertEquals(listOf(AcpEffectPhase.INTENT), store.rows.map { it.phase })
        val recovered = AcpReactionLedger(priorRows = store.rows)
        assertTrue(recovered.isUnknown("ping", EventKey.ConnectionPersisted("c1", 3)))
        val decision = AcpReactionReconciler(recovered, "c2")
            .decide("ping", EventKey.ConnectionPersisted("c1", 3), AcpStreamOrigin.LIVE, effectful = true)
        assertInstanceOf(AcpReconciledDecision.Suspended::class.java, decision)
    }

    @Test
    fun `an effect that threw after its intent is unknown too and its ack never appears`() {
        val store = DurableStore()
        val ledger = AcpReactionLedger(persist = store.sink)
        val verdict = runBlocking {
            ledger.drive("ping", EventKey.ConnectionPersisted("c1", 2), 2, 1) {
                throw IllegalStateException("the external call exploded mid-flight")
            }
        }
        assertEquals(AcpDriveVerdict.EffectUnknown, verdict)
        assertEquals(listOf(AcpEffectPhase.INTENT), store.rows.map { it.phase })
        assertTrue(ledger.rows().any { it.phase == AcpEffectPhase.UNKNOWN })
    }

    // ----------------------------------------------------------- AC#2 origins

    @Test
    fun `v1 history never re-fires effects while coalescible telemetry keeps flowing`() {
        val reconciler = AcpReactionReconciler(AcpReactionLedger(), "c1")
        val key = EventKey.ConnectionPersisted("c0", 9)
        assertEquals(
            AcpReconciledDecision.SuppressedHistoric,
            reconciler.decide("ping", key, AcpStreamOrigin.HISTORIC, effectful = true),
        )
        assertEquals(
            AcpReconciledDecision.Fire,
            reconciler.decide("telemetry", key, AcpStreamOrigin.HISTORIC, effectful = false),
        )
    }

    @Test
    fun `indistinguishable history suspends effectful reactions until the run reconciles`() {
        val reconciler = AcpReactionReconciler(AcpReactionLedger(), "c1")
        val key = EventKey.ConnectionPersisted("c1", 4)
        val suspended = reconciler.decide("ping", key, AcpStreamOrigin.INDISTINGUISHABLE, effectful = true)
        assertInstanceOf(AcpReconciledDecision.Suspended::class.java, suspended)
        assertTrue((suspended as AcpReconciledDecision.Suspended).reason.contains("suspended"))
        assertEquals(
            AcpReconciledDecision.Fire,
            reconciler.decide("telemetry", key, AcpStreamOrigin.INDISTINGUISHABLE, effectful = false),
        )
    }

    @Test
    fun `a foreign connection nonce proves the event is history not live`() {
        val reconciler = AcpReactionReconciler(AcpReactionLedger(), "mine")
        val foreign = EventKey.ConnectionPersisted("theirs", 1)
        // The nonce law: counters belong to the connection that counted, so a foreign-persisted
        // key under a live origin is still not this stream's live effect.
        assertEquals(
            AcpReconciledDecision.SuppressedHistoric,
            reconciler.decide("ping", foreign, AcpStreamOrigin.LIVE, effectful = true),
        )
    }

    @Test
    fun `the reconciler dedupes by event key not cursor and suspends on a blank reaction`() {
        val prior = listOf(
            AcpReactionLedgerRow("ping", EventKey.External("agent", "e-7"), 5, 1, AcpEffectPhase.ACKED),
        )
        val reconciler = AcpReactionReconciler(AcpReactionLedger(priorRows = prior), "c2")
        // Same external key, renumbered cursor, live origin: the ack was for the IDENTITY, and
        // an identity does not get a second effect because a counter restarted.
        assertEquals(
            AcpReconciledDecision.SuppressedAcknowledged,
            reconciler.decide("ping", EventKey.External("agent", "e-7"), AcpStreamOrigin.LIVE, effectful = true),
        )
        assertInstanceOf(
            AcpReconciledDecision.Suspended::class.java,
            reconciler.decide("", EventKey.External("agent", "e-7"), AcpStreamOrigin.LIVE, effectful = true),
        )
    }

    // ------------------------------------------------------------ AC#3 identity

    @Test
    fun `the run-operation-session-reaction-event tuple cannot collide across runs or branches`() {
        val base = EventKey.ConnectionPersisted("c1", 1)
        val paths = setOf(
            AcpReactionIdentity("r1", "op1", "s1", "ping", base),
            AcpReactionIdentity("r2", "op1", "s1", "ping", base), // another run
            AcpReactionIdentity("r1", "op2", "s1", "ping", base), // another branch/operation
            AcpReactionIdentity("r1", "op1", "s2", "ping", base), // another session
            AcpReactionIdentity("r1", "op1", "s1", "pong", base), // another reaction
            AcpReactionIdentity("r1", "op1", "s1", "ping", EventKey.ConnectionPersisted("c1", 2)),
        ).map { it.canonicalPath }
        assertEquals(6, paths.size, "every identity part must keep its rows distinguishable")
    }

    @Test
    fun `an unverified external token is not laundered into an identity and a blank part fails the mint`() {
        val fallback = EventKey.mint("e1", "agent", externalTrustworthy = false, "c1", 4)
        assertEquals(EventKey.ConnectionPersisted("c1", 4), fallback)
        assertNull(EventKey.mint("e1", "agent", externalTrustworthy = false, "", 4))
        assertNull(EventKey.mint("", "", externalTrustworthy = true, "", 0))
        assertNull(AcpReactionIdentity.mint("", "op1", "s1", "ping", EventKey.ConnectionPersisted("c1", 1)))
        assertNull(AcpReactionIdentity.mint("r1", "op1", "s1", "ping", null))
    }

    // ----------------------------------------------------------- AC#4 operators

    @Test
    fun `distinct folds consecutive repeats of one fact and lets changes through`() {
        val gate = AcpOperatorGate(AcpReactionOperator.Distinct)
        val verdicts = listOf("a", "a", "b", "b", "a").map { gate.onEvent(it, 0L) }
        assertEquals(
            listOf("a", "b", "a"),
            gate.releasedTokens(),
            "two chunks saying the same thing are one dispatch",
        )
        assertTrue(verdicts[1] is AcpOperatorGate.Decision.Suppressed)
    }

    @Test
    fun `debounce coalesces inside its window on the injected clock and the window closes`() {
        val gate = AcpOperatorGate(AcpReactionOperator.Debounce(1_000))
        assertEquals(AcpOperatorGate.Decision.Fire, gate.onEvent("tick", 0L))
        assertTrue(gate.onEvent("tick", 500L) is AcpOperatorGate.Decision.Suppressed)
        assertFalse(gate.flushDue(999L))
        assertTrue(gate.flushDue(1_000L))
        assertEquals(AcpOperatorGate.Decision.Fire, gate.onEvent("tick", 1_500L))
    }

    @Test
    fun `replaying the recorded token sequence reproduces the same verdicts`() {
        val sequence = listOf("a" to 0L, "a" to 400L, "a" to 1_200L, "b" to 1_300L, "b" to 1_400L)
        fun fold(): List<Pair<String, Boolean>> {
            val gate = AcpOperatorGate(AcpReactionOperator.Debounce(1_000))
            return sequence.map { (token, at) ->
                // The tape replays events AND clock ticks in order: flush first, then decide.
                val flushed = gate.flushDue(at)
                val verdict = when (gate.onEvent(token, at)) {
                    is AcpOperatorGate.Decision.Fire -> "fire"
                    is AcpOperatorGate.Decision.FireProtected -> "protected"
                    is AcpOperatorGate.Decision.Suppressed -> "suppressed"
                }
                verdict to flushed
            }
        }
        // The fold is pure over the recorded (token, instant) pairs: the same tape replays the
        // same verdicts, which is what a recovered run needs to reconstruct its skips.
        assertEquals(fold(), fold())
        assertEquals(2, fold().count { it.first == "suppressed" })
    }

    @Test
    fun `the compiler bounds the operators and refuses debounce on a non-coalescible reaction`() {
        val rejected = compileAcpReactionPlan(
            reactions = listOf(
                acpOn("a", AcpEventSelector.AnyUpdate, operator = acpTake(0)),
                acpOn("b", AcpEventSelector.AnyUpdate, operator = acpDebounce(10L)),
                acpOn("c", AcpEventSelector.AnyUpdate, operator = acpDebounce(7_000_000L), coalescible = true),
                acpOn("d", AcpEventSelector.AnyUpdate, operator = acpTake(99_999)),
                acpOn("e", AcpEventSelector.AnyUpdate, once = true, operator = acpDistinct()),
            ),
            awaits = emptyList(),
        )
        assertInstanceOf(AcpPlanCompilation.Rejected::class.java, rejected)
        val reasons = (rejected as AcpPlanCompilation.Rejected).reasons.joinToString(" | ")
        assertTrue(reasons.contains("take(0)"))
        assertTrue(reasons.contains("non-coalescible"))
        assertTrue(reasons.contains("parking"))
        assertTrue(reasons.contains("exceeds the cap"))
        assertTrue(reasons.contains("one slot gets one budget"))
    }

    @Test
    fun `a permission request is never discarded by any operator`() {
        val gate = AcpOperatorGate(AcpReactionOperator.Take(1))
        assertEquals(AcpOperatorGate.Decision.Fire, gate.onEvent("x", 0L))
        assertTrue(gate.onEvent("y", 0L) is AcpOperatorGate.Decision.Suppressed)
        // Budget spent — and a permission still passes: suppression would say "no" in a name
        // the run never holds.
        assertEquals(AcpOperatorGate.Decision.FireProtected, gate.onEvent("may-i", 0L, protectedEvent = true))
    }

    @Test
    fun `the operator travels as serializable data in the declared plan`() {
        val declaration = acpOn(
            "fold", AcpEventSelector.UpdateKind("agent_message_chunk"),
            operator = acpDebounce(250L), coalescible = true,
        )
        val encoded = AcpReactiveStepDefinition.InputCodec.encode(
            AcpReactiveInput(command = listOf("agent"), prompt = "p", reactions = listOf(declaration)),
        )
        val decoded = AcpReactiveStepDefinition.InputCodec.decode(encoded)
        assertEquals(declaration, decoded.reactions.single())
        val compiledPlan = compileAcpReactionPlan(decoded.reactions, emptyList())
        assertInstanceOf(AcpPlanCompilation.Compiled::class.java, compiledPlan)
        val reaction = (compiledPlan as AcpPlanCompilation.Compiled).reactions.single()
        assertEquals(AcpReactionOperator.Debounce(250L), reaction.operator)
        assertTrue(reaction.effectful.not())
    }
}
