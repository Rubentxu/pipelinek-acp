package pipelinek.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-142 AC#3 and AC#4 (the parts the wire cannot cheaply prove): the negotiation is
 * explicit, the experimental adapter is OFF by default, the v2 announcement cannot carry a
 * removed v1 capability, and the dialect differences (auth, resume cursor, per-entity cancel)
 * stay as stated rows.
 */
class AcpV2HandshakeTest {

    // --- AC#4: opt-in, measured at the gate itself ---------------------------------------------

    @Test
    fun `the v2 adapter is disabled by default and asks for v2 only on the explicit opt-in`() {
        assertFalse(AcpV2Adapter.DEFAULT_OPT_IN,
            "v2 is draft: the default answer of the shipped plugin must be v1",
        )
        assertEquals(1, AcpV2Adapter.requestedProtocolVersion(),
            "without the opt-in the handshake requests version 1 — the draft never rides a " +
                "default",
        )
        assertEquals(2, AcpV2Adapter.requestedProtocolVersion(optInV2 = true),
            "the opt-in is the ONLY way the connection asks for 2",
        )
    }

    // --- AC#3: no removed v1 capability can be announced in v2 ---------------------------------

    @Test
    fun `the v2 announcement cannot even represent the removed v1 capabilities`() {
        // The migration removed the client fs/terminal capabilities ENTIRELY. The strong form
        // of the promise: the v2 type has no such fields, so no mapping accident can announce
        // them. (The weak form — serialization — rides below; if a field ever reappears in an
        // SDK uptick, this row goes red first.)
        val fieldNames = com.agentclientprotocol.model.v2.ClientCapabilities::class
            .members.map { it.name }
        listOf("fs", "terminal", "loadSession", "plan").forEach { removed ->
            assertFalse(fieldNames.any { it == removed },
                "the SDK's v2 client capabilities grew '$removed' back: v2 REMOVED it, and an " +
                    "announcement carrying it would advertise a promise the draft does not define",
            )
        }
    }

    @Test
    fun `the serialized v2 announcement states only v2 shapes`() {
        val info = AcpV2Adapter.clientInfoV2("test")
        val element = Json.encodeToJsonElement(
            com.agentclientprotocol.model.v2.ClientCapabilities.serializer(),
            info.capabilities,
        ).jsonObject
        listOf("fs", "terminal", "loadSession", "plan").forEach { removed ->
            assertFalse(removed in element.keys, "announcement carries removed key '$removed'")
        }
        assertEquals(2, info.protocolVersion)
        assertEquals("pipelinek-acp", info.implementation.name,
            "the v2 path announces the same identity as the v1 one — experimental dialect, " +
                "same plugin",
        )
    }

    // --- AC#3: the differences of the dialects, one stated row each ----------------------------

    @Test
    fun `auth rides renamed rows and the v1 oracle keeps its own verdicts`() {
        // authenticate → auth/login, logout → auth/logout: RENAMED (one operation, two names),
        // and the v1 matrix's classifications stay EXACTLY where PKH-141 left them.
        assertEquals(AcpV2EquivalenceKind.RENAMED, AcpV2VocabularyEquivalence.rowFor("auth/login")?.kind)
        assertEquals(AcpV2EquivalenceKind.RENAMED, AcpV2VocabularyEquivalence.rowFor("authenticate")?.kind)
        assertEquals(AcpV2EquivalenceKind.RENAMED, AcpV2VocabularyEquivalence.rowFor("auth/logout")?.kind)
        // v1 logout stays TESTED in the v1 matrix (PKH-141's no-regression oracle, untouched);
        // its v2 name is declined with its own argument — the decline did not silently migrate.
        assertEquals(AcpRowClassification.TESTED,
            AcpCoverageMatrix.rowOf("logout", AcpMethodDirection.AGENT_SIDE)?.classification)
        assertEquals(AcpRowClassification.UNSUPPORTED,
            AcpV2CoverageMatrix.rowOf("auth/logout", AcpMethodDirection.AGENT_SIDE)?.classification)
        assertNull(AcpCoverageMatrix.rowOf("auth/login", AcpMethodDirection.AGENT_SIDE),
            "the v2 name must not leak into the v1 matrix",
        )
    }

    @Test
    fun `the resume cursor replaces session load instead of merging into it`() {
        // session/load is gone in v2; its job moves to session/resume + replayFrom. The
        // equivalence keeps BOTH facts: load is REPLACED_BY_MECHANISM (not equivalent), resume
        // is EQUIVALENT, and normalizing either name never returns the other's operation.
        assertEquals(AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
            AcpV2VocabularyEquivalence.rowFor("session/load")?.kind)
        assertEquals("session_load", AcpV2VocabularyEquivalence.normalize("session/load"))
        assertEquals("session/resume", AcpV2VocabularyEquivalence.normalize("session/resume"))
        assertFalse(AcpV2VocabularyEquivalence.sameNormalizedVocabulary("session_load"),
            "fusing load into resume would erase the no-replay resume v1 also had",
        )
        assertNotNull(AcpV2CoverageMatrix.rowOf("session/resume", AcpMethodDirection.AGENT_SIDE))
        // v1's session/load row stays TESTED: the v1 dialect is still the supported path.
        assertEquals(AcpRowClassification.TESTED,
            AcpCoverageMatrix.rowOf("session/load", AcpMethodDirection.AGENT_SIDE)?.classification)
    }

    @Test
    fun `the v1 client file system and terminal legs are stated removed, never renamed`() {
        // The other half of AC#3's auth/fs/terminal differences: the v1 REVERSE leg methods
        // have no v2 name at all — they are REMOVED_IN_V2 rows, and the v1 matrix keeps them
        // TESTED (the shipped dialect).
        listOf("fs/read_text_file", "fs/write_text_file", "terminal/create", "terminal/output",
            "terminal/wait_for_exit", "terminal/kill", "terminal/release",
        ).forEach { name ->
            assertEquals(AcpV2EquivalenceKind.REMOVED_IN_V2, AcpV2VocabularyEquivalence.rowFor(name)?.kind,
                "'$name' lost its removal row",
            )
            assertEquals(AcpRowClassification.TESTED,
                AcpCoverageMatrix.rowOf(name, AcpMethodDirection.CLIENT_SIDE)?.classification,
                "the v1 oracle moved: PKH-141's rows are frozen",
            )
        }
    }

    // --- AC#4: the split at the SDK level, from both ends ---------------------------------------

    @Test
    fun `the v1 matrix and the v2 matrix hold disjoint dialect-specific vocabularies`() {
        // v2-only names never enter v1; v1-only names never enter v2. The equivalence table is
        // the ONLY place the two inventories touch, and only for its EQUIVALENT/RENAMED rows.
        val v1Names = AcpCoverageMatrix.METHODS.map { it.method }.toSet()
        val v2Names = AcpV2CoverageMatrix.METHODS.map { it.method }.toSet()
        (v2Names - v1Names).forEach { name ->
            assertNotNull(AcpV2VocabularyEquivalence.rowFor(name),
                "v2-only name '$name' crosses the dialect boundary with no equivalence row",
            )
        }
        (v1Names - v2Names).forEach { name ->
            assertNotNull(AcpV2VocabularyEquivalence.rowFor(name),
                "v1-only name '$name' crosses the dialect boundary with no equivalence row",
            )
        }
    }
}
