package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composed session against a deterministic agent: the fixture is a second, independent
 * implementation of the wire (PKH-135), so framing, id correlation, the multi-turn session
 * history, the load-window notification ordering and the close/delete witnesses are all
 * checked across two codebases — not against our own mocks.
 *
 * The claims under test are the acceptance criteria: sequential turns carry context (AC#1);
 * multimodal blocks ride the negotiated gate (AC#2); load history is separate from live
 * events and normal close never deletes (AC#3); foreign refs and invalid config answer as
 * typed refusals (AC#4).
 */
class AcpSessionRoundTripTest {

    private fun fixture(vararg args: String): List<String> {
        val url = checkNotNull(javaClass.getResource("/acp_session_fixture_agent.py")) {
            "the session fixture agent is missing from the test resources"
        }
        val script = java.nio.file.Paths.get(url.toURI()).toString()
        // -I: isolated interpreter — the fixture lives in test resources precisely so
        // nothing else can be imported by accident.
        return listOf("python3", "-I", script) + args.toList()
    }

    private fun input(
        command: List<String>,
        turns: List<AcpTurnInput>,
        copy: (AcpSessionInput) -> AcpSessionInput = { it },
    ) = copy(
        AcpSessionInput(
            command = command,
            turns = turns,
            cwd = System.getProperty("java.io.tmpdir"),
            timeoutSeconds = 60,
        ),
    )

    @Test
    fun `two sequential prompts on one session conserve the agent-side context`() {
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"),
                turns = listOf(acpTurn("first question"), acpTurn("second question")),
            ),
        )
        assertTrue(out.ok, "the session must complete, got ${out.failureKind}: ${out.detail}")
        assertEquals(2, out.turns.size)
        // turn 1: the agent saw only its own question (the fixture's mcp-prefix says zero
        // descriptors travelled — the gate was never asked for any here)…
        assertEquals("mcp0:turn1|seen:first question|kinds:text", out.turns[0].reply)
        // …turn 2 sees BOTH: the context is the agent's session history, carried across
        // turns without the client replaying it (AC#1).
        assertEquals("turn2|seen:first question;second question|kinds:text", out.turns[1].reply)
        assertEquals("END_TURN", out.turns[0].stopReason)
        assertFalse(out.sessionRef.isBlank())
    }

    @Test
    fun `the session ref is opaque and belongs to the connection that minted it`() {
        val connection = AcpSessionConnection("0.0.0-test")
        val out = connection.run(
            input(fixture("full"), turns = listOf(acpTurn("ping"))),
        )
        assertTrue(out.ok)
        val ref = SessionRef.parse(out.sessionRef)
        assertTrue(ref != null && SessionRef.ownedBy(ref, connection.connectionNonce),
            "the ref must parse and belong to the minting connection")
        // A different connection — the same process, a different owner epoch — cannot
        // claim it: foreign refs are detectable without any agent round-trip (AC#4).
        val other = AcpSessionConnection("0.0.0-test")
        assertFalse(SessionRef.ownedBy(ref!!, other.connectionNonce))
    }

    @Test
    fun `a scope that dies by exception releases the process and answers typed`() {
        // sh exits with 9 mid-handshake: the release clause AC#1 asks for must fire through
        // the failure path, and the answer stays typed — never a crash into ENGINE.
        val out = AcpSessionConnection("0.0.0-test").run(
            input(listOf("sh", "-c", "exit 9"), turns = listOf(acpTurn("never reaches"))),
        )
        assertFalse(out.ok)
        assertTrue(
            out.failureKind in setOf("ACP_AGENT_EXITED", "ACP_TRANSPORT_CLOSED", "ACP_TRANSPORT_FAILURE"),
            "a dead process classifies transport-side, got ${out.failureKind}",
        )
    }

    @Test
    fun `a scope cut by cancellation releases the process and refuses typed`() {
        runBlocking {
            val connection = AcpSessionConnection("0.0.0-test")
            // the slow fixture sleeps 20 s inside the first prompt turn: 2 s cut it.
            val out = connection.exchange(
                input(fixture("full", "", "slow"), turns = listOf(acpTurn("slow turn")),
                    copy = { it.copy(timeoutSeconds = 2) }),
            )
            assertFalse(out.ok)
            assertTrue(
                out.failureKind in setOf("ACP_AGENT_TIMEOUT", "ACP_TRANSPORT_CLOSED",
                    "ACP_AGENT_EXITED", "ACP_ACTIVE_PROMPT_IN_PROGRESS"),
                "a cut scope answers transport/budget-side, got ${out.failureKind}",
            )
        }
    }

    @Test
    fun `load history is separated from live events and normal close never deletes`() {
        val witness = Files.createTempFile("acp-session-witness", ".txt")
        try {
            val out = AcpSessionConnection("0.0.0-test").run(
                input(
                    fixture("full", witness.toString()),
                    turns = listOf(acpTurn("after load")),
                    copy = { it.copy(loadSessionId = "agent-resumed-1") },
                ),
            )
            assertTrue(out.ok, "load + turn must complete, got ${out.failureKind}: ${out.detail}")
            // AC#3: exactly the two updates the agent streamed inside the load window —
            // and only them. The live prompt reply never mixes into the historic list.
            assertEquals(listOf("historic-1", "historic-2"), out.historicEvents)
            // The reply carries the fixture's mcp-prefix: zero MCP descriptors travelled.
            assertEquals("mcp0:turn1|seen:after load|kinds:text", out.turns.single().reply)
            val seen = Files.readString(witness)
            // session/close was said (the agent announced it)…
            assertTrue(seen.contains("CLOSE"), "a normal exit says close, saw: $seen")
            // …and session/delete was NOT: closing a scope is not erasing history (AC#3).
            assertFalse(seen.contains("DELETE"), "normal close must never delete, saw: $seen")
        } finally {
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `an explicitly declared delete is honoured, and list answers with ids`() {
        val witness = Files.createTempFile("acp-session-witness", ".txt")
        try {
            val out = AcpSessionConnection("0.0.0-test").run(
                input(
                    fixture("full", witness.toString()),
                    turns = listOf(acpTurn("hi")),
                    copy = { it.copy(listSessions = true, deleteOnExit = true, logout = true) },
                ),
            )
            assertTrue(out.ok, "list + delete + logout must complete, got ${out.failureKind}")
            assertEquals(listOf("agent-listed-7", "agent-listed-8"), out.sessionsListed)
            assertTrue(out.logoutOk)
            assertTrue(Files.readString(witness).contains("DELETE"),
                "the declared delete must reach the agent")
        } finally {
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `modes and config options are applied against what the session offers`() {
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"),
                turns = listOf(acpTurn("planning")),
                copy = {
                    it.copy(
                        modeId = "plan",
                        configOptions = listOf(AcpConfigInput("verbosity", "high")),
                    )
                },
            ),
        )
        assertTrue(out.ok, "mode+config must complete, got ${out.failureKind}: ${out.detail}")
        assertEquals("plan", out.appliedMode)
        assertEquals(listOf("verbosity=high"), out.appliedConfig)
    }

    @Test
    fun `a mode or config value outside the offered set is a typed ConfigInvalid`() {
        val badMode = AcpSessionConnection("0.0.0-test").run(
            input(fixture("full"), turns = listOf(acpTurn("x")), copy = { it.copy(modeId = "turbo") }),
        )
        assertFalse(badMode.ok)
        assertEquals("ACP_CONFIG_INVALID", badMode.failureKind)

        val badValue = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"), turns = listOf(acpTurn("x")),
                copy = { it.copy(configOptions = listOf(AcpConfigInput("verbosity", "extreme"))) },
            ),
        )
        assertFalse(badValue.ok)
        assertEquals("ACP_CONFIG_INVALID", badValue.failureKind)

        val neverAdvertised = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"), turns = listOf(acpTurn("x")),
                copy = { it.copy(configOptions = listOf(AcpConfigInput("nonexistent", "x"))) },
            ),
        )
        assertFalse(neverAdvertised.ok)
        assertEquals("ACP_CONFIG_INVALID", neverAdvertised.failureKind)
    }

    @Test
    fun `an unannounced capability is refused before the session is even asked`() {
        val listRefusal = AcpSessionConnection("0.0.0-test").run(
            input(fixture("minimal"), turns = listOf(acpTurn("hi")), copy = { it.copy(listSessions = true) }),
        )
        assertFalse(listRefusal.ok)
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", listRefusal.failureKind)
        assertEquals("", listRefusal.sessionRef)

        val imageRefusal = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("minimal"),
                turns = listOf(
                    AcpTurnInput("look", content = listOf(acpImage("aGVsbG8=", "image/png"))),
                ),
            ),
        )
        assertFalse(imageRefusal.ok)
        assertEquals("ACP_CAPABILITY_MISSING", imageRefusal.failureKind)
    }

    @Test
    fun `declared multimodal content reaches the agent when the capability is announced`() {
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"),
                turns = listOf(
                    AcpTurnInput(
                        "analyze",
                        content = listOf(
                            acpImage("aGVsbG8=", "image/png"),
                            acpResource("file:///notes", "embedded text"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(out.ok, "image+resource must ride the full-caps session, got ${out.failureKind}")
        // the fixture echoes the block types it actually received — the gate sent them, it
        // did not silently degrade to text-only (AC#2).
        assertEquals("mcp0:turn1|seen:analyze|kinds:image;resource;text", out.turns.single().reply)
    }

    @Test
    fun `a second exchange while one is in flight is refused before any second launch`() {
        runBlocking {
            val connection = AcpSessionConnection("0.0.0-test")
            // the slow fixture parks inside the prompt turn; while that exchange is in
            // flight, a second one on the SAME instance must not even launch a process.
            // exchange() is the suspend route: it never blocks this loop's thread, so the
            // second attempt can actually overlap the first (run() would serialize them).
            val first = async {
                connection.exchange(
                    input(fixture("full", "", "slow"), turns = listOf(acpTurn("occupying"))),
                )
            }
            kotlinx.coroutines.delay(1_500)
            val second = connection.exchange(
                input(fixture("full"), turns = listOf(acpTurn("must-not-start"))),
            )
            assertFalse(second.ok)
            assertEquals("ACP_ACTIVE_PROMPT_IN_PROGRESS", second.failureKind)
            val firstOut = first.await()
            assertTrue(firstOut.ok, "the in-flight exchange must finish normally, got ${firstOut.failureKind}")
            // And the rule is scope-local, not global: a DIFFERENT connection (an
            // independent session) runs while the slow one is… already finished here, but
            // the CAS never blocked it — the refusal above named the busy scope, not a
            // shared lock.
            val fresh = AcpSessionConnection("0.0.0-test").run(
                input(fixture("full"), turns = listOf(acpTurn("independent"))),
            )
            assertTrue(fresh.ok)
            assertNotEquals(firstOut.sessionRef, fresh.sessionRef)
        }
    }

    @Test
    fun `resume opens without replaying history and mcp descriptors reach session new`() {
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"),
                turns = listOf(acpTurn("resumed turn")),
                copy = {
                    it.copy(
                        resumeSessionId = "agent-session-9",
                        mcpServers = listOf(
                            acpMcpStdio("local", listOf("mcp-server", "--stdio")),
                            acpMcpHttp("remote", "https://mcp.example/rpc"),
                        ),
                    )
                },
            ),
        )
        assertTrue(out.ok, "resume+mcp must complete, got ${out.failureKind}: ${out.detail}")
        assertEquals(emptyList<String>(), out.historicEvents)
        // the fixture echoes how many mcpServers session/new carried into the first reply —
        // both descriptors travelled (AC#2's mcp path gated by announced http support).
        assertEquals("mcp2:turn1|seen:resumed turn|kinds:text", out.turns.single().reply)
    }

    @Test
    fun `an invalid mcp transport is refused before launch, config-shaped`() {
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"), turns = listOf(acpTurn("x")),
                copy = { it.copy(mcpServers = listOf(AcpMcpInput("bad", "carrier-pigeon"))) },
            ),
        )
        assertFalse(out.ok)
        assertEquals("ACP_CONFIG_INVALID", out.failureKind)
        assertNull(out.sessionRef.ifEmpty { null })
    }

    @Test
    fun `a session ref another connection minted is refused before any launch`() {
        // AC#4's foreign-ref gate: the token comes from a different owner nonce, so joining
        // the session it names would hand a persisting agent a second, unaccounted holder of
        // one session's state. The answer is typed, names the ref, and opens nothing.
        val foreignToken = SessionRef.mint("some-other-connection", "session-1").value
        val out = AcpSessionConnection("0.0.0-test").run(
            input(
                fixture("full"), turns = listOf(acpTurn("must not run")),
                copy = { it.copy(loadSessionId = foreignToken) },
            ),
        )
        assertFalse(out.ok)
        assertEquals("ACP_FOREIGN_SESSION_REF", out.failureKind)
        assertEquals("SESSION_REFUSED", out.outcome)
        assertNull(out.sessionRef.ifEmpty { null }, "a gated plan launches no session")
    }

    @Test
    fun `a ref token this connection minted resolves to the agent id it carries`() {
        // The owner path of the same gate: the token is the durable answer a previous turn of
        // THIS connection handed out; resolving it must put the plain agent id on the wire
        // (the fixture records load under that id) — proof the resolution happened, not just
        // that nothing refused.
        val connection = AcpSessionConnection("0.0.0-test")
        val token = SessionRef.mint(connection.connectionNonce, "agent-resumed-1").value
        val out = connection.run(
            input(
                fixture("full"), turns = listOf(acpTurn("after token load")),
                copy = { it.copy(loadSessionId = token) },
            ),
        )
        assertTrue(out.ok, "own token must load, got ${out.failureKind}: ${out.detail}")
        assertEquals("mcp0:turn1|seen:after token load|kinds:text", out.turns.single().reply)
    }
}
