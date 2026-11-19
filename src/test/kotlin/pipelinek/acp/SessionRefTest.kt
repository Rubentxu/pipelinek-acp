package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ownership token a session ref carries (PKH-135 AC#4), tested as the pure decision it
 * is: mint, encode, decode, compare — no process, no pipe.
 *
 * The ref is opaque: the durable output must not leak the agent's wire id as something a
 * reader could address, and a ref from another connection must be provably foreign by its
 * nonce rather than by a lookup the connection cannot make after its process died.
 */
class SessionRefTest {

    @Test
    fun `a minted ref parses back with every component and the agent id stays inside`() {
        val nonce = "owner-nonce-1"
        val ref = SessionRef.mint(nonce, "session-42")
        assertNotNull(SessionRef.parse(ref.value))
        assertEquals(nonce, ref.ownerNonce)
        assertEquals("session-42", ref.agentSessionId)
        // the token is opaque: the agent id is not plain text in the value the journal keeps
        assertFalse(ref.value.contains("session-42"), "the wire id must not leak as plain text")
        assertTrue(SessionRef.ownedBy(ref, nonce))
    }

    @Test
    fun `a ref from another connection is foreign by its nonce alone`() {
        val mine = SessionRef.mint("connection-a", "session-1")
        val parsed = SessionRef.parse(mine.value)!!
        assertFalse(SessionRef.ownedBy(parsed, "connection-b"))
    }

    @Test
    fun `a malformed token is not a session ref and never decodes into a fake one`() {
        assertNull(SessionRef.parse(""))
        assertNull(SessionRef.parse("plain-agent-id"))
        assertNull(SessionRef.parse("not-base64!!"))
        // well-formed base64 but not the v1 envelope: garbage in, refusal-shaped null out.
        val foreign = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("v2|1|n|s".toByteArray())
        assertNull(SessionRef.parse(foreign))
    }

    @Test
    fun `every mint is distinct and its epoch rises`() {
        val a = SessionRef.mint("c", "s1")
        val b = SessionRef.mint("c", "s2")
        assertNotEquals(a.value, b.value)
        assertTrue(b.connectionEpoch > a.connectionEpoch,
            "the epoch is the JVM-wide owner serial; equal epochs would make refs interchangeable")
    }
}
