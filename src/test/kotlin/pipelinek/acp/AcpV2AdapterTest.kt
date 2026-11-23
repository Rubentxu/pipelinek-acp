package pipelinek.acp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The v2 adapter's routing law and opt-in switch, measured (PKH-142 AC#1, AC#4).
 *
 * Three claims, each the cheapest witness of a way the dual stack could lie:
 *
 *   * the route table is THE PINNED RESOURCE, not a copy in code: stable names route
 *     STABLE_BASELINE, unstable-only names route UNSTABLE_EXTENSION, and a v1-only or foreign
 *     name routes NOT_IN_V2 — so an upstream diff updates the table by updating the data;
 *   * the no-cross-dialect rule holds per name: a v1-negotiated session never dials a v2
 *     introduction, a v2-negotiated session never dials what v2 removed or renamed away, and
 *     shared names dial on both;
 *   * the adapter is OFF unless an operator says the literal "true" (AC#4): a draft protocol
 *     cannot be enabled by a typo, a truncated value or the mere presence of the property.
 */
class AcpV2AdapterTest {

    @AfterEach
    fun clearOptIn() {
        System.clearProperty(AcpV2Adapter.ENABLE_PROPERTY)
    }

    // --- the route table follows the pinned resource ------------------------------------------

    @Test
    fun `stable v2 names route to the baseline and unstable-only names are marked extensions`() {
        for (name in listOf(
            "initialize", "session/new", "session/prompt", "session/cancel", "session/resume",
            "session/close", "session/list", "session/delete", "session/set_config_option",
            "auth/login", "auth/logout", "tool_call/cancel", "terminal/cancel",
            "$/cancel_request", "session/update", "session/request_permission",
            "elicitation/create", "elicitation/complete",
        )) {
            assertEquals(AcpV2Route.STABLE_BASELINE, AcpV2Adapter.route(name),
                "'$name' is in schema/v2/meta.json and must route as the stable baseline")
        }
        for (name in listOf(
            "session/fork", "session/archive", "session/status", "session/options",
            "session/set_title", "providers/list", "providers/set", "providers/disable",
            "auth/status", "mcp/message", "auth/status_update", "proxy/initialize",
        )) {
            assertEquals(AcpV2Route.UNSTABLE_EXTENSION, AcpV2Adapter.route(name),
                "'$name' lives only in meta.unstable.json and must carry the extension mark")
        }
    }

    @Test
    fun `v1-only and foreign names are not v2 vocabulary`() {
        // The removals and renames AC#3 tests at the vocabulary level: these names are not
        // merely unsupported in v2 — they are NOT v2, and the routing law says so by name.
        for (name in listOf(
            "authenticate", "logout", "session/load", "session/set_mode", "session/set_model",
            "fs/read_text_file", "fs/write_text_file", "terminal/create", "terminal/kill",
            "nes/start", "document/didOpen", "proxy/unknown", "_pipelinekTest/whatever",
        )) {
            assertEquals(AcpV2Route.NOT_IN_V2, AcpV2Adapter.route(name),
                    "'$name' is v1 vocabulary (or foreign) and must never route as v2")
        }
    }

    @Test
    fun `the route table is loaded from the pinned resource, both sections`() {
        // The join that makes AC#1's updatability claim testable: what route() answers for
        // EVERY name of the resource equals what the resource's sections say — no name is
        // special-cased in code, so replacing the resource with an upstream diff replaces the
        // routing law with it.
        val pinned = AcpV2Adapter.stableMethodNames + AcpV2Adapter.unstableMethodNames
        assertTrue(pinned.size >= 30, "the resource yielded ${pinned.size} names")
        pinned.forEach { name ->
            val expected = when {
                name in AcpV2Adapter.stableMethodNames -> AcpV2Route.STABLE_BASELINE
                else -> AcpV2Route.UNSTABLE_EXTENSION
            }
            assertEquals(expected, AcpV2Adapter.route(name), "route disagrees with the resource on '$name'")
        }
    }

    // --- the no-cross-dialect rule -------------------------------------------------------------

    @Test
    fun `a v1 session never dials what v2 introduced`() {
        // The v2-only names (present in the v2 inventory, absent from the frozen v1 one): the
        // routing law must refuse every one of them for a V1 connection. The stable set here
        // is exactly where "unknown v2 methods on a v1 session" becomes a PROTOCOL-CORRECT
        // refusal instead of a wire error the agent answers with -32601.
        val v1Names = AcpCoverageMatrix.METHODS.map { it.method }.toSet()
        val v2Only = AcpV2Adapter.stableMethodNames - v1Names
        assertTrue("tool_call/cancel" in v2Only && "terminal/cancel" in v2Only && "auth/login" in v2Only,
            "the join found no v2-only stable names: $v2Only")
        v2Only.forEach { name ->
            assertFalse(AcpV2Adapter.dialsOn(AcpProtocolVersion.V1, name),
                "'$name' is v2-only and a v1 session must not dial it")
        }
    }

    @Test
    fun `a v2 session never dials what v2 removed or renamed away`() {
        // The v1 TESTED/UNSUPPORTED names v2 does not speak: the renames (authenticate,
        // plain logout), the merged one (session/load → resume with a cursor), the folded one
        // (session/set_mode → config options) and the deleted client legs (fs/*, terminal/*).
        val removed = listOf(
            "authenticate", "logout", "session/load", "session/set_mode", "session/set_model",
            "fs/read_text_file", "fs/write_text_file", "terminal/create", "terminal/output",
            "terminal/wait_for_exit", "terminal/kill", "terminal/release", "nes/start",
            "document/didOpen",
        )
        removed.forEach { name ->
            assertTrue(name in AcpCoverageMatrix.METHODS.map { it.method },
                "'$name' is no longer a v1 matrix name — this row needs re-derivation")
            assertFalse(AcpV2Adapter.dialsOn(AcpProtocolVersion.V2, name),
                "'$name' was removed or renamed by v2 and a v2 session must not dial it")
        }
    }

    @Test
    fun `a name both dialects speak dials on both`() {
        listOf("session/prompt", "session/new", "session/cancel", "initialize").forEach { name ->
            assertTrue(AcpV2Adapter.dialsOn(AcpProtocolVersion.V1, name), name)
            assertTrue(AcpV2Adapter.dialsOn(AcpProtocolVersion.V2, name), name)
        }
    }

    // --- AC#4: the experimental switch is off by default ----------------------------------------

    @Test
    fun `the v2 adapter is disabled unless the literal true opts in`() {
        System.clearProperty(AcpV2Adapter.ENABLE_PROPERTY)
        // The default. (The environment slot is checked in the same law; the ambient CI
        // environment must not carry it, and if it did, the property still wins below.)
        val offByDefault = AcpV2Adapter.unstableMethodNames.isNotEmpty() &&
            run {
                System.getProperty(AcpV2Adapter.ENABLE_PROPERTY)
                // no property set: the answer is whatever the env says — assert both paths.
                val env = System.getenv(AcpV2Adapter.ENABLE_ENV)
                AcpV2Adapter.enabled() == (env == "true")
            }
        assertTrue(offByDefault, "enabled() must equal the literal 'true' of property-or-env, nothing else")
        for (value in listOf("1", "yes", "TRUE", "on", "false", "")) {
            System.setProperty(AcpV2Adapter.ENABLE_PROPERTY, value)
            assertFalse(AcpV2Adapter.enabled(), "'$value' is not the literal true and must not enable v2")
        }
        System.setProperty(AcpV2Adapter.ENABLE_PROPERTY, "true")
        assertTrue(AcpV2Adapter.enabled(), "the literal true must enable the experimental adapter")
    }

    // --- the v2 initialization identity ----------------------------------------------------------

    @Test
    fun `the announced v2 client info requests protocol version 2 and promises no reverse leg`() {
        val info = AcpV2Adapter.clientInfoV2("0.0.0-test")
        assertEquals(AcpProtocolVersion.V2.wireVersion, info.protocolVersion,
            "the v2 initialize asks for version 2 explicitly — the negotiation doc's demand")
        assertEquals("pipelinek-acp", info.implementation.name)
        // The empty capability set: AC#3's removal clause is structural (the v2 type has no
        // fs/terminal fields at all — asserted in AcpV2CoverageMatrixTest) AND a decision
        // (nothing else is promised either).
        val capabilities = info.capabilities
        assertNotNull(capabilities)
        assertEquals(null, capabilities.auth, "the adapter announces no client auth extension")
        assertEquals(null, capabilities.elicitation,
            "elicitation is answered defensively, never announced")
    }
}
