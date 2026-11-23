package pipelinek.acp

import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission

/**
 * The emission seam of a host that has registered none of this plugin's kinds.
 *
 * A step that publishes its observation declares the emission capability and reads the seam
 * before it launches anything, so a test that drives such a handler has to bind it. This is
 * the binding for the turns whose facts no consumer reads: the seam answers honestly that the
 * kind is not registered, and the publisher counts that refusal as a gap instead of letting
 * the fact vanish silently.
 */
class AcpUnregisteredEventEmission : PluginEventEmission {
    override fun <P : Any> emit(kind: String, payload: P): EmissionOutcome =
        EmissionOutcome.UnregisteredKind(kind)
}
