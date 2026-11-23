package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PKH-142 AC#2: prompt acceptance and foreground completion are SEPARATE facts, and the
 * await says which condition it waits on — including the background policy and the close.
 *
 * The v1 shape entangled them: the `session/prompt` response WAS the turn. v2 answers the
 * prompt the moment the user message is inserted and moves completion (and its stop reason)
 * to the `state_update` lane. Every false-green that split could produce has a row here:
 * settling on acceptance, settling on an unexplained idle, ignoring still-running background
 * work, or calling the turn done before the close was acknowledged. The wire half of the
 * claim (the two facts arrive on different messages) is [AcpV2DualStackTest].
 */
class AcpV2TurnLifecycleTest {

    private val defaultAwait = AcpV2TurnAwait()

    @Test
    fun `acceptance alone is never a finished turn`() {
        val verdict = settleAcpV2Turn(listOf(AcpV2TurnEvent.Accepted("m1")), defaultAwait)
        assertEquals(AcpV2TurnVerdict.AcceptedOnly("m1"), verdict,
            "the prompt response only filed the message: v2 progress lives on the update lane",
        )
    }

    @Test
    fun `an idle without a recognized stop reason is not success`() {
        // Spec: clients MUST recognize idle even without a reason, and CANNOT infer success or
        // failure from it. Three shapes of "no reason": absent, unknown, and never arrived.
        listOf(null, "unknown").forEach { reason ->
            val verdict = settleAcpV2Turn(
                listOf(AcpV2TurnEvent.Accepted("m1"), AcpV2TurnEvent.Foreground("idle", reason)),
                defaultAwait,
            )
            assertEquals(AcpV2TurnVerdict.IdleUnconfirmed, verdict,
                "idle with stopReason=$reason settled as something inferable",
            )
        }
    }

    @Test
    fun `requires action and running are not idle`() {
        listOf("running", "requires_action", "unknown").forEach { state ->
            val verdict = settleAcpV2Turn(
                listOf(AcpV2TurnEvent.Accepted("m1"), AcpV2TurnEvent.Foreground(state)),
                defaultAwait,
            )
            assertEquals(AcpV2TurnVerdict.AcceptedOnly("m1"), verdict)
        }
    }

    @Test
    fun `idle with background running is not quiet - the detach verdict still names the work`() {
        val bg = listOf(AcpV2BackgroundWork("task-1", "detached test run"))
        val verdict = settleAcpV2Turn(
            listOf(
                AcpV2TurnEvent.Accepted("m1"),
                AcpV2TurnEvent.Foreground("idle", "end_turn", bg),
                AcpV2TurnEvent.Closed(true),
            ),
            AcpV2TurnAwait(background = AcpV2BackgroundPolicy.DETACH_ON_IDLE),
        )
        assertEquals(AcpV2TurnVerdict.Finished("end_turn", bg), verdict,
            "the detach policy settles the foreground lane, and the verdict CARRIES the still-" +
                "running work so the caller can tell an idle-with-work session from a quiet one",
        )
    }

    @Test
    fun `the quiescent policy refuses success while background work runs, then settles`() {
        val bg = listOf(AcpV2BackgroundWork("task-1"))
        val events = mutableListOf(
            AcpV2TurnEvent.Accepted("m1"),
            AcpV2TurnEvent.Foreground("idle", "end_turn", bg),
        )
        assertEquals(
            AcpV2TurnVerdict.BackgroundOutstanding("end_turn", bg),
            settleAcpV2Turn(events, AcpV2TurnAwait(background = AcpV2BackgroundPolicy.WAIT_UNTIL_QUIESCENT)),
            "idle != success under the quiescent policy: the snapshot still lists work",
        )
        events.add(AcpV2TurnEvent.Foreground("idle", "end_turn", emptyList()))
        events.add(AcpV2TurnEvent.Closed(true))
        assertEquals(
            AcpV2TurnVerdict.Finished("end_turn", emptyList()),
            settleAcpV2Turn(events, AcpV2TurnAwait(background = AcpV2BackgroundPolicy.WAIT_UNTIL_QUIESCENT)),
        )
    }

    @Test
    fun `the close belongs to finalization - success without it is AwaitingClose`() {
        val successIdle = listOf(
            AcpV2TurnEvent.Accepted("m1"),
            AcpV2TurnEvent.Foreground("idle", "end_turn"),
        )
        assertEquals(AcpV2TurnVerdict.AwaitingClose,
            settleAcpV2Turn(successIdle, AcpV2TurnAwait(requireCloseAck = true)),
            "the await's condition INCLUDES the close; settling before it would be the v1 habit " +
                "of trusting one message",
        )
        assertEquals(AcpV2TurnVerdict.Finished("end_turn", emptyList()),
            settleAcpV2Turn(successIdle + AcpV2TurnEvent.Closed(true), AcpV2TurnAwait(requireCloseAck = false)),
            "with the close requirement off, the same history settles — the knob is real",
        )
    }

    @Test
    fun `a failed close acknowledgement never reports success`() {
        val verdict = settleAcpV2Turn(
            listOf(
                AcpV2TurnEvent.Accepted("m1"),
                AcpV2TurnEvent.Foreground("idle", "end_turn"),
                AcpV2TurnEvent.Closed(false),
            ),
            defaultAwait,
        )
        assertEquals(AcpV2TurnVerdict.AwaitingClose, verdict,
            "an unacknowledged close is not a close: the finalization fact did not happen",
        )
    }

    @Test
    fun `non-success stop reasons settle as named outcomes, never merged`() {
        assertEquals(AcpV2TurnVerdict.Stopped("refusal"),
            settleTurnWithIdle("refusal"))
        assertEquals(AcpV2TurnVerdict.Stopped("max_tokens"),
            settleTurnWithIdle("max_tokens"))
        assertEquals(AcpV2TurnVerdict.Stopped("max_turn_requests"),
            settleTurnWithIdle("max_turn_requests"))
        assertEquals(AcpV2TurnVerdict.Cancelled,
            settleTurnWithIdle("cancelled"))
    }

    private fun settleTurnWithIdle(reason: String, await: AcpV2TurnAwait = defaultAwait): AcpV2TurnVerdict =
        settleAcpV2Turn(
            listOf(
                AcpV2TurnEvent.Accepted("m1"),
                AcpV2TurnEvent.Foreground("idle", reason),
                AcpV2TurnEvent.Closed(true),
            ),
            await,
        )

    @Test
    fun `a cancelled turn that was never closed is still awaiting its close`() {
        val verdict = settleAcpV2Turn(
            listOf(
                AcpV2TurnEvent.Accepted("m1"),
                AcpV2TurnEvent.Foreground("idle", "cancelled"),
            ),
            AcpV2TurnAwait(),
        )
        assertEquals(AcpV2TurnVerdict.AwaitingClose, verdict)
    }

    @Test
    fun `pre-insertion rejection never opens a turn`() {
        // The v2 rule: a prompt rejected BEFORE insertion answers a JSON-RPC error and produces
        // no user-message fact. Settling it as "accepted, then whatever" would invent a turn.
        val verdict = settleAcpV2Turn(
            listOf(AcpV2TurnEvent.Rejected("invalid params"), AcpV2TurnEvent.Closed(true)),
            defaultAwait,
        )
        assertEquals(AcpV2TurnVerdict.Refused("invalid params"), verdict)
    }

    @Test
    fun `two awaits settle the SAME history differently - the condition is specified, not assumed`() {
        val events = listOf(
            AcpV2TurnEvent.Accepted("m1"),
            AcpV2TurnEvent.Foreground("idle", "end_turn", listOf(AcpV2BackgroundWork("t"))),
            AcpV2TurnEvent.Closed(true),
        )
        assertEquals(
            AcpV2TurnVerdict.BackgroundOutstanding("end_turn", listOf(AcpV2BackgroundWork("t"))),
            settleAcpV2Turn(events, AcpV2TurnAwait(background = AcpV2BackgroundPolicy.WAIT_UNTIL_QUIESCENT)),
        )
        assertEquals(
            AcpV2TurnVerdict.Finished("end_turn", listOf(AcpV2BackgroundWork("t"))),
            settleAcpV2Turn(events, AcpV2TurnAwait(background = AcpV2BackgroundPolicy.DETACH_ON_IDLE)),
        )
    }

    @Test
    fun `successStopReasons is the await's own vocabulary - refusing to invent a second stop language`() {
        val verdict = settleTurnWithIdle(
            "end_turn",
            AcpV2TurnAwait(successStopReasons = setOf("cancelled")),
        )
        assertEquals(AcpV2TurnVerdict.Stopped("end_turn"), verdict,
            "with end_turn outside the declared success set, the same idle settles Stopped: " +
                "the await really waits for ITS condition",
        )
    }
}
