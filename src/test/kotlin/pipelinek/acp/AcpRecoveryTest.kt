package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.RecoveredProjection
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC#5: an interrupted execution recovers without repeating uncertain effects.
 *
 * The plugin's half of that promise is a DECLARATION and a PROJECTION: the step says what
 * its effect is and that the turn is never replayed, and the definition projects a recovered
 * terminal onto the step's own value — or refuses to, when the durable facts do not carry
 * the answer.
 *
 * The rows below also MEASURE the half the published SDK does not carry: the ports a
 * recovery needs (`OperationJournal`, `RuntimeRecoverPort`, `DurableTaskRuntime`) are
 * published, but nothing binds them to a plugin step and the published handler context
 * carries no durable operation identity — so a plugin cannot ask the journal whether its own
 * turn already happened.
 */
class AcpRecoveryTest {

    private val input = AcpReactiveInput(command = listOf("agent"), prompt = "once")

    private fun failure(message: String): FailureRecord = FailureRecord(
        code = "E_TEST",
        kind = FailureKind.INFRASTRUCTURE,
        message = message,
        origin = FailureOrigin.RECONCILIATION,
        retryable = false,
        operationId = "op-1",
    )

    @Test
    fun `the step declares that its effect is a subprocess and that the turn is never replayed`() {
        val descriptor = AcpReactiveStepDefinition.contract.descriptor
        assertEquals(
            listOf(Effect.EXECUTES_SUBPROCESS),
            descriptor.effects,
            "the agent is an external process: the declaration must say so",
        )
        assertEquals(
            ReplayPolicy.NEVER,
            descriptor.replayPolicy,
            "an agent turn is a conversation with a third party: replaying it is a second prompt",
        )
    }

    @Test
    fun `a recovered terminal with a failed exit materialises the failure it observed`() {
        val projection = AcpReactiveStepDefinition.project(
            input,
            DurableTaskTerminal.Exited(
                exitCode = 7,
                output = DurableTaskOutput(controlDir = "/tmp/control"),
            ),
        )
        assertTrue(
            projection is RecoveredProjection.Materialised,
            "a non-zero exit is a fact of the substrate and must be materialised: $projection",
        )
        val materialised = projection as RecoveredProjection.Materialised<AcpReactiveOutput>
        assertEquals(false, materialised.value.ok, "the recovered turn failed: ${materialised.value}")
        assertEquals("ACP_AGENT_EXITED", materialised.value.failureKind)
    }

    @Test
    fun `a recovered terminal that carries no reply refuses to invent one`() {
        // The ACP reply is NOT a durable fact of the substrate: a clean exit only says the
        // process ended. Materialising a success here would claim an answer nobody witnessed,
        // and the recovered run would carry a reply the agent never gave.
        val projection = AcpReactiveStepDefinition.project(
            input,
            DurableTaskTerminal.Exited(
                exitCode = 0,
                output = DurableTaskOutput(controlDir = "/tmp/control"),
            ),
        )
        assertTrue(
            projection is RecoveredProjection.InsufficientEvidence,
            "a clean exit is not a completed turn: $projection",
        )
    }

    @Test
    fun `an uncertain terminal refuses to materialise anything`() {
        val lost = AcpReactiveStepDefinition.project(input, DurableTaskTerminal.Lost(failure("the outcome is unknown")))
        assertTrue(lost is RecoveredProjection.InsufficientEvidence, "an unknown outcome is not a value: $lost")

        val cancelled = AcpReactiveStepDefinition.project(
            input,
            DurableTaskTerminal.Cancelled(
                InterruptionRecord(
                    kind = InterruptionKind.TIMEOUT,
                    message = "the deadline fired",
                    operationId = "op-1",
                ),
            ),
        )
        assertTrue(cancelled is RecoveredProjection.InsufficientEvidence, "an interruption is not a value: $cancelled")
    }

    @Test
    fun `the published handler context carries no durable operation identity`() {
        // The measurement of the gap: a plugin that wants to know whether its own turn already
        // happened needs the durable identity of that turn. The published context carries the
        // run and the step index, and nothing that names the journal row.
        val fields = StepHandlerContext::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            fields.none { it.contains("operation") || it.contains("opid") || it.contains("journal") },
            "the published handler context must not carry a durable operation identity: $fields",
        )
    }

    @Test
    fun `the published SDK carries the recovery PORTS and not their implementations`() {
        // The ports are published: a plugin can name them. The implementations and the
        // normative replay table are not: they live in a module the bundle does not publish,
        // so a plugin can neither reach the journal nor know what the engine will do with
        // the declarations it writes.
        assertTrue(
            published("dev.rubentxu.pipeline.v2.runtime.durable.OperationJournal"),
            "the journal port is published and must stay so",
        )
        assertTrue(
            published("dev.rubentxu.pipeline.v2.runtime.recover.RuntimeRecoverPort"),
            "the recover port is published and must stay so",
        )
        assertTrue(
            published("dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime"),
            "the durable task port is published and must stay so",
        )
        assertFalse(
            published("dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime"),
            "the durable substrate implementation must stay out of the published bundle",
        )
        assertFalse(
            published("dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy"),
            "the normative replay table must stay out of the published bundle",
        )
    }

    private fun published(className: String): Boolean =
        runCatching { Class.forName(className) }.isSuccess
}
