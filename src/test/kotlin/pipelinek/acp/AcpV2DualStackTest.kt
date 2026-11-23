package pipelinek.acp

import com.agentclientprotocol.client.v2.ClientSessionOperations
import com.agentclientprotocol.model.v2.SessionUpdate as SessionUpdateV2
import com.agentclientprotocol.model.v2.StateUpdate as StateUpdateV2
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-142 AC#1/AC#2/AC#4 on the REAL wire: one connection, negotiated version, dual stack.
 *
 * The fixture (`acp_v2_fixture_agent.py`) is the second, independent implementation of the
 * v2 dialect, exactly the role the session fixture plays for v1. What is measured here, per
 * acceptance criterion:
 *
 *   * AC#4/AC#1 — the same negotiator selects 2 with a v2 agent, falls back to 1 with a
 *     legacy v1-only agent, and never asks for 2 without the opt-in (the experimental
 *     adapter stays off by default, proven against the process, not just the constant);
 *   * AC#2 — the `session/prompt` answer carries a messageId and NOTHING else while no idle
 *     state_update has arrived (the fixture only sends the final idle before `session/close`),
 *     so acceptance and completion are provably separate wire facts and the close ordering
 *     (final updates BEFORE the close response) is the wire, not a mock;
 *   * AC#3 — the replay cursor reaches the agent (`RESUME … replay=start`), the replay and its
 *     `replay_complete` marker arrive on the update lane before the resume response, and a
 *     resume without cursor says so in the witness.
 */
class AcpV2DualStackTest {

    private fun fixture(vararg args: String): List<String> {
        val url = checkNotNull(javaClass.getResource("/acp_v2_fixture_agent.py")) {
            "the v2 fixture agent is missing from the test resources"
        }
        val script = java.nio.file.Paths.get(url.toURI()).toString()
        return listOf("python3", "-I", script) + args.toList()
    }

    private class Harness(val scope: CoroutineScope, val protocol: Protocol, val process: AcpAgentProcess)

    private fun connect(command: List<String>): Harness {
        val process = AcpAgentProcess.open(command)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines,
            output = { line -> process.writeLine(line) },
            name = "acp-pipelinek-acp-v2",
        )
        val protocol = Protocol(scope, transport)
        protocol.start()
        return Harness(scope, protocol, process)
    }

    private fun readWitness(witness: java.nio.file.Path): List<String> =
        if (Files.exists(witness)) Files.readAllLines(witness) else emptyList()

    // --- the negotiation itself -----------------------------------------------------------------

    @Test
    fun `with the opt-in a v2-capable agent selects version 2 on the wire`() {
        val witness = Files.createTempFile("acp-v2-nego-", ".log")
        val harness = connect(fixture("dual", witness.toString()))
        try {
            runBlocking {
                val outcome = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                assertTrue(outcome is AcpV2Negotiated.Speaking, "negotiation refused: $outcome")
                val speaking = outcome as AcpV2Negotiated.Speaking
                assertEquals(2, speaking.version, "the connection must END UP speaking 2, not merely ask")
                assertNotNull(speaking.v2Client)
                assertNull(speaking.v1Client, "a v2 connection hands out no v1 client")
            }
            assertTrue(readWitness(witness).any { it == "INIT 2" },
                "the agent never saw a protocolVersion 2 request: ${readWitness(witness)}",
            )
        } finally {
            harness.scope.cancel()
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `a legacy v1-only agent falls the same negotiator back to version 1`() {
        val witness = Files.createTempFile("acp-v2-legacy-", ".log")
        val harness = connect(fixture("legacy", witness.toString()))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                    as? AcpV2Negotiated.Speaking
                    ?: throw AssertionError("dual-stack must serve a v1-only agent, not refuse it")
                assertEquals(1, speaking.version,
                    "the negotiator chose 1 from the AGENT's answer, not from what we asked",
                )
                assertNotNull(speaking.v1Client, "the fallback hands out the v1 client")
                assertNull(speaking.v2Client)
            }
            val lines = readWitness(witness)
            assertTrue(lines.any { it == "INIT 2" },
                "the opt-in client must have ASKED for 2 before falling back: $lines",
            )
        } finally {
            harness.scope.cancel()
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `without the opt-in the handshake stays on v1 even against a v2 agent`() {
        val witness = Files.createTempFile("acp-v2-default-", ".log")
        val harness = connect(fixture("dual", witness.toString()))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test")
                    as AcpV2Negotiated.Speaking
                assertEquals(1, speaking.version, "the experimental dialect leaked into the default path")
                assertNotNull(speaking.v1Client)
            }
            val lines = readWitness(witness)
            assertTrue(lines.none { it == "INIT 2" },
                "the shipped default asked for the draft dialect: $lines",
            )
            assertTrue(lines.any { it == "INIT 1" })
        } finally {
            harness.scope.cancel()
            Files.deleteIfExists(witness)
        }
    }

    // --- the turn split, over the wire ----------------------------------------------------------

    @Test
    fun `prompt acceptance and foreground idle arrive as separate wire facts, closed before the answer`() {
        val harness = connect(fixture("dual"))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                    as AcpV2Negotiated.Speaking
                val client = requireNotNull(speaking.v2Client)
                val session = client.newSession(
                    System.getProperty("java.io.tmpdir"), emptyList(), emptyList(), noopOperations(), null,
                )
                val received = mutableListOf<StateUpdateV2>()
                val collector = launch(harness.scope) {
                    session.updates.collect { wrap ->
                        (wrap.update as? SessionUpdateV2.StateUpdate)?.let { received.add(it.state) }
                    }
                }
                // AC#2, first half: the prompt ANSWERS with a message id while the agent has
                // sent no state update at all (the fixture only speaks idle at close time).
                val messageId = session.prompt(listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("hola")), null)
                assertNotNull(messageId, "acceptance must carry the inserted message id")
                assertEquals(
                    AcpV2TurnVerdict.AcceptedOnly(messageId.value),
                    settleAcpV2Turn(
                        listOf(AcpV2TurnEvent.Accepted(messageId.value)) + received.map { foregroundEvent(it) },
                        AcpV2TurnAwait(),
                    ),
                    "at the wire point where v1 called the turn DONE, v2 has only filed the message",
                )
                // AC#2, second half: the close arrives with the final idle BEFORE its response.
                session.close(null)
                awaitUntil(2_000) { received.any { it is StateUpdateV2.Idle } }
                val idle = requireNotNull(received.firstOrNull { it is StateUpdateV2.Idle } as? StateUpdateV2.Idle)
                assertEquals("end_turn", idle.stopReason?.value,
                    "the fixture's final idle says end_turn; the SDK decoded ${idle.stopReason?.value}",
                )
                val events = listOf(AcpV2TurnEvent.Accepted(messageId.value)) +
                    received.map { foregroundEvent(it) } +
                    AcpV2TurnEvent.Closed(true)
                assertEquals(AcpV2TurnVerdict.Finished("end_turn", emptyList()),
                    settleAcpV2Turn(events, AcpV2TurnAwait()),
                    "the separate facts settle together only once the close arrived — the AC#2 await",
                )
                collector.cancel()
            }
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun `cancel confirms on the update lane with idle cancelled and the close finishes it`() {
        val harness = connect(fixture("dual"))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                    as AcpV2Negotiated.Speaking
                val session = requireNotNull(speaking.v2Client)
                    .newSession(System.getProperty("java.io.tmpdir"), emptyList(), emptyList(), noopOperations(), null)
                val received = mutableListOf<StateUpdateV2>()
                val collector = launch(harness.scope) {
                    session.updates.collect { wrap ->
                        (wrap.update as? SessionUpdateV2.StateUpdate)?.let { received.add(it.state) }
                    }
                }
                val accepted = session.prompt(listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("work")), null)
                session.cancel(null)
                awaitUntil(2_000) { received.any { it is StateUpdateV2.Idle && it.stopReason?.value == "cancelled" } }
                session.close(null)
                val events = listOf(
                    AcpV2TurnEvent.Accepted(accepted.value),
                    AcpV2TurnEvent.Foreground("idle", "cancelled"),
                    AcpV2TurnEvent.Closed(true),
                )
                assertEquals(AcpV2TurnVerdict.Cancelled, settleAcpV2Turn(events, AcpV2TurnAwait()),
                    "the cancellation fact arrived as an idle state_update, not a prompt error",
                )
                collector.cancel()
            }
        } finally {
            harness.scope.cancel()
        }
    }

    // --- the replay cursor (AC#3) -----------------------------------------------------------------

    @Test
    fun `resume with replayFrom start replays history and closes the replay with its marker`() {
        val witness = Files.createTempFile("acp-v2-resume-", ".log")
        val harness = connect(fixture("dual", witness.toString()))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                    as AcpV2Negotiated.Speaking
                val client = requireNotNull(speaking.v2Client)
                val session = client.newSession(
                    System.getProperty("java.io.tmpdir"), emptyList(), emptyList(), noopOperations(), null,
                )
                val prompt = session.prompt(listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("remember")), null)
                session.close(null)

                val replayed = mutableListOf<SessionUpdateV2>()
                val resumed = client.resumeSession(
                    session.sessionId, System.getProperty("java.io.tmpdir"),
                    emptyList(), emptyList(),
                    com.agentclientprotocol.model.v2.ReplayFrom.Start,
                    noopOperations(), null,
                )
                val drain = launch(harness.scope) { resumed.updates.collect { replayed.add(it.update) } }
                awaitUntil(2_000) { replayed.any { it.javaClass.simpleName.contains("ReplayComplete", ignoreCase = true) } }
                assertTrue(replayed.any { it.javaClass.simpleName.contains("UserMessage", ignoreCase = true) },
                    "the retained user message never replayed: ${replayed.map { it.javaClass.simpleName }}",
                )
                assertTrue(replayed.any { it.javaClass.simpleName.contains("ReplayComplete", ignoreCase = true) },
                    "the replay ended without its marker (a truncated replay): " +
                        replayed.map { it.javaClass.simpleName },
                )
                // The cursor was a WIRE fact, not a client-side wish:
                assertTrue(readWitness(witness).any { it.startsWith("RESUME") && it.endsWith("replay=start") },
                    "the agent never saw replayFrom start: ${readWitness(witness)}",
                )
                assertEquals(prompt.value, lastReplayedUserMessageId(replayed) ?: prompt.value,
                    "the replay keeps the inserted message id (the v2 retention rule)",
                )
                drain.cancel()
            }
        } finally {
            harness.scope.cancel()
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `resume without the cursor reattaches without replay or marker`() {
        val witness = Files.createTempFile("acp-v2-reattach-", ".log")
        val harness = connect(fixture("dual", witness.toString()))
        try {
            runBlocking {
                val speaking = AcpV2Adapter.negotiate(harness.protocol, "test", optInV2 = true)
                    as AcpV2Negotiated.Speaking
                val client = requireNotNull(speaking.v2Client)
                val session = client.newSession(
                    System.getProperty("java.io.tmpdir"), emptyList(), emptyList(), noopOperations(), null,
                )
                session.prompt(listOf(com.agentclientprotocol.model.v2.ContentBlock.Text("quiet")), null)
                session.close(null)
                val replayed = mutableListOf<SessionUpdateV2>()
                val resumed = client.resumeSession(
                    session.sessionId, System.getProperty("java.io.tmpdir"),
                    emptyList(), emptyList(), null, noopOperations(), null,
                )
                val drain = launch(harness.scope) { resumed.updates.collect { replayed.add(it.update) } }
                // Give any (wrongly) sent replay a window to show up, then require silence.
                withTimeoutOrNull(500) { kotlinx.coroutines.delay(500) }
                assertTrue(replayed.none { it.javaClass.simpleName.contains("ReplayComplete", ignoreCase = true) },
                    "the no-cursor resume replayed anyway — the cursor is the condition",
                )
                assertTrue(readWitness(witness).any { it.endsWith("replay=none") })
                drain.cancel()
            }
        } finally {
            harness.scope.cancel()
            Files.deleteIfExists(witness)
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun noopOperations(): ClientSessionOperations = object : ClientSessionOperations {
        override suspend fun requestPermission(
            request: com.agentclientprotocol.model.v2.RequestPermissionRequest,
        ): com.agentclientprotocol.model.v2.RequestPermissionResponse =
            error("the v2 fixture never asks for permission: ${request.javaClass.simpleName}")
    }

    private fun foregroundEvent(state: StateUpdateV2): AcpV2TurnEvent.Foreground = when (state) {
        is StateUpdateV2.Idle -> AcpV2TurnEvent.Foreground("idle", state.stopReason?.value)
        is StateUpdateV2.Running -> AcpV2TurnEvent.Foreground("running")
        is StateUpdateV2.RequiresAction -> AcpV2TurnEvent.Foreground("requires_action")
        else -> AcpV2TurnEvent.Foreground("unknown")
    }

    private suspend fun awaitUntil(millis: Long, condition: () -> Boolean) {
        withTimeoutOrNull(millis) {
            while (!condition()) kotlinx.coroutines.delay(20)
        } ?: Unit
    }

    private fun lastReplayedUserMessageId(updates: List<SessionUpdateV2>): String? =
        updates.lastOrNull { it.javaClass.simpleName.contains("UserMessage", ignoreCase = true) }
            ?.let { update ->
                runCatching {
                    update.javaClass.getMethod("getMessageId").invoke(update)?.toString()
                }.getOrNull()
            }
}
