package pipelinek.acp

/**
 * Which half of the connection DIALS a method: the plugin sends agent-side methods and
 * answers client-side ones; protocol methods belong to neither role and are said by whoever
 * aborts a request.
 */
enum class AcpMethodDirection {
    /** client → agent: the plugin dials it when the plan declares the need. */
    AGENT_SIDE,

    /** agent → client: the plugin answers it when the agent dials it. */
    CLIENT_SIDE,

    /** JSON-RPC protocol layer, either direction. */
    PROTOCOL,

    /** the third role: an intermediary dialing on behalf of a real agent (the proxy methods). */
    PROXY,
}

/**
 * Whether a method expects a response. The split is load-bearing for AC#3: an unknown
 * NOTIFICATION must be dropped silently, an unknown REQUEST must answer a protocol error.
 */
enum class AcpMethodKind { REQUEST, NOTIFICATION }

/**
 * The classification a row cannot escape (PKH-141 AC#1). Every row of [AcpCoverageMatrix]
 * carries exactly one, and the coverage test refuses the build when the SDK's or the pinned
 * schema's vocabulary outruns the classification.
 */
enum class AcpRowClassification {
    /** a test on this repository exercises the method over the real wire (or, for the update */
    /** axis, the matrix row that PKH-136 already proved against the shipped jar). */
    TESTED,

    /** the plugin answers "no", on purpose: the pinned SDK carries no API to dial it, or it */
    /** carries one and the plugin's policy declines to use it. [AcpMethodRow.reason] says which. */
    UNSUPPORTED,

    /** the method belongs to a DIFFERENT role than the one this plugin plays (proxy, NES */
    /** editor, protocol v2): not applicable to this adapter unless an explicit role appears. */
    ROLE_EXCLUDED,
}

/**
 * One method of the ACP v1 vocabulary, classified. [method] is the WIRE name (the same string
 * the schema and the SDK's `AcpMethod` carry), so the coverage test can join rows against the
 * SDK jar and the pinned schema resource without a translation table that could drift.
 *
 * [evidence] (for TESTED rows) names a test class in this repository that exercises the method
 * over the real wire; [reason] (for every other row) is the explicit policy or role claim —
 * a row whose reason is empty is a row nobody decided, and the coverage test fails on it.
 */
data class AcpMethodRow(
    val method: String,
    val direction: AcpMethodDirection,
    val kind: AcpMethodKind,
    val classification: AcpRowClassification,
    val evidence: String = "",
    val reason: String = "",
)

/**
 * One capability field of the ACP v1 negotiation, with the stance this plugin takes on it.
 *
 * [ownerClass] is the simple name of the SDK model class that declares the field
 * (`AgentCapabilities`, `SessionCapabilities`, `ClientCapabilities`, `FileSystemCapability`),
 * and [field] the property name the SDK getter exposes. The coverage test reflects those
 * classes and refuses the build when a field appears on the SDK without a stance here —
 * a capability nobody decided is a capability silently ignored.
 */
data class AcpCapabilityRow(
    val ownerClass: String,
    val field: String,
    val stance: AcpRowClassification,
    val note: String,
)

/**
 * The inventory of the ACP v1 protocol as the pinned schema and the pinned SDK state it,
 * classified row by row (PKH-141 AC#1).
 *
 * ## Where the vocabulary comes from, and why it is data
 *
 * The enumeration is NOT hand-written: [AcpCoverageMatrixTest] rebuilds it from two authorities
 * that cannot be quietly wrong — the sealed `AcpMethod` vocabulary of the shipped
 * `com.agentclientprotocol:acp` jar (the session operations are exactly the ones
 * [AcpSessionScope] dials through `sessionOp(...)`), and the schema files of
 * `agentclientprotocol/agent-client-protocol` pinned by commit in
 * `acp/schema-v1-methods.json`. What this object stores is the DECISIONS: per (wire name,
 * direction), whether the method is tested, declined by an explicit policy, or belongs to a
 * different role. A hand-written list could only be observed; this one is checked — when the
 * SDK uptick or the schema pin adds a method, a row goes missing and the gate goes red instead
 * of the coverage quietly lying.
 *
 * ## What v1 does NOT mean
 *
 * v1 is the protocol dialect, not a checklist every agent must satisfy. A method being on this
 * inventory does not promise any given agent speaks it — the negotiation (capabilities,
 * [AcpHandshake.sessionRefusal]) is what decides that per exchange; the rows say only what
 * THIS plugin does with each method. The proxy methods (`proxy/initialize`, `proxy/successor`)
 * belong to a third role — an
 * intermediary in front of a real agent — and mcp/message to the same adapter position; they
 * are classified ROLE_EXCLUDED here, and become applicable only when such an adapter exists.
 *
 * ## What is NOT here
 *
 * The reaction/observation axes — session/update variants, content blocks, lanes — are already
 * the inventory of [AcpSchemaMatrix] (PKH-136/137), measured against the same jar by
 * [AcpSchemaMatrixTest]; duplicating them here would put two authorities on one list. The
 * coverage test re-checks their classification totality and stops there. Draft protocol v2 is
 * the vocabulary of PKH-142, not of this matrix.
 */
object AcpCoverageMatrix {

    // --- methods the plugin DIALS (client → agent) ------------------------------------------

    /** The agent-side methods, one row per (wire name). */
    private val AGENT_SIDE: List<AcpMethodRow> = listOf(
        AcpMethodRow(
            "initialize", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "authenticate", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "the pinned SDK carries client.authenticate(), and the plugin declines it by " +
                "policy: authenticating the AGENT account is the operator's act outside any run; " +
                "a pipeline that could log in would also be able to log the operator out.",
        ),
        AcpMethodRow(
            "logout", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "auth/status", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "providers/list", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "providers/set", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "the pinned SDK carries client.setProvider(); the plugin declines it by policy: " +
                "provider configuration can carry credentials, and a run that rewrote it would be a " +
                "second authority over the agent's secret material. The operator sets providers.",
        ),
        AcpMethodRow(
            "providers/disable", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "same policy as providers/set: disabling a provider mutates the agent's " +
                "credential-bearing configuration from inside a run, which is the split this " +
                "plugin's authority model exists to prevent.",
        ),
        AcpMethodRow(
            "session/new", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/load", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/list", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/resume", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/close", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/delete", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/fork", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/prompt", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/cancel", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.TESTED, evidence = "AcpTaskTimeTest",
        ),
        AcpMethodRow(
            "session/set_mode", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/set_model", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "the pinned SDK carries session.setModel(); the plugin declines a model field: " +
                "the plan's knobs are modes and config options (PKH-135), agents that want the " +
                "client to pick expose it AS a config option, and a second surface for the same " +
                "knob is two authorities over one decision.",
        ),
        AcpMethodRow(
            "session/set_config_option", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpSessionRoundTripTest",
        ),
        AcpMethodRow(
            "session/archive", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "the pinned SDK carries no API for it — there is nothing to negotiate. The " +
                "scope's release semantics are close (keep) and delete (erase); an archive state " +
                "the plugin cannot even dial would be a promise it cannot keep.",
        ),
        AcpMethodRow(
            "session/unarchive", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "same as session/archive: no carrier in the pinned SDK, nothing to negotiate.",
        ),
        AcpMethodRow(
            "session/status", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "no carrier in the pinned SDK. The plugin's liveness answer is the typed " +
                "output of its own step — every operation already ends in a response, a typed " +
                "refusal or a cancellation; polling a status endpoint would re-ask what the " +
                "exchange just answered.",
        ),
        AcpMethodRow(
            "session/options", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "no carrier in the pinned SDK. The options surface the plugin DOES speak is " +
                "session/set_config_option plus the ConfigOptionUpdate the schema matrix already " +
                "classifies — the read side rides the session/new answer, not a separate dial.",
        ),
        AcpMethodRow(
            "session/set_title", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.UNSUPPORTED,
            reason = "no carrier in the pinned SDK, and the direction is wrong anyway: the title " +
                "is the AGENT's announcement (SessionInfoUpdate, inventoried in AcpSchemaMatrix); " +
                "a delegating client naming the session would fight the agent over its own history.",
        ),
        AcpMethodRow(
            "mcp/message", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "the relay belongs to the adapter position between an embedded MCP server and " +
                "the agent. This plugin never sits there: declared mcpServers are handed to the " +
                "agent at session creation and the agent dials the server directly. Applicable " +
                "only behind an explicit proxy adapter, which this repository does not ship.",
        ),
        AcpMethodRow(
            "nes/start", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "NES is the editor role (next-edit suggestions over an open document surface); " +
                "a delegating client has no editing surface, announces no ClientCapabilities.nes, " +
                "and the SDK's own ClientNesSession is never opened.",
        ),
        AcpMethodRow(
            "nes/suggest", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as nes/start: suggestions need a caret, and a pipeline run has none.",
        ),
        AcpMethodRow(
            "nes/accept", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as nes/start: accepting a suggestion edits a document this plugin " +
                "never holds.",
        ),
        AcpMethodRow(
            "nes/reject", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as nes/start.",
        ),
        AcpMethodRow(
            "nes/close", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as nes/start: there is no NES session to close.",
        ),
        AcpMethodRow(
            "document/didOpen", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "document sync feeds the NES role; the plugin's file surface is the REVERSE " +
                "leg (fs/read_text_file, fs/write_text_file), which the schema matrix and the " +
                "authority tests already prove.",
        ),
        AcpMethodRow(
            "document/didChange", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as document/didOpen.",
        ),
        AcpMethodRow(
            "document/didFocus", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as document/didOpen: focus is an editor concept.",
        ),
        AcpMethodRow(
            "document/didSave", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as document/didOpen.",
        ),
        AcpMethodRow(
            "document/didClose", AcpMethodDirection.AGENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as document/didOpen.",
        ),
    )

    // --- methods the plugin ANSWERS (agent → client) ----------------------------------------

    private val CLIENT_SIDE: List<AcpMethodRow> = listOf(
        AcpMethodRow(
            "session/request_permission", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseLegE2ETest",
        ),
        AcpMethodRow(
            "session/update", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.TESTED, evidence = "AcpSchemaMatrixTest",
        ),
        AcpMethodRow(
            "fs/read_text_file", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "fs/write_text_file", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "terminal/create", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "terminal/output", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "terminal/wait_for_exit", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "terminal/kill", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "terminal/release", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "elicitation/create", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.TESTED, evidence = "AcpReverseOperationsTest",
        ),
        AcpMethodRow(
            "elicitation/complete", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.TESTED, evidence = "AcpCoverageMatrixTest",
        ),
        AcpMethodRow(
            "mcp/message", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "the agent-side counterpart of the mcp/message relay: answering it means the " +
                "client stands between the agent and the MCP servers it was handed — the proxy " +
                "role, which no adapter here plays. The same ROLE_EXCLUDED decision as the " +
                "agent-side row, seen from the other end of the pipe.",
        ),
        AcpMethodRow(
            "auth/status_update", AcpMethodDirection.CLIENT_SIDE, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.UNSUPPORTED,
            reason = "no handler carrier in the pinned SDK 0.34.0: the notification arrives, the " +
                "protocol layer drops it as an unknown notification (AC#3, proven over the wire " +
                "by AcpWireExtensionCompatTest), and the exchange is untouched — compatibility " +
                "kept, authority not taken.",
        ),
    )

    // --- protocol-layer methods -------------------------------------------------------------

    private val PROTOCOL_SIDE: List<AcpMethodRow> = listOf(
        AcpMethodRow(
            "$/cancel_request", AcpMethodDirection.PROTOCOL, AcpMethodKind.NOTIFICATION,
            AcpRowClassification.UNSUPPORTED,
            reason = "the plugin cancels at the SESSION level (session/cancel, tested): the ACP " +
                "agent understands \"stop this turn\", while $/cancel_request abandons a JSON-RPC " +
                "request whose typed answer the step output still needs to classify. Saying it " +
                "would trade a classified outcome for a silent dangling slot.",
        ),
    )

    // --- the proxy role (neither side of this connection) -------------------------------------

    private val PROXY_SIDE: List<AcpMethodRow> = listOf(
        AcpMethodRow(
            "proxy/initialize", AcpMethodDirection.PROXY, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "the proxy is a third role — an intermediary in front of a real agent with " +
                "its own handshake. This plugin speaks for itself as the client; the method " +
                "becomes applicable only behind an explicit proxy adapter, which is out of scope " +
                "as long as no such adapter ships.",
        ),
        AcpMethodRow(
            "proxy/successor", AcpMethodDirection.PROXY, AcpMethodKind.REQUEST,
            AcpRowClassification.ROLE_EXCLUDED,
            reason = "same role as proxy/initialize: successor negotiation is what an " +
                "intermediary announces about the agent behind it, not about itself.",
        ),
    )

    /** Every method row, both directions, the protocol layer and the proxy role. */
    val METHODS: List<AcpMethodRow> = AGENT_SIDE + CLIENT_SIDE + PROTOCOL_SIDE + PROXY_SIDE

    // --- the capability axis -----------------------------------------------------------------

    /**
     * One stance per capability field the pinned SDK negotiates with. The field names are the
     * SDK getters' own, so the coverage test can join them against the shipped jar.
     */
    private val CAPABILITIES: List<AcpCapabilityRow> = listOf(
        // AgentCapabilities — what the AGENT offered; the plan's needs are gated on it.
        AcpCapabilityRow("AgentCapabilities", "loadSession", AcpRowClassification.TESTED,
            "boolean flag; AcpHandshake.sessionRefusal gates needs.load on it"),
        AcpCapabilityRow("AgentCapabilities", "promptCapabilities", AcpRowClassification.TESTED,
            "image/audio/embeddedContext booleans, each gated (ACP_CAPABILITY_MISSING)"),
        AcpCapabilityRow("AgentCapabilities", "mcpCapabilities", AcpRowClassification.TESTED,
            "http/sse booleans, each gated before session/new is sent"),
        AcpCapabilityRow("AgentCapabilities", "sessionCapabilities", AcpRowClassification.TESTED,
            "marker objects whose absence is the refusal; see the SessionCapabilities rows"),
        AcpCapabilityRow("AgentCapabilities", "auth", AcpRowClassification.TESTED,
            "logout marker and status boolean, each gated on its own shape"),
        AcpCapabilityRow("AgentCapabilities", "providers", AcpRowClassification.UNSUPPORTED,
            "marker object; the plugin gates providers/list on its presence and declines " +
                "providers/set|disable by policy (see the method rows)"),
        AcpCapabilityRow("AgentCapabilities", "nes", AcpRowClassification.ROLE_EXCLUDED,
            "the NES dialect of the agent side; the plugin never opens a NES session, so the " +
                "announcement is read and left unused"),
        AcpCapabilityRow("AgentCapabilities", "positionEncoding", AcpRowClassification.ROLE_EXCLUDED,
            "positions belong to document/diagnostic content; the plugin sends text blocks and " +
                "resources, never LSP positions"),

        // SessionCapabilities — the per-operation markers.
        AcpCapabilityRow("SessionCapabilities", "fork", AcpRowClassification.TESTED,
            "needs.fork (a declared forkSessionId) is refused without the marker"),
        AcpCapabilityRow("SessionCapabilities", "list", AcpRowClassification.TESTED,
            "needs.list is refused without the marker"),
        AcpCapabilityRow("SessionCapabilities", "resume", AcpRowClassification.TESTED,
            "needs.resume is refused without the marker"),
        AcpCapabilityRow("SessionCapabilities", "delete", AcpRowClassification.TESTED,
            "needs.delete (deleteOnExit) is refused without the marker"),
        AcpCapabilityRow("SessionCapabilities", "close", AcpRowClassification.TESTED,
            "the marker is what licenses saying session/close on the normal exit"),
        AcpCapabilityRow("SessionCapabilities", "additionalDirectories", AcpRowClassification.UNSUPPORTED,
            "the plugin never declares additional directories: the fence of a delegation is its " +
                "cwd and authority, and widening it from the plan is a refusal, not a capability"),

        // ClientCapabilities — what THIS client announces.
        AcpCapabilityRow("ClientCapabilities", "fs", AcpRowClassification.TESTED,
            "AcpHandshake.clientInfo(reverse) announces readTextFile/writeTextFile ONLY for the " +
                "effects the declared authority can grant"),
        AcpCapabilityRow("ClientCapabilities", "terminal", AcpRowClassification.TESTED,
            "announced only when the authority grants EXECUTE; the composed session announces no " +
                "terminal because it answers no reverse leg"),
        AcpCapabilityRow("ClientCapabilities", "planCapabilities", AcpRowClassification.UNSUPPORTED,
            "never announced: plans arrive as updates whatever the announcement says, and the " +
                "update axis (AcpSchemaMatrix) already classifies them"),
        AcpCapabilityRow("ClientCapabilities", "auth", AcpRowClassification.UNSUPPORTED,
            "the client-side auth marker tells the agent the client tracks auth state; the plugin " +
                "reads auth/status when declared and holds no auth state between calls"),
        AcpCapabilityRow("ClientCapabilities", "nes", AcpRowClassification.ROLE_EXCLUDED,
            "the editor role again, from the client side: nothing to offer an inline suggester"),
        AcpCapabilityRow("ClientCapabilities", "positionEncodings", AcpRowClassification.ROLE_EXCLUDED,
            "no positional content crosses this fence"),
        AcpCapabilityRow("ClientCapabilities", "elicitation", AcpRowClassification.UNSUPPORTED,
            "not announced, yet answered: elicitation/create is replied to with the protocol " +
                "cancel or the human seam even when an agent asks unadvertised (defensive, " +
                "AcpReverseOperationsTest) — answering without announcing keeps compatibility " +
                "and concedes no promise"),

        // FileSystemCapability — the sub-fields the reverse leg announces.
        AcpCapabilityRow("FileSystemCapability", "readTextFile", AcpRowClassification.TESTED,
            "granted per authority (READ effect)"),
        AcpCapabilityRow("FileSystemCapability", "writeTextFile", AcpRowClassification.TESTED,
            "granted per authority (MUTATE effect)"),
    )

    val capabilities: List<AcpCapabilityRow> = CAPABILITIES

    /** The row for one (wire name, direction), or null when the inventory does not carry it. */
    fun rowOf(method: String, direction: AcpMethodDirection): AcpMethodRow? =
        METHODS.firstOrNull { it.method == method && it.direction == direction }

    /** The stance row for one capability field, or null when nobody decided it. */
    fun capabilityRow(ownerClass: String, field: String): AcpCapabilityRow? =
        CAPABILITIES.firstOrNull { it.ownerClass == ownerClass && it.field == field }

    /** Every TESTED evidence class named by the method axis. */
    val evidenceClasses: Set<String> =
        METHODS.filter { it.classification == AcpRowClassification.TESTED }
            .map { it.evidence }
            .filter { it.isNotEmpty() }
            .toSet()
}
