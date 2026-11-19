package pipelinek.acp

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reverse leg over a REAL pipe (PKH-134): the fixture agent — an independent second
 * implementation of the wire — starts the agent→client requests, and the declared authority
 * answers them through the SDK's own dispatch. This is the pairing the unit tests cannot
 * prove: framing, request-id correlation and the capability negotiation of initialize all
 * have to agree across the two codebases before a grant or a fence refusal can happen at all.
 *
 * Profile note (the honest shape): the handler-level gate refuses WRITE_RESTRICTED wholesale
 * on this host (no confinement attestation, PKH-133), so only READ-only ceilings run in
 * production today. These rows drive [AcpConnection.exchangeTask] DIRECTLY, below that gate,
 * to test the machinery that will serve the wider ceiling the moment the host offers the
 * attestation. The grant rows that run under READONLY — the production ceiling — are the
 * permission ones, and they show the whole point of AC#1: the SAME agent request that
 * PKH-132 answered Cancelled unconditionally now follows the declaration.
 */
class AcpReverseLegE2ETest {

    private val fixture: Path = run {
        val url = checkNotNull(javaClass.getResource("/acp_task_fixture_agent.py")) {
            "the task fixture agent is missing from the test resources"
        }
        Path.of(url.toURI())
    }

    private fun agent(vararg args: String): List<String> = listOf("python3", "-I", fixture.toString()) + args

    private fun exchange(
        root: Path,
        mode: List<String>,
        profile: AcpTaskProfile,
        authority: AcpAuthority?,
    ): AcpExchange = runBlocking {
        AcpConnection(pluginVersion = "0.0.0-test").exchangeTask(
            input = AcpTaskInput(
                command = agent(*mode.toTypedArray()),
                prompt = "reverse probe",
                profile = profile,
                budgetSeconds = 60,
            ),
            environment = AcpTaskIsolation.childEnvironment(null),
            workingDirectory = root,
            reverse = AcpReverseSession(root, profile, authority),
        )
    }

    private fun replyOf(exchange: AcpExchange): String {
        val completed = exchange as? AcpExchange.Completed
            ?: throw AssertionError("the probe exchange must complete, got ${(exchange as AcpExchange.Refused).refusal.detail}")
        return completed.reply
    }

    // ------------------------------------------------------------------ AC#1 permissions

    @Test
    fun `a declared READ authority grants the offered allow-once option, over the real wire`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        val exchange = exchange(root, listOf("permission", "read"), AcpTaskProfile.READONLY,
            AcpAuthority(listOf(AcpToolEffect.READ)))
        // The reply names the optionId the AGENT offered — a grant selects it verbatim.
        assertEquals("PERM=granted:opt-once", replyOf(exchange),
            "allow-once is preferred and no id may be invented")
        assertTrue(
            exchange.reverseEvents().any { it == "perm granted (read)" },
            "the journal must ride out with the completed exchange: ${exchange.reverseEvents()}",
        )
    }

    @Test
    fun `the same request an authority does not declare is denied - and absence still denies everything`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        val outsideEffect = replyOf(
            exchange(root, listOf("permission", "edit"), AcpTaskProfile.READONLY,
                AcpAuthority(listOf(AcpToolEffect.READ))),
        )
        assertEquals("PERM=denied", outsideEffect, "an edit-kind request under a READ authority must be denied")
        val noAuthority = replyOf(
            exchange(root, listOf("permission", "read"), AcpTaskProfile.READONLY, null),
        )
        assertEquals("PERM=denied", noAuthority, "deny-by-default is the ABSENCE of authority, not a value")
    }

    // ------------------------------------------------------------------ AC#2 filesystem

    @Test
    fun `fs write and read run inside the fence and an escape is refused with the resource-not-found code`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        java.nio.file.Files.writeString(root.resolve("../escape.txt").normalize(), "outside")
        val reply = replyOf(
            exchange(root, listOf("fs-probe"), AcpTaskProfile.WRITE_RESTRICTED,
                AcpAuthority(listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE))),
        )
        // FS=write-ok:read-<content>:<escape-code> — the escape must carry a JSON-RPC error.
        val parts = reply.removePrefix("FS=").split(":")
        assertEquals(3, parts.size, "unexpected probe reply: $reply")
        assertEquals("write-ok", parts[0])
        assertEquals("read-rev-write-ok", parts[1])
        assertEquals("err--32002", parts[2], "the fence answers -32002, never the outside bytes")
        // And the write really landed INSIDE the root.
        assertEquals("rev-write-ok", java.nio.file.Files.readString(root.resolve("rev-fs/written.txt")))
    }

    @Test
    fun `fs writes are refused when the authority does not declare MUTATE, with the invalid-params code`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        val reply = replyOf(
            exchange(root, listOf("fs-probe"), AcpTaskProfile.READONLY,
                AcpAuthority(listOf(AcpToolEffect.READ))),
        )
        assertTrue(reply.startsWith("FS=write-err--32602"), "READ-only authority must refuse the write: $reply")
        assertTrue(
            replyOf(exchange(root, listOf("fs-probe"), AcpTaskProfile.READONLY, null))
                .startsWith("FS=write-err--32602"),
            "no authority at all refuses by the same door",
        )
    }

    // ------------------------------------------------------------------ AC#3 terminal

    @Test
    fun `a terminal runs, reports output and exit, and a foreign id is not resolvable`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        val reply = replyOf(
            exchange(root, listOf("term-probe"), AcpTaskProfile.WRITE_RESTRICTED,
                AcpAuthority(listOf(AcpToolEffect.EXECUTE))),
        )
        assertEquals("TERM=exit0:out-term-ok:kill-err--32002:release-ok", reply,
            "create→wait→output must round-trip; the foreign kill is not-found; release closes it")
        // The exchange left NO live terminal behind: reap runs inside cleanup, and the ids
        // die with the session.
        assertTrue(
            replyOf(exchange(root, listOf("term-probe"), AcpTaskProfile.READONLY,
                AcpAuthority(listOf(AcpToolEffect.READ)))).startsWith("TERM=create-err--32602"),
            "READ authority may not open a terminal at all",
        )
    }

    // ------------------------------------------------------------------ AC#4 elicitation

    @Test
    fun `an unadvertised elicitation is answered with the protocol cancel, not a hang`() {
        val root = java.nio.file.Files.createTempDirectory("acp-rev-e2e-")
        val started = System.nanoTime()
        val reply = replyOf(exchange(root, listOf("elic-probe"), AcpTaskProfile.READONLY,
            AcpAuthority(listOf(AcpToolEffect.READ))))
        assertEquals("ELIC=cancel", reply, "the honest answer is cancel, said in the vocabulary")
        assertTrue(TimeUnit.SECONDS.convert(System.nanoTime() - started, TimeUnit.NANOSECONDS) < 30,
            "an elicitation must never park the exchange on a human that cannot answer")
    }

    private fun AcpExchange.reverseEvents(): List<String> =
        (this as AcpExchange.Completed).reverseEvents
}
