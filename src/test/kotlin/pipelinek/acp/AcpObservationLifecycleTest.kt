package pipelinek.acp

import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lifecycle facts, measured on the route that produces them.
 *
 * The plugin's own vocabulary DECLARES exchange_started, turn_ended, exchange_cancelled and
 * exchange_refused, and the README promises they survive a chunk avalanche. A declaration is
 * worth what its producer is worth: these rows start the real connection against the fixture
 * agent, under the real publisher, and read what the seam received. Nothing here hand-builds
 * an observation — a fact nobody publishes is exactly the defect this row exists to catch.
 */
class AcpObservationLifecycleTest {

    /** Every kind the plugin declares is a kind this seam accepts; the payloads are the row. */
    private class RecordingEmission : PluginEventEmission {
        val payloads = mutableListOf<Pair<String, Any?>>()

        override fun <P : Any> emit(kind: String, payload: P): EmissionOutcome {
            payloads.add(kind to payload)
            return EmissionOutcome.Emitted(
                dev.rubentxu.pipeline.v2.events.PluginEventEmitted(
                    eventId = "evt-lc",
                    runId = "run-lc",
                    sequence = 1L,
                    occurredAt = java.time.Instant.EPOCH,
                    registryKind = kind,
                    schemaVersion = AcpObservationCodec.SCHEMA_VERSION,
                    payload = AcpObservationCodec.encode(payload as AcpObservation),
                    emittedBy = AcpPluginDeclaration.CONTRIBUTOR_ID,
                ),
            )
        }

        val kinds: Set<String> get() = payloads.map { it.first }.toSet()
    }

    private val fixture: Path = run {
        val url = checkNotNull(javaClass.getResource("/acp_task_fixture_agent.py")) {
            "the task fixture agent is missing from the test resources"
        }
        Path.of(url.toURI())
    }

    private fun agent(vararg args: String): List<String> = listOf("python3", "-I", fixture.toString()) + args

    private fun runTask(
        mode: List<String>,
        budgetSeconds: Long,
        emission: RecordingEmission,
    ): AcpExchange = runBlocking {
        val root = Files.createTempDirectory("acp-lifecycle-")
        withObservationPublisher(emission, "run-lc-0") { publisher ->
            AcpConnection(pluginVersion = "0.0.0-test").exchangeTask(
                input = AcpTaskInput(
                    command = agent(*mode.toTypedArray()),
                    prompt = "lifecycle probe",
                    profile = AcpTaskProfile.READONLY,
                    budgetSeconds = budgetSeconds,
                ),
                environment = AcpTaskIsolation.childEnvironment(null),
                workingDirectory = root,
                reverse = AcpReverseSession(root, AcpTaskProfile.READONLY, null),
                observation = publisher,
            )
        }
    }

    @Test
    fun `a cancelled exchange says so on the lane even under a chunk avalanche`() {
        // The lane's promise is that control facts outrank telemetry; this is the avalanche
        // that promise is measured against: 1200 chunks bury the queue, and the lifecycle
        // facts of the same exchange must still reach the stream when the budget cancels it.
        val emission = RecordingEmission()
        val exchange = runTask(listOf("flood"), budgetSeconds = 1L, emission = emission)
        val refused = exchange as? AcpExchange.Refused
        assertTrue(refused != null, "the flooding agent must end by budget, got $exchange")

        val kinds = emission.kinds
        assertTrue(
            AcpFactToken.kindOf(AcpFactToken.EXCHANGE_STARTED) in kinds,
            "the exchange never said it started: $kinds",
        )
        assertTrue(
            AcpFactToken.kindOf(AcpFactToken.EXCHANGE_CANCELLED) in kinds,
            "session/cancel was said to the agent but never to the stream: $kinds",
        )
        val cancelled = emission.payloads
            .first { it.first == AcpFactToken.kindOf(AcpFactToken.EXCHANGE_CANCELLED) }
            .second as AcpObservation.ExchangeCancelled
        assertEquals(AcpEvidence.VERIFIED, cancelled.evidence, "the cancellation is the plugin's own act")
        assertTrue(cancelled.reason.isNotEmpty(), "the cancellation says nothing about why")
    }

    @Test
    fun `a completed turn ends with the stop reason the agent gave`() {
        val emission = RecordingEmission()
        val exchange = runTask(listOf("reply", "lifecycle-ok"), budgetSeconds = 60L, emission = emission)
        assertTrue(exchange is AcpExchange.Completed, "the probe exchange must complete: $exchange")

        val turn = emission.payloads
            .firstOrNull { it.first == AcpFactToken.kindOf(AcpFactToken.TURN_ENDED) }
            ?.second as? AcpObservation.TurnEnded
        assertTrue(turn != null, "the exchange completed but no turn ever ended on the stream")
        assertEquals(1, turn!!.turnIndex)
        assertEquals("END_TURN", turn.stopReason)
        assertTrue(AcpFactToken.kindOf(AcpFactToken.EXCHANGE_STARTED) in emission.kinds)
        assertFalse(
            AcpFactToken.kindOf(AcpFactToken.EXCHANGE_CANCELLED) in emission.kinds,
            "a completed exchange must not claim a cancellation",
        )
        assertFalse(
            AcpFactToken.kindOf(AcpFactToken.EXCHANGE_REFUSED) in emission.kinds,
            "a completed exchange must not claim a refusal",
        )
    }

    @Test
    fun `a handshake that never answers is refused on the stream`() {
        // hang-init never answers initialize: the exchange ends before a session exists, and
        // the refusal fact is the stream's only witness that it happened and why.
        val emission = RecordingEmission()
        val exchange = runTask(listOf("hang-init"), budgetSeconds = 1L, emission = emission)
        assertTrue(exchange is AcpExchange.Refused, "the silent handshake must be refused: $exchange")

        val refusedFact = emission.payloads
            .firstOrNull { it.first == AcpFactToken.kindOf(AcpFactToken.EXCHANGE_REFUSED) }
            ?.second as? AcpObservation.ExchangeRefused
        assertTrue(refusedFact != null, "the typed refusal never reached the stream: ${emission.kinds}")
        assertEquals(AcpEvidence.VERIFIED, refusedFact!!.evidence)
        assertTrue(refusedFact.failureKind.isNotEmpty(), "the refusal carries no classification")
        assertFalse(
            AcpFactToken.kindOf(AcpFactToken.EXCHANGE_STARTED) in emission.kinds,
            "an exchange that never opened a session must not claim it started",
        )
    }

    @Test
    fun `the lifecycle facts do not park the reader thread`() {
        // The sink's contract is the lane's contract: the doors an exchange calls must be a
        // bounded offer away from returning, whatever the publisher coroutine is doing with
        // the same lane. This row waits on the call itself, not on the facts' order.
        val emission = RecordingEmission()
        val publisher = AcpObservationPublisher(emission) { "run-lc-1" }
        val caller = Executors.newSingleThreadExecutor()
        try {
            val finished = caller.submit {
                publisher.exchangeStarted()
                publisher.turnEnded(1, "END_TURN")
                publisher.exchangeCancelled("withdrawn")
                publisher.exchangeRefused("ACP_AGENT_TIMEOUT")
            }
            finished.get(5, TimeUnit.SECONDS)
            publisher.drain()
            val lifecycle = emission.payloads.filter { it.first.startsWith(AcpFactToken.KIND_PREFIX) }
            assertEquals(4, lifecycle.count { it.first != AcpFactToken.kindOf(AcpFactToken.OBSERVATION_GAP) })
        } finally {
            caller.shutdownNow()
        }
    }
}
