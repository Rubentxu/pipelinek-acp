package pipelinek.acp

import com.agentclientprotocol.client.ClientNegotiator
import com.agentclientprotocol.client.NegotiatedClient
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.client.V1ClientConfig
import com.agentclientprotocol.client.V2ClientConfig
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The outcome of a dual-stack negotiation: either the connection ENDED UP speaking a version
 * (with the client object for THAT dialect, never both), or the attempt is a typed refusal.
 *
 * The shape is AC#1's routing clause at the connection level: nothing may hold a v2 client
 * and a v1 client of the same connection at once — the negotiated integer picks the tool the
 * caller gets, and the caller cannot ask the wrong one for the other dialect's verbs.
 */
sealed interface AcpV2Negotiated {
    /** what the connection speaks: [version] is the agent's answer, not our request. */
    data class Speaking(
        val version: Int,
        val v1Client: com.agentclientprotocol.client.Client?,
        val v2Client: com.agentclientprotocol.client.v2.Client?,
        val agentInfoV1: com.agentclientprotocol.agent.AgentInfo? = null,
        val agentInfoV2: com.agentclientprotocol.agent.v2.AgentInfo? = null,
    ) : AcpV2Negotiated {
        init {
            require((v1Client != null) xor (v2Client != null)) {
                "a negotiated connection hands out exactly one dialect's client"
            }
            require((v1Client != null) == (version == AcpProtocolVersion.V1.wireVersion)) {
                "the client handed out must be the dialect the version integer names"
            }
        }
    }

    /** the negotiation failed as a typed refusal — never a half-open connection. */
    data class Refused(val refusal: AcpRefusal) : AcpV2Negotiated
}

/**
 * The protocol dialect a negotiated ACP connection speaks.
 *
 * v2 is additive: the wire framing is JSON-RPC in both dialects and the negotiated integer
 * (the one `initialize` agrees on) decides which vocabulary the connection owes. The plugin
 * speaks v1 through the frozen v1 path ([AcpConnection], [AcpSessionConnection]); it speaks v2
 * only through the experimental [AcpV2SessionConnection], and only when the operator opted in
 * ([AcpV2Adapter.enabled]).
 */
enum class AcpProtocolVersion(val wireVersion: Int) {
    /** the released protocol, the plugin's default and contract (PKH-141's matrix). */
    V1(1),

    /** the draft protocol, experimental until the spec freezes (PKH-142). */
    V2(2),
    ;

    companion object {
        /** The version a negotiated `protocolVersion` integer names, or null when it is foreign. */
        fun of(wireVersion: Int): AcpProtocolVersion? =
            entries.firstOrNull { it.wireVersion == wireVersion }
    }
}

/** Where one wire method belongs once the v2 vocabulary (the pinned schema) has been consulted. */
enum class AcpV2Route {
    /** named by `schema/v2/meta.json`: a negotiated protocolVersion 2 owes it. */
    STABLE_BASELINE,

    /** named ONLY by `schema/v2/meta.unstable.json`: a draft extension — negotiating v2 does
     * not imply support for it, so the adapter marks it and dials it never by accident. */
    UNSTABLE_EXTENSION,

    /** not v2 vocabulary at all: a v1 name (removed or renamed by v2), or an unknown one. */
    NOT_IN_V2,
}

/**
 * The experimental ACP v2 adapter's declared surface (PKH-142): the opt-in switch, the
 * routing law, and the v2 initialization identity.
 *
 * ## Opt-in, off by default (AC#4)
 *
 * v2 is in Preview — "ready to build on, not frozen". The adapter therefore does nothing
 * unless an operator flips it: no step negotiates v2 while [enabled] answers false, and the
 * v1 path is untouched either way. The switch reads the `pipelinek.acp.v2.enabled` system
 * property first, then the `PIPELINEK_ACP_V2_ENABLED` environment slot; anything but a
 * literal "true" is false — a typo must not silently enable a draft protocol.
 *
 * ## The routing law (AC#1)
 *
 * [route] answers where a wire method lives, reading the v2 inventory from the pinned
 * resource `acp/schema-v2-methods.json` (the upstream meta.json + meta.unstable.json, pinned
 * by commit). An upstream diff updates THE RESOURCE, and with it this routing law — no step,
 * DSL or handshake code needs to change, which is exactly the updatability AC#1 asks for. The
 * plugin's DECISIONS about each name (tested / declined / role-excluded) live as
 * classifications in [AcpV2CoverageMatrix]; the vocabulary lives upstream.
 *
 * [dialsOn] is the no-cross-dialect clause of the dual stack: a v1 session never dials a name
 * v2 introduced, a v2 session never dials a name v2 removed — the renames (`authenticate` →
 * `auth/login`, `logout` → `auth/logout`) and removals (`session/load`, `session/set_mode`,
 * the client `fs/*` and `terminal/*`) refuse at this law BEFORE any request goes out.
 */
object AcpV2Adapter {

    /** AC#4's default, measured by [requestedProtocolVersion]: the shipped plugin asks for 1. */
    const val DEFAULT_OPT_IN: Boolean = false

    /** The system property that opts a JVM into the experimental v2 adapter. */
    const val ENABLE_PROPERTY: String = "pipelinek.acp.v2.enabled"

    /** The environment slot the property falls back to (for hosts that pass env, not -D). */
    const val ENABLE_ENV: String = "PIPELINEK_ACP_V2_ENABLED"

    /**
     * Whether the experimental v2 adapter is enabled. Default FALSE (AC#4): the draft protocol
     * runs only where an operator said so, and saying so is this — nothing else opts in.
     */
    fun enabled(): Boolean {
        val declared = System.getProperty(ENABLE_PROPERTY) ?: System.getenv(ENABLE_ENV)
        return declared == "true"
    }

    /** Resource path of the pinned v2 inventory (meta.json + meta.unstable.json by commit). */
    const val INVENTORY_RESOURCE: String = "/acp/schema-v2-methods.json"

    private val methodGroups = listOf(
        "agentMethods", "clientMethods", "protocolMethods", "proxyMethods",
    )

    private fun names(section: String): Set<String> {
        val root = inventoryJson.getValue(section).jsonObject
        return methodGroups.flatMap { group ->
            runCatching {
                root.getValue(group).jsonObject.values.map { it.jsonPrimitive.content }
            }.getOrElse { emptyList() }
        }.toSet()
    }

    /** The `schema/v2/meta.json` names as pinned (the stable baseline vocabulary). */
    val stableMethodNames: Set<String> get() = names("stable")

    /** The `schema/v2/meta.unstable.json` names as pinned (baseline + draft extensions). */
    val unstableMethodNames: Set<String> get() = names("unstable")

    /** The v2 wire inventory, parsed once per JVM (the resource ships with the plugin). */
    private val inventoryJson: kotlinx.serialization.json.JsonObject by lazy {
        val stream = AcpV2Adapter::class.java.getResourceAsStream(INVENTORY_RESOURCE)
            ?: error("the pinned v2 inventory '$INVENTORY_RESOURCE' is missing from the plugin")
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    /** Where [method] lives in the v2 vocabulary. Never a guess: unknown names are NOT_IN_V2. */
    fun route(method: String): AcpV2Route = when {
        method in stableMethodNames -> AcpV2Route.STABLE_BASELINE
        method in unstableMethodNames -> AcpV2Route.UNSTABLE_EXTENSION
        else -> AcpV2Route.NOT_IN_V2
    }

    /**
     * Whether a connection that negotiated [version] may dial [method] at all.
     *
     * The v1 side of the answer is PKH-141's frozen inventory: v1 dials what the v1 matrix
     * and SDK name. The v2 side is the pinned v2 vocabulary. A method that exists in BOTH
     * dialects under the same name (session/prompt) dials on both; a rename (logout →
     * auth/logout) dials under exactly one name per dialect — that is the whole no-cross-
     * dialect rule, stated as data.
     */
    fun dialsOn(version: AcpProtocolVersion, method: String): Boolean = when (version) {
        AcpProtocolVersion.V2 -> route(method) != AcpV2Route.NOT_IN_V2
        AcpProtocolVersion.V1 -> AcpCoverageMatrix.METHODS.any { it.method == method }
    }

    /**
     * The protocol version the handshake will ASK for. Without the opt-in this is 1 — the
     * shipped contract; 2 is reachable only where the caller names the opt-in (and the
     * operator enabled the adapter). AC#4's default, measured at the request, not the answer.
     */
    fun requestedProtocolVersion(optInV2: Boolean = DEFAULT_OPT_IN): Int =
        if (optInV2) AcpProtocolVersion.V2.wireVersion else AcpProtocolVersion.V1.wireVersion

    /**
     * The dual-stack negotiation entry (AC#1's routing, at the connection level).
     *
     * Without the opt-in — the shipped path — this is the v1 handshake exactly: the SDK's v1
     * `initialize`, version 1, and the v1 client handed back; the draft is never even asked
     * for. With it, the SDK's `ClientNegotiator` speaks the v2 initialize shape and reads the
     * VERSION OFF THE ANSWER: an agent that answers 1 falls back to a v1 client (the legacy
     * fleet does not need to move), an agent that answers 2 gets its v2 client, and anything
     * else is a typed [AcpRefusal] — never a connection in an unknown dialect. Exactly one
     * client object ever leaves this function for a connection: the routing law has no
     * both-hands case.
     */
    suspend fun negotiate(
        protocol: Protocol,
        pluginVersion: String,
        optInV2: Boolean = DEFAULT_OPT_IN,
    ): AcpV2Negotiated {
        if (!optInV2) {
            val client = com.agentclientprotocol.client.Client(protocol)
            val agent = client.initialize(AcpHandshake.clientInfo(pluginVersion))
            return AcpV2Negotiated.Speaking(
                version = agent.protocolVersion,
                v1Client = client,
                v2Client = null,
                agentInfoV1 = agent,
            )
        }
        return try {
            when (val negotiated = ClientNegotiator(
                protocol = protocol,
                v1 = V1ClientConfig(),
                v2 = V2ClientConfig(
                    clientInfo = clientInfoV2(pluginVersion),
                    elicitationHandler = AcpV2DecliningElicitation,
                ),
            ).negotiate()) {
                is NegotiatedClient.V1 -> AcpV2Negotiated.Speaking(
                    version = negotiated.protocolVersion,
                    v1Client = negotiated.client,
                    v2Client = null,
                    agentInfoV1 = negotiated.agentInfo,
                )
                is NegotiatedClient.V2 -> AcpV2Negotiated.Speaking(
                    version = negotiated.protocolVersion,
                    v1Client = null,
                    v2Client = negotiated.client,
                    agentInfoV2 = negotiated.agentInfo,
                )
            }
        } catch (e: UnsupportedProtocolVersionException) {
            AcpV2Negotiated.Refused(
                AcpRefusal.VersionMismatch(e.requestedVersion, e.offeredVersion, e.supportedVersions),
            )
        } catch (e: JsonRpcException) {
            // An agent that REFUSES version 2 answers a JSON-RPC error (the -32022 shape the
            // spec reserves for it). That is a protocol-correct answer to read, not a crash:
            // the attempt ends as a typed refusal naming the request and its code.
            AcpV2Negotiated.Refused(
                AcpRefusal.AgentRefusedRequest(
                    "initialize", "protocol version refused: ${e.message} (code ${e.code})",
                ),
            )
        }
    }

    /** The declining elicitation answer the v2 path installs (see [AcpV2DecliningElicitation]). */
    internal object AcpV2DecliningElicitation : com.agentclientprotocol.client.v2.ElicitationHandler {
        override suspend fun createElicitation(
            request: com.agentclientprotocol.model.v2.CreateElicitationRequest,
        ): com.agentclientprotocol.model.v2.CreateElicitationResponse =
            com.agentclientprotocol.model.v2.CreateElicitationResponse(
                action = com.agentclientprotocol.model.v2.ElicitationAction.Decline,
                _meta = null,
            )
    }

    /**
     * The v2 `initialize` identity this client sends (AC#3's removal clause, structurally):
     * `model.v2.ClientCapabilities` has no `fs` and no `terminal` group — v2 deleted the
     * client-side file and terminal capabilities, and the SDK's own type makes announcing
     * them impossible. The plugin sends the EMPTY capability set: the experimental adapter
     * promises no reverse leg, no elicitation tracking and no terminal auth; whatever the
     * agent offers, it offers to a client that declared it needs nothing.
     * ([AcpV2CoverageMatrix] states the stance per capability field.)
     */
    fun clientInfoV2(pluginVersion: String): com.agentclientprotocol.client.v2.ClientInfo =
        com.agentclientprotocol.client.v2.ClientInfo(
            protocolVersion = AcpProtocolVersion.V2.wireVersion,
            implementation = com.agentclientprotocol.model.Implementation(
                name = "pipelinek-acp",
                version = pluginVersion,
                title = "PipelineK ACP client plugin (experimental v2 adapter)",
                _meta = null,
            ),
            capabilities = com.agentclientprotocol.model.v2.ClientCapabilities(),
        )
}
