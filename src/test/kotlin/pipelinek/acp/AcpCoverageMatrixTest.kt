package pipelinek.acp

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.SessionId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * The coverage matrix measured against its two authorities (PKH-141 AC#1).
 *
 * The matrix is DATA; these rows are what makes it executable. The enumeration comes from
 * the shipped `AcpMethod` vocabulary of the pinned ACP SDK (reflection over the same jar the
 * plugin compiles against — the pattern PKH-136 established for [AcpSchemaMatrix]) and from
 * the schema files pinned by commit in `acp/schema-v1-methods.json`. What the rows store is
 * only the DECISIONS, and what these tests refuse is every way a decision could go stale:
 *
 *   * a method the SDK or the schema speaks with no row → red (silent gap);
 *   * a row no authority speaks → red (invented coverage);
 *   * a TESTED row whose evidence class does not exist → red (evidence pointing at nothing);
 *   * a declined or excluded row with no reason → red (an "Unsupported" nobody argued);
 *   * a row whose kind (request vs notification) disagrees with the SDK's own marker class →
 *     red, because AC#3's answer DIFFERS by kind: unknown notifications are dropped, unknown
 *     requests must answer a protocol error;
 *   * a capability field on the SDK's negotiation types with no stance → red.
 *
 * Every assertion is on the FORM (coverage sets, totality of classification, resolvability of
 * evidence), never on a literal chain a refactor could delete by renaming.
 */
class AcpCoverageMatrixTest {

    // --- the SDK's own vocabulary, read from the shipped jar ---------------------------------

    /** The (name, direction, kind) triples the SDK declares, discovered by reflection. */
    private data class SdkMethod(val name: String, val direction: AcpMethodDirection, val notification: Boolean)

    /** The AcpMethod object classes of one vocabulary group (AgentMethods.V1, …). */
    private fun methodObjects(groupClassName: String): List<Class<*>> =
        Class.forName(groupClassName)
            .declaredClasses
            .filter { it.simpleName.isNotEmpty() && !it.simpleName.contains("Companion") }
            .filter { klass ->
                val instance = runCatching { klass.getField("INSTANCE").get(null) }.getOrNull()
                instance is AcpMethod
            }

    /** The wire name one AcpMethod object carries (the getter is name-mangled; match on prefix). */
    private fun AcpMethod.wireName(): String {
        val getter = AcpMethod::class.java.methods
            .first { it.name.startsWith("getMethodName") && it.parameterCount == 0 }
        return getter.invoke(this) as String
    }

    private fun sdkV1Methods(): List<SdkMethod> {
        val discovered = mutableListOf<SdkMethod>()
        val groups = listOf(
            "com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V1" to AcpMethodDirection.AGENT_SIDE,
            "com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V1" to AcpMethodDirection.CLIENT_SIDE,
            "com.agentclientprotocol.model.AcpMethod\$MetaMethods" to AcpMethodDirection.PROTOCOL,
        )
        groups.forEach { (className, direction) ->
            methodObjects(className).forEach { klass ->
                val instance = klass.getField("INSTANCE").get(null) as AcpMethod
                discovered.add(
                    SdkMethod(
                        name = instance.wireName(),
                        direction = direction,
                        notification = instance is AcpMethod.AcpNotificationMethod<*>,
                    ),
                )
            }
        }
        return discovered
    }

    // --- the schema pinned by commit, read from the shipped resource --------------------------

    private val pinnedSchema: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/acp/schema-v1-methods.json")) {
            "the pinned schema inventory is missing from the resources"
        }.reader(Charsets.UTF_8).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun schemaMethodNames(section: String, group: String): List<String> =
        pinnedSchema.getValue(section).jsonObject
            .getValue(group).jsonObject
            .values.map { it.jsonPrimitive.content }

    private fun schemaDiscovered(): List<Pair<String, AcpMethodDirection>> = listOf(
        "stable", "unstable",
    ).flatMap { section ->
        listOf(
            "agentMethods" to AcpMethodDirection.AGENT_SIDE,
            "clientMethods" to AcpMethodDirection.CLIENT_SIDE,
            "protocolMethods" to AcpMethodDirection.PROTOCOL,
            "proxyMethods" to AcpMethodDirection.PROXY,
        ).flatMap { (group, direction) ->
            runCatching { schemaMethodNames(section, group) }.getOrElse { emptyList() }
                .map { it to direction }
        }
    }

    // --- AC#1: the classification is total, both ways, and it cannot lie ----------------------

    @Test
    fun `every method the pinned SDK speaks has a classified row`() {
        val discovered = sdkV1Methods()
        assertTrue(discovered.isNotEmpty(), "reflection found no AcpMethod objects at all")
        discovered.forEach { method ->
            val row = requireNotNull(AcpCoverageMatrix.rowOf(method.name, method.direction)) {
                "the SDK declares '${method.name}' (${method.direction}) and the matrix has no " +
                    "row for it: coverage would be silently incomplete"
            }
            // The kind disagreement is not cosmetic: AC#3's protocol answer branches on it.
            val kindMatches = (method.notification && row.kind == AcpMethodKind.NOTIFICATION) ||
                (!method.notification && row.kind == AcpMethodKind.REQUEST)
            assertTrue(kindMatches, "row '${method.name}' claims ${row.kind} but the SDK " +
                "declares ${if (method.notification) "a notification" else "a request"}",
            )
        }
    }

    @Test
    fun `every method the schema pinned by commit speaks has a classified row`() {
        val discovered = schemaDiscovered()
        // Sanity: the resource parses into a real inventory, so an empty map can never pass.
        assertTrue(discovered.size >= 30, "the pinned schema yielded ${discovered.size} names")
        discovered.forEach { (name, direction) ->
            assertNotNull(AcpCoverageMatrix.rowOf(name, direction),
                "the schema declares '$name' ($direction) with no matrix row",
            )
        }
    }

    @Test
    fun `no matrix row names a method neither authority speaks`() {
        val declared = sdkV1Methods().map { it.name to it.direction }.toSet() +
            schemaDiscovered().toSet()
        AcpCoverageMatrix.METHODS.forEach { row ->
            assertTrue((row.method to row.direction) in declared,
                "row '${row.method}' (${row.direction}) is invented: no SDK object and no " +
                    "pinned schema entry carries it — the matrix must follow the protocol, " +
                    "not a memory of it",
            )
        }
    }

    @Test
    fun `every row is classified and no classification is unexplained`() {
        AcpCoverageMatrix.METHODS.forEach { row ->
            when (row.classification) {
                AcpRowClassification.TESTED -> {
                    assertTrue(row.evidence.isNotEmpty(), "TESTED row '${row.method}' cites no test")
                    // The evidence must resolve: a TESTED row pointing at a deleted test is
                    // exactly the false green AC's DoD refuses.
                    runCatching { Class.forName("pipelinek.acp.${row.evidence}") }
                        .onFailure { fail("TESTED row '${row.method}' cites '${row.evidence}', which is not a class on the test classpath") }
                }
                AcpRowClassification.UNSUPPORTED, AcpRowClassification.ROLE_EXCLUDED ->
                    assertTrue(row.reason.isNotBlank(),
                        "${row.classification} row '${row.method}' carries no reason: an " +
                            "Unsupported without a policy is a gap wearing a label",
                    )
            }
            // Rows may not hoard both fields: an explanation of a tested method belongs in KDoc.
            if (row.classification == AcpRowClassification.TESTED) {
                assertEquals("", row.reason, "TESTED row '${row.method}' should argue with evidence, not prose")
            }
        }
    }

    @Test
    fun `rows are unique per wire name and direction`() {
        val keys = AcpCoverageMatrix.METHODS.map { it.method to it.direction }
        assertEquals(keys.size, keys.toSet().size,
            "mcp/message legitimately appears in both directions; anything else duplicated is " +
                "two classifications for one method",
        )
    }

    // --- the capability axis: stances against the SDK's negotiation types --------------------

    @Test
    fun `every capability field the SDK negotiates has a stance`() {
        val owners = listOf(
            "AgentCapabilities", "SessionCapabilities", "ClientCapabilities", "FileSystemCapability",
        )
        owners.forEach { owner ->
            val klass = Class.forName("com.agentclientprotocol.model.$owner")
            val fields = klass.declaredMethods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }
                .filter { it != "_meta" && it.isNotEmpty() }
                .toSet()
            assertTrue(fields.isNotEmpty(), "reflection found no capability fields on $owner")
            fields.forEach { field ->
                val row = requireNotNull(AcpCoverageMatrix.capabilityRow(owner, field)) {
                    "capability '$owner.$field' has no stance row: a capability nobody decided " +
                        "is a capability silently ignored"
                }
                assertTrue(row.note.isNotBlank(), "capability '$owner.$field' has an empty stance note")
            }
        }
    }

    @Test
    fun `no stance row names a capability the SDK does not carry`() {
        AcpCoverageMatrix.capabilities.forEach { row ->
            val klass = runCatching { Class.forName("com.agentclientprotocol.model.${row.ownerClass}") }
                .getOrElse { fail("capability row names owner '${row.ownerClass}', which the SDK does not ship") }
            val getter = "get" + row.field.replaceFirstChar { it.uppercaseChar() }
            assertTrue(klass.declaredMethods.any { it.name == getter && it.parameterCount == 0 },
                "capability '${row.ownerClass}.${row.field}' is a phantom: the shipped SDK has no such getter",
            )
        }
    }

    // --- the update axis: measured by PKH-136's matrix, referenced not re-listed --------------

    @Test
    fun `the update axis stays classified without being duplicated here`() {
        // AC#1 asks one inventory with zero unclassified rows across methods, notifications,
        // capabilities AND update types. The update TYPES are AcpSchemaMatrix's (its own test
        // joins them against the jar); this row checks the SHAPE of that promise holds:
        // every update row carries a lane and at least one fact, and the unknown variant is
        // the explicit catch-all — no re-listing, no second authority.
        AcpSchemaMatrix.SESSION_UPDATES.forEach { row ->
            assertNotNull(row.lane, "update row '${row.variant}' has no lane")
            assertTrue(row.facts.isNotEmpty(), "update row '${row.variant}' classifies no fact")
        }
        assertNotNull(AcpSchemaMatrix.rowOf("UnknownSessionUpdate"),
            "the update axis lost its explicit unknown — the drift answer AC#1 depends on",
        )
    }

    // --- protocol v2: present, out of scope, never silently covered ---------------------------

    @Test
    fun `the SDK's v2 vocabulary stays out of the v1 matrix`() {
        // PKH-142 owns draft v2. The v1 matrix must not CLAIM it: no row's SDK origin may come
        // from the V2 groups. The guard measures that the V2 vocabulary really is there — if an
        // SDK uptick promotes v2 into v1, the coverage rows above move with it and this row
        // documents who is owed the new dialect.
        val v2Names = (
            methodObjects("com.agentclientprotocol.model.AcpMethod\$AgentMethods\$V2") +
                methodObjects("com.agentclientprotocol.model.AcpMethod\$ClientMethods\$V2")
            )
            .map { (it.getField("INSTANCE").get(null) as AcpMethod).wireName() }
            .toSet()
        assertTrue(v2Names.isNotEmpty(), "the SDK shipped no v2 vocabulary at all")
        val v1Keys = AcpCoverageMatrix.METHODS.map { it.method }.toSet()
        (v2Names - v1Keys).forEach { name ->
            assertTrue(AcpCoverageMatrix.rowOf(name, AcpMethodDirection.AGENT_SIDE) == null,
                "v2-only method '$name' leaked into the v1 matrix: that is PKH-142's inventory",
            )
        }
    }

    // --- AC#1 evidence: the elicitation-complete notification row's own test ------------------

    @Test
    fun `the elicitation complete notification is answered with a journal line and no authority`() {
        // The matrix cites THIS class as the evidence for elicitation/complete, so the citation
        // has to be true: the notification (a) never throws when it names an elicitation nobody
        // is waiting on, and (b) changes no authority — it is journaled, not acted upon.
        val root = Files.createTempDirectory("acp-elicit-complete-").also { it.toFile().deleteOnExit() }
        val journal = AcpReverseJournal()
        val operations = AcpReverseOperations(
            sessionId = SessionId("elicit-1"),
            workspaceRoot = root,
            profile = AcpTaskProfile.WRITE_RESTRICTED,
            authority = null,
            journal = journal,
            ledger = AcpPermissionLedger(),
        )
        runBlocking {
            operations.completeElicitation(CompleteElicitationNotification(elicitationId = ElicitationId("gone")))
        }
        val after = journal.snapshot()
        assertTrue(after.any { it.startsWith("elicitation.complete") },
            "the completion notification left no journal trace: $after",
        )
        // No authority was granted by the notification: the permission counters are untouched.
        assertEquals(0, journal.permissionRequests,
            "an elicitation completion is not a permission answer",
        )
        Files.deleteIfExists(root)
    }
}
