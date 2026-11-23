package pipelinek.acp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The reconciliation half of PKACP-07/08 (PKH-138): the identities, durable rows and decisions
 * that let a recovered or reconnected run tell WHICH reactions it already paid for.
 *
 * ## The one authority (and why there is no second one)
 *
 * The plugin's durable journal is the encoded step input/output the engine persists — the rows
 * of [AcpSessionInput] and [AcpReactiveInput]/[AcpReactiveOutput]. This file does NOT open a
 * side database of runs: [AcpReactionLedgerRow] is @Serializable precisely so the SAME journal
 * carries it, and [AcpReactiveOutput.reactionLedger] is where a finished or interrupted turn
 * leaves those rows for the next run to read back.
 *
 * ## The identity law (AC#3)
 *
 * An observed event is addressed by an [EventKey], never by a bare cursor: cursors are
 * per-connection counters and a reload or reconnect renumbers them (AC#2). The key is the
 * EXTERNAL identity when the agent provides a reliable one, and a key PERSISTED PER CONNECTION
 * otherwise — a foreign connection cannot mint it, and the same connection's counter cannot
 * silently restart. If neither holds — no external id, no persisted nonce for this connection —
 * identity is not demonstrable and the reconciler FAILS CLOSED: the event is suspended, never
 * dispatched on a guess.
 *
 * ## What is never promised (and why)
 *
 * **Exactly-once for external effects without remote idempotency is not this port's promise.**
 * A crash between saying `git push` and journalling the ack is not observable from this side:
 * the effect may or may not have reached the remote. The honest verdict for that window is
 * [AcpDriveVerdict.EffectUnknown], and an Unknown effect is NEVER re-driven automatically —
 * re-sending it would bet the run's idempotency on a remote that never declared it. A caller
 * that needs the effect retried proves idempotency at the remote (or a human decides); the
 * ledger then records the decision under the SAME [EventKey].
 *
 * ## The history law (AC#2)
 *
 * A v1 `session/load` replays the agent's history into the live stream. Historic events are
 * facts the run already saw through their own connection — re-firing an EFFECTFUL reaction for
 * them would be a second effect for one piece of history. So: [AcpStreamOrigin.Historic] never
 * dispatches effects, and when history and live cannot be told apart reliably
 * ([AcpStreamOrigin.Indistinguishable]) every effectful reaction is SUSPENDED until the run
 * reconciles, while coalescible telemetry keeps flowing — suspending telemetry would trade a
 * bounded delay for a lost fact.
 */

/**
 * The identity of one observed agent event for reconciliation purposes (AC#3).
 *
 * Two shapes, chosen by whether the agent's identity can be trusted:
 *
 *  * [External] — a token the agent itself stamped (the wire's `_meta` event id) from a source
 *    the run marked trustworthy. It survives reloads and reconnections with the SAME value, so
 *    a renumbered stream still dedupes by identity (AC#2).
 *  * [ConnectionPersisted] — the fallback law: the connection nonce (the same identity
 *    [SessionRef] uses to fence foreign refs) plus the monotonic cursor this connection assigned.
 *    Reliable WITHIN a connection's durable rows; a foreign nonce proves the row belongs to
 *    another connection, which is exactly what makes a reconnect's renumbered counters safe.
 *
 * Trust is a property of the SOURCE, not of the string: an unverified external token is not an
 * identity (anyone could replay it into a different run), so [mint] refuses it rather than
 * laundering it — the caller falls back to the connection-persisted key.
 */
@Serializable
sealed class EventKey {

    /** The stable string every dedupe comparison and durable row carries. */
    abstract val canonical: String

    /** Agent-stamped identity from a source the run verified; survives renumbering. */
    @Serializable
    @SerialName("External")
    data class External(val source: String, val value: String) : EventKey() {
        override val canonical: String = "ext:$source:$value"
    }

    /** Per-connection persisted key: this connection's nonce plus the cursor it assigned. */
    @Serializable
    @SerialName("ConnectionPersisted")
    data class ConnectionPersisted(val connectionNonce: String, val sequence: Long) : EventKey() {
        override val canonical: String = "conn:$connectionNonce:$sequence"
    }

    companion object {
        /**
         * The key of one observed event, under the identity law: [externalValue] is used ONLY
         * when [externalTrustworthy] is set and the value and source are non-blank — an
         * unverified token is not an identity, and minting one from it would let a replayed
         * string impersonate another connection's event. Without a usable external token the
         * key is the connection-persisted one; without a connection nonce either, NOTHING can
         * address the event and the mint answers null — the caller fails closed.
         */
        fun mint(
            externalValue: String,
            externalSource: String,
            externalTrustworthy: Boolean,
            connectionNonce: String,
            sequence: Long,
        ): EventKey? {
            if (externalTrustworthy && externalValue.isNotBlank() && externalSource.isNotBlank()) {
                return External(externalSource, externalValue)
            }
            if (connectionNonce.isBlank() || sequence <= 0) return null
            return ConnectionPersisted(connectionNonce, sequence)
        }
    }
}

/** The phase one reaction's durable row last proved (the three crash points of AC#1). */
@Serializable
enum class AcpEffectPhase {
    /** The intent was journalled; the effect may or may not have run — recovery says UNKNOWN. */
    INTENT,

    /** The effect ran AND the acknowledgement was journalled: the reaction is paid for. */
    ACKED,

    /** Recovery's verdict for an INTENT never acknowledged: real, but its outcome is not. */
    UNKNOWN,
}

/**
 * One durable row: which reaction, for which event, under which attempt, at which phase.
 *
 * It is pure plugin vocabulary — no SDK class, no coroutine object — because it outlives the
 * artifact that wrote it, exactly like the turn rows of [AcpSessionInput]. [cursor] is the
 * runner's per-connection position (kept for ordering evidence), while [eventKey] is what
 * dedupe and reconciliation compare: after a reconnect the cursors renumber, the keys do not.
 */
@Serializable
data class AcpReactionLedgerRow(
    val reactionId: String,
    val eventKey: EventKey,
    /** The runner's cursor of the event when the intent was written; ordering evidence only. */
    val cursor: Long,
    /** The attempt index the invocation carried; two attempts of one reaction are two rows. */
    val attemptIndex: Int,
    val phase: AcpEffectPhase,
)

/** The typed answer of [AcpReactionLedger.drive] — what the run may conclude about one effect. */
@Serializable
sealed class AcpDriveVerdict {

    /** The effect ran and its acknowledgement is journalled: the reaction is complete. */
    @Serializable
    @SerialName("Executed")
    data class Executed(val outcomeToken: String) : AcpDriveVerdict()

    /**
     * The intent never reached the journal, so the effect NEVER RAN — the one crash point
     * recovery may safely redrive (AC#1's first case).
     */
    @Serializable
    @SerialName("IntentNotPersisted")
    data object IntentNotPersisted : AcpDriveVerdict()

    /**
     * The effect ran (or may have) without a journalled ack: the honest unknown. NEVER
     * re-driven automatically — see the file KDoc's exactly-once refusal.
     */
    @Serializable
    @SerialName("EffectUnknown")
    data object EffectUnknown : AcpDriveVerdict()

    /** A row for this reaction and event key was already ACKED; the effect is not run again. */
    @Serializable
    @SerialName("AlreadyAcknowledged")
    data object AlreadyAcknowledged : AcpDriveVerdict()
}

/**
 * The in-run ledger over the durable rows: it writes through a caller-supplied [persist] — the
 * engine's journal append as the plugin sees it — and answers typed verdicts when that write
 * fails, which is exactly what a crash between the phases looks like.
 *
 * The fault-injection contract: [persist] throwing is not an error to swallow, it is a SIMULATED
 * CRASH. The ledger never executes an effect whose intent it could not write, and it never
 * promises an ack it could not persist: both land as [AcpDriveVerdict.EffectUnknown] (or
 * [AcpDriveVerdict.IntentNotPersisted] before the effect), which is what makes AC#1's three
 * crash windows testable against real code paths rather than against a mock's hope.
 */
class AcpReactionLedger(
    /** Durable rows read back from the journal — a recovered run's starting truth. */
    priorRows: List<AcpReactionLedgerRow> = emptyList(),
    /** The journal append. Throwing stands in for a crash during the write. */
    private val persist: (AcpReactionLedgerRow) -> Unit = {},
) {
    private val rowList = mutableListOf<AcpReactionLedgerRow>()

    /**
     * Recovery classification happens HERE, once, at construction: a prior INTENT row without a
     * later ACK for the same (reaction, event key) is what a crash left behind, so it enters as
     * UNKNOWN — a fact the run knows about but does not act on automatically.
     */
    init {
        for (row in priorRows) {
            rowList += row
        }
        val acked = priorRows
            .filter { it.phase == AcpEffectPhase.ACKED }
            .map { it.reactionId to it.eventKey.canonical }
            .toSet()
        for (index in rowList.indices) {
            val row = rowList[index]
            if (row.phase == AcpEffectPhase.INTENT &&
                (row.reactionId to row.eventKey.canonical) !in acked
            ) {
                rowList[index] = row.copy(phase = AcpEffectPhase.UNKNOWN)
            }
        }
    }

    /** Every row the ledger holds: prior (recovered) rows first, then this run's writes. */
    fun rows(): List<AcpReactionLedgerRow> = rowList.toList()

    /** Whether one (reaction, event key) already has a journalled ack — the dedupe question. */
    fun isAcknowledged(reactionId: String, eventKey: EventKey): Boolean =
        rowList.any {
            it.reactionId == reactionId && it.eventKey.canonical == eventKey.canonical &&
                it.phase == AcpEffectPhase.ACKED
        }

    /** Whether one (reaction, event key) sits in the honest-unknown state after recovery. */
    fun isUnknown(reactionId: String, eventKey: EventKey): Boolean =
        rowList.any {
            it.reactionId == reactionId && it.eventKey.canonical == eventKey.canonical &&
                it.phase == AcpEffectPhase.UNKNOWN
        }

    /**
     * Run [effect] under the intent/ack protocol, or refuse to.
     *
     * The order is the whole guarantee: intent FIRST (never run an effect the journal does not
     * know was asked for), then the effect, then the ack only after [effect] returned success.
     * A [persist] failure at the intent means the effect never ran (redriveable); a failure at
     * the ack — or an effect that threw after its intent was written — means the outcome is
     * unknown, and unknown is never automatically re-driven.
     */
    suspend fun drive(
        reactionId: String,
        eventKey: EventKey,
        cursor: Long,
        attemptIndex: Int,
        effectSucceeded: suspend () -> Boolean,
    ): AcpDriveVerdict {
        if (isAcknowledged(reactionId, eventKey)) return AcpDriveVerdict.AlreadyAcknowledged
        val intent = AcpReactionLedgerRow(
            reactionId, eventKey, cursor, attemptIndex, AcpEffectPhase.INTENT,
        )
        val intentWritten = try {
            persist(intent)
            rowList += intent
            true
        } catch (e: Exception) {
            false
        }
        if (!intentWritten) return AcpDriveVerdict.IntentNotPersisted
        val succeeded = try {
            effectSucceeded()
        } catch (e: CancellationException) {
            markUnknown(intent)
            throw e
        } catch (e: Exception) {
            markUnknown(intent)
            return AcpDriveVerdict.EffectUnknown
        }
        if (!succeeded) {
            // The body ran and said it failed: whether its external effect half-happened is
            // not knowable from here, so the row stops at intent and recovery calls it unknown.
            markUnknown(intent)
            return AcpDriveVerdict.EffectUnknown
        }
        val ack = intent.copy(phase = AcpEffectPhase.ACKED)
        return try {
            persist(ack)
            rowList += ack
            AcpDriveVerdict.Executed(outcomeToken = "success")
        } catch (e: Exception) {
            markUnknown(intent)
            AcpDriveVerdict.EffectUnknown
        }
    }

    /**
     * Close every intent this run never acknowledged as UNKNOWN (the cancellation law, AC#5):
     * a scope that dies mid-body leaves effects whose outcome nobody witnessed, and the journal
     * must say so before the tree disappears.
     */
    fun markPendingUnknown() {
        for (index in rowList.indices) {
            val row = rowList[index]
            if (row.phase == AcpEffectPhase.INTENT && !isAcknowledged(row.reactionId, row.eventKey)) {
                rowList[index] = row.copy(phase = AcpEffectPhase.UNKNOWN)
            }
        }
    }

    private fun markUnknown(intent: AcpReactionLedgerRow) {
        val index = rowList.indexOfFirst {
            it.phase == AcpEffectPhase.INTENT && it.reactionId == intent.reactionId &&
                it.eventKey.canonical == intent.eventKey.canonical && it.attemptIndex == intent.attemptIndex
        }
        if (index >= 0) rowList[index] = intent.copy(phase = AcpEffectPhase.UNKNOWN)
    }
}

/** Where one incoming event came from, as far as the run can PROVE it (AC#2). */
enum class AcpStreamOrigin {
    /** Said live on this connection after the reload/reconnect completed. */
    LIVE,

    /** The agent's replayed history — a v1 run never fires effects for it again. */
    HISTORIC,

    /**
     * The run cannot tell history from live for this event (renumbered cursors, no usable
     * external identity): effectful reactions suspend until reconciliation, they do not guess.
     */
    INDISTINGUISHABLE,
}

/** What [AcpReactionReconciler] decided about one (reaction, event) pair. */
sealed class AcpReconciledDecision {
    /** Dispatch is owed: the event is live (or non-effectful history) and nothing paid for it. */
    data object Fire : AcpReconciledDecision()

    /** A v1 effectful reaction never re-fires for replayed history (the history law). */
    data object SuppressedHistoric : AcpReconciledDecision()

    /** The durable journal already acked this (reaction, event key) — dedupe, no re-run. */
    data object SuppressedAcknowledged : AcpReconciledDecision()

    /**
     * Fail-closed: history and live are indistinguishable for an effectful reaction, or the
     * row sits in the honest-unknown state. A human or a proven-idempotent caller decides;
     * the reconciler itself never re-drives.
     */
    data class Suspended(val reason: String) : AcpReconciledDecision()
}

/**
 * The reload/reconnect reconciliation over durable rows (AC#2, AC#3).
 *
 * Dedupe compares [EventKey]s, never cursors: after a reconnect the counters renumber, but an
 * external key rode through unchanged and a connection-persisted key carries the nonce that
 * proves WHICH connection counted it. What the prior journal already acked stays acked; what it
 * left unacked is UNKNOWN and suspends until someone decides. A blank reaction identity is not
 * dispatchable either — an effect whose runner cannot name it is a second authority the journal
 * did not agree to, so the reconciler fails closed on it too.
 */
class AcpReactionReconciler(
    private val ledger: AcpReactionLedger,
    /** The nonce of the connection now receiving events; keys from other nonces are foreign. */
    private val currentConnectionNonce: String,
) {

    /** The decision for one (reaction, event) pair before any dispatch. */
    fun decide(
        reactionId: String,
        eventKey: EventKey,
        origin: AcpStreamOrigin,
        effectful: Boolean,
    ): AcpReconciledDecision {
        if (reactionId.isBlank()) {
            return AcpReconciledDecision.Suspended(
                "a reaction with no id cannot own a durable effect row; fail-closed",
            )
        }
        if (ledger.isAcknowledged(reactionId, eventKey)) {
            return AcpReconciledDecision.SuppressedAcknowledged
        }
        if (ledger.isUnknown(reactionId, eventKey)) {
            return AcpReconciledDecision.Suspended(
                "the journal holds an unacknowledged intent for reaction '$reactionId' at this " +
                    "event key: the effect's outcome is unknown and is never re-driven automatically",
            )
        }
        if (effectful) {
            when (origin) {
                AcpStreamOrigin.HISTORIC -> return AcpReconciledDecision.SuppressedHistoric
                AcpStreamOrigin.INDISTINGUISHABLE -> return AcpReconciledDecision.Suspended(
                    "history and live cannot be told apart for this event; effectful reaction " +
                        "'$reactionId' is suspended until the run reconciles its origin",
                )
                AcpStreamOrigin.LIVE -> Unit
            }
        }
        if (eventKey is EventKey.ConnectionPersisted &&
            eventKey.connectionNonce != currentConnectionNonce
        ) {
            // A persisted key from ANOTHER connection is, by the nonce law, not this stream's
            // live event — it is history arriving under someone else's counter. Same treatment:
            // effects never re-fire for it, and an indistinguishable run suspends instead.
            return if (effectful) {
                AcpReconciledDecision.SuppressedHistoric
            } else {
                AcpReconciledDecision.Fire
            }
        }
        return AcpReconciledDecision.Fire
    }
}

/**
 * The full identity tuple one reaction invocation is journalled under (AC#3).
 *
 * run + operation + session + reaction + event: five parts because each is a way two rows
 * COULD collide — two runs of one plan, two body operations in one run, two sessions on one
 * connection, two declared reactions, two events. [mint] refuses to build the tuple when any
 * part is blank: an identity that cannot be demonstrated is not guessed at, it fails closed and
 * the caller suspends the dispatch.
 */
data class AcpReactionIdentity(
    val runId: String,
    val operationId: String,
    val sessionRef: String,
    val reactionId: String,
    val eventKey: EventKey,
) {
    /** The deterministic path segment pair every durable comparison uses. */
    val canonicalPath: String =
        "run/$runId/op/$operationId/session/$sessionRef/reaction/$reactionId/event/${eventKey.canonical}"

    companion object {
        /** The tuple, or null when any part is undemonstrable — the fail-closed mint. */
        fun mint(
            runId: String,
            operationId: String,
            sessionRef: String,
            reactionId: String,
            eventKey: EventKey?,
        ): AcpReactionIdentity? {
            if (runId.isBlank() || operationId.isBlank() || sessionRef.isBlank() ||
                reactionId.isBlank() || eventKey == null
            ) {
                return null
            }
            return AcpReactionIdentity(runId, operationId, sessionRef, reactionId, eventKey)
        }
    }
}

/**
 * The serializable limiting operator one reaction declares (AC#4).
 *
 * Each operator is DATA with a closed vocabulary — the plan that reaches the journal must say
 * everything the run will do — and each has a LIMIT, because an unbounded "wait for more" is
 * buffering under a clever name:
 *
 *  * [Take] — dispatch at most [limit] matching events; every later match is a recorded skip.
 *  * [Distinct] — collapse an event whose [AcpOperatorGate.Decision] token repeats the last
 *    dispatched one; consecutive repeats of one fact are one dispatch.
 *  * [Debounce] — coalesce same-token events arriving inside [windowMillis] of each other into
 *    one dispatch (the first, flushed at the deadline). Declared ONLY for telemetry/coalescible
 *    reactions ([compileAcpReactionPlan] refuses it otherwise): a fact that loses information
 *    when folded may never fold under a timer.
 *
 * The clock law: [AcpOperatorGate] takes every timestamp as an ARGUMENT, so replaying the same
 * recorded (token, instant) sequence reproduces the same verdicts — a debounce that read
 * wall-clock directly could not be tested, let alone replayed.
 *
 * Permission requests are never discarded by any operator: the gate's protected path fires them
 * regardless of limits, because a suppressed permission is a silent "no" said in a person's name.
 */
@Serializable
sealed class AcpReactionOperator {

    /** Dispatch at most [limit] matches, then record every later match as skipped. */
    @Serializable
    @SerialName("Take")
    data class Take(val limit: Int) : AcpReactionOperator()

    /** Dispatch only when the event token differs from the last dispatched one. */
    @Serializable
    @SerialName("Distinct")
    data object Distinct : AcpReactionOperator()

    /** Fold same-token events arriving within [windowMillis] into one dispatch. */
    @Serializable
    @SerialName("Debounce")
    data class Debounce(val windowMillis: Long) : AcpReactionOperator()

    companion object {
        /** Beyond this, a window is a parking lot; the plan says "telemetry stream" instead. */
        const val MAX_DEBOUNCE_WINDOW_MILLIS: Long = 60_000L

        /** An unbounded take is `AnyUpdate` with extra steps; say that instead. */
        const val MAX_TAKE_LIMIT: Int = 1_024
    }
}

/**
 * The injected clock (AC#4's determinism law): a gate that read wall-clock directly could not be
 * replayed, so every timestamp the operator logic sees arrives as an argument from this seam.
 * Production passes the system clock; a test passes a ladder and gets the same verdicts twice.
 */
fun interface AcpClock {
    fun nowMillis(): Long
}

/**
 * The per-reaction runtime gate one [AcpReactionOperator] enforces.
 *
 * It is a pure reducer over (token, instant) pairs: [onEvent] answers what to do with one
 * matching event, [flushDue] releases debounce windows whose deadline an injected clock has
 * passed. Given the same operator and the same recorded sequence, it reproduces the same
 * answers — which is the whole determinism claim of AC#4's replay, measured without a clock race.
 */
class AcpOperatorGate(private val operator: AcpReactionOperator?) {

    /** What the gate decided about one matching event. */
    sealed class Decision {
        /** Dispatch now: within every limit, past every window. */
        data object Fire : Decision()

        /** Dispatch now DESPITE the operator: a protected (permission) event is never dropped. */
        data object FireProtected : Decision()

        /** The operator folded this event into an earlier dispatch; recorded, never silent. */
        data class Suppressed(val reason: String) : Decision()
    }

    private var dispatched = 0
    private var lastToken: String? = null
    private var pendingToken: String? = null
    private var pendingDeadline = Long.MIN_VALUE
    private val released = mutableListOf<String>()

    /** Events the gate let through this gate, in order (evidence for summary and replay). */
    fun releasedTokens(): List<String> = released.toList()

    /** Decide one matching event with token [token] observed at [nowMillis]. */
    fun onEvent(token: String, nowMillis: Long, protectedEvent: Boolean = false): Decision {
        if (protectedEvent) {
            // AC#4's hard floor: no limit, no window and no dedupe may swallow a permission
            // request. The operator keeps its own state untouched by the protected fire, so
            // "once" still counts once — the protected path is the refusal to suppress, not a
            // licence to reset the budget.
            released += token
            return Decision.FireProtected
        }
        return when (val op = operator) {
            null -> accept(token)
            is AcpReactionOperator.Take ->
                if (dispatched >= op.limit) {
                    Decision.Suppressed("take(${op.limit}) exhausted")
                } else {
                    accept(token)
                }
            is AcpReactionOperator.Distinct ->
                if (lastToken == token) {
                    Decision.Suppressed("distinct: token repeats the last dispatched one")
                } else {
                    accept(token)
                }
            is AcpReactionOperator.Debounce -> {
                if (pendingToken == token && nowMillis < pendingDeadline) {
                    Decision.Suppressed("debounce(${op.windowMillis}ms): folded into the open window")
                } else {
                    pendingToken = token
                    pendingDeadline = nowMillis + op.windowMillis
                    accept(token)
                }
            }
        }
    }

    /** Release nothing but prove the window closed: the flush point for a coalesced dispatch. */
    fun flushDue(nowMillis: Long): Boolean {
        if (operator !is AcpReactionOperator.Debounce) return false
        if (pendingToken == null || nowMillis < pendingDeadline) return false
        pendingToken = null
        return true
    }

    private fun accept(token: String): Decision {
        dispatched++
        lastToken = token
        released += token
        return Decision.Fire
    }
}
