package pipelinek.acp

import com.agentclientprotocol.model.SessionUpdate
import java.nio.file.Files
import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The extension axis over the real wire (PKH-141 AC#3): namespaced and plain unknown methods,
 * unknown `_meta` and an unknown update variant all ride the SAME connection the plugin uses
 * in production, dialed by the fixture agent — a second, independent implementation of the
 * wire — while a prompt is in flight.
 *
 * The claim is COMPATIBILITY WITHOUT AUTHORITY, and it has three faces, each measured by the
 * cheapest witness that can falsify it:
 *
 *   * an unknown REQUEST the agent dials must be answered with the protocol error JSON-RPC
 *     demands (-32601 method-not-found) — silence would hang the agent's own turn, and a
 *     success answer would grant the invented method an authority it never negotiated;
 *   * an unknown NOTIFICATION carries no id and must get NO answer at all (ANSWERING it would
 *     corrupt the stream the real responses travel on);
 *   * an unknown update variant, and a known one carrying unknown `_meta`, must reach the
 *     client's drift answer (the explicit `UnknownSessionUpdate`) and be processed — the
 *     session survives the avalanche of the not-understood.
 *
 * The `_meta` payload asks for nothing, and the client's typed outputs carry no field that
 * could consume it: the authority half is [AcpSchemaMatrixTest]'s rule that no decoder reads
 * `_meta`, re-asserted here over the wire by the run that COMPLETES through it.
 *
 * The fourth row turns the direction around: a method the plan legitimately dialed, which the
 * agent announced and then refuses on the wire, must answer as the NAMED operation — the
 * plan's failure diagnosis is part of the compatibility story AC#3 tells.
 */
class AcpWireExtensionCompatTest {

    /** The updates the exchange saw, in arrival order — the drift answer's raw material. */
    private class RecordingSink : AcpUpdateSink {
        val updates: MutableList<SessionUpdate> = Collections.synchronizedList(mutableListOf())
        override fun accept(update: SessionUpdate) {
            updates.add(update)
        }
    }

    private fun fixture(vararg args: String): List<String> {
        val url = checkNotNull(javaClass.getResource("/acp_session_fixture_agent.py")) {
            "the session fixture agent is missing from the test resources"
        }
        val script = java.nio.file.Paths.get(url.toURI()).toString()
        return listOf("python3", "-I", script) + args.toList()
    }

    private fun runWithExtensions(witness: java.nio.file.Path, sink: RecordingSink): AcpSessionOutput =
        AcpSessionConnection("0.0.0-test").run(
            AcpSessionInput(
                command = fixture("full", witness.toString(), "extensions"),
                turns = listOf(acpTurn("extension probe")),
                cwd = System.getProperty("java.io.tmpdir"),
                timeoutSeconds = 60,
            ),
            observation = sink,
        )

    @Test
    fun `unknown requests answer the protocol error and the unknown notification stays silent`() {
        // The witness is the agent's own record of what came back over the wire; the codes are
        // JSON-RPC constants (the protocol's answer by direction), not fixture prose.
        val witness = Files.createTempFile("acp-ext-requests", ".txt")
        try {
            val out = runWithExtensions(witness, RecordingSink())
            assertTrue(out.ok, "extension chatter must not break the exchange: ${out.failureKind}: ${out.detail}")
            val lines = Files.readString(witness).lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            // FORM: every unknown REQUEST — namespaced AND plain — got an error answer, and
            // the error is the protocol's method-not-found. TIMEOUT or NO_ERROR_FIELD would be
            // the two ways the client could grant (or refuse to refuse) an un-negotiated method.
            for (tag in listOf("EXTREQ", "EXTREQ2")) {
                val answer = lines.firstOrNull { it.startsWith("$tag ") }
                assertTrue(answer != null, "the client never answered the $tag request: $lines")
                assertEquals("-32601", answer!!.removePrefix("$tag "),
                    "an unknown request must answer method-not-found, per JSON-RPC")
            }
            // The unknown NOTIFICATION has no id to answer: silence is the protocol's demand.
            assertTrue("NOTIFYDROP silent" in lines,
                "the client answered (or the fixture lost) the unknown notification: $lines")
            // The same law, measured on the schema's OWN client-side notification the pinned
            // SDK has no handler for — the exact claim the matrix's auth/status_update row
            // makes when it classifies UNSUPPORTED by protocol-drop rather than by gap.
            assertTrue("STATUSDROP silent" in lines,
                "auth/status_update is not the silent drop the UNSUPPORTED row argues: $lines")
        } finally {
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `unknown _meta rides through and the unknown variant lands as the explicit drift answer`() {
        val witness = Files.createTempFile("acp-ext-updates", ".txt")
        val sink = RecordingSink()
        try {
            val out = runWithExtensions(witness, sink)
            assertTrue(out.ok, "the update avalanche must not end the exchange: ${out.detail}")
            assertEquals(
                "mcp0:turn1|seen:extension probe|kinds:text",
                out.turns.single().reply,
                "the reply the session owes arrives whole, after the extension traffic",
            )
            // Compatibility kept: the known variant carrying unknown _meta was PROCESSED.
            val metaChunk = sink.updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().singleOrNull {
                it.content is com.agentclientprotocol.model.ContentBlock.Text &&
                    (it.content as com.agentclientprotocol.model.ContentBlock.Text).text == "meta-attached"
            }
            assertTrue(metaChunk != null,
                "an update with unknown _meta was dropped instead of processed: ${sink.updates.map { it::class.simpleName }}")
            // Authority not taken: the unknown variant becomes the SDK's explicit unknown —
            // named as such, not guessed into a known class, and never fatal.
            val unknown = sink.updates.filterIsInstance<SessionUpdate.UnknownSessionUpdate>()
            assertTrue(unknown.isNotEmpty(),
                "the unknown variant never reached the drift answer: ${sink.updates.map { it::class.simpleName }}")
            assertTrue(unknown.all { it.sessionUpdateType.isNotEmpty() },
                "the drift answer must still NAME what it did not understand")
            // And the extension traffic came DURING a live prompt: the order the updates
            // arrived in is the order the agent dialed them — the client kept reading while it
            // was itself waiting for a response.
            val thoughtAt = sink.updates.indexOfFirst {
                it is SessionUpdate.AgentThoughtChunk &&
                    (it.content as? com.agentclientprotocol.model.ContentBlock.Text)?.text == "meta-attached"
            }
            val unknownAt = sink.updates.indexOfFirst { it is SessionUpdate.UnknownSessionUpdate }
            assertTrue(thoughtAt in 0 until unknownAt,
                "the meta-bearing update must precede the unknown variant, as the agent dialed them")
        } finally {
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `an announced operation the agent refuses on the wire names itself, not a generic`() {
        // Direction reversed: this time the method-not-found comes FROM the agent, on a call
        // the plan legitimately dialed (deleteOnExit with the delete capability announced).
        // The answer must carry the operation's name — a generic rejection would hide which
        // half of the negotiation lied, and the plan branches on exactly that.
        val out = AcpSessionConnection("0.0.0-test").run(
            AcpSessionInput(
                command = fixture("full", "", "liar"),
                turns = listOf(acpTurn("x")),
                cwd = System.getProperty("java.io.tmpdir"),
                timeoutSeconds = 60,
                deleteOnExit = true,
            ),
        )
        assertFalse(out.ok, "a delete the agent answers with method-not-found is a real failure")
        assertEquals("ACP_SESSION_OPERATION_FAILED", out.failureKind)
        assertTrue(out.detail.contains("session/delete"),
            "the refusal must name the operation that lied, got: ${out.detail}")
        assertTrue(out.outcome in AcpSessionOutcome.VOCABULARY,
            "the failure is a classified answer of the session vocabulary, got '${out.outcome}'")
    }
}
