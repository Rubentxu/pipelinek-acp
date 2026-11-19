package pipelinek.acp

/**
 * The isolation profile an `acpTask` delegation runs under (PKH-133).
 *
 * The profile is a DECLARATION of intent validated against what the execution target can
 * prove — it is never a request the plugin grants itself. A profile whose promise the host
 * cannot back is refused before the agent is launched ([AcpRefusal.ProfileRefusal]); that
 * refusal is the whole security story of this enum: promises are cheap, attestations are not.
 */
@kotlinx.serialization.Serializable
enum class AcpTaskProfile {
    /**
     * The step itself writes nothing and the agent's permission requests are denied at the
     * protocol level ([DenyingOperations]). This is the profile the published seam can honour:
     * it claims no OS-level confinement, only the protocol-level refusal plus the plugin's own
     * stream rules.
     */
    READONLY,

    /**
     * The delegation is expected to mutate inside a confined workspace. Confinement is an
     * OS fact the plugin cannot produce from inside a step without becoming a SECOND isolation
     * authority outside the host's policy — and the published SDK 0.98.0 offers no attestation
     * seam to plugins (`IsolationProvider` is internal to pipeline-application). A target that
     * cannot attest effective confinement therefore REFUSES this profile, which is exactly what
     * AC#4 asks for: a promise the target cannot keep is a refusal, not a silent downgrade.
     */
    WRITE_RESTRICTED,
}

/**
 * What the run asks `acpTask` to delegate: argv, prompt, profile, relative cwd, absolute
 * budget, and credentials BY REFERENCE.
 *
 * [cwd] is relative to the execution lease root the host supplies through the published
 * `runtime.execution-location` capability — an absolute path in the DSL would be a claim to
 * reach outside the run's own directory tree, and claims like that are not what the profile
 * negotiates. [credentialRef] is a NAME resolved through the host's credential seam; the
 * secret itself never appears in this type, in the encoded input, or in any log line (AC#1).
 * [budgetSeconds] is ABSOLUTE: it bounds launch, the whole prompt turn and the cleanup, so a
 * hung agent cannot park the step past its deadline (AC#2).
 */
@kotlinx.serialization.Serializable
data class AcpTaskInput(
    /** argv of the agent to launch; the first entry is the executable. Never a shell string. */
    val command: List<String>,
    /** the text handed to the agent as the prompt's single text block. */
    val prompt: String,
    /** the isolation profile this delegation runs under. */
    val profile: AcpTaskProfile = AcpTaskProfile.READONLY,
    /** working directory for the agent, relative to the execution lease root. Empty means the root. */
    val cwd: String = "",
    /** absolute budget in seconds covering launch, the prompt turn and the process cleanup. */
    val budgetSeconds: Long = 300,
    /** name of a host credential the profile may resolve; never secret material itself. */
    val credentialRef: String = "",
    /**
     * The permission authority this delegation declares (PKH-134 AC#1). Null means NO
     * authority: every reverse request is denied — deny-by-default is preserved as the
     * absence, never as a value someone can widen by forgetting to set it.
     */
    val authority: AcpAuthority? = null,
)

/**
 * What one `acpTask` delegation produced, typed at the PipelineK boundary.
 *
 * [outcome] is the closed vocabulary of [AcpTaskOutcome] — stop reason, transport failure,
 * cancellation, budget and Unknown are said apart, and none of them means "the business
 * answer is correct" (AC#3): this type reports what the EXCHANGE did, never what the agent's
 * reply is worth. [failureKind] keeps the precise [AcpRefusal.kindToken] for the gate that
 * asserts on a specific cause while [outcome] serves the branch that only needs the class.
 */
@kotlinx.serialization.Serializable
data class AcpTaskOutput(
    val ok: Boolean,
    val reply: String = "",
    /** the ACP stop reason echoed as text when ok is true (END_TURN, MAX_TOKENS, ...). */
    val stopReason: String = "",
    /** one of [AcpTaskOutcome.VOCABULARY], always set — ok=false with an empty outcome is a bug. */
    val outcome: String = "",
    /** the [AcpRefusal.kindToken] behind [outcome] when ok is false; empty when it is true. */
    val failureKind: String = "",
    val detail: String = "",
    /**
     * The bounded typed journal of the reverse leg (PKH-134): what the agent asked the
     * client for and how the declared authority answered. Durable, capped by
     * [AcpReverseJournal.MAX_EVENTS] at the source — an agent looping requests cannot
     * widen the step's output past it.
     */
    val reverseEvents: List<String> = emptyList(),
)

/**
 * The closed outcome vocabulary of [AcpTaskOutput.outcome] and the one mapping from
 * [AcpExchange] into it.
 *
 * The classes are what AC#3 enumerates: a stop reason travels with COMPLETED, a transport
 * failure, a cancellation, a budget exhaustion, a profile refusal and the handshake refusals
 * are each said apart, and anything not classifiable lands on UNKNOWN rather than silently
 * joining a class it does not belong to. The mapping is total: every refusal of the sealed
 * [AcpRefusal] hierarchy has exactly one home here, so a new refusal kind is a compile
 * error until somebody decides which outcome it deserves.
 */
object AcpTaskOutcome {
    const val COMPLETED = "COMPLETED"
    const val TRANSPORT_FAILURE = "TRANSPORT_FAILURE"
    const val CANCELLED = "CANCELLED"
    const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    const val PROFILE_REFUSED = "PROFILE_REFUSED"
    const val HANDSHAKE_REFUSED = "HANDSHAKE_REFUSED"
    const val UNKNOWN = "UNKNOWN"

    val VOCABULARY: Set<String> = setOf(
        COMPLETED, TRANSPORT_FAILURE, CANCELLED, BUDGET_EXCEEDED,
        PROFILE_REFUSED, HANDSHAKE_REFUSED, UNKNOWN,
    )

    /** The outcome a refused exchange deserves; total over the sealed refusal hierarchy. */
    fun of(refusal: AcpRefusal): String = when (refusal) {
        is AcpRefusal.AgentTimeout -> BUDGET_EXCEEDED
        is AcpRefusal.ProfileRefusal -> PROFILE_REFUSED
        is AcpRefusal.VersionMismatch -> HANDSHAKE_REFUSED
        is AcpRefusal.MissingCapability -> HANDSHAKE_REFUSED
        is AcpRefusal.AgentRefusedRequest -> HANDSHAKE_REFUSED
        is AcpRefusal.TransportClosed -> TRANSPORT_FAILURE
        is AcpRefusal.TransportFailure -> TRANSPORT_FAILURE
        is AcpRefusal.AgentExited -> TRANSPORT_FAILURE
        is AcpRefusal.FramingError -> TRANSPORT_FAILURE
        is AcpRefusal.AgentUnreachable -> TRANSPORT_FAILURE
        // The credential seam is the host's machinery, not the delegation's: when it is
        // absent or answers "no", nothing about the agent explains it — UNKNOWN is honest.
        is AcpRefusal.CredentialSeamAbsent -> UNKNOWN
        is AcpRefusal.CredentialNotResolved -> UNKNOWN
        is AcpRefusal.PluginInternal -> UNKNOWN
    }

    /**
     * The exchange-shaped answer mapped into output; Completed always carries its stop reason.
     *
     * A turn that ENDED with CANCELLED is AC#3's cancellation class even though the exchange
     * technically completed: the agent answered, and what it answered with is that its own
     * work was cancelled. Collapsing it into COMPLETED would make "the user withdrew the run"
     * and "the agent finished the turn" one branch — the merge this vocabulary exists to
     * prevent. ok stays true: the reply is real durable content, whatever it is worth.
     */
    fun toOutput(exchange: AcpExchange): AcpTaskOutput = when (exchange) {
        is AcpExchange.Completed ->
            AcpTaskOutput(
                ok = true,
                reply = exchange.reply,
                stopReason = exchange.stopReason,
                outcome = if (exchange.stopReason == "CANCELLED") CANCELLED else COMPLETED,
                reverseEvents = exchange.reverseEvents,
            )
        is AcpExchange.Refused ->
            AcpTaskOutput(
                ok = false,
                outcome = of(exchange.refusal),
                failureKind = exchange.refusal.kindToken,
                detail = exchange.refusal.detail,
            )
    }
}
