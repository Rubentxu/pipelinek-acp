package pipelinek.acp

/**
 * What the plugin knows about one tool call at a point in time.
 *
 * The fields are the MERGED view: a partial update that carries only a status keeps the title
 * and the kind the earlier update gave the call.
 */
data class AcpToolCallState(
    val toolCallId: String,
    val title: String = "",
    val status: String = "",
    val toolKind: String = "",
    /** whether the call already reached a terminal state. */
    val completed: Boolean = false,
)

/**
 * The plugin's view of an ACP session, folded from what the agent said.
 *
 * It is the plugin's own state, in process: it is what a reaction declared over these facts
 * will read to answer "where is the session now", and it is NOT published. A published
 * snapshot would be a second copy of the stream inside the stream.
 */
data class AcpSessionState(
    val exchangeRef: String = "",
    /** everything the agent said, in order. */
    val message: String = "",
    val toolCalls: List<AcpToolCallState> = emptyList(),
    val plan: List<AcpPlanEntry> = emptyList(),
    val modeId: String = "",
    val used: Long = 0,
    val size: Long = 0,
    /** how many tool calls reached a terminal state — one per call, however often repeated. */
    val completions: Int = 0,
)

/**
 * The reducer: the observations of a session, folded into the session's state and into the
 * facts that are worth publishing.
 *
 * ## What the reducer decides
 *
 * The decoder normalizes; the reducer INTERPRETS. Two decisions live here and nowhere else:
 *
 * - **a partial update never erases what is known.** A tool call update that carries only a
 *   status merges field by field into the state the earlier updates built, so the published
 *   fact says what the call IS, not only what this update happened to mention;
 * - **the logical completion is published once.** An agent that repeats the completion of a
 *   call produces no second completion fact: the completion is a fact about the call, and a
 *   fact does not happen twice.
 *
 * The reducer performs no effect: it reads observations and returns observations. What happens
 * to them is the publisher's business.
 */
internal class AcpObservationReducer(private val exchangeRef: () -> String) {

    private val toolCalls = LinkedHashMap<String, AcpToolCallState>()
    private val message = StringBuilder()
    private var plan: List<AcpPlanEntry> = emptyList()
    private var modeId: String = ""
    private var used: Long = 0
    private var size: Long = 0
    private var completions: Int = 0

    /** The session's state as the reducer knows it. */
    val state: AcpSessionState
        get() = AcpSessionState(
            exchangeRef = exchangeRef(),
            message = message.toString(),
            toolCalls = toolCalls.values.toList(),
            plan = plan,
            modeId = modeId,
            used = used,
            size = size,
            completions = completions,
        )

    /**
     * Folds one observation and returns the facts to publish.
     *
     * An observation that changes nothing returns nothing: the stream carries what happened,
     * not what was repeated.
     */
    fun apply(observation: AcpObservation): List<AcpObservation> = when (observation) {
        is AcpObservation.AgentMessageChunk -> {
            message.append(observation.text)
            listOf(observation)
        }

        is AcpObservation.AgentThoughtChunk -> listOf(observation)

        is AcpObservation.UserMessageChunk -> listOf(observation)

        is AcpObservation.ToolCall -> {
            toolCalls[observation.toolCallId] = AcpToolCallState(
                toolCallId = observation.toolCallId,
                title = observation.title,
                status = observation.status,
                toolKind = observation.toolKind,
                completed = isTerminal(observation.status),
            )
            listOf(observation)
        }

        is AcpObservation.ToolCallChanged -> {
            val previous = toolCalls[observation.toolCallId]
            val merged = merge(previous, observation)
            if (previous != null && merged == previous) {
                emptyList()
            } else {
                toolCalls[observation.toolCallId] = merged
                val changed = observation.copy(
                    title = merged.title,
                    status = merged.status,
                    toolKind = merged.toolKind,
                )
                if (isTerminal(merged.status) && previous?.completed != true) {
                    completions++
                    // Both facts, in the order the state moved: the change carries what the call
                    // IS now and the completion says the call will not move again. Publishing
                    // only the completion would leave the stream unable to reproduce this state
                    // on replay — the reader would see a call that ended without ever knowing
                    // what it was.
                    listOf(changed, AcpObservation.ToolCallCompleted(
                        exchangeRef = exchangeRef(),
                        evidence = observation.evidence,
                        toolCallId = merged.toolCallId,
                        status = merged.status,
                    ))
                } else {
                    listOf(changed)
                }
            }
        }

        // The reducer never produces a completion itself: it produces it FROM a change. A
        // completion that arrives as an observation is already published, and folding it again
        // would count a second completion for a call the reducer already counted.
        is AcpObservation.ToolCallCompleted -> listOf(observation)

        is AcpObservation.Plan -> {
            plan = observation.entries
            listOf(observation)
        }

        is AcpObservation.PlanRemoved -> {
            plan = emptyList()
            listOf(observation)
        }

        is AcpObservation.Usage -> {
            used = observation.used
            size = observation.size
            listOf(observation)
        }

        is AcpObservation.ModeChanged -> {
            modeId = observation.modeId
            listOf(observation)
        }

        is AcpObservation.CommandsChanged -> listOf(observation)

        is AcpObservation.ConfigurationChanged -> listOf(observation)

        is AcpObservation.SessionInfoChanged -> listOf(observation)

        is AcpObservation.UnknownUpdate -> listOf(observation)

        is AcpObservation.ExchangeStarted -> listOf(observation)

        is AcpObservation.TurnEnded -> listOf(observation)

        is AcpObservation.ExchangeCancelled -> listOf(observation)

        is AcpObservation.ExchangeRefused -> listOf(observation)

        is AcpObservation.ObservationGap -> listOf(observation)
    }

    /**
     * The merged state of a tool call.
     *
     * A field the update does not carry — an empty one — keeps the value the earlier update
     * gave it. That is what makes a partial update safe: the agent may report only the new
     * status of a call whose title it said once, and the fact still names the call.
     */
    private fun merge(previous: AcpToolCallState?, update: AcpObservation.ToolCallChanged): AcpToolCallState {
        val title = update.title.ifEmpty { previous?.title ?: "" }
        val status = update.status.ifEmpty { previous?.status ?: "" }
        val toolKind = update.toolKind.ifEmpty { previous?.toolKind ?: "" }
        return AcpToolCallState(
            toolCallId = update.toolCallId,
            title = title,
            status = status,
            toolKind = toolKind,
            completed = previous?.completed == true || isTerminal(status),
        )
    }

    /** Whether a tool call status is an end: the call will not move again. */
    private fun isTerminal(status: String): Boolean =
        status == TERMINAL_COMPLETED || status == TERMINAL_FAILED

    private companion object {
        const val TERMINAL_COMPLETED = "COMPLETED"
        const val TERMINAL_FAILED = "FAILED"
    }
}
