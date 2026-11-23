package pipelinek.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The tokens of the facts this plugin publishes, and the kinds they travel under.
 *
 * A token names ONE fact; the kind is the token namespaced under the plugin's identity, and it
 * is the ONLY thing a consumer that does not link this plugin's codec can read: the payload of
 * a plugin event is opaque at rest by contract, so the kind IS the observable vocabulary. One
 * kind per token — not one kind with a payload that has to be decoded to be understood — is what
 * makes the stream self-describing for the console, for the socket API, and for the selector
 * the reaction engine will declare over these facts.
 */
object AcpFactToken {

    /** The namespace every kind of this plugin lives under. */
    const val KIND_PREFIX: String = "pipelinek.acp."

    const val AGENT_MESSAGE_CHUNK: String = "agent_message_chunk"
    const val AGENT_THOUGHT_CHUNK: String = "agent_thought_chunk"
    const val USER_MESSAGE_CHUNK: String = "user_message_chunk"
    const val TOOL_CALL: String = "tool_call"
    const val TOOL_CALL_CHANGED: String = "tool_call_changed"
    const val TOOL_CALL_COMPLETED: String = "tool_call_completed"
    const val PLAN: String = "plan"
    const val PLAN_REMOVED: String = "plan_removed"
    const val USAGE: String = "usage"
    const val MODE_CHANGED: String = "mode_changed"
    const val COMMANDS_CHANGED: String = "commands_changed"
    const val CONFIGURATION_CHANGED: String = "configuration_changed"
    const val SESSION_INFO_CHANGED: String = "session_info_changed"
    const val UNKNOWN_UPDATE: String = "unknown_update"
    const val EXCHANGE_STARTED: String = "exchange_started"
    const val TURN_ENDED: String = "turn_ended"
    const val EXCHANGE_CANCELLED: String = "exchange_cancelled"
    const val EXCHANGE_REFUSED: String = "exchange_refused"
    const val OBSERVATION_GAP: String = "observation_gap"

    /** The kind a fact of this token travels under. */
    fun kindOf(token: String): String = KIND_PREFIX + token
}

/**
 * How much a published fact rests on.
 *
 * This is NOT the SDK's `ProviderProvenance`, which names WHO emitted an event (publisher,
 * namespace, release). This axis answers a different question: how much of the fact is the
 * agent's own word, how much is something the plugin merely witnessed, and how much is the
 * plugin's own doing. The two never mix, and the name says so.
 */
enum class AcpEvidence {
    /**
     * The agent asserted the CONTENT: the text it wrote, the title it gave a tool call, the
     * status it reported, the plan it published. The plugin relays it and vouches for nothing.
     */
    AGENT_CLAIM,

    /**
     * The plugin WITNESSED the fact without being able to interpret it: an update arrived whose
     * variant this plugin does not know. What was seen is a fact; what it meant is not.
     */
    OBSERVED,

    /**
     * The plugin established the fact ITSELF: the exchange it started, the turn that ended, the
     * cancellation it asked for, the losses it counted. Nothing here is the agent's word.
     */
    VERIFIED,
}

/**
 * The lane a fact travels in, which decides what happens to it when the flow outruns the
 * reader.
 *
 * The three lanes are the ones the ACP traffic has: control traffic that must never be lost
 * because it says what the client is doing, durable facts that describe what the agent did, and
 * telemetry that is only a stream of text and may be coalesced away.
 */
enum class AcpLane {
    /** Never dropped: the facts that say what the exchange itself did. */
    CONTROL,

    /** Durable facts about what the agent did. */
    REACTIVE,

    /** Coalescible stream of text. */
    TELEMETRY,
}

/**
 * One published fact about an ACP session.
 *
 * This is the plugin's OWN vocabulary: it names no class of the ACP SDK, because the fact
 * outlives the artifact that wrote it — the durable run log, the socket API and any consumer
 * that reads them must be able to read it without the SDK on their classpath.
 *
 * The type is sealed on purpose: a fact is either one of the ones this plugin publishes or it
 * does not exist. Nothing in this type grants authority — there is no member that approves a
 * permission, resolves a tool call or mutates a session, because an observation is a report and
 * a report is not an order.
 */
@Serializable
sealed class AcpObservation {

    /** The token this fact travels under; it names the kind and the serial name. */
    abstract val token: String

    /** The opaque identity of the session this fact belongs to. */
    abstract val exchangeRef: String

    /** How much the fact rests on. */
    abstract val evidence: AcpEvidence

    /** The agent said this. */
    @Serializable
    @SerialName(AcpFactToken.AGENT_MESSAGE_CHUNK)
    data class AgentMessageChunk(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val text: String,
        val messageId: String = "",
        val content: String = "",
        val truncated: Boolean = false,
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.AGENT_MESSAGE_CHUNK
    }

    /** The agent said this while thinking. */
    @Serializable
    @SerialName(AcpFactToken.AGENT_THOUGHT_CHUNK)
    data class AgentThoughtChunk(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val text: String,
        val messageId: String = "",
        val content: String = "",
        val truncated: Boolean = false,
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.AGENT_THOUGHT_CHUNK
    }

    /** The user said this (the agent echoing a prompt back). */
    @Serializable
    @SerialName(AcpFactToken.USER_MESSAGE_CHUNK)
    data class UserMessageChunk(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val text: String,
        val messageId: String = "",
        val content: String = "",
        val truncated: Boolean = false,
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.USER_MESSAGE_CHUNK
    }

    /** The agent announced a tool call. */
    @Serializable
    @SerialName(AcpFactToken.TOOL_CALL)
    data class ToolCall(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val toolCallId: String,
        val title: String = "",
        val status: String = "",
        val toolKind: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.TOOL_CALL
    }

    /**
     * The agent changed a tool call it had announced.
     *
     * The fields are the MERGED view: a field this update did not carry keeps the value the
     * earlier update gave it, so a partial update never erases what was already known.
     */
    @Serializable
    @SerialName(AcpFactToken.TOOL_CALL_CHANGED)
    data class ToolCallChanged(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val toolCallId: String,
        val title: String = "",
        val status: String = "",
        val toolKind: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.TOOL_CALL_CHANGED
    }

    /**
     * The agent brought a tool call to its end — published ONCE per call, however many times
     * the agent repeats the completion.
     */
    @Serializable
    @SerialName(AcpFactToken.TOOL_CALL_COMPLETED)
    data class ToolCallCompleted(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val toolCallId: String,
        val status: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.TOOL_CALL_COMPLETED
    }

    /** The agent published a plan. */
    @Serializable
    @SerialName(AcpFactToken.PLAN)
    data class Plan(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val entries: List<AcpPlanEntry> = emptyList(),
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.PLAN
    }

    /** The agent withdrew a plan. */
    @Serializable
    @SerialName(AcpFactToken.PLAN_REMOVED)
    data class PlanRemoved(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val planId: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.PLAN_REMOVED
    }

    /** The agent reported what the session cost. */
    @Serializable
    @SerialName(AcpFactToken.USAGE)
    data class Usage(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val used: Long = 0,
        val size: Long = 0,
        val cost: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.USAGE
    }

    /** The session changed mode. */
    @Serializable
    @SerialName(AcpFactToken.MODE_CHANGED)
    data class ModeChanged(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val modeId: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.MODE_CHANGED
    }

    /** The session changed the commands it offers. */
    @Serializable
    @SerialName(AcpFactToken.COMMANDS_CHANGED)
    data class CommandsChanged(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val commands: List<AcpCommand> = emptyList(),
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.COMMANDS_CHANGED
    }

    /** The session changed its configuration. */
    @Serializable
    @SerialName(AcpFactToken.CONFIGURATION_CHANGED)
    data class ConfigurationChanged(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val options: List<AcpConfigOption> = emptyList(),
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.CONFIGURATION_CHANGED
    }

    /** The session's own description changed. */
    @Serializable
    @SerialName(AcpFactToken.SESSION_INFO_CHANGED)
    data class SessionInfoChanged(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val title: String = "",
        val updatedAt: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.SESSION_INFO_CHANGED
    }

    /**
     * An update arrived whose variant this plugin does not know.
     *
     * It carries the DISCRIMINATOR and nothing else: the raw JSON of an unknown variant is
     * exactly the place where an agent could hide anything, so it is never read.
     */
    @Serializable
    @SerialName(AcpFactToken.UNKNOWN_UPDATE)
    data class UnknownUpdate(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val discriminator: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.UNKNOWN_UPDATE
    }

    /** The exchange began. */
    @Serializable
    @SerialName(AcpFactToken.EXCHANGE_STARTED)
    data class ExchangeStarted(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.EXCHANGE_STARTED
    }

    /** A prompt turn ended with the reason the agent gave. */
    @Serializable
    @SerialName(AcpFactToken.TURN_ENDED)
    data class TurnEnded(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val turnIndex: Int = 0,
        val stopReason: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.TURN_ENDED
    }

    /** The exchange was cancelled before it finished. */
    @Serializable
    @SerialName(AcpFactToken.EXCHANGE_CANCELLED)
    data class ExchangeCancelled(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val reason: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.EXCHANGE_CANCELLED
    }

    /**
     * The exchange ended in a refusal.
     *
     * It carries the refusal's CLASSIFICATION and not its detail: the detail of a refusal may
     * quote the agent's stderr, and the stderr is not a fact about the session.
     */
    @Serializable
    @SerialName(AcpFactToken.EXCHANGE_REFUSED)
    data class ExchangeRefused(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        val failureKind: String = "",
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.EXCHANGE_REFUSED
    }

    /**
     * What the flow lost on the way here.
     *
     * A gap is a fact about the run, not a statistic about the plugin: it says that what the
     * reader sees is not everything the agent said. It is published in the same stream as the
     * facts it qualifies, so a reader can tell "the agent was silent" from "we dropped what it
     * said" without leaving the stream.
     */
    @Serializable
    @SerialName(AcpFactToken.OBSERVATION_GAP)
    data class ObservationGap(
        override val exchangeRef: String,
        override val evidence: AcpEvidence,
        /** how many facts were dropped, coalesced away or refused outright */
        val lost: Int = 0,
        /** how many facts were merged into another one to stay inside the lane */
        val coalesced: Int = 0,
        /** how many facts the emitter refused to publish */
        val unpublished: Int = 0,
        /** the token of every fact that was lost, with how many of it */
        val lostByToken: Map<String, Int> = emptyMap(),
    ) : AcpObservation() {
        override val token: String get() = AcpFactToken.OBSERVATION_GAP
    }
}

/** One entry of an agent's plan. */
@Serializable
data class AcpPlanEntry(
    val content: String = "",
    val priority: String = "",
    val status: String = "",
)

/** One command the session offers. */
@Serializable
data class AcpCommand(
    val name: String = "",
    val description: String = "",
)

/** One configuration option of the session, as text. */
@Serializable
data class AcpConfigOption(
    val id: String = "",
    val name: String = "",
    val currentValue: String = "",
)
