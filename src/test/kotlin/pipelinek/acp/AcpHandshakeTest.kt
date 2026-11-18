package pipelinek.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PromptCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two negotiation gates of AC#2: an incompatible version and a missing required
 * capability are refused in typed form, before any prompt is sent.
 */
class AcpHandshakeTest {

    private fun agentInfo(image: Boolean, version: Int = 1): AgentInfo = AgentInfo(
        protocolVersion = version,
        capabilities = AgentCapabilities(
            promptCapabilities = PromptCapabilities(audio = false, image = image, embeddedContext = false),
        ),
        authMethods = emptyList(),
        implementation = Implementation(name = "fixture", version = "0"),
    )

    @Test
    fun `a prompt needing image input is refused when the agent did not negotiate it`() {
        val refusal = AcpHandshake.refusal(agentInfo(image = false), needsImage = true)
        assertEquals(AcpRefusal.MissingCapability("image"), refusal)
        assertEquals("ACP_CAPABILITY_MISSING", refusal?.kindToken)
    }

    @Test
    fun `a text prompt proceeds on an agent without image capability`() {
        assertEquals(null, AcpHandshake.refusal(agentInfo(image = false), needsImage = false))
        assertEquals(null, AcpHandshake.refusal(agentInfo(image = true), needsImage = true))
    }

    @Test
    fun `the SDK's version-negotiation throw maps to a typed mismatch carrying all three numbers`() {
        val refusal = AcpHandshake.refusal(
            UnsupportedProtocolVersionException(requestedVersion = 1, offeredVersion = 7, supportedVersions = setOf(1)),
        )
        assertEquals("ACP_VERSION_MISMATCH", refusal.kindToken)
        assertEquals(1, refusal.requested)
        assertEquals(7, refusal.offered)
        assertEquals(setOf(1), refusal.supported)
    }

    @Test
    fun `every refusal kind has a distinct token the pipeline can branch on`() {
        val tokens = setOf(
            AcpRefusal.VersionMismatch(1, 2, setOf(1)),
            AcpRefusal.MissingCapability("image"),
            AcpRefusal.TransportClosed("x"),
            AcpRefusal.FramingError("x"),
            AcpRefusal.AgentExited(1, ""),
            AcpRefusal.AgentUnreachable("x"),
            AcpRefusal.AgentTimeout(1),
            AcpRefusal.AgentRefusedRequest("m", "x"),
            AcpRefusal.TransportFailure("x"),
            AcpRefusal.PluginInternal("x"),
        ).map { it.kindToken }
        // A duplicated token would silently merge two different failures into one branch.
        assertEquals(tokens.size, tokens.toSet().size)
        assertEquals(true, tokens.all { it.startsWith("ACP_") })
    }
}
