package pipelinek.acp

import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RecoveredProjection
import dev.rubentxu.pipeline.v2.domain.step.RecoveredStepProjection
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * What the plugin's reactive client reacts to: ONE update the agent sent during the turn.
 *
 * The update is the ACP wire type and never leaves the connection boundary; what crosses it
 * into the plugin is the decision to REACT, not the payload.
 */
fun interface AcpReaction {
    suspend fun react(update: SessionUpdate)
}

/**
 * The `acpReactive` step: one agent turn that REACTS to what the agent says while it runs.
 *
 * Where [AcpPromptStepDefinition] waits for the agent's answer and says nothing until it
 * arrives, this step declares a BODY and reacts to every update the agent sends: each update
 * dispatches the body's reaction slot, once, with its own durable identity. The body is the
 * pipeline's hook; the step is the client that drives it.
 *
 * ## The body, and who owns it
 *
 * The descriptor declares the body with [BodyExecutionOwner.HANDLER_CONTINUATION]: the engine
 * NEVER runs this body on its own, the handler does, through the slot seam. That is what makes
 * the reaction REACTIVE — the number of invocations is decided by what the agent said, not by a
 * declared cardinality the engine would enforce.
 *
 * The declared cardinality is [BodyInvocationPolicy.ZERO_OR_MORE] because a turn may produce no
 * update at all, and the declared shape is [BodyExecutionPolicy.Retrying] because that is the
 * only repeating shape the published coherence check admits: the two travel together, and a
 * cardinality the shape cannot honour is rejected fail-closed before any effect.
 *
 * ## The durable identity of one reaction
 *
 * Every invocation carries its own [AttemptSegment]. The engine appends it to the body's path
 * as a deterministic segment, so two reactions of the same turn are two different operations in
 * the journal and two different rows on restart — the identity is the segment, never a counter
 * held in memory.
 *
 * ## What the step does NOT do
 *
 * It never answers the agent in a person's name. The authority is null and the profile is
 * READONLY, so a permission request is refused at the protocol level; there is no published
 * seam that would let a plugin ask a human (see [AcpAuthority]).
 */
object AcpReactiveStepDefinition :
    StepDefinition<AcpPromptInput, AcpReactiveOutput>,
    RecoveredStepProjection<AcpPromptInput, AcpReactiveOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.reactive")

    /** The address of the body's child: the reaction the engine resolves and dispatches. */
    val REACTION_SLOT: PluginStepId = AcpReactionStepDefinition.KEY

    /** The segment key every reaction's attempt identity carries. */
    val ATTEMPT_KEY: PluginStepId = PluginStepId("acp-reaction")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object OutputCodec : StepCodec<AcpReactiveOutput> {
        override fun encode(value: AcpReactiveOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpReactiveOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpReactiveOutput =
            json.decodeFromString(AcpReactiveOutput.serializer(), encoded.value)
    }

    override val contract: StepContract<AcpPromptInput, AcpReactiveOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "acpReactive",
                jenkinsDivergence = "Jenkins has no ACP client, and a body that reacts to a " +
                    "third party's stream belongs to no pipeline language the product predates",
                configRef = "",
                pluginId = AcpPluginDeclaration.CONTRIBUTOR_ID,
                pluginVersion = AcpPluginDeclaration.declaredVersion(),
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
                // The turn is a conversation with a third party: re-executing it after a crash
                // is a second prompt the run never asked for, and memoising it would claim an
                // answer the durable log did not witness.
                replayPolicy = ReplayPolicy.NEVER,
                // The agent is an external process, but the plugin launches it OUTSIDE the
                // durable task substrate, so there is no control directory to reconcile it
                // from. The generic path is the honest declaration; the reconciliation this
                // effect would need is the seam the SDK does not publish to plugins yet.
                recoveryPolicy = RecoveryPolicy.None,
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ZERO_OR_MORE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                        policy = BodyExecutionPolicy.Retrying(RetryPolicy(attemptKey = ATTEMPT_KEY)),
                    ),
                    introduces = null,
                ),
            ),
            inputCodec = AcpPromptStepDefinition.InputCodec,
            outputCodec = OutputCodec,
            // The slot seam is what the handler drives. The continuation is declared because
            // the published coherence check binds a HANDLER_CONTINUATION step to it: a body
            // whose owner is the handler and whose capability is missing is rejected before
            // any effect, and the check reads the DECLARATION, not what the handler reads.
            requiredCapabilities = setOf(BODY_SLOT_CAPABILITY, BODY_CONTINUATION_CAPABILITY),
        )
    }

    override val handler = StepHandler<AcpPromptInput, AcpReactiveOutput> { input, context ->
        val slot: BodySlotInvoker = context.capabilities.get(BODY_SLOT_CAPABILITY, BodySlotInvoker::class)
        val dispatcher = AcpReactionDispatcher(slot, REACTION_SLOT, ATTEMPT_KEY)
        // The reverse leg denies every permission: the authority is null and the profile is
        // READONLY, so an agent that asks for a grant gets the protocol's refusal. The
        // reactive step reacts to what the agent says; it does not grant what the agent asks.
        val reverse = AcpReverseSession(
            workspaceRoot = Path.of(input.cwd.ifEmpty { System.getProperty("user.dir") }),
            profile = AcpTaskProfile.READONLY,
            authority = null,
        )
        when (
            val exchange = AcpConnection(AcpPluginDeclaration.declaredVersion())
                .exchange(input, reverse, dispatcher)
        ) {
            is AcpExchange.Completed -> AcpReactiveOutput(
                ok = true,
                reply = exchange.reply,
                stopReason = exchange.stopReason,
                reactions = dispatcher.invocations,
            )
            is AcpExchange.Refused -> AcpReactiveOutput(
                ok = false,
                failureKind = exchange.refusal.kindToken,
                detail = exchange.refusal.detail,
                reactions = dispatcher.invocations,
            )
        }
    }

    /**
     * The recovered turn, projected from what the substrate observed.
     *
     * The projection is the plugin's half of "an interrupted execution recovers without
     * repeating uncertain effects": the engine calls THIS instead of running the handler
     * again, so the agent is not prompted twice.
     *
     * What the terminal carries decides what may be said. An exit code is a fact: a non-zero
     * one is a failed turn and is materialised as one. A zero exit code is NOT a completed
     * turn — the ACP reply is not a durable fact of the substrate — so nothing is materialised
     * and the recovered run fails closed instead of claiming an answer nobody witnessed.
     */
    override fun project(
        input: AcpPromptInput,
        terminal: DurableTaskTerminal,
    ): RecoveredProjection<AcpReactiveOutput> = when (terminal) {
        is DurableTaskTerminal.Exited ->
            if (terminal.exitCode == 0) {
                RecoveredProjection.InsufficientEvidence(
                    "the recovered terminal carries the agent's exit code, not the turn's reply: " +
                        "a clean exit is not a completed ACP turn",
                )
            } else {
                val output = AcpReactiveOutput(
                    ok = false,
                    failureKind = "ACP_AGENT_EXITED",
                    detail = "the agent exited with code ${terminal.exitCode} before the turn ended",
                    reactions = 0,
                )
                RecoveredProjection.Materialised(
                    value = output,
                    outcome = StepOutcome.Failure(
                        PipelineFailure(
                            kind = FailureKind.SCRIPT,
                            message = output.detail,
                        ),
                    ),
                )
            }
        is DurableTaskTerminal.LaunchFailed ->
            RecoveredProjection.InsufficientEvidence("the agent never started: ${terminal.failure.message}")
        is DurableTaskTerminal.Lost ->
            RecoveredProjection.InsufficientEvidence(
                "the outcome of the interrupted turn is unknown: ${terminal.failure.message}",
            )
        is DurableTaskTerminal.Cancelled ->
            RecoveredProjection.InsufficientEvidence(
                "the turn was interrupted before it produced a result: ${terminal.interruption.message}",
            )
    }
}

/**
 * What a reactive turn produced.
 *
 * [reactions] is the number of body-slot invocations the turn caused — one per update the
 * agent sent while it ran. It travels in the durable output because it is the observable
 * trace of the reaction, and a turn that reacted to nothing must be distinguishable from a
 * turn that never ran.
 */
@kotlinx.serialization.Serializable
data class AcpReactiveOutput(
    val ok: Boolean,
    val reply: String = "",
    val stopReason: String = "",
    /** How many times the body's reaction slot was invoked. */
    val reactions: Int = 0,
    /** one of [AcpRefusal.kindToken] when [ok] is false; empty when it is true. */
    val failureKind: String = "",
    val detail: String = "",
)

/**
 * The body child of [AcpReactiveStepDefinition]: the reaction the engine dispatches once per
 * update the agent sent.
 *
 * The step is the pipeline's hook point. What it produces is deliberately thin — the durable
 * value of a reaction is the journal row the engine writes for it, and that row carries the
 * attempt identity the reaction was invoked with, so every reaction of a turn is a separate
 * operation in the durable log.
 */
object AcpReactionStepDefinition : StepDefinition<AcpReactionInput, AcpReactionOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.reaction")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpReactionInput> {
        override fun encode(value: AcpReactionInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpReactionInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpReactionInput =
            json.decodeFromString(AcpReactionInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpReactionOutput> {
        override fun encode(value: AcpReactionOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpReactionOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpReactionOutput =
            json.decodeFromString(AcpReactionOutput.serializer(), encoded.value)
    }

    override val contract: StepContract<AcpReactionInput, AcpReactionOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "acpReaction",
                jenkinsDivergence = "Jenkins has no ACP client, and a hook that runs once per " +
                    "agent update belongs to no pipeline language the product predates",
                configRef = "",
                pluginId = AcpPluginDeclaration.CONTRIBUTOR_ID,
                pluginVersion = AcpPluginDeclaration.declaredVersion(),
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = InputCodec,
            outputCodec = OutputCodec,
            requiredCapabilities = emptySet(),
        )
    }

    override val handler = StepHandler<AcpReactionInput, AcpReactionOutput> { _, _ ->
        AcpReactionOutput(reacted = true)
    }
}

/** The reaction's input: nothing, because the event it reacts to travels with the invocation. */
@kotlinx.serialization.Serializable
data class AcpReactionInput(
    /** Reserved for future reaction payloads; absent today means "no payload". */
    val note: String = "",
)

/** What a reaction produced. */
@kotlinx.serialization.Serializable
data class AcpReactionOutput(
    /** Always true: the reaction ran, and the engine journalled it under its own identity. */
    val reacted: Boolean = true,
)

/**
 * The reactive half of [AcpReactiveStepDefinition]: one slot invocation per agent update,
 * each with its own durable identity.
 *
 * The counter that numbers the attempts is NOT the identity: the identity is the
 * [AttemptSegment] the engine turns into a deterministic path segment, and it is minted here
 * because the handler is the party that knows which reaction this is.
 */
class AcpReactionDispatcher(
    private val slot: BodySlotInvoker,
    private val slotId: PluginStepId,
    private val attemptKey: PluginStepId,
) : AcpReaction {

    private val segments = mutableListOf<AttemptSegment>()
    private val outcomes = mutableListOf<BodyOutcome>()

    /** How many reactions were dispatched. */
    val invocations: Int get() = segments.size

    /** The identity each reaction travelled with, in order. */
    val identities: List<AttemptSegment> get() = segments.toList()

    /** What the engine answered each reaction with, in order. */
    val results: List<BodyOutcome> get() = outcomes.toList()

    override suspend fun react(update: SessionUpdate) {
        val segment = AttemptSegment(index = segments.size + 1, key = attemptKey)
        val outcome = slot.invoke(slotId, BodyInvocationContext(attempt = segment))
        segments += segment
        outcomes += outcome
        // A failed or cancelled reaction does not stop the turn: the agent keeps talking and
        // the pipeline keeps reacting, and the reaction's own outcome stays in its journal
        // row. The update itself never crosses the boundary — the reaction is the decision
        // to react, and the payload is the agent's.
    }
}
