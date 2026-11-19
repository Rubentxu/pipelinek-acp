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
 * The `acpSession` step: a composed ACP conversation — one connection, several sequential
 * prompt turns, and the session operations the negotiated schema offers (PKH-135).
 *
 * Like [AcpPromptStepDefinition] and unlike [AcpTaskStepDefinition], this step asks the host
 * for NOTHING: zero required capabilities. A session scope is the plugin's own subprocess
 * effect; the fence, credentials and reverse authority belong to the task path, and the
 * session path keeps its promises without them. That separation is what lets the session be
 * composed (load/resume/list/config) while the delegation stays profiled and audited.
 *
 * The handler NEVER throws to signal a step failure. [AcpSessionConnection] answers every
 * outcome as an [AcpSessionOutput]; a refusal becomes `ok = false` carrying
 * [AcpSessionOutput.failureKind], and the pipeline branches on it. A throw would be
 * re-classified as `ENGINE` by the execution boundary and the declared kind lost (AC#4).
 *
 * Replay: [ReplayPolicy.NEVER], same argument as the prompt step — a session is stateful
 * conversation with a third party; re-executing it is a second conversation the run never
 * asked for.
 */
object AcpSessionStepDefinition : StepDefinition<AcpSessionInput, AcpSessionOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.session")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpSessionInput> {
        override fun encode(value: AcpSessionInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpSessionInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpSessionInput =
            json.decodeFromString(AcpSessionInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpSessionOutput> {
        override fun encode(value: AcpSessionOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpSessionOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpSessionOutput =
            json.decodeFromString(AcpSessionOutput.serializer(), encoded.value)
    }

    // Lazy for the same reason the prompt step's contract is: the descriptor carries the
    // version THIS artifact declares, which is only readable once the build has written the
    // provenance document.
    override val contract: StepContract<AcpSessionInput, AcpSessionOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "acpSession",
                jenkinsDivergence = "Jenkins has no ACP client, and a multi-turn agent session " +
                    "belongs to no pipeline language the product predates",
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

    override val handler = StepHandler<AcpSessionInput, AcpSessionOutput> { input, _ ->
        // suspend all the way: the agent I/O runs on the connection's own IO scope, never
        // parking an engine thread in a blocking run. The scope's finally releases the
        // process through completion, refusal, cancellation and timeout alike (AC#1).
        AcpSessionConnection(AcpPluginDeclaration.declaredVersion()).exchange(input)
    }
}
