package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.json.Json

/**
 * The `acpPrompt` step: one agent exchange, typed at the PipelineK boundary.
 *
 * The step asks the host for NOTHING — zero required capabilities, the strongest statement a
 * plugin can make that no core change is needed: the agent process is the plugin's own effect,
 * launched through the plugin's own transport, and the host only runs the handler.
 *
 * The handler NEVER throws to signal an agent-side failure. AcpConnection answers every
 * outcome as an [AcpExchange], and the refusal becomes a `ok = false` output carrying the
 * classification token; the pipeline branches on [AcpPromptOutput.failureKind]. A throw here
 * would be re-classified as `ENGINE` by the execution boundary and the declared kind lost.
 *
 * Replay: [ReplayPolicy.NEVER]. An agent turn is a conversation with a third party that has
 * its own state on the other end; re-executing it after a crash is a second prompt the run
 * never asked for, and memoising it would claim an answer the durable log did not witness.
 */
object AcpPromptStepDefinition : StepDefinition<AcpPromptInput, AcpPromptOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.prompt")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpPromptInput> {
        override fun encode(value: AcpPromptInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpPromptInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpPromptInput =
            json.decodeFromString(AcpPromptInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpPromptOutput> {
        override fun encode(value: AcpPromptOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpPromptOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpPromptOutput =
            json.decodeFromString(AcpPromptOutput.serializer(), encoded.value)
    }

    // Lazy because the descriptor carries the version THIS artifact declares, and that
    // declaration is only readable once the build has written the provenance document: an
    // eagerly-built contract would snapshot the absence of provenance at class-load time
    // inside the build itself.
    override val contract: StepContract<AcpPromptInput, AcpPromptOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "acpPrompt",
                jenkinsDivergence = "Jenkins has no ACP client; an agent exchange belongs to no " +
                    "pipeline language the product predates",
                configRef = "",
                pluginId = AcpPluginDeclaration.CONTRIBUTOR_ID,
                pluginVersion = AcpPluginDeclaration.declaredVersion(),
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
                replayPolicy = ReplayPolicy.NEVER,
            ),
            inputCodec = InputCodec,
            outputCodec = OutputCodec,
            requiredCapabilities = emptySet(),
        )
    }

    override val handler = StepHandler<AcpPromptInput, AcpPromptOutput> { input, _ ->
        // suspend all the way: the agent I/O runs on the connection's own IO scope, never
        // parking an engine thread in a blocking run.
        when (val exchange = AcpConnection(AcpPluginDeclaration.declaredVersion()).exchange(input)) {
            is AcpExchange.Completed ->
                AcpPromptOutput(ok = true, reply = exchange.reply, stopReason = exchange.stopReason)
            is AcpExchange.Refused ->
                AcpPromptOutput(
                    ok = false,
                    failureKind = exchange.refusal.kindToken,
                    detail = exchange.refusal.detail,
                )
        }
    }
}
