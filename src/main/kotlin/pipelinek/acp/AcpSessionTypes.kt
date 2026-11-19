package pipelinek.acp

/**
 * The vocabulary of the composed session (PKH-135): what a run asks an ACP agent to do
 * across MORE THAN ONE prompt turn, plus the session operations the negotiated schema
 * offers (load/resume/list/close/delete/logout, modes and configuration options).
 *
 * These are the plugin's payload types, not wire types: the SDK's `ContentBlock`,
 * `McpServer` and friends never leave [AcpSessionScope]; the encoded input below is durable
 * state the journal keeps, so it must round-trip without any SDK class on the other end.
 *
 * The scope is LEXICAL, not a daemon: [AcpSessionInput] plans one connection's work; the
 * handler opens it, runs every declared turn through it, and releases it in the same scope
 * that started it. Sessions independent of each other parallelize because they are
 * different steps with different connections — never different fields of one parked object.
 */
@kotlinx.serialization.Serializable
data class AcpSessionInput(
    /** argv of the agent to launch; the first entry is the executable. Never a shell string. */
    val command: List<String>,
    /**
     * The prompt turns, executed IN ORDER, one per session on this connection. v1 keeps at
     * most one prompt ACTIVE at a time: the next turn only starts after the previous turn's
     * terminal event, which is what lets the agent's own history carry the context between
     * turns (AC#1).
     */
    val turns: List<AcpTurnInput>,
    /** working directory advertised to the agent for the session. Empty means "the plugin's own". */
    val cwd: String = "",
    /** total budget for handshake, every declared operation and every turn, including unwinding. */
    val timeoutSeconds: Long = 300,
    /** MCP server descriptors to offer at session creation; gated by the negotiated mcp capabilities. */
    val mcpServers: List<AcpMcpInput> = emptyList(),
    /** a mode to set right after the session opens; empty means "say nothing about modes". */
    val modeId: String = "",
    /** configuration options to set right after the session opens, in declaration order. */
    val configOptions: List<AcpConfigInput> = emptyList(),
    /** if set, the session is OPENED WITH `session/load` (historic updates separated, AC#3). */
    val loadSessionId: String = "",
    /** if set, the session is opened with `session/resume` instead of `session/new`. */
    val resumeSessionId: String = "",
    /** ask `session/list` once the session is open; the ids land in the typed output. */
    val listSessions: Boolean = false,
    /** end the connection with `logout` (gated by the negotiated auth capability). */
    val logout: Boolean = false,
    /**
     * Delete the session before unwinding. FALSE by default and that default is the AC#3
     * rule: closing a scope is not deleting history — the ordinary exit says `session/close`
     * only when a delete was declared, and never erases what the agent persisted.
     */
    val deleteOnExit: Boolean = false,
)

/**
 * One turn: the text prompt plus any multimodal content blocks that ride with it (AC#2).
 *
 * [content] blocks travel in declared order after the text block and are each gated by the
 * negotiated prompt capability that covers them; a block the agent never announced is a
 * typed refusal, never a silent drop and never a degraded text-only prompt.
 */
@kotlinx.serialization.Serializable
data class AcpTurnInput(
    val text: String,
    val content: List<AcpContentInput> = emptyList(),
)

/**
 * A multimodal content block in the plugin's vocabulary; mapped to the SDK's `ContentBlock`
 * only inside the connection boundary. The `capability` field is the negotiation gate each
 * variant obeys (AC#2): the named flag under the agent's `promptCapabilities`.
 */
@kotlinx.serialization.Serializable
sealed class AcpContentInput {
    /** the promptCapabilities flag that covers this block, or null when text-only gates it. */
    abstract val capability: String?

    /** image input, base64 in [data]; gated by promptCapabilities.image. */
    @kotlinx.serialization.Serializable
    data class Image(val data: String, val mimeType: String) : AcpContentInput() {
        override val capability: String = "image"
    }

    /** audio input, base64 in [data]; gated by promptCapabilities.audio. */
    @kotlinx.serialization.Serializable
    data class Audio(val data: String, val mimeType: String) : AcpContentInput() {
        override val capability: String = "audio"
    }

    /** an embedded text resource; gated by promptCapabilities.embeddedContext. */
    @kotlinx.serialization.Serializable
    data class Resource(val uri: String, val text: String, val mimeType: String = "text/plain") :
        AcpContentInput() {
        override val capability: String = "embeddedContext"
    }

    /** a resource LINK (no bytes embedded); needs no prompt capability beyond text. */
    @kotlinx.serialization.Serializable
    data class ResourceLink(val uri: String, val name: String, val mimeType: String = "") :
        AcpContentInput() {
        override val capability: String? = null
    }
}

/**
 * An MCP server descriptor offered at session creation. [transport] is the closed vocabulary
 * `stdio`, `http` or `sse`: a stdio server needs no negotiated capability, while `http` and
 * `sse` are gated by the agent's mcpCapabilities and refused when not announced.
 */
@kotlinx.serialization.Serializable
data class AcpMcpInput(
    val name: String,
    val transport: String,
    val command: List<String> = emptyList(),
    val url: String = "",
    val headers: List<AcpMcpHeader> = emptyList(),
)

/** One header of an http/sse MCP server; plain name/value pairs, never secrets by shape. */
@kotlinx.serialization.Serializable
data class AcpMcpHeader(val name: String, val value: String)

/**
 * A configuration option to set on the open session. Select-style options carry [value];
 * boolean-style options carry `value` as the literal `true`/`false`, which the session maps
 * onto the SDK's typed option value after the agent's advertised current shape is checked
 * (an option the agent never advertised, or a value outside its offered set, is
 * [AcpRefusal.ConfigInvalid]).
 */
@kotlinx.serialization.Serializable
data class AcpConfigInput(val configId: String, val value: String)

/**
 * One completed turn as durable output: the reply the agent streamed and the stop reason it
 * ended with, in the plugin's own text vocabulary (the output codec must not name SDK
 * classes — the journal row outlives the artifact that wrote it).
 */
@kotlinx.serialization.Serializable
data class AcpTurnOutput(
    val reply: String,
    val stopReason: String,
)

/**
 * What the composed session produced: every turn's typed answer, plus the typed answers of
 * the session operations the plan declared. The fields are the evidence AC#2 and AC#3 ask
 * for: [historicEvents] carries ONLY what `session/load` streamed before its response
 * (live prompt events never mix into it), and [sessionsListed] the ids `session/list`
 * answered.
 */
@kotlinx.serialization.Serializable
data class AcpSessionOutput(
    val ok: Boolean,
    /** the opaque reference of the session this scope ran; minted per connection (epoch). */
    val sessionRef: String = "",
    val turns: List<AcpTurnOutput> = emptyList(),
    val historicEvents: List<String> = emptyList(),
    val sessionsListed: List<String> = emptyList(),
    val appliedMode: String = "",
    val appliedConfig: List<String> = emptyList(),
    val logoutOk: Boolean = false,
    /** one of [AcpSessionOutcome.VOCABULARY], always set — ok=false with an empty outcome is a bug. */
    val outcome: String = "",
    /** one of [AcpRefusal.kindToken] when [ok] is false; empty when it is true. */
    val failureKind: String = "",
    val detail: String = "",
)

/**
 * The outcome vocabulary of the session step, beside the task step's (AC#4's typed branch
 * points). Total over [AcpRefusal]: a refusal kind without a home here is a compile error,
 * so nothing can land unclassified at the durable boundary.
 */
object AcpSessionOutcome {
    const val COMPLETED = "COMPLETED"
    const val TURN_CANCELLED = "TURN_CANCELLED"
    const val CAPABILITY_MISSING = "CAPABILITY_MISSING"
    const val SESSION_REFUSED = "SESSION_REFUSED"
    const val TRANSPORT_FAILURE = "TRANSPORT_FAILURE"
    const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    const val UNKNOWN = "UNKNOWN"

    val VOCABULARY: Set<String> = setOf(
        COMPLETED, TURN_CANCELLED, CAPABILITY_MISSING, SESSION_REFUSED,
        TRANSPORT_FAILURE, BUDGET_EXCEEDED, UNKNOWN,
    )

    /** Classification token the output carries for a refusal of THIS step kind. */
    fun of(refusal: AcpRefusal): String = when (refusal) {
        is AcpRefusal.MissingCapability, is AcpRefusal.SessionCapabilityMissing -> CAPABILITY_MISSING
        is AcpRefusal.ForeignSessionRef, is AcpRefusal.ConfigInvalid,
        is AcpRefusal.ActivePromptInProgress, is AcpRefusal.SessionOperationFailed,
        -> SESSION_REFUSED
        is AcpRefusal.AgentTimeout -> BUDGET_EXCEEDED
        is AcpRefusal.VersionMismatch -> SESSION_REFUSED
        is AcpRefusal.ProfileRefusal -> SESSION_REFUSED
        is AcpRefusal.TransportClosed, is AcpRefusal.TransportFailure,
        is AcpRefusal.AgentExited, is AcpRefusal.FramingError, is AcpRefusal.AgentUnreachable,
        -> TRANSPORT_FAILURE
        is AcpRefusal.AgentRefusedRequest -> SESSION_REFUSED
        is AcpRefusal.CredentialSeamAbsent, is AcpRefusal.CredentialNotResolved,
        is AcpRefusal.PluginInternal, -> UNKNOWN
    }

    /**
     * The exchange-shaped answer mapped into output. A turn that ENDED with CANCELLED keeps
     * ok = true: the reply is real durable content and the stop reason says what it is worth.
     */
    fun toOutput(
        refusal: AcpRefusal?,
        turns: List<AcpTurnOutput> = emptyList(),
        sessionRef: String = "",
        historicEvents: List<String> = emptyList(),
        sessionsListed: List<String> = emptyList(),
        appliedMode: String = "",
        appliedConfig: List<String> = emptyList(),
        logoutOk: Boolean = false,
    ): AcpSessionOutput = when {
        refusal == null -> AcpSessionOutput(
            ok = true,
            sessionRef = sessionRef,
            turns = turns,
            historicEvents = historicEvents,
            sessionsListed = sessionsListed,
            appliedMode = appliedMode,
            appliedConfig = appliedConfig,
            logoutOk = logoutOk,
            outcome = COMPLETED,
        )
        else -> AcpSessionOutput(
            ok = false,
            sessionRef = sessionRef,
            turns = turns,
            historicEvents = historicEvents,
            sessionsListed = sessionsListed,
            outcome = of(refusal),
            failureKind = refusal.kindToken,
            detail = refusal.detail,
        )
    }
}
