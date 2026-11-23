package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-142 AC#4: v1 and v2 produce the SAME normalized vocabulary where they are
 * semantically equivalent, and every non-equivalent case stays an explicit row.
 *
 * The mapping is checked against all four authorities — the SDK's V1 and V2 method groups
 * and the two pinned schema inventories (v1 and v2) — so no name spoken by any of them can
 * cross the dialect boundary undecided, and no row can fuse two operations the specs keep
 * apart (session/load into resume, a removed fs leg into an MCP suggestion, a rename into a
 * no-op). The v1 oracle itself is asserted UNMOVED where the dialects differ: the frozen
 * PKH-141 rows are the other side of the same coin.
 */
class AcpV2VocabularyEquivalenceTest {

    /** The (name, direction) pairs the SDK's V1 or V2 groups carry, by reflection. */
    private fun sdkNames(): Set<String> {
        val groups = listOf(
            "com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V1",
            "com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V1",
            "com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V2",
            "com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V2",
            "com.agentclientprotocol.model.AcpMethod\$MetaMethods",
        )
        val getter = com.agentclientprotocol.model.AcpMethod::class.java.methods
            .first { it.name.startsWith("getMethodName") && it.parameterCount == 0 }
        return groups.flatMap { className ->
            Class.forName(className).declaredClasses.mapNotNull { klass ->
                val instance = runCatching { klass.getField("INSTANCE").get(null) }
                    .getOrNull() as? com.agentclientprotocol.model.AcpMethod ?: return@mapNotNull null
                getter.invoke(instance) as String
            }
        }.toSet()
    }

    /** The wire names either pinned schema inventory speaks (v1 and v2, both metas each). */
    private fun pinnedSchemaNames(): Set<String> {
        val v1 = javaClass.getResourceAsStream("/acp/schema-v1-methods.json")!!.reader().readText()
        val v2 = javaClass.getResourceAsStream("/acp/schema-v2-methods.json")!!.reader().readText()
        val json = kotlinx.serialization.json.Json
        return listOf(v1, v2).flatMap { text ->
            val root = json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }
            listOf("stable", "unstable").flatMap { section ->
                root.getValue(section).let { it as kotlinx.serialization.json.JsonObject }
                    .values.flatMap { group ->
                        (group as kotlinx.serialization.json.JsonObject).values.map {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        }
                    }
            }
        }.toSet()
    }

    @Test
    fun `every method named by any authority has an equivalence row`() {
        val spoken = sdkNames() + pinnedSchemaNames()
        assertTrue(spoken.size > 40, "the join found suspiciously few names: ${spoken.size}")
        spoken.forEach { name ->
            assertNotNull(AcpV2VocabularyEquivalence.rowFor(name),
                "the dialects speak '$name' and the equivalence table never decided how it " +
                    "relates to the other side",
            )
        }
    }

    @Test
    fun `shared wire names normalize to one operation across the two SDK vocabularies`() {
        // The names BOTH SDK dialects speak (initialize, session/new, session/prompt, …):
        // same name, same operation — the normalized vocabulary is one, per AC#4.
        val v1 = sdkGroupNames("com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V1") +
            sdkGroupNames("com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V1")
        val v2 = sdkGroupNames("com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V2") +
            sdkGroupNames("com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V2")
        val shared = v1 intersect v2
        assertTrue(shared.isNotEmpty(), "the SDK stopped sharing any vocabulary between dialects?")
        shared.forEach { name ->
            val row = requireNotNull(AcpV2VocabularyEquivalence.rowFor(name))
            assertEquals(AcpV2EquivalenceKind.EQUIVALENT, row.kind,
                "'$name' is spoken identically by both SDK groups yet the row claims ${row.kind}",
            )
            assertEquals(row.v1, row.v2, "an EQUIVALENT row must carry the same name twice")
            assertEquals(name, AcpV2VocabularyEquivalence.normalize(name))
            assertTrue(AcpV2VocabularyEquivalence.sameNormalizedVocabulary(row.operation),
                "operation '${row.operation}' is EQUIVALENT but does not normalize as one",
            )
        }
    }

    private fun sdkGroupNames(className: String): Set<String> {
        val getter = com.agentclientprotocol.model.AcpMethod::class.java.methods
            .first { it.name.startsWith("getMethodName") && it.parameterCount == 0 }
        return Class.forName(className).declaredClasses.mapNotNull { klass ->
            val instance = runCatching { klass.getField("INSTANCE").get(null) }
                .getOrNull() as? com.agentclientprotocol.model.AcpMethod ?: return@mapNotNull null
            getter.invoke(instance) as String
        }.toSet()
    }

    @Test
    fun `renames are one operation with two rows - never a silent merge of the names`() {
        val login = requireNotNull(AcpV2VocabularyEquivalence.rowFor("auth/login"))
        assertEquals("authenticate", login.v1)
        assertEquals("auth/login", login.v2)
        // The operation normalizes BOTH names together…
        assertEquals(AcpV2VocabularyEquivalence.normalize("authenticate"), login.operation)
        assertEquals(AcpV2VocabularyEquivalence.normalize("auth/login"), login.operation)
        // …but the names themselves are not equal, and no v2 name was retroactively written
        // into the frozen v1 matrix (PKH-141's oracle).
        assertNotEquals(login.v1, login.v2)
        assertNull(AcpCoverageMatrix.rowOf("auth/login", AcpMethodDirection.AGENT_SIDE))
        assertNotNull(AcpCoverageMatrix.rowOf("authenticate", AcpMethodDirection.AGENT_SIDE))
    }

    @Test
    fun `removed legs stay removed - the normalization gives them no v2 alias`() {
        listOf("fs/read_text_file", "terminal/create", "session/set_model", "session/load").forEach { name ->
            val row = requireNotNull(AcpV2VocabularyEquivalence.rowFor(name))
            assertTrue(
                row.kind == AcpV2EquivalenceKind.REMOVED_IN_V2 ||
                    row.kind == AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
                "'$name' must not have migrated into an EQUIVALENT row",
            )
            assertFalse(AcpV2VocabularyEquivalence.sameNormalizedVocabulary(row.operation),
                "'$name' (${row.kind}) would fuse the dialects on a difference",
            )
            // The v2 inventories truly do not speak it — the removal claim is measured, not remembered.
            assertFalse(AcpV2CoverageMatrix.METHODS.any { it.method == name },
                "'$name' is stated removed but sits in the v2 matrix anyway",
            )
        }
    }

    @Test
    fun `the experimental adapter is opt-in where the v1 path is the shipped default`() {
        // AC#4's second sentence, at the table: nothing in the shipped v1 surface references
        // the v2 types (the v2 axis is reachable only through AcpV2Adapter's explicit entry).
        val v1Surface = listOf(
            AcpHandshake::class, AcpSessionConnection::class, AcpCoverageMatrix::class,
            Class.forName("pipelinek.acp.AcpDslKt").kotlin,
        ).flatMap { owner ->
            owner.java.declaredMethods.map { it.name } +
                owner.java.declaredFields.map { it.name }
        }
        val offenders = v1Surface.filter { it.contains("V2", ignoreCase = true) }
        assertTrue(offenders.isEmpty(),
            "the v1 path grew v2 members $offenders: the v2 axis is reachable only through " +
                "AcpV2Adapter's explicit entry",
        )
        assertFalse(AcpV2Adapter.DEFAULT_OPT_IN)
    }
}
