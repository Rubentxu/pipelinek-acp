package pipelinek.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.model.AgentAuthCapabilities
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LogoutCapabilities
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionDeleteCapabilities
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The session gate (PKH-135 AC#2/AC#3) as a pure decision: the declared needs against the
 * negotiated capabilities, answered BEFORE any request is sent.
 *
 * The capability vocabulary is not uniform on purpose — booleans for load and prompt
 * content, marker objects (null = absent) for the session operations and auth — and this
 * test is the ledger of that asymmetry: a marker misread as a boolean would send
 * `session/list` to an agent that never announced it, and AC#2's "no silent degradation"
 * would be gone with the refusal.
 */
class AcpSessionHandshakeTest {

    private val fullCaps = AgentCapabilities(
        loadSession = true,
        promptCapabilities = PromptCapabilities(image = true, audio = true, embeddedContext = true),
        mcpCapabilities = McpCapabilities(http = true, sse = true),
        sessionCapabilities = SessionCapabilities(
            list = SessionListCapabilities(),
            resume = SessionResumeCapabilities(),
            delete = SessionDeleteCapabilities(),
            close = SessionCloseCapabilities(),
        ),
        auth = AgentAuthCapabilities(logout = LogoutCapabilities()),
    )

    private val minimalCaps = AgentCapabilities(
        loadSession = false,
        promptCapabilities = PromptCapabilities(image = false, audio = false, embeddedContext = false),
        sessionCapabilities = SessionCapabilities(),
    )

    private fun agent(caps: AgentCapabilities) = AgentInfo(
        protocolVersion = 1,
        capabilities = caps,
        authMethods = emptyList(),
        implementation = Implementation(name = "fixture-agent", version = "0.1.0"),
    )

    @Test
    fun `a full-caps agent honours every declared session need`() {
        val needs = AcpSessionNeeds(
            image = true, audio = true, embeddedContext = true, mcpHttp = true, mcpSse = true,
            load = true, resume = true, list = true, delete = true, logout = true,
        )
        assertNull(AcpHandshake.sessionRefusal(agent(fullCaps), needs))
    }

    @Test
    fun `a multimodal block the agent never announced is refused, not degraded`() {
        val a = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(image = true))
        assertEquals("ACP_CAPABILITY_MISSING", a?.kindToken)
        val b = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(audio = true))
        assertEquals("ACP_CAPABILITY_MISSING", b?.kindToken)
        val c = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(embeddedContext = true))
        assertEquals("ACP_CAPABILITY_MISSING", c?.kindToken)
    }

    @Test
    fun `load is gated by the boolean flag, not by a marker object`() {
        val refusal = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(load = true))
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", refusal?.kindToken)
        assertEquals("loadSession", (refusal as AcpRefusal.SessionCapabilityMissing).capability)
    }

    @Test
    fun `the session operations are gated by marker presence, and absence is not zero`() {
        for ((name, needs) in mapOf(
            "session resume" to AcpSessionNeeds(resume = true),
            "session list" to AcpSessionNeeds(list = true),
            "session delete" to AcpSessionNeeds(delete = true),
            "auth logout" to AcpSessionNeeds(logout = true),
        )) {
            val refusal = AcpHandshake.sessionRefusal(agent(minimalCaps), needs)
            assertEquals("ACP_SESSION_CAPABILITY_MISSING", refusal?.kindToken, name)
            assertEquals(name, (refusal as AcpRefusal.SessionCapabilityMissing).capability)
        }
    }

    @Test
    fun `an http mcp server the agent cannot reach is refused before session new is sent`() {
        val refusal = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(mcpHttp = true))
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", refusal?.kindToken)
        val sse = AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds(mcpSse = true))
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", sse?.kindToken)
    }

    @Test
    fun `text-only prompts need no gate — every agent speaks text`() {
        assertNull(AcpHandshake.sessionRefusal(agent(minimalCaps), AcpSessionNeeds()))
    }
}
