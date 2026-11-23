package pipelinek.acp

import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepBodyScopeWrappers
import dev.rubentxu.pipeline.v2.dsl.StepEmitter

/**
 * The plugin teaching the DSL its own step; the core compiler never learns the name `acpPrompt`.
 *
 * ```kotlin
 * import pipelinek.acp.acpPrompt
 * pipeline {
 *     stages {
 *         stage("Agent") {
 *             acpPrompt(listOf("my-agent", "--acp"), "summarize the diff")
 *         }
 *     }
 * }
 * ```
 *
 * The call lowers to a generic registry step — the same primitive every external plugin uses —
 * and the typed [AcpPromptOutput] the handler returns is read by whoever bound the step's name,
 * exactly as with any other step. This function declares; it never executes.
 *
 * The contract route works in every scope that hosts steps; the [StageScope] overload exists
 * because the receiver of a Kotlin extension is part of the JVM descriptor a PRECOMPILED
 * script names — published scripts compiled against this plugin bind to it, so removing it
 * would break them while the generic route still resolves for source scripts. The historic
 * route delegates to the contract route; there is one lowering, never two.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.acpPrompt(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
) {
    registryStep(
        stepKey = AcpPromptStepDefinition.KEY,
        encodedInput = AcpPromptStepDefinition.InputCodec.encode(
            AcpPromptInput(
                command = command,
                prompt = prompt,
                cwd = cwd,
                timeoutSeconds = timeoutSeconds,
            ),
        ),
    )
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
fun StageScope.acpPrompt(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.acpPrompt(command, prompt, cwd, timeoutSeconds)
}

/**
 * One isolated, profiled, budgeted delegation to an external agent (PKH-133).
 *
 * ```kotlin
 * import pipelinek.acp.acpTask
 * pipeline {
 *     stages {
 *         stage("Delegate") {
 *             acpTask(listOf("my-agent", "--acp"), "fix the failing test",
 *                 profile = AcpTaskProfile.READONLY, cwd = "work", budgetSeconds = 120)
 *         }
 *     }
 * }
 * ```
 *
 * What the DSL deliberately cannot say: an absolute cwd (the host's lease root fences it),
 * a shell-wrapped argv ([AcpTaskIsolation] refuses it before launch), a secret (a credential
 * travels by NAME via [credentialRef] and resolves through the host's seam). Lowering is the
 * same single `registryStep` primitive as [acpPrompt] — one lowering, never two.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.acpTask(
    command: List<String>,
    prompt: String,
    profile: AcpTaskProfile = AcpTaskProfile.READONLY,
    cwd: String = "",
    budgetSeconds: Long = 300,
    credentialRef: String = "",
    authority: AcpAuthority? = null,
) {
    registryStep(
        stepKey = AcpTaskStepDefinition.KEY,
        encodedInput = AcpTaskStepDefinition.InputCodec.encode(
            AcpTaskInput(
                command = command,
                prompt = prompt,
                profile = profile,
                cwd = cwd,
                budgetSeconds = budgetSeconds,
                credentialRef = credentialRef,
                authority = authority,
            ),
        ),
    )
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
fun StageScope.acpTask(
    command: List<String>,
    prompt: String,
    profile: AcpTaskProfile = AcpTaskProfile.READONLY,
    cwd: String = "",
    budgetSeconds: Long = 300,
    credentialRef: String = "",
    authority: AcpAuthority? = null,
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.acpTask(command, prompt, profile, cwd, budgetSeconds, credentialRef, authority)
}

/**
 * Declare one prompt turn of a composed session (PKH-135) — [text] plus any multimodal
 * [content] blocks that ride with it. A plain data construction; the capability gate runs
 * at the connection, not here.
 */
fun acpTurn(text: String, content: List<AcpContentInput> = emptyList()): AcpTurnInput =
    AcpTurnInput(text = text, content = content)

/** Declare an image block (base64 in [data]) gated by the negotiated `image` capability. */
fun acpImage(data: String, mimeType: String): AcpContentInput = AcpContentInput.Image(data, mimeType)

/** Declare an audio block (base64 in [data]) gated by the negotiated `audio` capability. */
fun acpAudio(data: String, mimeType: String): AcpContentInput = AcpContentInput.Audio(data, mimeType)

/** Declare an embedded text resource gated by the negotiated `embeddedContext` capability. */
fun acpResource(uri: String, text: String, mimeType: String = "text/plain"): AcpContentInput =
    AcpContentInput.Resource(uri, text, mimeType)

/** Declare a resource LINK: no bytes embedded, so no multimodal capability gates it. */
fun acpResourceLink(uri: String, name: String, mimeType: String = ""): AcpContentInput =
    AcpContentInput.ResourceLink(uri, name, mimeType)

/** Declare an MCP descriptor over stdio for the session's tool servers. */
fun acpMcpStdio(name: String, command: List<String>): AcpMcpInput =
    AcpMcpInput(name = name, transport = "stdio", command = command)

/** Declare an MCP descriptor over HTTP; gated by the negotiated mcp `http` capability. */
fun acpMcpHttp(
    name: String,
    url: String,
    headers: List<AcpMcpHeader> = emptyList(),
): AcpMcpInput = AcpMcpInput(name = name, transport = "http", url = url, headers = headers)

/** Declare an MCP descriptor over SSE; gated by the negotiated mcp `sse` capability. */
fun acpMcpSse(
    name: String,
    url: String,
    headers: List<AcpMcpHeader> = emptyList(),
): AcpMcpInput = AcpMcpInput(name = name, transport = "sse", url = url, headers = headers)

/** One http/sse MCP header as the DSL declares it. */
fun acpMcpHeader(name: String, value: String): AcpMcpHeader = AcpMcpHeader(name, value)

/**
 * A composed ACP session: one connection, [turns] prompt turns run IN ORDER against the
 * same session, plus the session operations the negotiated schema offers (PKH-135).
 *
 * ```kotlin
 * import pipelinek.acp.acpSession
 * import pipelinek.acp.acpTurn
 * pipeline {
 *     stages {
 *         stage("Agent") {
 *             acpSession(
 *                 command = listOf("my-agent", "--acp"),
 *                 turns = listOf(
 *                     acpTurn("read file X and list its exports"),
 *                     acpTurn("now write a test for the second export"),
 *                 ),
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * The scope is LEXICAL, not a daemon: the session lives exactly as long as the step, and
 * the ids it mints come back only as opaque refs. What the DSL deliberately cannot say: a
 * second concurrent prompt on this session (v1 answers that with
 * `ACP_ACTIVE_PROMPT_IN_PROGRESS`), a raw wire session id (load/resume name the AGENT's id;
 * ownership is checked at the connection), or a delete implied by closing — [deleteOnExit]
 * is the only thing that erases history, and the default is false (AC#3). Lowering is the
 * same single `registryStep` primitive as [acpPrompt] — one lowering, never two.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.acpSession(
    command: List<String>,
    turns: List<AcpTurnInput>,
    cwd: String = "",
    timeoutSeconds: Long = 300,
    mcpServers: List<AcpMcpInput> = emptyList(),
    modeId: String = "",
    configOptions: List<AcpConfigInput> = emptyList(),
    loadSessionId: String = "",
    resumeSessionId: String = "",
    listSessions: Boolean = false,
    logout: Boolean = false,
    deleteOnExit: Boolean = false,
) {
    registryStep(
        stepKey = AcpSessionStepDefinition.KEY,
        encodedInput = AcpSessionStepDefinition.InputCodec.encode(
            AcpSessionInput(
                command = command,
                turns = turns,
                cwd = cwd,
                timeoutSeconds = timeoutSeconds,
                mcpServers = mcpServers,
                modeId = modeId,
                configOptions = configOptions,
                loadSessionId = loadSessionId,
                resumeSessionId = resumeSessionId,
                listSessions = listSessions,
                logout = logout,
                deleteOnExit = deleteOnExit,
            ),
        ),
    )
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
@Suppress("LongParameterList")
fun StageScope.acpSession(
    command: List<String>,
    turns: List<AcpTurnInput>,
    cwd: String = "",
    timeoutSeconds: Long = 300,
    mcpServers: List<AcpMcpInput> = emptyList(),
    modeId: String = "",
    configOptions: List<AcpConfigInput> = emptyList(),
    loadSessionId: String = "",
    resumeSessionId: String = "",
    listSessions: Boolean = false,
    logout: Boolean = false,
    deleteOnExit: Boolean = false,
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.acpSession(
        command = command,
        turns = turns,
        cwd = cwd,
        timeoutSeconds = timeoutSeconds,
        mcpServers = mcpServers,
        modeId = modeId,
        configOptions = configOptions,
        loadSessionId = loadSessionId,
        resumeSessionId = resumeSessionId,
        listSessions = listSessions,
        logout = logout,
        deleteOnExit = deleteOnExit,
    )
}

/**
 * One agent turn that REACTS to what the agent says while it runs.
 *
 * ```kotlin
 * import pipelinek.acp.acpAwait
 * import pipelinek.acp.acpOn
 * import pipelinek.acp.acpReactive
 * pipeline {
 *     stages {
 *         stage("Agent") {
 *             acpReactive(
 *                 command = listOf("my-agent", "--acp"),
 *                 prompt = "fix the failing test",
 *                 reactions = listOf(acpOn("alert", AcpEventSelector.TextContains("ERROR"))),
 *                 awaits = listOf(acpAwait(AcpEventSelector.UpdateKind("plan"), 30_000)),
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * Where `acpPrompt` waits for the agent's answer, this step declares a BODY and dispatches it
 * as the agent talks. [reactions] route those dispatches: each declared reaction names its
 * child, and the lowering writes that name as a SLOT around the plugin's own reaction step, so
 * the engine resolves `alert` to exactly one child and the runner can address it. An empty
 * [reactions] keeps the shipped shape — one unnamed child, every update — because a plan that
 * says nothing must not change yesterday's run.
 *
 * The body is written by the plugin, not by the author: the reaction is the plugin's own hook,
 * and a body the author filled with arbitrary steps would leave the plugin unable to name the
 * children it has to dispatch. [awaits] declare observation, not dispatch: they answer in the
 * turn's output, in order.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.acpReactive(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
    reactions: List<AcpReactionDeclaration> = emptyList(),
    awaits: List<AcpAwaitDeclaration> = emptyList(),
) {
    registryBlock(
        stepKey = AcpReactiveStepDefinition.KEY,
        encodedInput = AcpReactiveStepDefinition.InputCodec.encode(
            AcpReactiveInput(
                command = command,
                prompt = prompt,
                cwd = cwd,
                timeoutSeconds = timeoutSeconds,
                reactions = reactions,
                awaits = awaits,
            ),
        ),
    ) {
        val reactionInput = AcpReactionStepDefinition.InputCodec.encode(AcpReactionInput())
        if (reactions.isEmpty()) {
            registryStep(AcpReactionStepDefinition.KEY, reactionInput)
        } else {
            // Naming a child is the body scope's own verb; an emitter without it cannot host
            // declared reactions — say so at composition, not as a silent collapse to one slot.
            val namer = this as? StepBodyScopeWrappers
                ?: error(
                    "acpReactive with declared reactions needs a scope that can name body " +
                        "children (the StageScope/StepBodyScope route); ${this::class.simpleName} cannot",
                )
            for (declaration in reactions) {
                if (declaration.id.isBlank()) continue // the compiler refuses it; emit no child for it
                namer.slot(declaration.id) {
                    registryStep(AcpReactionStepDefinition.KEY, reactionInput)
                }
            }
        }
    }
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
fun StageScope.acpReactive(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
    reactions: List<AcpReactionDeclaration> = emptyList(),
    awaits: List<AcpAwaitDeclaration> = emptyList(),
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.acpReactive(command, prompt, cwd, timeoutSeconds, reactions, awaits)
}

/**
 * Repair broken builds with an independent verifier: the PKH-140 recipe as a step.
 *
 * ```kotlin
 * import pipelinek.acp.agenticRepair
 * pipeline {
 *     stages {
 *         stage("Repair") {
 *             agenticRepair(
 *                 command = listOf("my-agent", "--acp"),
 *                 repairPrompt = "make scripts/build-and-test.sh pass",
 *                 verifyCommand = listOf("./scripts/build-and-test.sh"),
 *                 cwd = "work/the-project",
 *                 maxRepairAttempts = 3,
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * What the DSL deliberately cannot say: a verify command chosen from the agent's answer (the
 * check is the plan's argv, always), an unbounded budget ([AcpRepairLoop.MAX_REPAIR_ATTEMPTS]
 * caps it and [AcpRepairLoop.rejectPlan] refuses beyond), or a promotion verb — the step's
 * output is the verdict plus ARCHIVED candidate paths; committing is a different step a person
 * wrote. Lowering is the same single `registryStep` primitive as the other steps — one lowering,
 * never two.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.agenticRepair(
    command: List<String>,
    repairPrompt: String,
    verifyCommand: List<String>,
    cwd: String = "",
    archiveDir: String = "",
    maxRepairAttempts: Int = 3,
    timeoutSeconds: Long = 300,
    diffCommand: List<String> = emptyList(),
    revisionCommand: List<String> = emptyList(),
    priorLedgerRows: List<AcpReactionLedgerRow> = emptyList(),
) {
    agenticRepair(
        AcpRepairInput(
            command = command,
            repairPrompt = repairPrompt,
            verifyCommand = verifyCommand,
            cwd = cwd,
            archiveDir = archiveDir,
            maxRepairAttempts = maxRepairAttempts,
            timeoutSeconds = timeoutSeconds,
            diffCommand = diffCommand,
            revisionCommand = revisionCommand,
            priorLedgerRows = priorLedgerRows,
        ),
    )
}

/** The full-plan route: everything the recipe needs is in the [input], encoded as declared. */
fun <B : StepEmitter<B>> StepEmitter<B>.agenticRepair(input: AcpRepairInput) {
    registryStep(
        stepKey = AcpRepairStepDefinition.KEY,
        encodedInput = AcpRepairStepDefinition.InputCodec.encode(input),
    )
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
@Suppress("LongParameterList")
fun StageScope.agenticRepair(
    command: List<String>,
    repairPrompt: String,
    verifyCommand: List<String>,
    cwd: String = "",
    archiveDir: String = "",
    maxRepairAttempts: Int = 3,
    timeoutSeconds: Long = 300,
    diffCommand: List<String> = emptyList(),
    revisionCommand: List<String> = emptyList(),
    priorLedgerRows: List<AcpReactionLedgerRow> = emptyList(),
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.agenticRepair(
        command = command,
        repairPrompt = repairPrompt,
        verifyCommand = verifyCommand,
        cwd = cwd,
        archiveDir = archiveDir,
        maxRepairAttempts = maxRepairAttempts,
        timeoutSeconds = timeoutSeconds,
        diffCommand = diffCommand,
        revisionCommand = revisionCommand,
        priorLedgerRows = priorLedgerRows,
    )
}
