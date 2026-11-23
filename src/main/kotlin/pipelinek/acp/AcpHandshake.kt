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
     * The announced identity when the delegation carries a reverse session (PKH-134): the
     * client advertises `fs` and `terminal` ONLY for the effects its declared authority can
     * actually grant. A capability the policy would refuse anyway is not advertised — the
     * advertisement is a promise the intersection has already agreed to keep.
     */
    fun clientInfo(pluginVersion: String, reverse: AcpReverseSession): com.agentclientprotocol.client.ClientInfo =
        com.agentclientprotocol.client.ClientInfo(
            capabilities = ClientCapabilities(
                fs = reverse.fsCapabilities(),
                terminal = reverse.terminalAdvertised(),
            ),
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

    /**
     * The composed session's gate (PKH-135 AC#2/AC#3): the plan's declared needs checked
     * against the negotiated capabilities, BEFORE any request the agent cannot answer is
     * sent.
     *
     * The session vocabulary negotiates each of these one by one, and they are NOT shaped
     * alike: `loadSession` is a boolean, the prompt content flags are booleans, MCP
     * transports are booleans under `mcpCapabilities`, while `sessionCapabilities`
     * list/resume/delete/close and `auth.logout` are marker OBJECTS whose ABSENCE (null) is
     * the "not announced" answer. A plan naming an unoffered operation is refused
     * [AcpRefusal.SessionCapabilityMissing] naming the negotiation that failed — attempting
     * anyway would trade a diagnosable refusal for an opaque agent-side method-not-found,
     * and skipping quietly is the silent degradation AC#2 forbids. Multimodal content
     * blocks ride the same gate as the prompt step's image check, with the prompt's own
     * vocabulary ([AcpRefusal.MissingCapability]) kept for those.
     */
    fun sessionRefusal(agent: AgentInfo, needs: AcpSessionNeeds): AcpRefusal? {
        if (needs.image && !agent.capabilities.promptCapabilities.image) {
            return AcpRefusal.MissingCapability("image")
        }
        if (needs.audio && !agent.capabilities.promptCapabilities.audio) {
            return AcpRefusal.MissingCapability("audio")
        }
        if (needs.embeddedContext && !agent.capabilities.promptCapabilities.embeddedContext) {
            return AcpRefusal.MissingCapability("embeddedContext")
        }
        if (needs.mcpHttp && !agent.capabilities.mcpCapabilities.http) {
            return AcpRefusal.SessionCapabilityMissing("mcp http")
        }
        if (needs.mcpSse && !agent.capabilities.mcpCapabilities.sse) {
            return AcpRefusal.SessionCapabilityMissing("mcp sse")
        }
        if (needs.load && !agent.capabilities.loadSession) {
            return AcpRefusal.SessionCapabilityMissing("loadSession")
        }
        if (needs.resume && agent.capabilities.sessionCapabilities?.resume == null) {
            return AcpRefusal.SessionCapabilityMissing("session resume")
        }
        // PKH-141 AC#2: the management operations the pinned SDK negotiates, each on its own
        // announced shape — fork is a marker object on sessionCapabilities, auth status a
        // plain boolean on auth (NOT a marker: absence and `false` are the same answer only
        // because both refuse; the check says `!= true`, not `== null`), providers a marker.
        if (needs.fork && agent.capabilities.sessionCapabilities?.fork == null) {
            return AcpRefusal.SessionCapabilityMissing("session fork")
        }
        if (needs.authStatus && agent.capabilities.auth?.status != true) {
            return AcpRefusal.SessionCapabilityMissing("auth status")
        }
        if (needs.providers && agent.capabilities.providers == null) {
            return AcpRefusal.SessionCapabilityMissing("providers")
        }
        if (needs.list && agent.capabilities.sessionCapabilities?.list == null) {
            return AcpRefusal.SessionCapabilityMissing("session list")
        }
        if (needs.delete && agent.capabilities.sessionCapabilities?.delete == null) {
            return AcpRefusal.SessionCapabilityMissing("session delete")
        }
        if (needs.logout && agent.capabilities.auth?.logout == null) {
            return AcpRefusal.SessionCapabilityMissing("auth logout")
        }
        return null
    }
}
