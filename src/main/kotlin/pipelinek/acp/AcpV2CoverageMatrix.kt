package pipelinek.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which section of the pinned v2 metas a method comes from (PKH-142 AC#1).
 *
 * The distinction is data, not prose: an entry present in `meta.unstable.json` and absent
 * from `meta.json` is an UNSTABLE_EXTENSION — negotiating `protocolVersion: 2` does NOT imply
 * support for it (the migration guide is explicit), so the adapter may only route it when the
 * feature's own negotiation requirement is met. A STABLE entry is the v2 baseline.
 */
enum class AcpV2Origin { STABLE, UNSTABLE_EXTENSION }

/**
 * One v2 method as the pinned metas state it, with the stance the experimental adapter takes.
 * Same classification alphabet as [AcpMethodRow] — one vocabulary language across the two
 * dialects is what lets AC#4 compare them without a translation table.
 */
data class AcpV2MethodRow(
    val method: String,
    val direction: AcpMethodDirection,
    val origin: AcpV2Origin,
    val kind: AcpMethodKind,
    val classification: AcpRowClassification,
    val evidence: String = "",
    val reason: String = "",
)

/**
 * The inventory of the draft ACP v2 dialect (PKH-142 AC#1), built from the metas pinned in
 * `acp/schema-v2-methods.json` — the same data-pinning pattern PKH-141 established for v1,
 * with one deliberate difference: HERE THE ENUMERATION IS THE DATA.
 *
 * [rowsFrom] is a pure function of the schema JSON. When the upstream metas gain a method,
 * refreshing the pin adds the row; no Kotlin signature, no DSL verb, no step declaration
 * changes. The unclassified-by-default answer is UNSUPPORTED with an explicit reason: the row
 * EXISTS so a schema uptick can never be silently covered, and promoting a row to TESTED is
 * the only edit that needs code (a test class to cite, checked by [AcpV2CoverageMatrixTest]).
 *
 * The v1 matrix [AcpCoverageMatrix] is the no-regression oracle and is NOT touched by this
 * file: v2-only methods never enter it, and the v1 gate's own row keeps the split honest.
 */
object AcpV2CoverageMatrix {

    /** Wire names the pinned v2 metas speak as notifications (the metas carry only names). */
    private val NOTIFICATIONS: Set<String> = setOf(
        "session/cancel", "elicitation/complete", "auth/status_update",
    )

    /**
     * Decisions that need a policy argument the data cannot carry: the role exclusions and the
     * SDK-carrier facts, each naming its dialect. Methods absent here take the
     * unclassified-by-default UNSUPPORTED row, which is the honest answer for a draft.
     */
    private val DECISIONS: Map<Pair<String, AcpMethodDirection>, AcpV2MethodRow.() -> AcpV2MethodRow> = mapOf(
        ("initialize" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/new" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/prompt" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/resume" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/close" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/cancel" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("session/update" to AcpMethodDirection.CLIENT_SIDE) to {
            copy(
                classification = AcpRowClassification.TESTED,
                evidence = "AcpV2DualStackTest",
            )
        },
        ("auth/login" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.UNSUPPORTED,
                reason = "the v2 SDK carries client.login(), and the plugin declines it under the " +
                    "same policy as v1 authenticate (AcpCoverageMatrix): authenticating the AGENT " +
                    "account is the operator's act outside any run. The v2 rename is a dialect " +
                    "difference, not a policy change.",
            )
        },
        ("auth/logout" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.UNSUPPORTED,
                reason = "v1 'logout' stays TESTED in the v1 matrix; in the v2 dialect the plugin " +
                    "routes no logout: a plan that could end the operator's session is the same " +
                    "second-authority refusal, seen under the renamed wire method.",
            )
        },
        ("tool_call/cancel" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.UNSUPPORTED,
                reason = "the stable v2 meta speaks it; the pinned SDK 0.34.0 declares no v2 " +
                    "method object for it — there is nothing to dial. The plugin cancels the " +
                    "session (session/cancel, tested over the v2 wire) and the step's own budget " +
                    "covers the rest; per-entity cancel becomes routable when the SDK uptick " +
                    "carries it.",
            )
        },
        ("terminal/cancel" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.UNSUPPORTED,
                reason = "same as tool_call/cancel: stable v2 vocabulary, no carrier in the " +
                    "pinned SDK's v2 method groups.",
            )
        },
        ("mcp/message" to AcpMethodDirection.AGENT_SIDE) to {
            copy(
                classification = AcpRowClassification.ROLE_EXCLUDED,
                reason = "the relay belongs to the adapter position between an embedded MCP " +
                    "server and the agent — the same ROLE_EXCLUDED decision as the v1 row, " +
                    "carried into the v2 dialect (where it is an unstable-only entry) so the " +
                    "two inventories agree on the role, not by copy-paste of a merged row.",
            )
        },
        ("mcp/message" to AcpMethodDirection.CLIENT_SIDE) to {
            copy(
                classification = AcpRowClassification.ROLE_EXCLUDED,
                reason = "the agent-side counterpart of the same relay exclusion as in v1: " +
                    "answering it means the client stands between the agent and its MCP servers.",
            )
        },
        ("proxy/initialize" to AcpMethodDirection.PROXY) to {
            copy(
                classification = AcpRowClassification.ROLE_EXCLUDED,
                reason = "third role, same as the v1 matrix's claim: the v2 metas move proxy " +
                    "into their own group; no proxy adapter ships.",
            )
        },
        ("proxy/successor" to AcpMethodDirection.PROXY) to {
            copy(
                classification = AcpRowClassification.ROLE_EXCLUDED,
                reason = "same role as proxy/initialize.",
            )
        },
        ("session/request_permission" to AcpMethodDirection.CLIENT_SIDE) to {
            copy(
                classification = AcpRowClassification.UNSUPPORTED,
                reason = "the v2 SDK's ClientSessionOperations carries requestPermission and the " +
                    "negotiated session wires one, but no test on this repository answers a v2 " +
                    "permission request over the real wire yet: the v1 row " +
                    "(session/request_permission, AcpReverseLegE2ETest) is the tested dialect. " +
                    "Promoting this row is a pin-refresh plus an evidence class — the plan " +
                    "grammar gains no v2 verb from it (AC#1: data, not DSL).",
            )
        },
    )

    private val DEFAULT_REASON: String =
        "the draft v2 metas speak it and the plugin declares no need for it: the row is created " +
            "by the schema pin alone, so an upstream uptick can never land unclassified. The " +
            "v2 adapter is experimental and opt-in (AcpV2Adapter); until v2 leaves draft the " +
            "plugin answers 'no' by policy, with the v1 dialect as the supported path."

    /**
     * The rows the pinned v2 metas state, with every decision applied. Pure function of the
     * schema JSON: [AcpV2CoverageMatrixTest] feeds it a synthetic uptick to prove the matrix
     * is updatable by diff alone, with no change in any public signature.
     */
    fun rowsFrom(schema: JsonObject): List<AcpV2MethodRow> {
        val groups = listOf(
            "agentMethods" to AcpMethodDirection.AGENT_SIDE,
            "clientMethods" to AcpMethodDirection.CLIENT_SIDE,
            "protocolMethods" to AcpMethodDirection.PROTOCOL,
            "proxyMethods" to AcpMethodDirection.PROXY,
        )
        val stableNames = sectionNames(schema, "stable", groups)
        val unstableNames = sectionNames(schema, "unstable", groups)
        // Origin is the DIFF of the two metas, computed from the data: an entry only the
        // unstable meta speaks is an extension negotiating version 2 does not promise.
        val all = (stableNames + unstableNames).distinct()
        return all.map { (name, direction) ->
            val origin =
                if ((name to direction) in stableNames) AcpV2Origin.STABLE else AcpV2Origin.UNSTABLE_EXTENSION
            val base = AcpV2MethodRow(
                method = name,
                direction = direction,
                origin = origin,
                kind = if (name in NOTIFICATIONS) AcpMethodKind.NOTIFICATION else AcpMethodKind.REQUEST,
                classification = AcpRowClassification.UNSUPPORTED,
                reason = DEFAULT_REASON,
            )
            // A TESTED row cites a test; the default UNSUPPORTED reason would contradict it.
            DECISIONS[(name to direction)]?.invoke(base)
                ?.let { if (it.classification == AcpRowClassification.TESTED) it.copy(reason = "") else it }
                ?: base
        }
    }

    private fun sectionNames(
        schema: JsonObject,
        section: String,
        groups: List<Pair<String, AcpMethodDirection>>,
    ): List<Pair<String, AcpMethodDirection>> =
        schema.getValue(section).jsonObject
            .let { sectionObject ->
                groups.flatMap { (group, direction) ->
                    runCatching {
                        sectionObject.getValue(group).jsonObject.values.map { it.jsonPrimitive.content }
                    }.getOrElse { emptyList() }
                        .map { it to direction }
                }
            }

    /** The inventory shipped in the pinned resource. */
    val schemaJson: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/acp/schema-v2-methods.json")) {
            "the pinned v2 schema inventory is missing from the resources"
        }.reader(Charsets.UTF_8).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    val METHODS: List<AcpV2MethodRow> by lazy { rowsFrom(schemaJson) }

    /** The row for one (wire name, direction) of the v2 inventory, or null when absent. */
    fun rowOf(method: String, direction: AcpMethodDirection): AcpV2MethodRow? =
        METHODS.firstOrNull { it.method == method && it.direction == direction }

    /** Every TESTED evidence class named by the v2 axis. */
    val evidenceClasses: Set<String>
        get() = METHODS.filter { it.classification == AcpRowClassification.TESTED }
            .map { it.evidence }
            .filter { it.isNotEmpty() }
            .toSet()
}
