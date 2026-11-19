package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as WorkspaceExecutionLocation
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * The `acpTask` step: one isolated, budgeted, profiled delegation to an external agent.
 *
 * Where [AcpPromptStepDefinition] is the bare exchange (zero capabilities, the PKH-132
 * statement that no host cooperation is needed), this step CONSUMES the host: the run's
 * execution location is the authority for the workspace fence (AC#1, AC#4 — a cwd is resolved
 * against the lease the host granted, never against the JVM's working directory) and the
 * credential seam is the only way a delegation reaches a secret (the DSL carries a NAME).
 * Declaring those capabilities is the plugin asking permission the right way: admission is
 * fail-closed before the handler runs when the host does not offer them.
 *
 * Replay: [ReplayPolicy.NEVER], same argument as the prompt step — an agent turn is a
 * conversation with a third party; re-executing it is a second prompt the run never asked for.
 *
 * The handler NEVER throws to signal a step failure; [AcpTaskOutput] carries the typed
 * outcome, and AC#3's classes (stop reason, transport, cancellation, budget, Unknown) stay
 * distinguishable in the durable journal.
 */
object AcpTaskStepDefinition : StepDefinition<AcpTaskInput, AcpTaskOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.task")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpTaskInput> {
        override fun encode(value: AcpTaskInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpTaskInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpTaskInput =
            json.decodeFromString(AcpTaskInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpTaskOutput> {
        override fun encode(value: AcpTaskOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpTaskOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpTaskOutput =
            json.decodeFromString(AcpTaskOutput.serializer(), encoded.value)
    }

    override val contract: StepContract<AcpTaskInput, AcpTaskOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "acpTask",
                jenkinsDivergence = "Jenkins has no isolated-agent delegation; the closest " +
                    "ancestor is a shell step, which is exactly what the argv-without-shell rule refuses",
                configRef = "",
                pluginId = AcpPluginDeclaration.CONTRIBUTOR_ID,
                pluginVersion = AcpPluginDeclaration.declaredVersion(),
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
                replayPolicy = ReplayPolicy.NEVER,
            ),
            inputCodec = InputCodec,
            outputCodec = OutputCodec,
            // The fence (lease root) and the seam (credential by reference) are the two host
            // facts this step consumes; credentials.basic is contributed unconditionally by
            // the host — a run with no store answers NotConfigured rather than hiding the
            // capability — so declaring it costs admission nothing and says honestly what the
            // handler reads.
            requiredCapabilities = setOf(
                EXECUTION_LOCATION_CAPABILITY,
                StepCapability("credentials.basic"),
            ),
        )
    }

    override val handler = StepHandler<AcpTaskInput, AcpTaskOutput> { input, context ->
        // The lease root is the fence: taken from the host, never from a JVM property.
        val lease: WorkspaceExecutionLocation =
            context.capabilities.get(EXECUTION_LOCATION_CAPABILITY, WorkspaceExecutionLocation::class)
        val workspaceRoot: Path = lease.workspace.root

        AcpTaskIsolation.validate(input, leaseRoot = workspaceRoot)?.let { refusal ->
            return@StepHandler AcpTaskOutcome.toOutput(AcpExchange.Refused(refusal))
        }

        val credential: AcpTaskIsolation.ResolvedCredential?
        if (input.credentialRef.isNotBlank()) {
            val (resolved, refusal) = AcpTaskIsolation.resolveCredential(context, input.credentialRef)
            if (refusal != null) {
                return@StepHandler AcpTaskOutcome.toOutput(AcpExchange.Refused(refusal))
            }
            credential = resolved
        } else {
            credential = null
        }

        val effectiveCwd = if (input.cwd.isBlank()) workspaceRoot else workspaceRoot.resolve(input.cwd).normalize()
        val exchange = AcpConnection(AcpPluginDeclaration.declaredVersion()).exchangeTask(
            input = input,
            environment = AcpTaskIsolation.childEnvironment(credential),
            workingDirectory = effectiveCwd,
        )
        AcpTaskOutcome.toOutput(exchange)
    }
}
