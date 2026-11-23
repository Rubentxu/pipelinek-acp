package pipelinek.acp

/**
 * The v2 turn as the plugin settles it (PKH-142 AC#2): acceptance and finalization are
 * SEPARATE facts, and the await that closes the turn says WHICH condition it waits on.
 *
 * In v1 the pending `session/prompt` response WAS the turn: one message carried acceptance,
 * progress implicitly (the slot stays open) and the stop reason. v2 splits that into the
 * prompt response acknowledging INSERTION of the user message (a `messageId`, no stop reason)
 * and the `state_update` notifications carrying foreground completion — so an adapter that
 * reads the v2 response the v1 way reports "turn finished" the moment a message was filed.
 * This file is that mistake made impossible: [AcpV2TurnAwait] is a condition (idle with a
 * RECOGNIZED stop reason), a background policy and a close requirement, settled over the
 * event sequence [AcpV2TurnEvent], and every terminal verdict names the fact that earned it.
 *
 * The idle-with-background rule is the spec's own: "Background activity can continue and emit
 * other session/update notifications while the Agent reports idle". Idle is NOT success and
 * NOT quietness; it is only the end of the FOREGROUND lane, under whatever the background
 * snapshot still lists.
 */

/** One unit of work the agent's background snapshot lists as still running. */
data class AcpV2BackgroundWork(val id: String, val label: String = "")

/**
 * The events of one v2 turn, in the order the exchange produced them. Pure data: the wire
 * mapping (prompt response → [Accepted], state_update → [Foreground], close answer →
 * [Closed]) lives in the adapter; the settling lives here, where it is testable row by row.
 */
sealed interface AcpV2TurnEvent {
    /** `session/prompt` ANSWERED: the user message was inserted under [messageId]. Acceptance
     * is not completion — this event alone never settles the turn. */
    data class Accepted(val messageId: String) : AcpV2TurnEvent

    /** A foreground `state_update`: [state] is running / idle / requires_action / unknown,
     * [stopReason] is the idle update's (possibly absent) reason, [background] the snapshot
     * of work still running (null = the agent reports none). */
    data class Foreground(
        val state: String,
        val stopReason: String? = null,
        val background: List<AcpV2BackgroundWork>? = null,
    ) : AcpV2TurnEvent

    /** `session/close` was answered: the exchange's own finalization step, separate from the
     * idle update that precedes it on the wire. */
    data class Closed(val acknowledged: Boolean) : AcpV2TurnEvent

    /** The JSON-RPC error that rejected the prompt BEFORE insertion — pre-insertion rejection
     * never opens the turn, so it settles as refusal, not as a finished turn. */
    data class Rejected(val reason: String) : AcpV2TurnEvent
}

/** What the await does with background work still running when the foreground lane goes idle. */
enum class AcpV2BackgroundPolicy {
    /** The turn settles only when the foreground is idle AND the background snapshot is
     * quiescent: a detached task still running is work outstanding, not success. */
    WAIT_UNTIL_QUIESCENT,

    /** Idle settles the turn even with background running — but the verdict SAYS so; the
     * caller can tell an idle session with running work from a quiet one (the spec's own
     * distinction). */
    DETACH_ON_IDLE,
}

/**
 * The condition, background policy and close requirement the await waits on. The three knobs
 * are the AC: an await that only waits for "the prompt response came back" is the v1 shape
 * wearing v2 clothes, and one that cannot say WHY it stopped is guessing.
 */
data class AcpV2TurnAwait(
    val background: AcpV2BackgroundPolicy = AcpV2BackgroundPolicy.DETACH_ON_IDLE,
    val requireCloseAck: Boolean = true,
    /** The stop reasons this await counts as success. Everything else that ends the
     * foreground settles as a NAMED non-success; an idle without a recognized reason settles
     * unconfirmed, because "Clients MUST recognize idle even when the stop reason is omitted,
     * null or unknown... they cannot infer success or failure" without one. */
    val successStopReasons: Set<String> = setOf("end_turn"),
)

/** The settled (or explicitly not-settled) verdict of one v2 turn. */
sealed interface AcpV2TurnVerdict {
    /** The prompt answer filed the message; foreground work is (or may be) running. This is
     * the v1 "response came back" point — and it is NOT a terminal verdict in v2. */
    data class AcceptedOnly(val messageId: String) : AcpV2TurnVerdict

    /** Idle arrived without a recognized stop reason: foreground ended, outcome unknown.
     * Never success, never failure — the honest "cannot infer" answer. */
    data object IdleUnconfirmed : AcpV2TurnVerdict

    /** Foreground idle with a recognized stop reason, but background work is still listed and
     * the policy waits for quiescence. Idle ≠ done. */
    data class BackgroundOutstanding(val stopReason: String, val running: List<AcpV2BackgroundWork>) : AcpV2TurnVerdict

    /** Foreground ended with a non-success stop reason (refusal, max_tokens, max_turn_requests). */
    data class Stopped(val stopReason: String) : AcpV2TurnVerdict

    /** The agent's idle said cancelled, or the exchange cancelled and the idle confirmed it. */
    data object Cancelled : AcpV2TurnVerdict

    /** Foreground ended successfully but the close the await requires has not been acknowledged. */
    data object AwaitingClose : AcpV2TurnVerdict

    /** Success, the full condition: recognized idle stop reason, background policy satisfied,
     * close acknowledged when required. */
    data class Finished(val stopReason: String, val backgroundAtIdle: List<AcpV2BackgroundWork>) : AcpV2TurnVerdict

    /** The prompt was rejected before insertion: the turn never opened. */
    data class Refused(val reason: String) : AcpV2TurnVerdict
}

/**
 * Settles [events] under [await]. Pure fold — the sequence IS the exchange's observable
 * history, so a crash-replay of the same events reproduces the same verdict (the ledger
 * discipline PKH-138 established, applied to the v2 turn shape).
 */
fun settleAcpV2Turn(events: List<AcpV2TurnEvent>, await: AcpV2TurnAwait): AcpV2TurnVerdict {
    var accepted: String? = null
    var lastForeground: AcpV2TurnEvent.Foreground? = null
    var closed = false
    var rejected: String? = null
    events.forEach { event ->
        when (event) {
            is AcpV2TurnEvent.Accepted -> accepted = event.messageId
            is AcpV2TurnEvent.Foreground -> lastForeground = event
            is AcpV2TurnEvent.Closed -> closed = event.acknowledged
            is AcpV2TurnEvent.Rejected -> rejected = event.reason
        }
    }
    rejected?.let { return AcpV2TurnVerdict.Refused(it) }
    val foreground = lastForeground ?: return accepted?.let { AcpV2TurnVerdict.AcceptedOnly(it) }
        ?: AcpV2TurnVerdict.AcceptedOnly("")
    if (foreground.state != "idle") return accepted?.let { AcpV2TurnVerdict.AcceptedOnly(it) }
        ?: AcpV2TurnVerdict.AcceptedOnly("")
    // The foreground lane is idle. What the idle SAYS decides everything after this point.
    val reason = foreground.stopReason
    if (reason == null || reason == "unknown") return AcpV2TurnVerdict.IdleUnconfirmed
    if (reason == "cancelled") {
        return if (await.requireCloseAck && !closed) AcpV2TurnVerdict.AwaitingClose
        else AcpV2TurnVerdict.Cancelled
    }
    if (reason !in await.successStopReasons) return AcpV2TurnVerdict.Stopped(reason)
    val background = foreground.background.orEmpty()
    if (await.background == AcpV2BackgroundPolicy.WAIT_UNTIL_QUIESCENT && background.isNotEmpty()) {
        return AcpV2TurnVerdict.BackgroundOutstanding(reason, background)
    }
    if (await.requireCloseAck && !closed) return AcpV2TurnVerdict.AwaitingClose
    return AcpV2TurnVerdict.Finished(reason, background)
}
