package pipelinek.acp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lane under the pressure it exists for: more facts than it holds, and a reader that must
 * not wait.
 *
 * The rows are not about throughput. They are about the three promises the plan makes — a hard
 * capacity, a payload bound, and a reader that never blocks — and about the one way each can be
 * broken quietly: a drop nobody counted, an eviction of a control fact, an offer that waited.
 */
class AcpObservationLaneTest {

    private fun lane(capacity: Int = 8, maxBytes: Int = AcpObservationLane.DEFAULT_MAX_FACT_BYTES) =
        AcpObservationLane(AcpObservationCodec, capacity = capacity, maxFactBytes = maxBytes)

    private fun chunk(text: String) =
        AcpObservation.AgentMessageChunk("ex-1", AcpEvidence.AGENT_CLAIM, text)

    private fun control(reason: String = "user") =
        AcpObservation.ExchangeCancelled("ex-1", AcpEvidence.VERIFIED, reason)

    @Test
    fun `a flood of chunks is bounded and every loss is counted`() {
        val lane = lane(capacity = 4)
        repeat(200) { lane.offer(chunk("tok$it")) }

        assertTrue(lane.size <= 4, "the lane grew past its capacity: ${lane.size}")
        val gap = lane.accounting("ex-1")
        val drained = generateSequence { lane.poll() }.count()
        // Every fact that entered the lane either came out, was merged into one that did, or
        // was counted as lost. There is no fourth door.
        assertEquals(
            200,
            gap.lost + gap.coalesced + drained,
            "facts entered the lane that came out neither as a published fact nor as a counted loss",
        )
    }

    @Test
    fun `telemetry coalesces so the text survives the flood`() {
        val lane = lane(capacity = 2)
        listOf("Hello ", "world", "!").forEach { lane.offer(chunk(it)) }

        val first = lane.poll()?.first
        assertTrue(
            first is AcpObservation.AgentMessageChunk && first.text.contains("Hello "),
            "the coalesced fact lost the text it merged",
        )
        assertTrue(lane.accounting("ex-1").coalesced > 0, "a merge happened without being counted")
    }

    @Test
    fun `a control fact is never evicted, and telemetry is evicted first`() {
        val lane = lane(capacity = 3)
        repeat(3) { lane.offer(control("cancel-$it")) }
        // Full of control facts: the next reactive fact cannot displace one.
        assertTrue(
            !lane.offer(AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc", "t", "IN_PROGRESS", "READ")),
            "a control fact was evicted to admit another",
        )
        assertEquals(3, lane.size)

        // With telemetry inside, the telemetry is what goes.
        val mixed = lane(capacity = 2)
        mixed.offer(control())
        mixed.offer(chunk("noise"))
        assertTrue(mixed.offer(AcpObservation.PlanRemoved("ex-1", AcpEvidence.AGENT_CLAIM)))
        val kept = generateSequence { mixed.poll() }.map { it.first.token }.toList()
        assertTrue(AcpFactToken.EXCHANGE_CANCELLED in kept, "the control fact did not survive")
        assertTrue(AcpFactToken.PLAN_REMOVED in kept)
    }

    @Test
    fun `a fact bigger than the payload bound is dropped and counted, never split`() {
        val lane = lane(capacity = 4, maxBytes = 200)
        val huge = chunk("y".repeat(5_000))

        assertTrue(!lane.offer(huge), "an oversized fact entered the lane")
        assertEquals(0, lane.size, "a half-published fact is worse than a dropped one")
        assertEquals(1, lane.accounting("ex-1").lost)
        assertEquals(1, lane.accounting("ex-1").lostByToken[AcpFactToken.AGENT_MESSAGE_CHUNK])
    }

    @Test
    fun `coalescing never stores an entry past the payload bound`() {
        // The bound guards what the lane HOLDS, not only what enters it: two chunks each under
        // the bound can merge into an entry over it, and a merged entry that ignores the bound
        // is the half-published fact the drop rule refuses to publish in pieces.
        val lane = lane(capacity = 1)
        assertTrue(lane.offer(chunk("a".repeat(15_000))))
        // Full queue: the second chunk could only fit by coalescing into the first, and that
        // merge would carry ~30k of text past the default bound.
        assertTrue(
            !lane.offer(chunk("b".repeat(15_000))),
            "a merge past the bound was admitted as if it fit",
        )
        val stored = lane.poll()
        assertNotNull(stored)
        check(stored != null)
        assertTrue(
            stored.second.length <= AcpObservationLane.DEFAULT_MAX_FACT_BYTES,
            "the lane stored an entry bigger than the payload bound: ${stored.second.length}",
        )
        val gap = lane.accounting("ex-1")
        assertEquals(0, gap.coalesced, "a merge refused for exceeding the bound is not a merge")
        assertEquals(1, gap.lost, "the chunk that could not be merged was lost in silence")
        assertEquals(1, gap.lostByToken[AcpFactToken.AGENT_MESSAGE_CHUNK])
    }

    @Test
    fun `the reader never blocks, whatever the publisher is doing`() {
        // The lane is the reader's only contact with publishing, so this is the row that says
        // session/cancel still works during an avalanche: the offer that would have waited is
        // the offer that would have parked the pipe.
        val lane = lane(capacity = 16)
        val calls = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(4)
        val refused = java.util.concurrent.atomic.AtomicInteger()
        repeat(4) { thread ->
            calls.submit {
                ready.await()
                repeat(500) { i ->
                    if (!lane.offer(chunk("t${thread}-$i"))) refused.incrementAndGet()
                }
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "an offer blocked the reader")
        calls.shutdownNow()

        assertTrue(lane.size <= 16)
        val gap = lane.accounting("ex-1")
        assertEquals(gap.lost.toLong(), refused.get().toLong(), "a refusal the accounting did not see")
    }

    @Test
    fun `polling an empty lane says so instead of waiting`() {
        val lane = lane()
        assertNull(lane.poll())
        assertNotNull(lane.accounting("ex-1"))
    }

    @Test
    fun `the accounting restarts after a gap is published`() {
        val lane = lane(capacity = 1)
        repeat(5) { lane.offer(control("c$it")) }
        assertTrue(lane.accounting("ex-1").lost > 0)
        lane.resetAccounting()
        val fresh = lane.accounting("ex-1")
        assertEquals(0, fresh.lost)
        assertEquals(emptyMap<String, Int>(), fresh.lostByToken)
    }
}
