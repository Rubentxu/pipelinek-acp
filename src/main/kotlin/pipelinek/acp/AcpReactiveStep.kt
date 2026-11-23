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
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RecoveredProjection
import dev.rubentxu.pipeline.v2.domain.step.RecoveredStepProjection
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import java.nio.file.Path
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * arrives, this step declares a BODY and reacts to what the agent sends: each update is routed
 * by the plan compiled from the input's declarations (see [compileAcpReactionPlan]), and each
 * match dispatches its named body slot with its own durable identity. The body is the
 * pipeline's hook; the step is the client that drives it. Declaring no reaction keeps the
 * shipped law verbatim: one unnamed slot, every update.
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
 * Every invocation carries its own [AttemptSegment], minted by the [AcpReactionRunner]. The
 * engine appends it to the body's path as a deterministic segment, so two reactions of the
 * same turn are two different operations in the journal and two different rows on restart —
 * the identity is the segment, never a counter held in memory.
 *
 * ## What the step does NOT do
 *
 * It never answers the agent in a person's name. The authority is null and the profile is
 * READONLY, so a permission request is refused at the protocol level. An elicitation is a
 * different question — the agent asking for a person's input, not for a grant — and it is
 * put to a person through the human-decision port (see [AcpHumanDecision]).
 */
object AcpReactiveStepDefinition :
    StepDefinition<AcpReactiveInput, AcpReactiveOutput>,
    RecoveredStepProjection<AcpReactiveInput, AcpReactiveOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.reactive")

    /** The address of the body's child: the reaction the engine resolves and dispatches. */
    val REACTION_SLOT: PluginStepId = AcpReactionStepDefinition.KEY

    /** The segment key every reaction's attempt identity carries. */
    val ATTEMPT_KEY: PluginStepId = PluginStepId("acp-reaction")

    /** Refusal token for a plan the compiler will not route; no agent was launched yet. */
    const val PLAN_REJECTED: String = "ACP_PLAN_REJECTED"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpReactiveInput> {
        override fun encode(value: AcpReactiveInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpReactiveInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpReactiveInput =
            json.decodeFromString(AcpReactiveInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpReactiveOutput> {
        override fun encode(value: AcpReactiveOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpReactiveOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpReactiveOutput =
            json.decodeFromString(AcpReactiveOutput.serializer(), encoded.value)
    }

    override val contract: StepContract<AcpReactiveInput, AcpReactiveOutput> by lazy {
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
            inputCodec = InputCodec,
            outputCodec = OutputCodec,
            // The slot seam is what the handler drives. The continuation is declared because
            // the published coherence check binds a HANDLER_CONTINUATION step to it: a body
            // whose owner is the handler and whose capability is missing is rejected before
            // any effect, and the check reads the DECLARATION, not what the handler reads.
            // The human-decision capability is declared because this step runs a reverse
            // leg too: the agent may ask the client for a person's input while it reacts.
            // The emission capability is declared because the reactive turn is an exchange
            // like any other: its lifecycle facts ride PKH-136's published seam, and the
            // redaction belongs to that publisher — never to a second one invented here.
            requiredCapabilities = setOf(
                BODY_SLOT_CAPABILITY,
                BODY_CONTINUATION_CAPABILITY,
                INPUT_DECISIONS_CAPABILITY,
                PLUGIN_EVENT_EMISSION_CAPABILITY,
            ),
        )
    }

    override val handler = StepHandler<AcpReactiveInput, AcpReactiveOutput> { input, context ->
        val slot: BodySlotInvoker = context.capabilities.get(BODY_SLOT_CAPABILITY, BodySlotInvoker::class)
        when (val compiled = compileAcpReactionPlan(input.reactions, input.awaits)) {
            // The plan is data, so it is checked as data — BEFORE the agent is launched. A
            // routing ambiguity caught here costs no turn; one discovered mid-turn would be a
            // dispatch the journal cannot even attribute, because an unroutable plan has no
            // slot identity to journal under.
            is AcpPlanCompilation.Rejected -> AcpReactiveOutput(
                ok = false,
                failureKind = PLAN_REJECTED,
                detail = compiled.reasons.joinToString("; "),
            )
            is AcpPlanCompilation.Compiled -> {
                // PKH-139: the exposed tools compile against the ADDRESSES the reaction half
                // already claimed — one body child, one dispatcher. A tool stealing a
                // reaction's name is refused here, where nothing has launched and every
                // problem can still be named at once.
                val reactionAddresses = compiled.reactions.map { it.id }.toSet()
                when (val tools = compileAcpToolPlan(input.exposedTools, reactionAddresses)) {
                    is AcpToolPlanCompilation.Rejected -> AcpReactiveOutput(
                        ok = false,
                        failureKind = PLAN_REJECTED,
                        detail = tools.reasons.joinToString("; "),
                    )
                    is AcpToolPlanCompilation.Compiled -> {
                        val decisions: InputDecisions =
                            context.capabilities.get(INPUT_DECISIONS_CAPABILITY, InputDecisions::class)
                        val emission: PluginEventEmission =
                            context.capabilities.get(PLUGIN_EVENT_EMISSION_CAPABILITY, PluginEventEmission::class)
                        withObservationPublisher(
                            emission,
                            AcpObservationPublisher.exchangeRefOf(context),
                        ) { publisher ->
                            runReactiveTurn(
                                input,
                                slot,
                                decisions,
                                compiled,
                                tools.tools,
                                publisher,
                                context.runId.value,
                                "step-${context.stepIndex}",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Drive one reactive turn: the runner takes the events the reader receives, the awaits
     * observe the same stream, the exposed tools answer calls through the session MCP gateway,
     * and the output says what each of them did.
     *
     * The reader hands updates to [AcpReactionRunner.submit] and never waits for a body (the
     * deadlock law of AC#4); the awaits run as children of the turn's scope, and when the turn
     * ends their remaining budget is cut short — no event can arrive after the session closes,
     * so a still-pending await reports [AcpAwaitVerdict.TimedOut], never a lie.
     *
     * PKH-139: the gateway is minted FROM the compiled plan and dies in this turn's finally —
     * it cannot outlive the session that was handed its URL (AC#2's expiry). It dispatches
     * through the same [BodySlotInvoker] seam and the same [AcpReactionLedger] the reactions
     * drive, so a tool call is one more slot request the engine journals, never a second
     * executor, and the reader thread is never blocked by a tool body (AC#4).
     */
    private suspend fun runReactiveTurn(
        input: AcpReactiveInput,
        slot: BodySlotInvoker,
        decisions: InputDecisions,
        compiled: AcpPlanCompilation.Compiled,
        compiledTools: List<AcpCompiledTool>,
        publisher: AcpUpdateSink,
        runId: String,
        operationId: String,
    ): AcpReactiveOutput {
        // PKH-138: the turn runs its reactions over the durable ledger. The nonce is THIS
        // connection's persisted key half — it leaves in every row's EventKey, so a recovered
        // or reconnected run can tell its own counters from a foreign connection's. The sink
        // collects in-run; the rows themselves become durable through the step output, which
        // is the journal — no second authority is opened here.
        val connectionNonce = java.util.UUID.randomUUID().toString()
        val ledger = AcpReactionLedger()
        // PKH-139: no compiled tool opens no gateway — the generic surface AC#5 refuses does
        // not exist by construction, and the session travels with empty mcpServers as always.
        val gateway = AcpMcpGateway.start(compiledTools, slot, ledger, runId, operationId)
        val runner = AcpReactionRunner(
            slot,
            compiled.reactions,
            connectionNonce = connectionNonce,
            journal = ledger,
        )
        val awaitVerdicts = arrayOfNulls<AcpAwaitVerdict>(compiled.awaits.size)
        val exchange = coroutineScope {
            runner.start(this)
            val awaitJobs = compiled.awaits.mapIndexed { index, declaration ->
                launch {
                    awaitVerdicts[index] = runner.await(declaration.condition, declaration.timeoutMillis)
                }
            }
            // The reverse leg denies every permission: the authority is null and the profile is
            // READONLY, so an agent that asks for a grant gets the protocol's refusal. The
            // reactive step reacts to what the agent says; it does not grant what the agent asks.
            // An elicitation is not a grant: it is the agent asking for a person's input, and
            // the port is how that question reaches a person.
            val reverse = AcpReverseSession(
                workspaceRoot = Path.of(input.cwd.ifEmpty { System.getProperty("user.dir") }),
                profile = AcpTaskProfile.READONLY,
                authority = null,
                humanDecision = AcpHumanDecision(decisions),
                budgetMillis = input.timeoutSeconds * 1000,
            )
            try {
                AcpConnection(AcpPluginDeclaration.declaredVersion())
                    .exchange(
                        AcpPromptInput(input.command, input.prompt, input.cwd, input.timeoutSeconds),
                        reverse,
                        AcpReaction { runner.submit(it) },
                        observation = publisher,
                        sessionMcpServers = listOfNotNull(gateway?.descriptor()),
                        needsMcpHttp = gateway != null,
                    )
            } finally {
                // AC#2/AC#4's expiry: the gateway stops serving BEFORE the turn's bookkeeping
                // ends, so a call racing the turn's last moment is refused, never executed
                // against a session that no longer exists.
                gateway?.close()
                runner.finish()
                awaitJobs.forEach { it.cancel() }
                awaitJobs.forEach { it.join() }
            }
        }
        val verdicts = compiled.awaits.indices.map { awaitVerdicts[it] ?: AcpAwaitVerdict.TimedOut }
        val summaries = compiled.reactions.map { reaction ->
            AcpReactionSummary(
                id = reaction.id,
                invocations = runner.invocations.count { it.reactionId == reaction.id },
                skipped = runner.skipped.filter { it.reactionId == reaction.id }.map { it.eventCursor },
            )
        }
        val toolCalls = gateway?.callRecords() ?: emptyList()
        val gatewayUrl = gateway?.url ?: ""
        return when (exchange) {
            is AcpExchange.Completed -> AcpReactiveOutput(
                ok = true,
                reply = exchange.reply,
                stopReason = exchange.stopReason,
                reactions = runner.invocations.size,
                reactionSummary = summaries,
                awaitVerdicts = verdicts,
                reactionLedger = runner.journalRows,
                exposedToolCalls = toolCalls,
                mcpGatewayUrl = gatewayUrl,
            )
            is AcpExchange.Refused -> AcpReactiveOutput(
                ok = false,
                failureKind = exchange.refusal.kindToken,
                detail = exchange.refusal.detail,
                reactions = runner.invocations.size,
                reactionSummary = summaries,
                awaitVerdicts = verdicts,
                reactionLedger = runner.journalRows,
                exposedToolCalls = toolCalls,
                mcpGatewayUrl = gatewayUrl,
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
        input: AcpReactiveInput,
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
 * [reactions] is the number of body-slot invocations the turn caused — one per dispatch the
 * runner made. It travels in the durable output because it is the observable trace of the
 * reaction, and a turn that reacted to nothing must be distinguishable from a turn that never
 * ran. [reactionSummary] breaks that trace down per declared reaction — including the
 * [AcpReactionSummary.skipped] later matches of a `once` reaction, so a skip is an OBSERVED
 * outcome, not silence — and [awaitVerdicts] answers each declared await in declaration order.
 * [reactionLedger] is the PKH-138 reconciliation evidence: the intent/ack rows the runner
 * wrote, keyed by [EventKey], persisted HERE in the step's own journal output — a recovered run
 * reads these rows back and never re-fires an effect whose outcome the journal cannot prove.
 */
@kotlinx.serialization.Serializable
data class AcpReactiveOutput(
    val ok: Boolean,
    val reply: String = "",
    val stopReason: String = "",
    /** How many times the reaction slots were invoked in total. */
    val reactions: Int = 0,
    /** one of [AcpRefusal.kindToken] — or [AcpReactiveStepDefinition.PLAN_REJECTED] — when [ok] is false; empty when it is true. */
    val failureKind: String = "",
    val detail: String = "",
    /** Per-declared reaction: dispatch count and skipped cursors; the unnamed default plans as one entry. */
    val reactionSummary: List<AcpReactionSummary> = emptyList(),
    /** One verdict per declared await, in declaration order. */
    val awaitVerdicts: List<AcpAwaitVerdict> = emptyList(),
    /** Durable intent/ack rows of this turn's dispatches, in write order (PKH-138 AC#3). */
    val reactionLedger: List<AcpReactionLedgerRow> = emptyList(),
    /**
     * Every call the session MCP gateway answered this turn, allowed or refused (PKH-139).
     * Empty when the plan exposed no tools — the gateway is minted per turn and dies with it,
     * so this list is the whole of what the agent could do through it.
     */
    val exposedToolCalls: List<AcpToolCallRecord> = emptyList(),
    /**
     * The loopback URL the gateway served while the turn ran ("" when there was no gateway).
     * It is DEAD data once the turn ends — the server is stopped in the turn's finally — and
     * tests measure AC#2's expiry and AC#5's refusal against exactly that: connecting here
     * after the output exists must fail.
     */
    val mcpGatewayUrl: String = "",
)

/** The trace one declared reaction left in the turn. */
@kotlinx.serialization.Serializable
data class AcpReactionSummary(
    val id: String,
    val invocations: Int,
    /** Cursors of the later matches a `once` reaction refused; empty unless [AcpReactionDeclaration.once]. */
    val skipped: List<Long> = emptyList(),
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
 * The input of a reactive turn: the prompt half is [AcpPromptInput]'s shape, and the reaction
 * half is DECLARED data — [compileAcpReactionPlan] routes it into static slots before the
 * agent is launched, and the journal stores this same encoding, so a recovered run reads back
 * the routing it was promised.
 *
 * A failed or cancelled reaction does not stop the turn: the agent keeps talking and the
 * pipeline keeps reacting, and each reaction's own outcome stays in its journal row. The
 * update itself never crosses the boundary — the reaction is the decision to react, and the
 * payload is the agent's.
 */
@kotlinx.serialization.Serializable
data class AcpReactiveInput(
    val command: List<String>,
    val prompt: String,
    val cwd: String = "",
    val timeoutSeconds: Long = 300,
    /** `on(id, selector)` declarations; empty keeps the shipped unnamed single-slot plan. */
    val reactions: List<AcpReactionDeclaration> = emptyList(),
    /** `await(condition, timeout)` declarations, answered in this order. */
    val awaits: List<AcpAwaitDeclaration> = emptyList(),
    /**
     * PKH-139: steps the plan authorises the agent to CALL as session tools. They compile to
     * static slot addresses (see [compileAcpToolPlan]) and are offered through a temporary MCP
     * gateway; a tool name colliding with a reaction is rejected before the agent launches.
     */
    val exposedTools: List<AcpExposedToolDeclaration> = emptyList(),
)
