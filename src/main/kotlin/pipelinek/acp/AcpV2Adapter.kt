package pipelinek.acp

import com.agentclientprotocol.agent.v2.AgentInfo as AgentInfoV2
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientNegotiator
import com.agentclientprotocol.client.NegotiatedClient
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.client.V1ClientConfig
import com.agentclientprotocol.client.V2ClientConfig
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.v2.ClientCapabilities as ClientCapabilitiesV2
import com.agentclientprotocol.protocol.Protocol

/**
 * The experimental v2 adapter: version negotiation on the SAME connection, opt-in only while
 * the v2 spec is draft (PKH-142 AC#4).
 *
 * ## Why it is a separate object and not a DSL verb
 *
 * The plan grammar stays v1: no step, no declaration and no public DSL signature grows here.
 * The v2 inventory lives in [AcpV2CoverageMatrix] as data, and this object is the mechanism a
 * follow-up task mounts onto the DSL once v2 leaves draft. That separation is what AC#1
 * measures: refreshing `acp/schema-v2-methods.json` changes the matrix without touching any
 * of it.
 *
 * ## The two facts the handshake keeps apart
 *
 * [negotiate] speaks for the SDK's own dual-stack [ClientNegotiator]: the client sends the
 * highest version it supports (v2 ONLY when the explicit `optInV2 = true` says so), and the
 * agent picks the highest version at or below it, answering `-32022` when it speaks none.
 * [AcpV2Negotiated] names WHOSE version the connection speaks — one connection ends its
 * initialize speaking exactly ONE dialect, so every later dispatch decision (which method
 * table, which turn shape) reads the negotiated number, never the requested one.
 *
 * ## What the v2 announcement does NOT carry
 *
 * The v1 client capabilities for the file system and the client terminal are removed from v2
 * entirely (migration guide, "Client file system and terminal execution removed"): the v2
 * client announces [ClientCapabilitiesV2], whose type has no such fields to begin with. An
 * adapter that mapped the v1 announcement across would advertise promises the protocol no
 * longer defines; the serialization row in `AcpV2HandshakeTest` is the gate that refuses it.
 */
object AcpV2Adapter {

    /** The draft dialect is OFF by default: only an explicit `optInV2 = true` asks for it. */
    const val DEFAULT_OPT_IN: Boolean = false

    /** The version an opt-in client requests; the v1 path never sends a number above 1. */
    const val PROTOCOL_VERSION_V2: Int = 2
    const val PROTOCOL_VERSION_V1: Int = 1

    /** The version the handshake will REQUEST: 2 only under the explicit opt-in. */
    fun requestedProtocolVersion(
        optInV2: Boolean = DEFAULT_OPT_IN,
    ): Int = if (optInV2) PROTOCOL_VERSION_V2 else PROTOCOL_VERSION_V1

    /** The v2 client identity. No SDK version pretence, same rule as [AcpHandshake.clientInfo]. */
    fun clientInfoV2(pluginVersion: String): com.agentclientprotocol.client.v2.ClientInfo =
        com.agentclientprotocol.client.v2.ClientInfo(
            protocolVersion = PROTOCOL_VERSION_V2,
            implementation = Implementation(
                name = "pipelinek-acp",
                version = pluginVersion,
                title = "PipelineK ACP client plugin (experimental protocol v2)",
                _meta = null,
            ),
            // All-null announcement: v2 keeps only auth/elicitation/nes/positionEncodings, and
            // the plugin's stance on each is the v1 stance (answers defensively, announces
            // nothing it has not granted authority for). The reverse-leg file/terminal caps
            // have no field HERE at all — v2 removed them; there is nothing to leak.
            capabilities = ClientCapabilitiesV2(),
        )

    /**
     * Negotiates the dialect on an open [protocol] and returns WHOSE version the connection
     * speaks. Without the opt-in the SDK negotiator is never constructed: the connection is
     * initialized as v1 exactly the way every shipped step does it.
     */
    suspend fun negotiate(
        protocol: Protocol,
        pluginVersion: String,
        optInV2: Boolean = DEFAULT_OPT_IN,
    ): AcpV2Negotiated {
        val v1Info = AcpHandshake.clientInfo(pluginVersion)
        if (!optInV2) {
            return try {
                val client = Client(protocol)
                val agent = client.initialize(v1Info)
                AcpV2Negotiated.Speaking(
                    version = PROTOCOL_VERSION_V1,
                    v1Client = client,
                    v1Agent = agent,
                )
            } catch (e: UnsupportedProtocolVersionException) {
                AcpV2Negotiated.Refused(AcpHandshake.refusal(e))
            }
        }
        return try {
            when (
                val negotiated = ClientNegotiator(
                    protocol,
                    V1ClientConfig(clientInfo = v1Info),
                    V2ClientConfig(clientInfo = clientInfoV2(pluginVersion)),
                ).negotiate()
            ) {
                is NegotiatedClient.V1 -> AcpV2Negotiated.Speaking(
                    version = negotiated.protocolVersion,
                    v1Client = negotiated.client,
                    v1Agent = negotiated.agentInfo,
                )
                is NegotiatedClient.V2 -> AcpV2Negotiated.Speaking(
                    version = negotiated.protocolVersion,
                    v2Client = negotiated.client,
                    v2Agent = negotiated.agentInfo,
                )
            }
        } catch (e: UnsupportedProtocolVersionException) {
            AcpV2Negotiated.Refused(AcpHandshake.refusal(e))
        }
    }
}

/**
 * The outcome of [AcpV2Adapter.negotiate]. [Speaking] carries the version THE AGENT chose —
 * the version the plugin REQUESTED is not a fact about the connection. [Refused] is the same
 * typed refusal the v1 path produces for a version mismatch, so callers downstream of the
 * handshake see one refusal language, not a second one.
 */
sealed class AcpV2Negotiated {
    data class Speaking(
        val version: Int,
        val v1Client: com.agentclientprotocol.client.Client? = null,
        val v1Agent: com.agentclientprotocol.agent.AgentInfo? = null,
        val v2Client: com.agentclientprotocol.client.v2.Client? = null,
        val v2Agent: AgentInfoV2? = null,
    ) : AcpV2Negotiated()

    data class Refused(val refusal: AcpRefusal.VersionMismatch) : AcpV2Negotiated()
}
