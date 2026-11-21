package pipelinek.acp

import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import dev.rubentxu.pipeline.v2.runtime.input.InputDenialReason
import dev.rubentxu.pipeline.v2.runtime.input.InputRequest
import dev.rubentxu.pipeline.v2.runtime.input.InputResolution

/**
 * The human-decision port, answered by nobody.
 *
 * A step that can be asked by the agent for a person's input declares the capability and
 * reads the port before it does anything else, so a test that drives such a handler has to
 * bind it. This is the binding for the turns that never ask a person anything: the port is
 * there, and nobody is on the other side of it.
 */
class AcpUnansweredInputDecisions : InputDecisions {
    override suspend fun awaitDecision(request: InputRequest, waitMillis: Long?): InputResolution =
        InputResolution.Denied(InputDenialReason.Cancelled)
}
