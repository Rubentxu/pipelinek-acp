package pipelinek.acp

/**
 * The bounded lane between the agent's updates and the run's event stream.
 *
 * ## Why a lane at all
 *
 * The ACP transport delivers the agent's updates on the thread that reads the pipe. That
 * thread is the one that has to stay free to deliver `session/cancel` — a cancellation that
 * waits behind a flood of chunks is a cancellation that does not work. So the reader must
 * never block, and the publishing of a fact (which appends to the run's event store, and that
 * store has its own queue) must happen somewhere else. The lane is that somewhere else: a
 * bounded queue the reader fills without waiting and the publisher drains.
 *
 * ## What the lane guarantees
 *
 * - **The reader never blocks.** An offer is a short, synchronized operation that either
 *   admits the fact, merges it, or drops it. There is no path in it that waits.
 * - **Facts outrank telemetry.** When the lane is full, a fact evicts telemetry first and a
 *   reactive fact second; a control fact is never evicted. The reservation the facts need is
 *   expressed as this order rather than as a fixed partition of the queue, because a fact
 *   that has to wait for a slot that only telemetry would free is a fact that arrives late.
 * - **Telemetry is coalescible.** Two chunks of the same kind merge into one, so the text the
 *   agent said survives a flood even when the queue is at its bound.
 * - **Nothing is lost in silence.** Every dropped fact is counted, and the counts are
 *   published as a gap: a reader that sees fewer facts than the agent sent must be able to
 *   learn that from the stream itself.
 */
internal class AcpObservationLane(
    private val codec: AcpObservationCodec,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxFactBytes: Int = DEFAULT_MAX_FACT_BYTES,
) {

    /** One queued fact with its encoded form: encoding happens once, on the way in. */
    private class Entry(val observation: AcpObservation, val encoded: String)

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private val lostByToken = LinkedHashMap<String, Int>()
    private var lost: Int = 0
    private var coalesced: Int = 0

    /**
     * Offers a fact to the lane. Never blocks and never throws.
     *
     * Returns whether the fact was admitted (merged counts as admitted: its text is in the
     * lane either way).
     */
    fun offer(observation: AcpObservation): Boolean = synchronized(lock) {
        val encoded = codec.encode(observation)
        if (encoded.length > maxFactBytes) {
            // A fact that does not fit the payload bound is not published in pieces: a
            // half-published fact reads as a complete one. It is dropped and COUNTED.
            lose(observation.token)
            return@synchronized false
        }
        if (queue.size < capacity) {
            queue.addLast(Entry(observation, encoded))
            return@synchronized true
        }
        if (AcpSchemaMatrix.laneOf(observation.token) == AcpLane.TELEMETRY) {
            val merged = coalesce(observation)
            if (merged != null) {
                coalesced++
                return@synchronized true
            }
            lose(observation.token)
            return@synchronized false
        }
        val evicted = evictableIndex()
        if (evicted < 0) {
            // Every slot holds a control fact: the lane will not evict one, so this fact is
            // the one that goes — and the gap says so.
            lose(observation.token)
            return@synchronized false
        }
        lose(queue[evicted].observation.token)
        queue.removeAt(evicted)
        queue.addLast(Entry(observation, encoded))
        true
    }

    /** Takes the next fact out of the lane, or null when the lane is empty. */
    fun poll(): Pair<AcpObservation, String>? = synchronized(lock) {
        val entry = queue.removeFirstOrNull() ?: return@synchronized null
        entry.observation to entry.encoded
    }

    /** How many facts are waiting. */
    val size: Int get() = synchronized(lock) { queue.size }

    /**
     * What the lane lost, as the accounting the stream publishes.
     *
     * [unpublished] is the count the publisher keeps: facts the emitter refused are lost too,
     * and they are lost in a different way — they never reached the lane's queue at all.
     */
    fun accounting(exchangeRef: String, unpublished: Int = 0): AcpObservation.ObservationGap =
        synchronized(lock) {
            AcpObservation.ObservationGap(
                exchangeRef = exchangeRef,
                evidence = AcpEvidence.VERIFIED,
                lost = lost,
                coalesced = coalesced,
                unpublished = unpublished,
                lostByToken = lostByToken.toMap(),
            )
        }

    /** Forgets the accounting, so the next gap reports only what happened since. */
    fun resetAccounting() = synchronized(lock) {
        lost = 0
        coalesced = 0
        lostByToken.clear()
    }

    /**
     * The index of the fact that may be evicted to make room for a fact.
     *
     * Telemetry goes first, a reactive fact second, and a control fact never.
     */
    private fun evictableIndex(): Int {
        val telemetry = queue.indexOfFirst {
            AcpSchemaMatrix.laneOf(it.observation.token) == AcpLane.TELEMETRY
        }
        if (telemetry >= 0) return telemetry
        return queue.indexOfFirst {
            AcpSchemaMatrix.laneOf(it.observation.token) == AcpLane.REACTIVE
        }
    }

    /**
     * Merges [incoming] into the last queued fact of the same kind.
     *
     * Only the chunks merge: they are the facts whose text is the whole point. The merged
     * fact keeps the position of the one it merged into, and the text order inside it is the
     * order the agent said it in.
     */
    private fun coalesce(incoming: AcpObservation): AcpObservation? {
        val index = queue.indexOfLast { it.observation.token == incoming.token }
        if (index < 0) return null
        val existing = queue[index].observation
        val merged = when {
            existing is AcpObservation.AgentMessageChunk &&
                incoming is AcpObservation.AgentMessageChunk ->
                existing.copy(
                    text = existing.text + incoming.text,
                    truncated = existing.truncated || incoming.truncated,
                )

            existing is AcpObservation.AgentThoughtChunk &&
                incoming is AcpObservation.AgentThoughtChunk ->
                existing.copy(
                    text = existing.text + incoming.text,
                    truncated = existing.truncated || incoming.truncated,
                )

            existing is AcpObservation.UserMessageChunk &&
                incoming is AcpObservation.UserMessageChunk ->
                existing.copy(
                    text = existing.text + incoming.text,
                    truncated = existing.truncated || incoming.truncated,
                )

            else -> null
        } ?: return null
        queue[index] = Entry(merged, codec.encode(merged))
        return merged
    }

    /** Counts a fact as lost. */
    private fun lose(token: String) {
        lost++
        lostByToken[token] = (lostByToken[token] ?: 0) + 1
    }

    companion object {
        /** How many facts the lane holds at most. */
        const val DEFAULT_CAPACITY: Int = 512

        /** How many characters an encoded fact may have at most. */
        const val DEFAULT_MAX_FACT_BYTES: Int = 16 * 1024
    }
}
