package pipelinek.acp

import com.agentclientprotocol.model.SessionUpdate
import java.util.concurrent.TimeUnit
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the seam was handed, recorded in the order it arrived.
 *
 * The success branch of the published outcome carries the durable row the host wrote, which
 * this plugin cannot mint; what these rows measure is the ACCEPTANCE of a kind, so the stub
 * reports the seam's refusal honestly and treats anything else as accepted.
 */
private class RecordingEmission(private val known: Set<String>) : PluginEventEmission {
    val kinds = mutableListOf<String>()
    val payloads = mutableListOf<Any?>()

    override fun <P : Any> emit(kind: String, payload: P): EmissionOutcome {
        kinds.add(kind)
        payloads.add(payload)
        return if (kind in known) accepted(kind, payload) else EmissionOutcome.UnregisteredKind(kind)
    }

    private fun accepted(kind: String, payload: Any) = EmissionOutcome.Emitted(
        dev.rubentxu.pipeline.v2.events.PluginEventEmitted(
            eventId = "evt-1",
            runId = "run-1",
            sequence = 1L,
            occurredAt = java.time.Instant.EPOCH,
            registryKind = kind,
            schemaVersion = AcpObservationCodec.SCHEMA_VERSION,
            payload = AcpObservationCodec.encode(payload as AcpObservation),
            emittedBy = AcpPluginDeclaration.CONTRIBUTOR_ID,
        ),
    )
}

/**
 * The projection seen the way a consumer sees it: through the published seam.
 *
 * Nothing here starts a host or speaks JSON-RPC. What the seam receives IS the contract — the
 * kinds, the payload shape and the fact that a loss arrives as a fact of the same stream — and
 * the rows below are the ones that fail if any of that drifts.
 */
class AcpObservationProjectionTest {


    @Test
    fun `the contributor declares exactly the kinds the matrix names`() {
        val contributed = AcpObservationContributor().definitions().mapNotNull { (it as? EventDefinitionCreation.Valid)?.definition?.kind }

        assertEquals(AcpSchemaMatrix.kinds.sorted(), contributed.sorted())
        assertTrue(contributed.all { it.startsWith(AcpFactToken.KIND_PREFIX) })
        assertEquals(AcpObservationContributor().id, AcpPluginDeclaration.CONTRIBUTOR_ID)
    }

    @Test
    fun `the codec round-trips a fact and refuses a shape it does not know`() {
        val fact = AcpObservation.ToolCallCompleted("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "COMPLETED")
        val encoded = AcpObservationCodec.encode(fact)

        val back = AcpObservationCodec.decode(encoded, AcpObservationCodec.SCHEMA_VERSION)
        assertTrue(back is PayloadDecode.Decoded && back.payload == fact)
        assertEquals(AcpObservationCodec.encode(fact), encoded, "encoding is not deterministic")
        assertTrue(
            AcpObservationCodec.decode(encoded, AcpObservationCodec.SCHEMA_VERSION + 1)
                is PayloadDecode.UnknownSchemaVersion,
            "a newer payload was read under the old rules",
        )
        assertTrue(AcpObservationCodec.decode("{not-json", 1) is PayloadDecode.Malformed)
    }

    @Test
    fun `a decoded fact never names a class of the ACP SDK`() {
        // The promise is about what the DECODER produces, so the row must drive the decoder:
        // hand-written payloads would only prove the hand-written payloads are clean. One real
        // update per known variant goes in; the encoded fact comes out; no SDK package may
        // appear in it, however the payload is shaped.
        val updates: List<SessionUpdate> = listOf(
            SessionUpdate.AgentMessageChunk(com.agentclientprotocol.model.ContentBlock.Text("hola")),
            SessionUpdate.AgentThoughtChunk(com.agentclientprotocol.model.ContentBlock.Text("pienso")),
            SessionUpdate.UserMessageChunk(com.agentclientprotocol.model.ContentBlock.Text("eco"), null, null),
            SessionUpdate.ToolCall(
                toolCallId = com.agentclientprotocol.model.ToolCallId("tc-1"),
                title = "t",
                kind = null,
                status = null,
            ),
            SessionUpdate.ToolCallUpdate(toolCallId = com.agentclientprotocol.model.ToolCallId("tc-1"), title = "t", kind = null),
            SessionUpdate.PlanUpdateV2(
                plan = com.agentclientprotocol.model.PlanVariant.Markdown("p-1", "step", null),
                _meta = null,
            ),
            SessionUpdate.PlanRemoved("plan-1", null),
            SessionUpdate.UsageUpdate(10L, 100L, null, null),
            SessionUpdate.CurrentModeUpdate(com.agentclientprotocol.model.SessionModeId("default")),
            SessionUpdate.AvailableCommandsUpdate(
                listOf(com.agentclientprotocol.model.AvailableCommand("deploy", "d", null, null)),
            ),
            SessionUpdate.SessionInfoUpdate("title", "now", null),
            SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "brand_new",
                rawJson = kotlinx.serialization.json.Json.parseToJsonElement("""{"blob":"x"}""")
                    .let { it as kotlinx.serialization.json.JsonObject },
                _meta = null,
            ),
        )

        for (update in updates) {
            val fact = AcpObservationDecoder.decode(update, "ex-1")
            val encoded = AcpObservationCodec.encode(fact)
            assertFalse(
                encoded.contains("agentclientprotocol"),
                "an SDK type leaked into the durable payload of ${update::class.simpleName}: $encoded",
            )
            assertTrue(
                AcpSchemaMatrix.tokens.contains(fact.token),
                "the decoder invented a fact outside the declared vocabulary: ${fact.token}",
            )
        }
    }

    @Test
    fun `what the agent said is carried as its word and never as the plugin's`() {
        val emission = RecordingEmission(AcpSchemaMatrix.kinds.toSet())
        val publisher = AcpObservationPublisher(emission) { "run-1#0" }

        publisher.accept(
            com.agentclientprotocol.model.SessionUpdate.AgentMessageChunk(
                com.agentclientprotocol.model.ContentBlock.Text("hola"),
            ),
        )
        publisher.drain()

        val fact = emission.payloads.single() as AcpObservation.AgentMessageChunk
        assertEquals(AcpEvidence.AGENT_CLAIM, fact.evidence)
        assertEquals("run-1#0", fact.exchangeRef)
        assertEquals(AcpFactToken.kindOf(AcpFactToken.AGENT_MESSAGE_CHUNK), emission.kinds.single())
    }

    @Test
    fun `a seam that is not there is a counted loss, not a silent one`() {
        // The seam refuses the kind: the fact is gone from the stream, and the gap is the only
        // place a reader can learn that. A publisher that swallowed this would read as "the
        // agent was quiet".
        val emission = RecordingEmission(emptySet())
        val publisher = AcpObservationPublisher(emission) { "run-1#0" }

        publisher.accept(
            SessionUpdate.AgentMessageChunk(com.agentclientprotocol.model.ContentBlock.Text("gone")),
        )
        publisher.drain()
        emission.kinds.clear()
        emission.payloads.clear()
        publisher.publishGap()

        assertEquals(listOf(AcpFactToken.kindOf(AcpFactToken.OBSERVATION_GAP)), emission.kinds)
        val gap = emission.payloads.single() as AcpObservation.ObservationGap
        assertEquals(1, gap.unpublished)
        assertEquals(AcpEvidence.VERIFIED, gap.evidence, "the loss is the plugin's own fact, not the agent's")
    }

    @Test
    fun `the fold is never mutated from two threads at once`() {
        // accept runs on the thread of the prompt flow AND on the thread of the transport (the
        // reverse notifications arrive there). The reducer's laws — the message keeps every
        // character said, a completion happens once — only hold if one fold runs at a time, so
        // this storm drives accept from four threads at once and measures the laws afterwards.
        val emission = RecordingEmission(AcpSchemaMatrix.kinds.toSet())
        val publisher = AcpObservationPublisher(emission) { "run-1#0" }
        val threads = 4
        val perThread = 500
        val chunkChars = 8
        val ready = java.util.concurrent.CyclicBarrier(threads)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) { thread ->
            pool.submit {
                try {
                    ready.await()
                    repeat(perThread) { i ->
                        publisher.accept(
                            SessionUpdate.AgentMessageChunk(
                                com.agentclientprotocol.model.ContentBlock.Text("$thread$i".padEnd(chunkChars, '.')),
                            ),
                        )
                        if (i % 50 == 0) {
                            // The same call, announced terminal by every thread: the completion
                            // law is one per call, so the fold cannot be allowed to interleave.
                            publisher.accept(
                                SessionUpdate.ToolCall(
                                    toolCallId = com.agentclientprotocol.model.ToolCallId("tc-storm"),
                                    title = "storm",
                                    kind = null,
                                    status = com.agentclientprotocol.model.ToolCallStatus.COMPLETED,
                                ),
                            )
                        }
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "an accept parked the storm — the fold is not confined")
        pool.shutdownNow()
        assertEquals(emptyList<Throwable>(), failures.toList(), "accept threw under concurrent use")
        publisher.drain()
        publisher.publishGap()

        val chunks = emission.payloads.filterIsInstance<AcpObservation.AgentMessageChunk>()
        val gap = emission.payloads.filterIsInstance<AcpObservation.ObservationGap>().single()
        // Every character said is either in a published fact or in a counted loss: a fold that
        // lost appends to a racing StringBuilder publishes less than the stream owes.
        assertEquals(
            threads.toLong() * perThread * chunkChars,
            chunks.sumOf { it.text.length.toLong() } + gap.lost.toLong() * chunkChars,
            "characters vanished between accept and the lane — the reducer was mutated concurrently",
        )
        assertEquals(
            1,
            emission.payloads.count { it is AcpObservation.ToolCallCompleted },
            "the same call completed more than once — the fold interleaved",
        )
    }

    @Test
    fun `the projection carries no authority anywhere in its vocabulary`() {
        // AC#4's last clause, measured over the shipped words rather than asserted about them:
        // nothing a reader can decode from this plugin's kinds says "approve", "resolve" or
        // "mutate", and no member of the sealed vocabulary is consumable as an order.
        val vocabulary = AcpSchemaMatrix.tokens + AcpSchemaMatrix.CONTENT_BLOCKS.map { it.variant }
        vocabulary.forEach { token ->
            assertFalse(
                token.contains("approve") || token.contains("resolve") || token.contains("mutate"),
                "the observation vocabulary names an operation: $token",
            )
        }
        // The payload classes are the plugin's own; none of them is a request type of the SDK.
        val payload = AcpObservationCodec.encode(
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc", "t", "IN_PROGRESS", "READ"),
        )
        assertFalse(payload.contains("permission"), "an observation payload offers a grant")
    }

}
