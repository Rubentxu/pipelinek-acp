package pipelinek.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * PKH-142 AC#1: the v2 matrix is data, and it is DATA OF THE SAME GRAMMAR as the v1 one.
 *
 * The rows come from the metas pinned in `acp/schema-v2-methods.json`; the SDK's own v2
 * `AcpMethod` groups are the second authority (a method the SDK carries that the pin does not
 * name is drift, and the pin must cover it). What this test refuses:
 *
 *   * a v2 method the pinned SDK speaks with no row → red (silent gap in the draft dialect);
 *   * a meta.unstable-only method NOT marked as an extension → red (negotiating version 2 does
 *     not promise unstable features, and a matrix that says otherwise lies);
 *   * a schema uptick that cannot be expressed by editing the JSON alone → red, because AC#1's
 *     claim is "new data, no new signatures": [an upstream uptick updates the matrix by data
 *     alone] feeds a modified document through the pure builder and gets a classified row
 *     without touching any code;
 *   * the public DSL growing a v2 verb → red (the matrix must not smuggle a second surface).
 */
class AcpV2CoverageMatrixTest {

    private fun sdkV2Names(): List<Pair<String, AcpMethodDirection>> {
        val out = mutableListOf<Pair<String, AcpMethodDirection>>()
        listOf(
            "com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V2" to AcpMethodDirection.AGENT_SIDE,
            "com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V2" to AcpMethodDirection.CLIENT_SIDE,
        ).forEach { (className, direction) ->
            Class.forName(className).declaredClasses
                .filter { it.simpleName.isNotEmpty() && !it.simpleName.contains("Companion") }
                .forEach { klass ->
                    val instance = runCatching { klass.getField("INSTANCE").get(null) }
                        .getOrNull() as? com.agentclientprotocol.model.AcpMethod ?: return@forEach
                    val getter = com.agentclientprotocol.model.AcpMethod::class.java.methods
                        .first { it.name.startsWith("getMethodName") && it.parameterCount == 0 }
                    out.add(getter.invoke(instance) as String to direction)
                }
        }
        return out
    }

    @Test
    fun `every v2 method the pinned SDK speaks has a classified row`() {
        val discovered = sdkV2Names()
        assertTrue(discovered.isNotEmpty(), "the SDK shipped no v2 vocabulary at all")
        discovered.forEach { (name, direction) ->
            assertNotNull(AcpV2CoverageMatrix.rowOf(name, direction),
                "the SDK's v2 groups declare '$name' ($direction) and the v2 matrix has no row",
            )
        }
    }

    @Test
    fun `the pin marks the meta-unstable extensions and only those`() {
        val schema = AcpV2CoverageMatrix.schemaJson
        val stableKeys = pairsOf(schema, "stable")
        val unstableKeys = pairsOf(schema, "unstable")
        val extensionKeys = unstableKeys - stableKeys
        val marked = AcpV2CoverageMatrix.METHODS
            .filter { it.origin == AcpV2Origin.UNSTABLE_EXTENSION }
            .map { it.method to it.direction }
            .toSet()
        assertEquals(extensionKeys, marked,
            "the unstable marking must BE the diff of the two metas: negotiating protocolVersion " +
                "2 does not promise these, and the matrix says so per row",
        )
        assertTrue(marked.isNotEmpty(), "the unstable meta stopped extending the stable one?")
    }

    private fun pairsOf(schema: JsonObject, section: String): Set<Pair<String, AcpMethodDirection>> {
        val groups = listOf(
            "agentMethods" to AcpMethodDirection.AGENT_SIDE,
            "clientMethods" to AcpMethodDirection.CLIENT_SIDE,
            "protocolMethods" to AcpMethodDirection.PROTOCOL,
            "proxyMethods" to AcpMethodDirection.PROXY,
        )
        val sectionObject = schema.getValue(section).jsonObject
        return groups.flatMap { (group, direction) ->
            runCatching {
                sectionObject.getValue(group).jsonObject.values.map { it.jsonPrimitive.content }
            }.getOrElse { emptyList() }.map { it to direction }
        }.toSet()
    }

    @Test
    fun `an upstream uptick updates the matrix by data alone`() {
        // AC#1's measurable claim: NEW VOCABULARY NEEDS NO CODE. Clone the pinned document,
        // add one method to each meta, and feed the pure builder — the row appears, classified,
        // unstable-marked, with the default policy reason. No signature changed anywhere.
        val base = AcpV2CoverageMatrix.schemaJson
        val stableAgents = base.getValue("stable").jsonObject.getValue("agentMethods").jsonObject
        val unstableAgents = base.getValue("unstable").jsonObject.getValue("agentMethods").jsonObject
        val upticked = JsonObject(
            base.toMap() + listOf(
                "stable" to JsonObject(
                    base.getValue("stable").jsonObject.toMap() + (
                        "agentMethods" to JsonObject(
                            stableAgents.toMap() + ("probe" to JsonPrimitive("uptick/probe")),
                        )
                    ),
                ),
                "unstable" to JsonObject(
                    base.getValue("unstable").jsonObject.toMap() + (
                        "agentMethods" to JsonObject(
                            unstableAgents.toMap() + ("probe2" to JsonPrimitive("uptick/two")),
                        )
                    ),
                ),
            ),
        )
        val rows = AcpV2CoverageMatrix.rowsFrom(upticked)
        val stableRow = rows.first { it.method == "uptick/probe" }
        assertEquals(AcpRowClassification.UNSUPPORTED, stableRow.classification,
            "an unclassified-by-default uptick is the honest answer until a decision is argued",
        )
        assertEquals(AcpV2Origin.STABLE, stableRow.origin)
        assertTrue(stableRow.reason.contains("schema pin") || stableRow.reason.contains("pin"),
            "the default reason must name the mechanism that created the row: ${stableRow.reason}",
        )
        assertEquals(AcpV2Origin.UNSTABLE_EXTENSION, rows.first { it.method == "uptick/two" }.origin,
            "the extension marking follows the diff, not a hand-written list",
        )
        // And the shipped inventory is unaffected by the synthetic document: the matrix is a
        // view of ITS pin, so refreshing the pin is the only way new rows ever appear.
        assertTrue(AcpV2CoverageMatrix.METHODS.none { it.method.startsWith("uptick/") })
    }

    @Test
    fun `the v2 matrix changes no public DSL signature`() {
        // The DSL is the public surface the plans speak. If v2 needed a verb, AC#1 would have
        // failed; measure that it did not get one: no public member of the DSL or the plugin
        // declaration names the dialect. (AcpV2CoverageMatrix itself is inventory, not DSL.)
        listOf(Class.forName("pipelinek.acp.AcpDslKt").kotlin, AcpPluginDeclaration::class).forEach { owner ->
            val offenders = owner.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .map { it.name }
                .filter { it.contains("v2", ignoreCase = true) }
            assertTrue(offenders.isEmpty(),
                "${owner.simpleName} carries public members $offenders: the draft dialect must " +
                    "not reach the public DSL while it is draft (AC#1: data, not signatures)",
            )
        }
    }

    @Test
    fun `every v2 row carries its argument`() {
        AcpV2CoverageMatrix.METHODS.forEach { row ->
            when (row.classification) {
                AcpRowClassification.TESTED -> {
                    assertTrue(row.evidence.isNotEmpty(), "TESTED v2 row '${row.method}' cites no test")
                    runCatching { Class.forName("pipelinek.acp.${row.evidence}") }
                        .onFailure { fail("TESTED v2 row '${row.method}' cites '${row.evidence}', " +
                            "which is not a class on the test classpath") }
                    assertEquals("", row.reason)
                }
                AcpRowClassification.UNSUPPORTED, AcpRowClassification.ROLE_EXCLUDED ->
                    assertTrue(row.reason.isNotBlank(),
                        "${row.classification} v2 row '${row.method}' carries no reason",
                    )
            }
        }
    }

    @Test
    fun `tool and terminal cancel are v2-only vocabulary with an explicit decline`() {
        // AC#3's per-entity cancellation difference, stated as rows nobody wrote by hand:
        // the stable meta speaks them, the pinned SDK has no carrier, the matrix says no.
        listOf("tool_call/cancel", "terminal/cancel").forEach { name ->
            val row = requireNotNull(AcpV2CoverageMatrix.rowOf(name, AcpMethodDirection.AGENT_SIDE))
            assertEquals(AcpV2Origin.STABLE, row.origin, "'$name' is v2 BASELINE, not an extension")
            assertEquals(AcpRowClassification.UNSUPPORTED, row.classification)
            assertTrue(row.reason.contains("0.34.0") || row.reason.contains("carrier"),
                "'$name' must name WHY the draft cannot be dialed yet: ${row.reason}",
            )
            // …and they never leak into the v1 oracle (PKH-141's split holds from both ends).
            assertNull(AcpCoverageMatrix.rowOf(name, AcpMethodDirection.AGENT_SIDE),
                "'$name' leaked into the v1 matrix",
            )
        }
    }

    @Test
    fun `the mcp relay keeps its role exclusion in both dialects`() {
        // AC#3's MCP-client difference: v2 moves mcp/message to its unstable meta but the
        // ROLE answer is the v1 answer, stated as its own row rather than merged away.
        val v2Row = requireNotNull(AcpV2CoverageMatrix.rowOf("mcp/message", AcpMethodDirection.AGENT_SIDE))
        assertEquals(AcpRowClassification.ROLE_EXCLUDED, v2Row.classification)
        assertEquals(AcpV2Origin.UNSTABLE_EXTENSION, v2Row.origin)
        val v1Row = requireNotNull(AcpCoverageMatrix.rowOf("mcp/message", AcpMethodDirection.AGENT_SIDE))
        assertEquals(AcpRowClassification.ROLE_EXCLUDED, v1Row.classification)
    }

    @Test
    fun `the pinned resource states version 2 for both metas`() {
        val pinned = AcpV2CoverageMatrix.schemaJson.getValue("pinned").jsonObject
        assertEquals("schema/v2/meta.json", pinned.getValue("stableFile").jsonPrimitive.content)
        assertEquals(40, pinned.getValue("stableCommit").jsonPrimitive.content.length,
            "the v2 pin must be a commit, the same discipline as the v1 pin",
        )
        listOf("stable", "unstable").forEach { section ->
            val version = AcpV2CoverageMatrix.schemaJson.getValue(section).jsonObject
                .getValue("version").jsonPrimitive.content
            assertEquals("2", version, "the '$section' section of a v2 pin must state version 2")
        }
    }
}
