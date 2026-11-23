package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes a compiled reaction plan: routes each observed update to the slots its selector
 * matches, dispatching them SEQUENTIALLY through the body-slot seam.
 *
 * ## Why the three roles are separate (AC#4)
 *
 * The ACP reader thread hands events in through [submit] and never touches the slot. One
 * control loop — started by [start] in a scope the caller owns — takes events off a BOUNDED
 * queue and dispatches them. A body that waits on the agent (a permission prompt, a tool)
 * suspends the control loop, never the reader, so the answer the body waits for can still
 * arrive: the deadlock this shape refuses is the one where reacting blocks receiving. When the
 * queue is full the reader SUSPENDS rather than buffering without limit — an agent that talks
 * faster than the pipeline reacts feels backpressure, which is the honest outcome, not a
 * silently growing run.
 *
 * ## The identity law (AC#1)
 *
 * Each invocation carries its own [AttemptSegment]: the index increments per REACTION (not
 * per event), the key is derived from the reaction's name, and the invocation records the
 * event cursor it answered. [replay] of a confirmed event re-runs nothing — the recorded
 * invocations come back — while a reaction whose body never confirmed is owed a fresh attempt
 * with the NEXT index, exactly as a retry is.
 *
 * ## Reconciliation seams (PKH-138)
 *
 * When a [journal] is supplied, each event is additionally addressed by an [EventKey] (the
 * external id when the caller vouches for it, the persisted per-connection nonce otherwise),
 * every dispatch writes its intent BEFORE running the body and its ack only after, and an
 * unacknowledged intent is never re-driven automatically — neither by [replay] nor after
 * recovery. [gates] enforce each reaction's declared [AcpReactionOperator] against the injected
 * [clock], and a protected (permission) event passes the gate no matter what the operator
 * budget says: a dropped permission request is a silent "no" said in a person's name.
 * [abort] is the cancellation law (AC#5): subscriptions close, the control loop dies with the
 * scope, every un-acked intent lands UNKNOWN in the journal, and no worker survives it.
 *
 * ## What is kept, and for how long
 *
 * Observations are retained per run to bound memory: the last [OBSERVATION_RETENTION] events
 * stay replayable and older ones are evicted. A replay of an evicted cursor answers empty —
 * the engine's own recovery window is the ceiling this plugin may promise anyway.
 *
 * This class is the executor; [compileAcpReactionPlan] is the compiler, and the runner takes
 * ONLY compiled plans so that no routing decision escapes the serializable half.
 */
class AcpReactionRunner(
    private val slotInvoker: BodySlotInvoker,
    private val plan: List<AcpCompiledReaction>,
    /** Bound on buffered-but-undispatched events; the reader waits when it fills. */
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /** The connection's persisted nonce — the fallback half of every [EventKey] (AC#3). */
    private val connectionNonce: String = "",
    /** The durable ledger; null keeps the pure PKH-137 executor behaviour. */
    private val journal: AcpReactionLedger? = null,
    /** The clock the operator gates read; injected so a replay reproduces its verdicts. */
    private val clock: AcpClock = AcpClock { System.currentTimeMillis() },
) {

    companion object {
        /** Queue depth a well-behaved turn never approaches; overflowing it is backpressure. */
        const val DEFAULT_QUEUE_CAPACITY: Int = 64

        /** How many recent updates stay replayable; mirrors a bounded recovery window. */
        const val OBSERVATION_RETENTION: Int = 1_024
    }

    /** One update the agent sent, with the cursor and the reconciliation identity it earned. */
    private class Observed(
        val cursor: Long,
        val update: SessionUpdate,
        val eventKey: EventKey?,
        val permissionRequest: Boolean,
    )

    private val events = Channel<Observed>(queueCapacity)
    private val state = Mutex()
    private var observations = ArrayDeque<Observed>()
    private var cursor = 0L
    private var aborted = false
    private val invocationList = mutableListOf<AcpReactionInvocation>()
    private val invocationsByCursor = mutableMapOf<Long, MutableList<AcpReactionInvocation>>()
    private val attemptsByReaction = mutableMapOf<String, Int>()
    private val firedOnce = mutableSetOf<String>()
    private val skipList = mutableListOf<AcpSkippedReaction>()
    private val gatesByReaction = plan.associate { it.id to AcpOperatorGate(it.operator) }

    /** Signals the latest observed cursor; what an await re-scans against (see AcpAwait). */
    internal val lastObserved = MutableStateFlow(0L)

    private var control: Job? = null

    /**
     * Take the control loop into [scope]. The caller owns the lifetime: the loop ends when the
     * queue is [finish]ed or the scope is cancelled, and cancelling never loses an IN-FLIGHT
     * attempt's identity because that identity already travelled with the invocation.
     */
    fun start(scope: CoroutineScope) {
        check(control == null) { "the runner's control loop is already started" }
        control = scope.launch {
            for (observed in events) {
                dispatchObserved(observed)
            }
        }
    }

    /**
     * Record one update the reader received and hand it to the control loop.
     *
     * Returns as soon as the event is queued, never when its bodies finish: the reader that
     * waits on a reaction is the deadlock this runner exists to refuse. It suspends ONLY when
     * the bounded queue is full.
     *
     * The identity seam (AC#3): with [externalEventId] from a source the caller vouches for
     * ([externalTrustworthy]) the event is keyed by that external id; otherwise by this
     * connection's persisted nonce and the cursor. When neither exists the event carries no
     * key, and a journalled runner will NOT dispatch an effectful reaction for it — fail
     * closed. [permissionRequest] marks the protected class: an operator may never fold it away.
     */
    suspend fun submit(
        update: SessionUpdate,
        externalEventId: String = "",
        externalEventSource: String = "",
        externalTrustworthy: Boolean = false,
        permissionRequest: Boolean = false,
    ) {
        val observed = state.withLock {
            if (aborted) return
            cursor += 1
            lastObserved.value = cursor
            Observed(
                cursor,
                update,
                EventKey.mint(
                    externalEventId, externalEventSource, externalTrustworthy,
                    connectionNonce, cursor,
                ),
                permissionRequest || isPermissionUpdate(update),
            ).also {
                observations += it
                while (observations.size > OBSERVATION_RETENTION) observations.removeFirst()
            }
        }
        runCatching { events.send(observed) } // abort between lock and send: the close wins, no crash
    }

    /** Close the queue and wait for the control loop to drain what the reader handed in. */
    suspend fun finish() {
        events.close()
        control?.join()
        control = null
    }

    /**
     * Cancel the scope's reaction work (AC#5): the subscription closes, the control loop and
     * any body it was awaiting die with it, and every intent this run wrote but never
     * acknowledged lands UNKNOWN in the journal. After [abort] returns no worker of this runner
     * is alive, and late events are dropped at the door instead of dispatched half-journalled.
     */
    suspend fun abort() {
        state.withLock { aborted = true }
        events.close()
        control?.cancel()
        control?.join()
        control = null
        journal?.markPendingUnknown()
    }

    /** Every dispatch the run performed, in completion order. */
    val invocations: List<AcpReactionInvocation> get() = invocationList.toList()

    /** Every later match a `once` reaction refused, so the skip is observed, not silent. */
    val skipped: List<AcpSkippedReaction> get() = skipList.toList()

    /** The durable ledger's rows as they stand: intents, acks and the honest unknowns. */
    val journalRows: List<AcpReactionLedgerRow> get() = journal?.rows().orEmpty()

    /**
     * Re-drive the reactions owed for one observed event, answering with what now covers it.
     *
     * A reaction whose recorded outcome CONFIRMED comes back as recorded, with no new
     * invocation — replay of a confirmed event must not re-execute the body. A reaction that
     * matched the event but never confirmed is dispatched again with the NEXT attempt index:
     * replay owes it an attempt, not the identity it already spent. With a [journal] one more
     * law applies (AC#1): a (reaction, event key) whose intent was never acknowledged sits in
     * the unknown state and is NEVER re-driven automatically — the caller decides after
     * proving the remote idempotent, the runner never guesses. Unknown or evicted cursors
     * answer empty.
     */
    suspend fun replay(eventCursor: Long): List<AcpReactionInvocation> {
        val owed = mutableListOf<Pair<AcpCompiledReaction, Observed>>()
        val confirmed = mutableListOf<AcpReactionInvocation>()
        val blocked = mutableListOf<Pair<AcpCompiledReaction, Observed>>()
        state.withLock {
            val observed = observations.firstOrNull { it.cursor == eventCursor } ?: return emptyList()
            val recorded = invocationsByCursor[eventCursor].orEmpty()
            for (reaction in plan) {
                if (!reaction.selector.matches(observed.update)) continue
                val forReaction = recorded.filter { it.reactionId == reaction.id }
                val best = forReaction.maxByOrNull { it.attempt.index }
                if (best?.confirmed == true) confirmed += forReaction.filter { it.confirmed } else {
                    // A `once` reaction already fired owes nothing even when it failed: "once"
                    // counted the firing, not the confirmation, or the budget would reset itself.
                    if (reaction.once && reaction.id in firedOnce) {
                        confirmed += forReaction
                    } else {
                        owed += reaction to observed
                    }
                }
            }
        }
        for ((reaction, observed) in owed.toList()) {
            val key = observed.eventKey ?: continue
            if (journal?.isUnknown(reaction.id, key) == true) {
                blocked += reaction to observed
                owed -= reaction to observed
            }
        }
        blocked.forEach { (reaction, observed) ->
            state.withLock {
                skipList += AcpSkippedReaction(
                    reaction.id, observed.cursor, "unknown effect: never re-driven automatically",
                )
            }
        }
        val redispatched = owed.mapNotNull { (reaction, observed) -> fire(reaction, observed) }
        return confirmed + redispatched
    }

    /**
     * Wait until an OBSERVED update matches [condition], giving up after [timeoutMillis].
     *
     * Await reads the observed stream, not the dispatched one: an event the reader received is
     * visible even while the control loop is parked inside an earlier body — which is what lets
     * a reaction await a later event without deadlocking against its own queue.
     *
     * The no-gap argument: each round scans and takes the cursor INSIDE one lock section, so a
     * null answer means "nothing in (scanned, cursorSeen] matched" as a single fact. The
     * subscription is only armed for values BEYOND cursorSeen, and a StateFlow re-delivers its
     * current value — so an event that lands after the scan but before the subscription is
     * either already covered by the scan's cursor or delivered on subscribe; there is no window
     * that belongs to neither. The budget is real: [timeoutMillis] must be positive, and at its
     * expiry the verdict is [AcpAwaitVerdict.TimedOut], never an infinite wait.
     */
    suspend fun await(condition: AcpEventSelector, timeoutMillis: Long): AcpAwaitVerdict {
        require(timeoutMillis > 0) {
            "await budget must be positive, got $timeoutMillis — an await that cannot time out is an unbounded wait"
        }
        val withinBudget = withTimeoutOrNull<AcpAwaitVerdict>(timeoutMillis) {
            var scanned = 0L
            var verdict: AcpAwaitVerdict? = null
            while (verdict == null) {
                val (cursorSeen, match) = state.withLock {
                    cursor to observations.firstOrNull { it.cursor > scanned && condition.matches(it.update) }?.cursor
                }
                if (match != null) {
                    verdict = AcpAwaitVerdict.Satisfied(match)
                } else {
                    scanned = maxOf(scanned, cursorSeen)
                    lastObserved.first { it > scanned }
                }
            }
            verdict
        }
        return withinBudget ?: AcpAwaitVerdict.TimedOut
    }

    private suspend fun dispatchObserved(observed: Observed) {
        for (reaction in plan) {
            if (!reaction.selector.matches(observed.update)) continue
            val alreadyFired = reaction.once && state.withLock { reaction.id in firedOnce }
            if (alreadyFired) {
                state.withLock { skipList += AcpSkippedReaction(reaction.id, observed.cursor, "once") }
                continue
            }
            // AC#3 fail-closed: a journalled effectful dispatch needs an EventKey to own the
            // row — without one the effect could never be reconciled, so it skips, never guesses.
            if (journal != null && reaction.effectful && observed.eventKey == null) {
                state.withLock {
                    skipList += AcpSkippedReaction(
                        reaction.id, observed.cursor, "unaddressable event: no identity to journal",
                    )
                }
                continue
            }
            val gate = gatesByReaction.getValue(reaction.id)
            val decision = gate.onEvent(
                acpReactionFactToken(observed.update),
                clock.nowMillis(),
                observed.permissionRequest,
            )
            if (decision is AcpOperatorGate.Decision.Suppressed) {
                state.withLock {
                    skipList += AcpSkippedReaction(reaction.id, observed.cursor, decision.reason)
                }
                continue
            }
            fire(reaction, observed)
        }
    }

    /**
     * Mint the next attempt identity under the lock, invoke OUTSIDE it, record under the lock.
     *
     * With a [journal] the body runs inside [AcpReactionLedger.drive]: intent persisted BEFORE
     * the invocation, ack only after a confirmed outcome, and every crash window lands as the
     * typed verdict the caller (and a recovery) can read back. The null-outcome answers are the
     * windows where the body never ran (lost intent) — no invocation is recorded for those.
     */
    private suspend fun fire(
        reaction: AcpCompiledReaction,
        observed: Observed,
    ): AcpReactionInvocation? {
        val attempt = state.withLock {
            val index = (attemptsByReaction[reaction.id] ?: 0) + 1
            attemptsByReaction[reaction.id] = index
            if (reaction.once) firedOnce += reaction.id
            AttemptSegment(index = index, key = reaction.attemptKey)
        }
        var outcome: BodyOutcome? = null
        val ledger = journal
        val key = observed.eventKey
        val verdict: AcpDriveVerdict? = if (ledger != null && key != null) {
            ledger.drive(
                reaction.id, key, observed.cursor, attempt.index,
            ) {
                val invoked = slotInvoker.invoke(reaction.slot, BodyInvocationContext(attempt = attempt))
                outcome = invoked
                (invoked as? BodyOutcome.Completed)?.outcome == StepOutcome.Success
            }
        } else {
            outcome = slotInvoker.invoke(reaction.slot, BodyInvocationContext(attempt = attempt))
            null
        }
        val bodyOutcome = outcome ?: run {
            // The body never ran — either the intent write failed (redriveable, AC#1 case one)
            // or the durable journal had already paid for this effect (AC#2 dedupe). The skip is
            // the record, and its reason says which window this is.
            state.withLock {
                skipList += AcpSkippedReaction(
                    reaction.id, observed.cursor,
                    if (verdict is AcpDriveVerdict.AlreadyAcknowledged) {
                        "durably acknowledged: the effect is not paid for twice"
                    } else {
                        "intent not journalled: effect never ran"
                    },
                )
            }
            return null
        }
        return state.withLock {
            AcpReactionInvocation(
                reaction.id, reaction.slot, observed.cursor, attempt, bodyOutcome, key, verdict,
            ).also {
                invocationList += it
                invocationsByCursor.getOrPut(observed.cursor) { mutableListOf() } += it
            }
        }
    }
}

/**
 * One slot invocation the runner performed: which reaction, which event it answered, the
 * identity it travelled with, and what the engine said.
 *
 * [confirmed] is the replay predicate: only a body that COMPLETED with SUCCESS has said
 * everything a replay needs to know, so it is not run again. [eventKey] and [driveVerdict]
 * carry the PKH-138 reconciliation evidence of this dispatch — the durable identity of the
 * event and what the ledger concluded about the effect — null only for a runner without a
 * journal or nonce, which keeps PKH-137's behaviour untouched.
 */
data class AcpReactionInvocation(
    val reactionId: String,
    val slot: PluginStepId,
    /** The runner's cursor of the update this reaction fired for. */
    val eventCursor: Long,
    val attempt: AttemptSegment,
    val outcome: BodyOutcome,
    val eventKey: EventKey? = null,
    val driveVerdict: AcpDriveVerdict? = null,
) {
    val confirmed: Boolean
        get() = (outcome as? BodyOutcome.Completed)?.outcome == StepOutcome.Success
}

/**
 * A later match the runner refused to fire again — by `once`, by an operator, by the fail-closed
 * identity rule or by the unknown-effect law. [reason] says which, so every skip is attributable.
 */
data class AcpSkippedReaction(
    val reactionId: String,
    val eventCursor: Long,
    val reason: String = "once",
)

/**
 * The verdict of [AcpReactionRunner.await]: an event matched within budget, or the budget ran
 * out. Typed data, never an exception — a turn that awaited and did not get its event is a
 * legitimate outcome the durable output has to carry, not a crash. It is serializable for that
 * same reason: the verdict belongs to the journal, not just to the live call.
 */
@kotlinx.serialization.Serializable
sealed class AcpAwaitVerdict {

    /** Some observed update matched, at runner cursor [eventCursor]. */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Satisfied")
    data class Satisfied(val eventCursor: Long) : AcpAwaitVerdict()

    /** No matching update arrived within the declared budget — or the turn ended first. */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("TimedOut")
    data object TimedOut : AcpAwaitVerdict()
}

/**
 * Whether the update speaks about a permission: the wire token names it, and a permission
 * request is the protected class no operator may fold away (AC#4). Classification is by the
 * agent's OWN discriminator, never by a payload guess.
 */
internal fun isPermissionUpdate(update: SessionUpdate): Boolean =
    acpUpdateKind(update).contains("permission", ignoreCase = true)

/**
 * The fact token the operator gate compares: for a text chunk, the text itself (two chunks
 * saying the same thing ARE the repeat Distinct and Debounce fold); for anything else, the
 * wire kind. A bare cursor is never the token — cursors renumber on reconnect (AC#2).
 */
internal fun acpReactionFactToken(update: SessionUpdate): String = when (update) {
    is SessionUpdate.AgentMessageChunk ->
        (update.content as? ContentBlock.Text)?.text ?: acpUpdateKind(update)
    is SessionUpdate.AgentThoughtChunk ->
        (update.content as? ContentBlock.Text)?.text ?: acpUpdateKind(update)
    else -> acpUpdateKind(update)
}
