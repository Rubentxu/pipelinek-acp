package pipelinek.acp

/**
 * What the client asked the agent to do, in the plugin's own vocabulary.
 *
 * The handler decodes this from the encoded input the DSL lowered into the registry; it is the
 * plugin's payload, not a wire type — the wire types belong to the ACP SDK and never leave the
 * connection boundary.
 */
@kotlinx.serialization.Serializable
data class AcpPromptInput(
    /** argv of the agent to launch; the first entry is the executable. Never a shell string. */
    val command: List<String>,
    /** the text handed to the agent as the prompt's single text block. */
    val prompt: String,
    /** working directory advertised to the agent for the session. Empty means "the plugin's own". */
    val cwd: String = "",
    /**
     * Total budget in seconds for handshake, session creation and the whole prompt turn.
     * A generous default because an agent turn is agentic work, not an RPC; the bound is what
     * keeps a hung agent from parking the step forever.
     */
    val timeoutSeconds: Long = 300,
)

/**
 * What came back from the agent, always paired with [AcpPromptOutput.ok].
 *
 * [stopReason] is the ACP vocabulary echoed as text: the output codec must not depend on SDK
 * classes, because the encoded output is durable state that outlives the artifact that wrote it.
 */
@kotlinx.serialization.Serializable
data class AcpPromptOutput(
    val ok: Boolean,
    val reply: String = "",
    val stopReason: String = "",
    /** one of [AcpRefusal.kindToken] when [ok] is false; empty when it is true. */
    val failureKind: String = "",
    val detail: String = "",
)

/**
 * Why an ACP exchange did not complete, in typed form.
 *
 * Every refusal maps to exactly one [kindToken], and the token is what the step output carries
 * and the gate asserts on. A refusal is never reported as success: the handler returns one of
 * these instead of throwing, because the published handler contract says a step failure is a
 * typed result and a thrown [dev.rubentxu.pipeline.v2.domain.PluginStepException] would be
 * re-classified as `ENGINE` by the execution boundary, losing the declared kind.
 */
sealed class AcpRefusal {
    /** Classification token carried in [AcpPromptOutput.failureKind]. */
    abstract val kindToken: String
    abstract val detail: String

    /** The handshake could not agree a protocol version with the agent. */
    data class VersionMismatch(
        val requested: Int,
        val offered: Int,
        val supported: Set<Int>,
    ) : AcpRefusal() {
        override val kindToken: String = "ACP_VERSION_MISMATCH"
        override val detail: String =
            "agent offered protocol version $offered; this client requested $requested and " +
                "supports $supported"
    }

    /**
     * The agent answered with a version both sides can speak but without a capability the
     * declared prompt needs (text or image input), so the prompt could not be sent as declared.
     */
    data class MissingCapability(val capability: String) : AcpRefusal() {
        override val kindToken: String = "ACP_CAPABILITY_MISSING"
        override val detail: String =
            "the agent negotiated the connection without the '$capability' prompt capability " +
                "this step requires"
    }

    /** The transport died before a response arrived (EOF, or a write into a dead pipe). */
    data class TransportClosed(val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_TRANSPORT_CLOSED"
        override val detail: String = "the transport closed before an answer arrived: $reason"
    }

    /** A line arrived that is not a JSON-RPC message: the stream is not ACP traffic. */
    data class FramingError(val line: String) : AcpRefusal() {
        override val kindToken: String = "ACP_FRAMING_ERROR"
        override val detail: String = "the agent wrote a line that is not a JSON-RPC message: '${line.take(200)}'"
    }

    /** The agent process exited. Its exit code and the tail of its stderr are what they are. */
    data class AgentExited(val exitCode: Int, val stderrTail: String) : AcpRefusal() {
        override val kindToken: String = "ACP_AGENT_EXITED"
        override val detail: String =
            "the agent process exited with code $exitCode before answering; stderr tail: " +
                "'${stderrTail.take(2000)}'"
    }

    /** The agent could not even be started (missing executable, denied permission). */
    data class AgentUnreachable(val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_AGENT_UNREACHABLE"
        override val detail: String = "the agent process could not be launched: $reason"
    }

    /** The budget ran out. A hung agent is a refusal, never a success by silence. */
    data class AgentTimeout(val timeoutSeconds: Long) : AcpRefusal() {
        override val kindToken: String = "ACP_AGENT_TIMEOUT"
        override val detail: String = "the agent did not answer within $timeoutSeconds s"
    }

    /** The agent answered a request with a JSON-RPC error. */
    data class AgentRefusedRequest(val method: String, val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_AGENT_REFUSED_REQUEST"
        override val detail: String = "the agent refused '$method': $reason"
    }

    /** An error the SDK surfaced from the transport, not classifiable as any of the above. */
    data class TransportFailure(val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_TRANSPORT_FAILURE"
        override val detail: String = "the transport reported an error: $reason"
    }

    /**
     * The delegated task's profile could not be honoured by this target (PKH-133 AC#1/AC#4).
     *
     * Said as a refusal BEFORE the agent is launched: a promise about confinement, argv or
     * budget that the target cannot keep is not negotiable inside the exchange — the honest
     * answer is to start nothing and name what cannot be attested.
     */
    data class ProfileRefusal(val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_PROFILE_REFUSED"
        override val detail: String = "the delegation profile was refused before launch: $reason"
    }

    /**
     * The step declared a credential reference but the host does not offer the credential
     * seam to plugins (PKH-133 AC#1).
     *
     * `credentials.basic` IS provisioned by the running host, but its contract type is not in
     * the published SDK bundle, so the plugin reaches it structurally and fails CLOSED when
     * the seam is missing rather than launching an uncredentialed agent and pretending.
     */
    data class CredentialSeamAbsent(val credentialRef: String, val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_CREDENTIAL_SEAM_ABSENT"
        override val detail: String =
            "credential '$credentialRef' was requested but the host seam is absent: $reason"
    }

    /**
     * The credential seam answered, and the answer was not a usable secret (PKH-133 AC#1).
     *
     * The [reason] is the CLOSED case name the seam produced (`NotFound`, `StoreUnavailable`,
     * ...). Secret material never reaches this type; neither the username nor the password is
     * ever interpolated here, so the durable detail line cannot carry one by accident.
     */
    data class CredentialNotResolved(val credentialRef: String, val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_CREDENTIAL_NOT_RESOLVED"
        override val detail: String =
            "credential '$credentialRef' did not resolve: $reason"
    }

    /** Our own machinery broke. Said apart from the agent's misbehaviour on purpose. */
    data class PluginInternal(val reason: String) : AcpRefusal() {
        override val kindToken: String = "ACP_PLUGIN_INTERNAL"
        override val detail: String = "the ACP plugin failed internally: $reason"
    }
}

/** An exchange either completed with a reply, or it is refused with a typed reason. */
sealed class AcpExchange {
    data class Completed(val reply: String, val stopReason: String) : AcpExchange()
    data class Refused(val refusal: AcpRefusal) : AcpExchange()
}
