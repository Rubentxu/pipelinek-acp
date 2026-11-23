package pipelinek.acp

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {

    companion object {
        /** Queue depth a well-behaved turn never approaches; overflowing it is backpressure. */
        const val DEFAULT_QUEUE_CAPACITY: Int = 64

        /** How many recent updates stay replayable; mirrors a bounded recovery window. */
        const val OBSERVATION_RETENTION: Int = 1_024
    }

    /** One update the agent sent, with the cursor the runner assigned it. */
    private class Observed(val cursor: Long, val update: SessionUpdate)

    private val events = Channel<Observed>(queueCapacity)
    private val state = Mutex()
    private var observations = ArrayDeque<Observed>()
    private var cursor = 0L
    private val invocationList = mutableListOf<AcpReactionInvocation>()
    private val invocationsByCursor = mutableMapOf<Long, MutableList<AcpReactionInvocation>>()
    private val attemptsByReaction = mutableMapOf<String, Int>()
    private val firedOnce = mutableSetOf<String>()
    private val skipList = mutableListOf<AcpSkippedReaction>()

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
     */
    suspend fun submit(update: SessionUpdate) {
        val observed = state.withLock {
            cursor += 1
            lastObserved.value = cursor
            Observed(cursor, update).also {
                observations += it
                while (observations.size > OBSERVATION_RETENTION) observations.removeFirst()
            }
        }
        events.send(observed)
    }

    /** Close the queue and wait for the control loop to drain what the reader handed in. */
    suspend fun finish() {
        events.close()
        control?.join()
        control = null
    }

    /** Every dispatch the run performed, in completion order. */
    val invocations: List<AcpReactionInvocation> get() = invocationList.toList()

    /** Every later match a `once` reaction refused, so the skip is observed, not silent. */
    val skipped: List<AcpSkippedReaction> get() = skipList.toList()

    /**
     * Re-drive the reactions owed for one observed event, answering with what now covers it.
     *
     * A reaction whose recorded outcome CONFIRMED comes back as recorded, with no new
     * invocation — replay of a confirmed event must not re-execute the body. A reaction that
     * matched the event but never confirmed is dispatched again with the NEXT attempt index:
     * replay owes it an attempt, not the identity it already spent. Unknown or evicted cursors
     * answer empty.
     */
    suspend fun replay(eventCursor: Long): List<AcpReactionInvocation> {
        val owed = mutableListOf<Pair<AcpCompiledReaction, Observed>>()
        val confirmed = mutableListOf<AcpReactionInvocation>()
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
        val redispatched = owed.map { (reaction, observed) -> fire(reaction, observed) }
        return confirmed + redispatched
    }

    private suspend fun dispatchObserved(observed: Observed) {
        for (reaction in plan) {
            if (!reaction.selector.matches(observed.update)) continue
            val alreadyFired = reaction.once && state.withLock { reaction.id in firedOnce }
            if (alreadyFired) {
                state.withLock { skipList += AcpSkippedReaction(reaction.id, observed.cursor) }
                continue
            }
            fire(reaction, observed)
        }
    }

    /** Mint the next attempt identity under the lock, invoke OUTSIDE it, record under the lock. */
    private suspend fun fire(reaction: AcpCompiledReaction, observed: Observed): AcpReactionInvocation {
        val attempt = state.withLock {
            val index = (attemptsByReaction[reaction.id] ?: 0) + 1
            attemptsByReaction[reaction.id] = index
            if (reaction.once) firedOnce += reaction.id
            AttemptSegment(index = index, key = reaction.attemptKey)
        }
        val outcome = slotInvoker.invoke(reaction.slot, BodyInvocationContext(attempt = attempt))
        return state.withLock {
            AcpReactionInvocation(reaction.id, reaction.slot, observed.cursor, attempt, outcome).also {
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
 * everything a replay needs to know, so it is not run again.
 */
data class AcpReactionInvocation(
    val reactionId: String,
    val slot: PluginStepId,
    /** The runner's cursor of the update this reaction fired for. */
    val eventCursor: Long,
    val attempt: AttemptSegment,
    val outcome: BodyOutcome,
) {
    val confirmed: Boolean
        get() = (outcome as? BodyOutcome.Completed)?.outcome == StepOutcome.Success
}

/** A later match a `once` reaction refused to fire again. */
data class AcpSkippedReaction(
    val reactionId: String,
    val eventCursor: Long,
)
