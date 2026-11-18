package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The transport rules of AC#3: stdout reserved, stderr drained with a bound, exits observable. */
class AcpTransportTest {

    @Test
    fun `the stderr ring evicts oldest lines and never exceeds its byte bound`() {
        val ring = BoundedLineRing(capBytes = 64)
        repeat(50) { ring.add("line-$it-padding") }
        val kept = ring.joined().split("\n")
        val keptBytes = kept.sumOf { it.length }
        assertTrue(keptBytes <= 64, "ring kept $keptBytes bytes over a 64-byte bound")
        assertTrue("line-49-padding" in kept, "the newest line must survive eviction")
        assertTrue("line-0-padding" !in kept, "the oldest line must be evicted first")
    }

    @Test
    fun `a finished process reports its exit code and its stderr tail`() {
        val process = AcpAgentProcess.open(listOf("sh", "-c", "echo noise-to-stderr 1>&2; exit 3"))
        try {
            var exit: Int? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (exit == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                exit = process.exitCodeOrNull()
            }
            assertEquals(3, exit, "a step child that died with 3 must report 3, not silence")
            // The drain thread reads to EOF; give it one settle tick after exit — the pipe
            // closing is what ends it, and the lines before EOF must already be in the ring.
            Thread.sleep(100)
            assertTrue("noise-to-stderr" in process.stderrTail, "stderr was never drained: '${process.stderrTail}'")
        } finally {
            process.destroy()
        }
    }

    @Test
    fun `a missing executable is an unreachable agent, never a crash and never a success`() {
        val failure = runCatching { AcpAgentProcess.open(listOf("/nonexistent/acp-agent-xyz")) }
        val refusal = (failure.exceptionOrNull() as AgentLaunchFailed).refusal
        assertEquals("ACP_AGENT_UNREACHABLE", refusal.kindToken)
        assertTrue(refusal.detail.isNotBlank())
    }
}
