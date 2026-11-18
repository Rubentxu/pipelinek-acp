package pipelinek.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation

/**
 * The client's side of ACP initialization, as a decision the plugin can state and test.
 *
 * The SDK performs the version negotiation and throws
 * [UnsupportedProtocolVersionException] when the agent answers with a version this client
 * cannot speak; the connection maps that throw into [AcpRefusal.VersionMismatch] rather than
 * letting it escape as an untyped failure. This object owns the SECOND gate — the capability
 * check — which the SDK leaves to the caller: a negotiated connection whose agent cannot
 * produce what the step declares it needs is refused before a prompt is ever sent, because a
 * prompt sent into a capability gap comes back as an opaque agent-side error.
 */
object AcpHandshake {

    /** The client identity announced to the agent. No SDK version pretence: this is the plugin. */
    fun clientInfo(pluginVersion: String): com.agentclientprotocol.client.ClientInfo =
        com.agentclientprotocol.client.ClientInfo(
            capabilities = ClientCapabilities(),
            implementation = Implementation(
                name = "pipelinek-acp",
                version = pluginVersion,
                title = "PipelineK ACP client plugin",
                _meta = null,
            ),
        )

    /**
     * The refusal this agent answer deserves, or null when the exchange may proceed.
     *
     * [needsImage] is the step's declared requirement: the ACP vocabulary has no flag for text
     * prompts (every agent speaks text), but image input is negotiated, and a prompt that
     * embeds an image into an agent that answered `image = false` is a prompt the agent will
     * fail for a reason the reader cannot see from the outside. Checking here means the refusal
     * names the capability, not the agent.
     */
    fun refusal(agent: AgentInfo, needsImage: Boolean): AcpRefusal? {
        if (needsImage && !agent.capabilities.promptCapabilities.image) {
            return AcpRefusal.MissingCapability("image")
        }
        return null
    }

    /** The SDK's negotiation throw, translated into the plugin's typed refusal. */
    fun refusal(e: UnsupportedProtocolVersionException): AcpRefusal.VersionMismatch =
        AcpRefusal.VersionMismatch(
            requested = e.requestedVersion,
            offered = e.offeredVersion,
            supported = e.supportedVersions,
        )
}
