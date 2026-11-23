package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reducer's two laws, plus the replay that proves they are laws and not one good example.
 *
 * A fold that only works in the order the agent happened to send things is not a reducer: the
 * durable stream can be read back in pieces, and the state it describes has to come out the
 * same. So every row here folds twice — incrementally and at once — and compares.
 */
class AcpObservationReducerTest {

    private fun reducer() = AcpObservationReducer { "ex-1" }

    private fun chunk(text: String) =
        AcpObservation.AgentMessageChunk("ex-1", AcpEvidence.AGENT_CLAIM, text)

    private fun call(id: String, title: String = "", status: String = "", kind: String = "") =
        AcpObservation.ToolCallChanged("ex-1", AcpEvidence.AGENT_CLAIM, id, title, status, kind)

    @Test
    fun `a partial tool-call update never erases what an earlier one said`() {
        val reducer = reducer()
        reducer.apply(
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "read file", "IN_PROGRESS", "READ"),
        )
        // The agent reports only the new status — the shape ACP actually takes.
        val published = reducer.apply(call("tc-1", status = "COMPLETED"))

        val state = reducer.state.toolCalls.single()
        assertEquals("read file", state.title, "the change dropped a field this update did not carry")
        assertEquals("READ", state.toolKind)
        assertEquals("COMPLETED", state.status)
        assertEquals(
            listOf(
                // the merged view of the call, then the fact that it will not move again
                AcpObservation.ToolCallChanged("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "read file", "COMPLETED", "READ"),
                AcpObservation.ToolCallCompleted("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "COMPLETED"),
            ),
            published,
        )
    }

    @Test
    fun `a logical completion is published once however often the agent repeats it`() {
        val reducer = reducer()
        reducer.apply(
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "t", "IN_PROGRESS", "EXECUTE"),
        )
        val first = reducer.apply(call("tc-1", status = "COMPLETED"))
        val repeated = reducer.apply(call("tc-1", status = "COMPLETED"))
        val again = reducer.apply(call("tc-1", status = "COMPLETED"))

        assertEquals(1, first.count { it is AcpObservation.ToolCallCompleted })
        assertEquals(emptyList<AcpObservation>(), repeated, "a second completion for one call")
        assertEquals(emptyList<AcpObservation>(), again)
        assertEquals(1, reducer.state.completions, "the state counted the completion twice")
    }

    @Test
    fun `a tool call announced already terminal publishes its completion exactly once`() {
        // The same law as the repeated update, measured at the other door: an agent that
        // announces a call with its terminal status still owes the completion fact — and owes
        // it once, however often it re-announces the end.
        val reducer = reducer()
        val announced = reducer.apply(
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "t", "COMPLETED", "READ"),
        )
        val repeated = reducer.apply(
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "t", "COMPLETED", "READ"),
        )

        assertEquals(1, announced.count { it is AcpObservation.ToolCallCompleted })
        assertEquals(0, repeated.count { it is AcpObservation.ToolCallCompleted })
        assertEquals(1, reducer.state.completions, "the state must count the completion it published")
        assertTrue(reducer.state.toolCalls.single().completed)
    }

    @Test
    fun `replaying the transcript reproduces the same snapshot`() {
        val transcript = listOf(
            AcpObservation.ExchangeStarted("ex-1", AcpEvidence.VERIFIED),
            chunk("Hello "),
            chunk("world"),
            AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc-1", "run", "IN_PROGRESS", "EXECUTE"),
            call("tc-1", status = "COMPLETED"),
            AcpObservation.Plan("ex-1", AcpEvidence.AGENT_CLAIM, listOf(AcpPlanEntry("step", "HIGH", "PENDING"))),
            AcpObservation.Usage("ex-1", AcpEvidence.AGENT_CLAIM, used = 10, size = 100, cost = "0.1 USD"),
            AcpObservation.ModeChanged("ex-1", AcpEvidence.AGENT_CLAIM, "default"),
            AcpObservation.TurnEnded("ex-1", AcpEvidence.VERIFIED, turnIndex = 1, stopReason = "END_TURN"),
        )

        val incremental = reducer()
        val published = mutableListOf<AcpObservation>()
        transcript.forEach { published.addAll(incremental.apply(it)) }

        val replayed = reducer()
        published.forEach { replayed.apply(it) }

        assertEquals(incremental.state, replayed.state, "fold of the published stream differs from fold of the transcript")
        assertEquals("Hello world", replayed.state.message)
        assertEquals(1, replayed.state.completions)
    }

    @Test
    fun `the transcript a run publishes folds back to the message the agent said`() {
        val transcript = List(20) { chunk("tok$it ") } +
            listOf(
                AcpObservation.ToolCall("ex-1", AcpEvidence.AGENT_CLAIM, "tc", "title", "IN_PROGRESS", "READ"),
                call("tc", status = "FAILED"),
            )
        val live = reducer()
        val published = mutableListOf<AcpObservation>()
        transcript.forEach { published.addAll(live.apply(it)) }

        val replay = reducer()
        published.forEach { replay.apply(it) }

        assertEquals(live.state, replay.state)
        assertEquals("FAILED", live.state.toolCalls.single().status)
        assertEquals(
            listOf(AcpObservation.ToolCallCompleted("ex-1", AcpEvidence.AGENT_CLAIM, "tc", "FAILED")),
            published.filterIsInstance<AcpObservation.ToolCallCompleted>(),
            "a terminal state must be published exactly once",
        )
    }

    @Test
    fun `an observation the decoder does not know still reaches the stream as a fact`() {
        val reducer = reducer()
        val unknown = AcpObservation.UnknownUpdate("ex-1", AcpEvidence.OBSERVED, "brand_new_update")

        assertEquals(listOf(unknown), reducer.apply(unknown))
    }

    @Test
    fun `the decoder never lets credentials ride in a uri`() {
        // The whitelist reads a resource link's URI as its kind, but a URI is also the one
        // place an agent can put user:token and a query secret and call it a locator. The
        // shape that must survive is scheme://host/path; anything past the authority that
        // authenticates or selects must not.
        val secret = "https://user:SECRETTOKEN@host.example/deep/path?access=SECRETTOKEN#SECRETTOKEN"
        val link = AcpObservationDecoder.decode(
            SessionUpdate.AgentMessageChunk(ContentBlock.ResourceLink(uri = secret, name = "n", mimeType = null)),
            "ex-1",
        ) as AcpObservation.AgentMessageChunk
        val plan = AcpObservationDecoder.decode(
            SessionUpdate.PlanUpdateV2(
                plan = com.agentclientprotocol.model.PlanVariant.File(id = "p", uri = secret, _meta = null),
                _meta = null,
            ),
            "ex-1",
        ) as AcpObservation.Plan

        for (fact in listOf(link.content, plan.entries.single().content)) {
            assertTrue(fact.isNotEmpty(), "the uri summary went blank instead of redacted: $fact")
            assertTrue(fact.contains("host.example"), "the summary lost the locator's identity: $fact")
            assertTrue(!fact.contains("SECRETTOKEN"), "a credential rode into the durable fact: $fact")
            assertTrue(!fact.contains("user:"), "the userinfo survived the redaction: $fact")
            assertTrue(!fact.contains("?") && !fact.contains("#"), "query/fragment survived the redaction: $fact")
        }
        assertTrue(link.content.startsWith("link/"), "the summary no longer names what it is: ${link.content}")
    }

    @Test
    fun `the decoder answers an unknown variant with the discriminator and nothing else`() {
        val raw = kotlinx.serialization.json.Json.parseToJsonElement("""{"secret":"hide me"}""")
            .let { it as kotlinx.serialization.json.JsonObject }
        val update = SessionUpdate.UnknownSessionUpdate(sessionUpdateType = "brand_new", rawJson = raw, _meta = null)

        val fact = AcpObservationDecoder.decode(update, "ex-1")

        assertEquals(AcpEvidence.OBSERVED, fact.evidence, "what the plugin could not read is not its word")
        assertTrue(fact is AcpObservation.UnknownUpdate && fact.discriminator == "brand_new")
        assertTrue(!AcpObservationCodec.encode(fact).contains("hide me"), "the raw JSON rode into the payload")
    }

    @Test
    fun `the decoder never reads the bytes of an image block`() {
        val update = SessionUpdate.AgentMessageChunk(
            ContentBlock.Image(data = "QUJDREVG", mimeType = "image/png", uri = null),
        )

        val fact = AcpObservationDecoder.decode(update, "ex-1") as AcpObservation.AgentMessageChunk

        assertEquals("", fact.text, "an image has no text to publish")
        assertEquals("image/image/png", fact.content)
        assertTrue(!AcpObservationCodec.encode(fact).contains("QUJDREVG"), "the payload rode into the durable log")
    }

    @Test
    fun `a clipped text block says it was clipped`() {
        val long = "x".repeat(AcpObservationDecoder.MAX_TEXT_LENGTH + 500)
        val fact = AcpObservationDecoder.decode(
            SessionUpdate.AgentMessageChunk(ContentBlock.Text(long)),
            "ex-1",
        ) as AcpObservation.AgentMessageChunk

        assertEquals(AcpObservationDecoder.MAX_TEXT_LENGTH, fact.text.length)
        assertTrue(fact.truncated, "a silent truncation reads as a complete sentence")
    }
}
