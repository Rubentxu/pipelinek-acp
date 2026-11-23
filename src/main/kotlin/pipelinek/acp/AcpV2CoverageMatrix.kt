package pipelinek.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which of the two pinned v2 metas names a method. AC#1's marking axis: a name from
 * `schema/v2/meta.json` is what a negotiated protocolVersion 2 OWES; a name that only
 * `meta.unstable.json` speaks is a draft EXTENSION — negotiating 2 does not promise it, so
 * the adapter dials one only where a plan explicitly declares it.
 */
enum class AcpV2Origin {
    /** named by the stable `schema/v2/meta.json` pin. */
    STABLE,

    /** named only by the `schema/v2/meta.unstable.json` pin. */
    UNSTABLE_EXTENSION,
}

/**
 * One method of the ACP v2 vocabulary, classified.
 *
 * Same shape as [AcpMethodRow] — the wire name is the join key against both authorities (the
 * SDK's `V2` method groups and the pinned `schema/v2` meta files) — plus the axis v1 never
 * had: [origin], which of the two pinned metas names the name. [AcpRowClassification.TESTED]
 * rows are exercised over the wire against the v2 fixture agent (a second, independent
 * implementation of the v2 wire, same scaffolding pattern as the v1 fixtures); UNSUPPORTED
 * rows say whether the pinned SDK carries no dialer or the plugin's policy declines;
 * ROLE_EXCLUDED rows name the role the plugin does not play.
 */
data class AcpV2MethodRow(
    val method: String,
    val direction: AcpMethodDirection,
    val kind: AcpMethodKind,
    val origin: AcpV2Origin,
    val classification: AcpRowClassification,
    val evidence: String = "",
    val reason: String = "",
)

/**
 * The inventory of the draft ACP v2 protocol (PKH-142 AC#1) — GENERATED from the pin, not
 * written as a list.
 *
 * ## Why a second matrix and not new rows in [AcpCoverageMatrix]
 *
 * The v1 matrix is PKH-141's no-regression oracle: its rows are frozen data and its test
 * refuses ANY v2-only name inside it (`the SDK's v2 vocabulary stays out of the v1 matrix`).
 * v2 vocabulary therefore lives HERE, measured by [AcpV2CoverageMatrixTest] against the same
 * two kinds of authority — the shipped SDK jar and the upstream schema pinned by commit in
 * `acp/schema-v2-methods.json`.
 *
 * ## The updatability clause, made structural
 *
 * The rows ARE the pinned document: [rowsFrom] turns every (name, group) pair of the two metas
 * into a classified row, and the pin's diff decides [AcpV2Origin] — a name that only
 * `meta.unstable.json` speaks is marked UNSTABLE_EXTENSION by the DIFF itself, never by a
 * hand-copied list. An upstream uptick therefore adds rows by REPLACING THE RESOURCE (and
 * [AcpV2CoverageMatrixTest] proves exactly that with a synthetic document): the new name
 * arrives UNSUPPORTED with the default reason, visible and undecided, until a decision is
 * argued into [DECISIONS]. No step, no DSL, no handshake code moves (AC#1's "data, not
 * signatures").
 *
 * ## What the classifications say about v2
 *
 * v2 REARRANGED the vocabulary the v1 matrix had already classified: `authenticate` became
 * `auth/login` and `logout` became `auth/logout` (and in v2 logout has NO capability marker —
 * a non-empty `authMethods` list is the announcement); `session/load` and `session/set_mode`
 * are gone (resume with a replay cursor, config options); the client-side `fs/*` and
 * `terminal/*` methods are removed outright. Those names are NOT rows here — they are not v2
 * vocabulary, and [the routing law][AcpV2Adapter.route] says so by name: a v1 session never
 * dials a v2-only name and a v2 session cannot dial a removed one.
 */
object AcpV2CoverageMatrix {

    /** The pinned v2 inventory as parsed JSON (both metas, verbatim, plus their provenance). */
    val schemaJson: JsonObject by lazy {
        val stream = AcpV2CoverageMatrix::class.java.getResourceAsStream(AcpV2Adapter.INVENTORY_RESOURCE)
            ?: error("the pinned v2 inventory '${AcpV2Adapter.INVENTORY_RESOURCE}' is missing from the plugin")
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private val groups = listOf(
        "agentMethods" to AcpMethodDirection.AGENT_SIDE,
        "clientMethods" to AcpMethodDirection.CLIENT_SIDE,
        "protocolMethods" to AcpMethodDirection.PROTOCOL,
        "proxyMethods" to AcpMethodDirection.PROXY,
    )

    /**
     * The names the v2 metas speak as NOTIFICATIONS (everything else the metas name is a
     * request the answer-slot distinguishes). Data, same as the groups: the set is the v2
     * meta files' own request/notification split.
     */
    private val notificationNames = setOf(
        "session/cancel", "session/update", "elicitation/complete", "auth/status_update",
        "$/cancel_request",
    )

    /** The pair-set of one section of the document: (name, direction) per meta group. */
    private fun pairsOf(doc: JsonObject, section: String): Set<Pair<String, AcpMethodDirection>> {
        val root = doc.getValue(section).jsonObject
        return groups.flatMap { (group, direction) ->
            runCatching {
                root.getValue(group).jsonObject.values.map { it.jsonPrimitive.content }
            }.getOrElse { emptyList() }.map { it to direction }
        }.toSet()
    }

    /**
     * The pure builder: every name the document's two sections speak becomes a row, origin
     * marked by the DIFF of the sections (stable pairs STABLE; pairs only the unstable
     * section names UNSTABLE_EXTENSION), classification defaulting to UNSUPPORTED with the
     * reason that names the mechanism. Called with the pin it yields the shipped inventory;
     * called with a modified document (the uptick law in
     * [AcpV2CoverageMatrixTest]) it yields what the uptick would mean — no code in between.
     */
    fun rowsFrom(doc: JsonObject): List<AcpV2MethodRow> {
        val stable = pairsOf(doc, "stable")
        val unstable = pairsOf(doc, "unstable")
        return (stable + (unstable - stable)).map { (method, direction) ->
            AcpV2MethodRow(
                method = method,
                direction = direction,
                kind = if (method in notificationNames) AcpMethodKind.NOTIFICATION
                else AcpMethodKind.REQUEST,
                origin = if ((method to direction) in stable) AcpV2Origin.STABLE
                else AcpV2Origin.UNSTABLE_EXTENSION,
                classification = AcpRowClassification.UNSUPPORTED,
                reason = DEFAULT_REASON,
            )
        }
    }

    private const val DEFAULT_REASON =
        "the name arrived with the schema pin and no argued decision for this plugin yet: " +
            "declined by default, so an uptick is visible as an undecided row instead of an " +
            "accidental dial"

    private fun decide(row: AcpV2MethodRow): AcpV2MethodRow {
        val key = row.method to row.direction
        val tested = TESTED_EVIDENCE[key]
        if (tested != null) {
            return row.copy(classification = AcpRowClassification.TESTED, evidence = tested, reason = "")
        }
        DECLINED[key]?.let { return row.copy(reason = it) }
        EXCLUDED[key]?.let { return row.copy(classification = AcpRowClassification.ROLE_EXCLUDED, reason = it) }
        return row
    }

    /** Rows exercised over the v2 wire, and the class that exercises them. */
    private val TESTED_EVIDENCE: Map<Pair<String, AcpMethodDirection>, String> = mapOf(
        ("initialize" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/new" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/prompt" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/cancel" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/list" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/resume" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/close" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/delete" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("auth/status" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("providers/list" to AcpMethodDirection.AGENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/request_permission" to AcpMethodDirection.CLIENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("elicitation/create" to AcpMethodDirection.CLIENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("elicitation/complete" to AcpMethodDirection.CLIENT_SIDE) to "AcpV2SessionRoundTripTest",
        ("session/update" to AcpMethodDirection.CLIENT_SIDE) to "AcpV2EquivalenceTest",
    )

    /** Declined names whose WHY differs from the default pin sentence. */
    private val DECLINED: Map<Pair<String, AcpMethodDirection>, String> = mapOf(
        ("auth/login" to AcpMethodDirection.AGENT_SIDE) to
            "renamed from v1 authenticate, same policy as the v1 row: authenticating the AGENT " +
            "account is the operator's act outside any run, and v2 tightens the reason — a " +
            "non-empty authMethods list makes auth/login AND auth/logout mandatory for the " +
            "agent, so a pipeline that could log in could also log the operator out of a method " +
            "the agent must implement. The adapter reads authMethods (the gate the round-trip " +
            "row tests); it never dials.",
        ("auth/logout" to AcpMethodDirection.AGENT_SIDE) to
            "renamed from v1 logout, and the OPERATION is declined by policy in this dialect: " +
            "logging out the account the agent authenticates with is an operator act, not a " +
            "run's. The AC#3 difference the round trip tests is the GATE v2 moved: v1's " +
            "boolean logout marker is gone, a non-empty authMethods list IS the announcement, " +
            "and a declared logout against an agent that lists none is refused before launch " +
            "(AcpV2SessionRoundTripTest).",
        ("providers/set" to AcpMethodDirection.AGENT_SIDE) to
            "same policy as the v1 row: provider configuration can carry credentials and a run " +
            "that rewrote it would be a second authority over the agent's secret material; in " +
            "v2 it is additionally a meta.unstable extension.",
        ("providers/disable" to AcpMethodDirection.AGENT_SIDE) to
            "same policy as providers/set, and the same extension mark.",
        ("session/set_config_option" to AcpMethodDirection.AGENT_SIDE) to
            "the pinned SDK carries the dialer, but the experimental scope keeps to the prompt " +
            "lifecycle and the session-opening operations: v2 folded modes INTO config " +
            "options, and the plugin's v1 mode/config story (PKH-135/141) belongs to the v1 " +
            "scope, not to this adapter.",
        ("session/fork" to AcpMethodDirection.AGENT_SIDE) to
            "the SDK's v2 fork dialer exists, but fork is a meta.unstable extension of v2: " +
            "negotiating protocolVersion 2 does not imply it, and the experimental adapter " +
            "opens sessions along the STABLE baseline only (new, resume, list).",
        ("session/archive" to AcpMethodDirection.AGENT_SIDE) to
            "extension name with no carrier in the pinned SDK 0.34.0 — nothing to dial, same " +
            "gap the v1 row argued.",
        ("session/unarchive" to AcpMethodDirection.AGENT_SIDE) to "same as session/archive.",
        ("session/status" to AcpMethodDirection.AGENT_SIDE) to
            "no carrier in the pinned SDK, and v2 answers the same question better: the " +
            "foreground state arrives as state_update notifications, which the turn machinery " +
            "reconciles instead of polling.",
        ("session/options" to AcpMethodDirection.AGENT_SIDE) to
            "no carrier in the pinned SDK. v2 also moved the read side: configOptions and " +
            "availableCommands ride every session setup response, not a separate dial.",
        ("session/set_title" to AcpMethodDirection.AGENT_SIDE) to
            "no carrier in the pinned SDK; same wrong direction as the v1 row — the title is " +
            "the agent's announcement.",
        ("tool_call/cancel" to AcpMethodDirection.AGENT_SIDE) to
            "new in the v2 STABLE baseline (stop ONE tool call that advertised cancel), but the " +
            "pinned SDK 0.34.0 declares no AcpMethod for it — there is nothing to dial. The " +
            "plugin's v2 cancellation is session-level (session/cancel, tested); per-tool " +
            "cancel becomes applicable with the next SDK uptick, and this row is where that " +
            "diff lands.",
        ("terminal/cancel" to AcpMethodDirection.AGENT_SIDE) to
            "same as tool_call/cancel: stable v2 vocabulary, no carrier in the pinned SDK. v2 " +
            "moved terminals to the AGENT side (the client no longer executes commands at " +
            "all), so the only terminal this plugin could cancel is the agent's own display " +
            "state — which is exactly why the method exists and why the row is not " +
            "ROLE_EXCLUDED.",
        ("$/cancel_request" to AcpMethodDirection.PROTOCOL) to
            "same policy as the v1 row, and v2 SHARPENED it: an inserted session/prompt must " +
            "never be answered with -32800 (the messageId response is the insertion ack), so " +
            "abandoning the request would trade the insertion fact for a dangling slot. " +
            "Cancellation rides session/cancel and the idle state_update confirms it.",
        ("elicitation/complete" to AcpMethodDirection.CLIENT_SIDE) to
            "the notification is dialled by the round trip and lands where the pinned SDK puts " +
            "it: the default handler accepts and ignores it — the experimental scope journals " +
            "no elicitation state (the ledger of PKH-138 belongs to the v1 reactive path).",
        ("auth/status_update" to AcpMethodDirection.CLIENT_SIDE) to
            "no handler carrier in the pinned SDK 0.34.0 AND a meta.unstable extension: the " +
            "notification arrives, the protocol layer drops it as an unknown notification, and " +
            "the exchange is untouched — the same silent-drop claim the v1 row argues, now " +
            "proven against the v2 wire too.",
    )

    /** Roles this plugin does not play, in either direction. */
    private val EXCLUDED: Map<Pair<String, AcpMethodDirection>, String> = mapOf(
        ("mcp/message" to AcpMethodDirection.AGENT_SIDE) to
            "the MCP-over-ACP relay is the adapter position between an embedded MCP server and " +
            "the agent; this plugin hands declared mcpServers to the agent at session creation " +
            "and the agent dials the server directly. In v2 it is ALSO a meta.unstable " +
            "extension (the stable client announces MCP servers, not a relay).",
        ("mcp/message" to AcpMethodDirection.CLIENT_SIDE) to
            "the agent-side counterpart of the relay: same adapter position, same absence of a " +
            "proxy role here, plus the meta.unstable mark.",
        ("proxy/initialize" to AcpMethodDirection.PROXY) to
            "the third role again; in v2 the proxy handshake also has to carry the version " +
            "negotiation, which makes it a method of its OWN — still no proxy adapter ships " +
            "here.",
        ("proxy/successor" to AcpMethodDirection.PROXY) to "same role as proxy/initialize.",
    )

    /** Every v2 method row, both directions, the protocol layer and the proxy role. */
    val METHODS: List<AcpV2MethodRow> = rowsFrom(schemaJson).map { decide(it) }

    // --- the capability axis -----------------------------------------------------------------

    /**
     * One stance per v2 capability field the pinned SDK negotiates. The v2 capability tree is
     * REORGANIZED, not renamed: everything session-scoped lives under `AgentCapabilities.session`,
     * support markers are OBJECTS whose presence is the yes (booleans are gone), the client's
     * `fs`/`terminal` groups are removed, and MCP transports became explicit per-transport
     * capabilities under `session.mcp` — the AC#3 differences, stated per field and measured
     * by the reflection law in [AcpV2CoverageMatrixTest].
     */
    private val CAPABILITIES: List<AcpCapabilityRow> = listOf(
        // AgentCapabilities v2 — what the AGENT offered.
        AcpCapabilityRow("AgentCapabilities", "session", AcpRowClassification.TESTED,
            "advertising capabilities.session commits the agent to the baseline methods " +
                "(new/list/resume/close/prompt/cancel/update); the v2 gate reads it once, not " +
                "per operation like v1"),
        AcpCapabilityRow("AgentCapabilities", "auth", AcpRowClassification.TESTED,
            "v2 nests the agent's auth extensions; status is the boolean that licenses auth/status"),
        AcpCapabilityRow("AgentCapabilities", "providers", AcpRowClassification.UNSUPPORTED,
            "marker object; the scope gates providers/list reads on it and declines " +
                "providers/set|disable by policy, same split as v1"),
        AcpCapabilityRow("AgentCapabilities", "nes", AcpRowClassification.ROLE_EXCLUDED,
            "the editor role did not move in v2 either; a delegating client has no caret"),
        AcpCapabilityRow("AgentCapabilities", "positionEncoding", AcpRowClassification.ROLE_EXCLUDED,
            "no positional content crosses this fence in either protocol version"),

        // AgentAuthCapabilities v2 — the auth rework's negotiation side.
        AcpCapabilityRow("AgentAuthCapabilities", "status", AcpRowClassification.TESTED,
            "boolean; licenses the auth/status read, same gate as v1's auth.status"),

        // ClientCapabilities v2 — what THIS client announces.
        AcpCapabilityRow("ClientCapabilities", "auth", AcpRowClassification.UNSUPPORTED,
            "the v2 client auth group carries the terminal-LOGIN marker (support for running an " +
                "interactive terminal auth method): the plugin never launches interactive logins " +
                "inside a run, so it announces nothing"),
        AcpCapabilityRow("ClientCapabilities", "elicitation", AcpRowClassification.UNSUPPORTED,
            "not announced; elicitations that arrive anyway are declined by the installed " +
                "handler — the compatibility-without-authority posture of the v1 row, carried " +
                "over"),
        AcpCapabilityRow("ClientCapabilities", "nes", AcpRowClassification.ROLE_EXCLUDED,
            "the editor role again, from the client side"),
        AcpCapabilityRow("ClientCapabilities", "positionEncodings", AcpRowClassification.ROLE_EXCLUDED,
            "no positional content crosses this fence"),

        // AuthCapabilities v2 (the client's auth group itself).
        AcpCapabilityRow("AuthCapabilities", "terminal", AcpRowClassification.UNSUPPORTED,
            "the terminal-auth support MARKER ({} = supported): v2 moved it from a boolean on " +
                "clientCapabilities.auth to a support-marker object here; the plugin still " +
                "declines to run terminal logins in a run"),

        // SessionCapabilities v2 — the per-baseline markers.
        AcpCapabilityRow("SessionCapabilities", "prompt", AcpRowClassification.TESTED,
            "the prompt content flags became presence-markers (image/audio/embeddedContext " +
                "objects, not booleans) — the v2 gate checks != null where v1 checked == true"),
        AcpCapabilityRow("SessionCapabilities", "mcp", AcpRowClassification.TESTED,
            "per-transport MCP announcement, new in v2 (stdio was implicit in v1, sse is gone)"),
        AcpCapabilityRow("SessionCapabilities", "delete", AcpRowClassification.TESTED,
            "the marker licenses session/delete — in v2 delete is the ONE remaining optional " +
                "session operation of the baseline, everything else is committed by .session"),
        AcpCapabilityRow("SessionCapabilities", "additionalDirectories", AcpRowClassification.UNSUPPORTED,
            "same stance as v1: the fence of a delegation is its cwd and authority"),
        AcpCapabilityRow("SessionCapabilities", "fork", AcpRowClassification.UNSUPPORTED,
            "meta.unstable marker (absent from the v2 stable schema): the experimental adapter " +
                "opens sessions along the stable baseline, so the marker is read and left unused"),

        // McpCapabilities v2 — the transport story AC#3 asks about.
        AcpCapabilityRow("McpCapabilities", "stdio", AcpRowClassification.TESTED,
            "NEW explicit capability: an agent that cannot launch subprocesses can opt out; the " +
                "scope gates a stdio server on it (v1 assumed stdio always)"),
        AcpCapabilityRow("McpCapabilities", "http", AcpRowClassification.TESTED,
            "gates the http transport, replacing v1's mcpCapabilities.http"),
        AcpCapabilityRow("McpCapabilities", "acp", AcpRowClassification.ROLE_EXCLUDED,
            "MCP-over-ACP: the relay the mcp/message rows already exclude — this plugin never " +
                "stands between the agent and its MCP servers"),

        // PromptCapabilities v2 — the content blocks, now marker objects.
        AcpCapabilityRow("PromptCapabilities", "image", AcpRowClassification.TESTED,
            "presence marker gating image content (v1: boolean == true)"),
        AcpCapabilityRow("PromptCapabilities", "audio", AcpRowClassification.TESTED,
            "presence marker gating audio content"),
        AcpCapabilityRow("PromptCapabilities", "embeddedContext", AcpRowClassification.TESTED,
            "presence marker gating embedded resource text"),
    )

    val capabilities: List<AcpCapabilityRow> = CAPABILITIES

    /** The v2 row for one (wire name, direction), or null when the v2 inventory does not carry it. */
    fun rowOf(method: String, direction: AcpMethodDirection): AcpV2MethodRow? =
        METHODS.firstOrNull { it.method == method && it.direction == direction }

    /** The v2 stance row for one capability field, or null when nobody decided it. */
    fun capabilityRow(ownerClass: String, field: String): AcpCapabilityRow? =
        CAPABILITIES.firstOrNull { it.ownerClass == ownerClass && it.field == field }

    /** Every TESTED evidence class named by the v2 method axis. */
    val evidenceClasses: Set<String> =
        METHODS.filter { it.classification == AcpRowClassification.TESTED }
            .map { it.evidence }
            .filter { it.isNotEmpty() }
            .toSet()
}
