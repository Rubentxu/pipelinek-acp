package pipelinek.acp

import com.agentclientprotocol.model.SessionUpdate
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
        val text = AcpSchemaMatrix.tokens.map { token ->
            AcpObservationCodec.encode(
                AcpObservation.AgentMessageChunk("ex-1", AcpEvidence.AGENT_CLAIM, token),
            )
        }.joinToString("|") + AcpObservationCodec.encode(
            AcpObservation.UnknownUpdate("ex-1", AcpEvidence.OBSERVED, "whatever"),
        )

        assertFalse(text.contains("agentclientprotocol"), "an SDK type leaked into the durable payload")
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
