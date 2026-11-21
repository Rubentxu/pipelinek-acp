package pipelinek.acp

import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationContentValue
import dev.rubentxu.pipeline.v2.runtime.input.InputDecision
import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import dev.rubentxu.pipeline.v2.runtime.input.InputDenialReason
import dev.rubentxu.pipeline.v2.runtime.input.InputRequest
import dev.rubentxu.pipeline.v2.runtime.input.InputResolution
import kotlinx.coroutines.CancellationException

/**
 * The plugin's ASKING side of the human-decision seam: it publishes a question through the
 * port the runtime binds and reports what the person answered.
 *
 * ## What this is
 * The agent asks the client for a person's input; this class asks the PERSON, through the
 * port, and translates the answer into the protocol's own vocabulary. The question travels
 * the runtime's channel, so the service client sees it as a pending question and the
 * person's answer comes back through the very port the plugin declared.
 *
 * ## What this is NOT
 * It is NOT an authorization surface. The answer travels to the AGENT as the answer to the
 * agent's own question, and it grants nothing: a permission is still decided by the
 * authority the pipeline declared, and the port's submitter is attribution, never
 * authorization. A plugin that turned an answer into a grant would open the second way of
 * authorizing that the port exists to close.
 *
 * ## Why the port and not the published service client
 * The bundle also publishes the client that ANSWERS a question: it lists the pending ones
 * and answers them. The plugin does not use it to ask. The runtime owns the identity of
 * the question (the operation it is filed under) and owns the admission of the capability
 * that carries it, so a plugin that published its question through the client would file
 * it outside the run's own operation.
 */
class AcpHumanDecision(private val decisions: InputDecisions) {

    /**
     * Asks [question] and waits for the person's answer.
     *
     * @param waitMillis how long the person may still take, or `null` to wait until the
     *   caller is cancelled. The bound is the caller's: this class does not know the turn's
     *   budget and does not compute a second one.
     */
    suspend fun ask(question: String, waitMillis: Long?): AcpHumanAnswer =
        when (val resolution = await(question, waitMillis)) {
            is InputResolution.Answered -> when (val decision = resolution.decision) {
                is InputDecision.Proceed -> AcpHumanAnswer(
                    action = ElicitationAction.Accept(valuesOf(decision)),
                    detail = "answered (proceed)",
                )
                is InputDecision.Abort -> AcpHumanAnswer(
                    action = ElicitationAction.Decline,
                    detail = "answered (abort)",
                )
            }
            is InputResolution.Denied -> AcpHumanAnswer(
                action = ElicitationAction.Cancel,
                detail = "unanswered (${resolution.reason})",
            )
        }

    /**
     * The one call to the port, with the failure shapes folded into the port's own
     * vocabulary.
     *
     * A published question lives on disk, and a question that could not be published is a
     * question nobody can answer: the port reports that as an exception, and this class
     * turns it into the denial the port itself would have returned. Cancellation is NOT
     * folded: a cancelled caller cannot receive a value, so the signal travels up.
     */
    private suspend fun await(question: String, waitMillis: Long?): InputResolution =
        try {
            decisions.awaitDecision(
                request = InputRequest(
                    message = question,
                    // The label of the affirmative answer, in the port's own vocabulary.
                    ok = PROCEED_LABEL,
                    // Attribution of who is expected to answer. A headless runner has no
                    // user database to name, and the field is attribution and not
                    // authorization either way.
                    submitter = null,
                    // The correlation id is the author's to supply, and an agent's
                    // elicitation carries none.
                    id = null,
                ),
                waitMillis = waitMillis,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InputResolution.Denied(
                InputDenialReason.Unanswerable(e.message ?: "the question could not be asked"),
            )
        }

    /**
     * What the person said, in the shape the agent receives.
     *
     * The keys are this plugin's and not the agent's: the agent asked for a form, and the
     * published seam carries a free-text answer, so the answer travels under a key the
     * agent can read but cannot have predicted.
     */
    private fun valuesOf(decision: InputDecision.Proceed): Map<String, ElicitationContentValue> {
        val values = linkedMapOf<String, ElicitationContentValue>()
        decision.submitter?.let { values[SUBMITTER_KEY] = ElicitationContentValue.StringValue(it) }
        decision.message?.let { values[ANSWER_KEY] = ElicitationContentValue.StringValue(it) }
        return values
    }

    companion object {
        /** The label the person sees as the affirmative answer. */
        const val PROCEED_LABEL: String = "Proceed"

        /** The key the answer travels under when the agent receives it. */
        const val ANSWER_KEY: String = "answer"

        /** The key the attribution of the person travels under. */
        const val SUBMITTER_KEY: String = "submitter"
    }
}

/**
 * What the person answered, in the vocabulary the agent understands.
 *
 * [action] is the protocol's own answer; [detail] is the fact the journal records, so the
 * durable output of the step says that a person was asked and what they said.
 */
data class AcpHumanAnswer(
    val action: ElicitationAction,
    val detail: String,
)
