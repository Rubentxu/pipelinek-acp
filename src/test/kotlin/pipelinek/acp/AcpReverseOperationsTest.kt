package pipelinek.acp

import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.JsonRpcException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reverse leg of PKH-134, driven DIRECTLY through [AcpReverseOperations] — the same
 * object the real SDK factory builds inside a session, minus the pipe. Each row asserts
 * both halves of the contract: the wire answer (typed response or JSON-RPC error code) and
 * the journal event the durable step output will carry.
 *
 * No fixture process here on purpose: the fence, the quota and the ledger are decisions,
 * and a decision that needs a subprocess to be tested is a decision observed too late. The
 * end-to-end pairing (agent asks, client answers, journal rides out) is the e2e gate row's
 * job against the real host; these rows are the cheap gate that can fail.
 */
class AcpReverseOperationsTest {

    private val session = SessionId("rev-1")

    /**
     * WRITE_RESTRICTED is the profile that HOSTS write/execute effects, so the fs and
     * terminal rows below run under it with a full authority: the PROFILE ceiling itself
     * is AcpAuthorityTest's subject (decide() over all profiles), and the two READONLY
     * rows in here name their own narrower authority. What every row shares is that the
     * AUTHORITY leg is what gates these operations, which is AC#1's intersection minus
     * the ceiling — tested separately, not re-tested forty times.
     */
    private fun operations(
        root: Path,
        profile: AcpTaskProfile = AcpTaskProfile.WRITE_RESTRICTED,
        authority: AcpAuthority? = AcpAuthority(listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE, AcpToolEffect.EXECUTE)),
        journal: AcpReverseJournal = AcpReverseJournal(),
        ledger: AcpPermissionLedger = AcpPermissionLedger(),
    ) = Triple(
        AcpReverseOperations(session, root, profile, authority, journal, ledger),
        journal,
        ledger,
    )

    private fun toolCall(kind: ToolKind?, id: String = "tc-1") = SessionUpdate.ToolCallUpdate(
        toolCallId = ToolCallId(id),
        title = "agent tool",
        kind = kind,
    )

    private fun option(id: String, kind: PermissionOptionKind) =
        PermissionOption(PermissionOptionId(id), id, kind, null)

    /** The seam's acceptance log, enough for the notification-routing rows below. */
    private class ListingEmission : dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission {
        val payloads = mutableListOf<Any?>()
        override fun <P : Any> emit(
            kind: String,
            payload: P,
        ): dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome {
            payloads.add(payload)
            return dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome.Emitted(
                dev.rubentxu.pipeline.v2.events.PluginEventEmitted(
                    eventId = "evt-rev",
                    runId = "run-rev",
                    sequence = 1L,
                    occurredAt = java.time.Instant.EPOCH,
                    registryKind = kind,
                    schemaVersion = AcpObservationCodec.SCHEMA_VERSION,
                    payload = AcpObservationCodec.encode(payload as AcpObservation),
                    emittedBy = AcpPluginDeclaration.CONTRIBUTOR_ID,
                ),
            )
        }
    }

    @Test
    fun `a notification outside the prompt stream reaches the observation lane and keeps its journal row`() {
        // What the SDK routes to notify (no prompt in flight) is still an update the agent
        // sent: dropping it here means the run's stream says the agent was silent. The
        // journalizado of the reverse leg must survive the forwarding, unchanged.
        val root = Files.createTempDirectory("acp-rev-notify-")
        val emission = ListingEmission()
        val publisher = AcpObservationPublisher(emission) { "run-rev-0" }
        val journal = AcpReverseJournal()
        val ops = AcpReverseOperations(
            session, root, AcpTaskProfile.READONLY, null, journal, AcpPermissionLedger(),
            observer = publisher,
        )
        runBlocking {
            ops.notify(
                SessionUpdate.AgentThoughtChunk(
                    com.agentclientprotocol.model.ContentBlock.Text("thought outside the prompt"),
                ),
                null,
            )
            ops.notify(toolCall(ToolKind.READ, id = "tc-n").copy(status = com.agentclientprotocol.model.ToolCallStatus.COMPLETED), null)
        }
        publisher.drain()

        val tokens = emission.payloads.filterIsInstance<AcpObservation>().map { it.token }
        assertTrue(AcpFactToken.AGENT_THOUGHT_CHUNK in tokens, "the thought vanished: $tokens")
        assertTrue(AcpFactToken.TOOL_CALL_CHANGED in tokens, "the tool-call notification vanished: $tokens")
        assertTrue(
            journal.snapshot().any { it.startsWith("tool agent tool") },
            "forwarding must not drop the journal row: ${journal.snapshot()}",
        )
    }

    // ---------------------------------------------------------------- permissions AC#1

    @Test
    fun `a declared authority grants by selecting the option the agent offered, allow-once first`() {
        val root = Files.createTempDirectory("acp-rev-perm-")
        val (ops, journal, _) = operations(root)
        val answer = runBlocking {
            ops.requestPermissions(
                toolCall(ToolKind.READ),
                listOf(option("reject", PermissionOptionKind.REJECT_ONCE), option("always", PermissionOptionKind.ALLOW_ALWAYS), option("once", PermissionOptionKind.ALLOW_ONCE)),
                null,
            )
        }
        val outcome = answer.outcome
        assertTrue(outcome is RequestPermissionOutcome.Selected, "authority READ must grant: $outcome")
        assertEquals(
            PermissionOptionId("once"),
            (outcome as RequestPermissionOutcome.Selected).optionId,
            "allow-once is preferred; ALLOW_ALWAYS would be a wider door than the grant",
        )
        assertTrue(journal.snapshot().any { it.startsWith("perm granted (read)") }, journal.snapshot().toString())
    }

    @Test
    fun `an agent request with no kind is effect MISC and an ungranted effect is denied and journaled`() {
        val root = Files.createTempDirectory("acp-rev-perm-")
        val (ops, journal, _) = operations(root, authority = AcpAuthority(listOf(AcpToolEffect.READ)))
        val answer = runBlocking { ops.requestPermissions(toolCall(null), listOf(option("once", PermissionOptionKind.ALLOW_ONCE)), null) }
        assertTrue(answer.outcome is RequestPermissionOutcome.Cancelled, "kind-less must not slip past: ${answer.outcome}")
        assertTrue(journal.snapshot().any { it == "perm denied (misc)" }, journal.snapshot().toString())
    }

    @Test
    fun `a grant with no offerable option cancels instead of inventing an optionId`() {
        val root = Files.createTempDirectory("acp-rev-perm-")
        val (ops, _, _) = operations(root)
        val answer = runBlocking { ops.requestPermissions(toolCall(ToolKind.EDIT), emptyList(), null) }
        assertTrue(answer.outcome is RequestPermissionOutcome.Cancelled, "no options to select: $answer")
    }

    // ---------------------------------------------------------------- filesystem AC#2

    @Test
    fun `fs read returns windowed lines inside the fence`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        Files.writeString(root.resolve("data.txt"), "l1\nl2\nl3\n")
        val (ops, journal, _) = operations(root)
        val response = runBlocking { ops.fsReadTextFile("data.txt", 2u, 1u, null) }
        assertEquals("l2", response.content)
        assertTrue(journal.snapshot().any { it.startsWith("fs.read ok") }, journal.snapshot().toString())
    }

    @Test
    fun `fs read refuses absolute, escaping and symlink paths with resource-not-found`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        val outside = Files.createTempDirectory("acp-rev-outside-")
        Files.writeString(outside.resolve("secret.txt"), "top-secret")
        Files.createSymbolicLink(root.resolve("escape"), outside.resolve("secret.txt"))
        val (ops, journal, _) = operations(root)
        for (path in listOf(outside.resolve("secret.txt").toString(), "../escapes-root", "escape")) {
            val error = assertThrows(JsonRpcException::class.java) {
                runBlocking { ops.fsReadTextFile(path, null, null, null) }
            }
            assertEquals(-32002, error.code, "fence breach must answer resource-not-found, not leak: $path")
        }
        val events = journal.snapshot()
        assertTrue(events.any { it.contains("absolute") }, events.toString())
        assertTrue(events.any { it.contains("path escape") }, events.toString())
        assertTrue(events.any { it.contains("symlink escape") }, events.toString())
    }

    @Test
    fun `fs write refuses a symlink target even one pointing back inside the root`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        val real = root.resolve("real.txt")
        Files.writeString(real, "original")
        Files.createSymbolicLink(root.resolve("link.txt"), real)
        val (ops, journal, _) = operations(root)
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.fsWriteTextFile("link.txt", "through-the-link", null) }
        }
        assertEquals(-32002, error.code)
        assertEquals("original", Files.readString(real), "the write through the planted link must not land")
        assertTrue(journal.snapshot().any { it.contains("symlink/escape") }, journal.snapshot().toString())
    }

    @Test
    fun `fs write creates plain files and is refused when the authority lacks MUTATE`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        val (ops, _, _) = operations(root)
        runBlocking { ops.fsWriteTextFile("new/out.txt", "created", null) }
        assertEquals("created", Files.readString(root.resolve("new/out.txt")))
        val readOnly = operations(root, authority = AcpAuthority(listOf(AcpToolEffect.READ))).first
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { readOnly.fsWriteTextFile("second.txt", "no", null) }
        }
        assertEquals(-32602, error.code, "a policy refusal carries INVALID_PARAMS: $error")
    }

    @Test
    fun `fs write refuses a create whose missing parent walks out through a planted link`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        val outside = Files.createTempDirectory("acp-rev-outside-")
        // root/deep -> outside; creating deep/escaped/new.txt would grow a file OUTSIDE the
        // root through the link even though the target itself never existed to be checked.
        Files.createSymbolicLink(root.resolve("deep"), outside)
        val (ops, journal, _) = operations(root)
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.fsWriteTextFile("deep/escaped/new.txt", "leaked", null) }
        }
        assertEquals(-32002, error.code, "the ancestor fence must resolve BEFORE creating anything")
        assertTrue(
            Files.list(outside).noneMatch { it.fileName.toString() == "escaped" },
            "nothing may be created outside the root by a refused write",
        )
        assertTrue(journal.snapshot().any { it.contains("parent escape") }, journal.snapshot().toString())
    }

    @Test
    fun `fs refuses content past the byte cap instead of truncating`() {
        val root = Files.createTempDirectory("acp-rev-fs-")
        val (ops, journal, _) = operations(root)
        val big = "x".repeat(AcpReverseOperations.MAX_FS_BYTES.toInt() + 1)
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.fsWriteTextFile("big.txt", big, null) }
        }
        assertEquals(-32602, error.code)
        assertTrue(journal.snapshot().any { it.contains("too large") }, journal.snapshot().toString())
    }

    // ---------------------------------------------------------------- terminal AC#3

    @Test
    fun `terminal create-output-wait-kill-release works for the owning session`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, journal, _) = operations(root)
        val created = runBlocking { ops.terminalCreate("echo", listOf("hi"), null, emptyList(), null, null) }
        val response = runBlocking { ops.terminalWaitForExit(created.terminalId, null) }
        assertEquals(0u, response.exitCode)
        val output = runBlocking { ops.terminalOutput(created.terminalId, null) }
        assertEquals("hi\n", output.output)
        assertNotNull(output.exitStatus)
        runBlocking { ops.terminalRelease(created.terminalId, null) }
        assertTrue(journal.snapshot().any { it.startsWith("terminal.create") }, journal.snapshot().toString())
    }

    @Test
    fun `a foreign or unknown terminal id is not resolvable at all`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, _, _) = operations(root)
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.terminalOutput("term-other-session-1", null) }
        }
        assertEquals(-32002, error.code, "owner mismatch is structural: a foreign id is not-found")
    }

    @Test
    fun `the quota refuses the fifth concurrent terminal and a shell argv is refused`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, journal, _) = operations(root)
        val ids = (1..AcpReverseOperations.MAX_ACTIVE_TERMINALS).map {
            runBlocking { ops.terminalCreate("sleep", listOf("30"), null, emptyList(), null, null).terminalId }
        }
        val quotaError = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.terminalCreate("sleep", listOf("1"), null, emptyList(), null, null) }
        }
        assertEquals(-32602, quotaError.code)
        val shellError = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.terminalCreate("sh", listOf("-c", "echo hi"), null, emptyList(), null, null) }
        }
        assertEquals(-32602, shellError.code, "a shell through terminal.create is PKH-133's argv rule again")
        assertTrue(journal.snapshot().any { it.contains("quota") }, journal.snapshot().toString())
        // Freed capacity accepts the next create — the quota counts LIVE terminals.
        runBlocking { ops.terminalRelease(ids.first(), null) }
        runBlocking { ops.terminalCreate("echo", listOf("again"), null, emptyList(), null, null) }
    }

    @Test
    fun `reapTerminals kills what the agent left running and is bounded in time`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, _, _) = operations(root)
        val id = runBlocking { ops.terminalCreate("sleep", listOf("120"), null, emptyList(), null, null).terminalId }
        val started = System.nanoTime()
        val reaped = ops.reapTerminals()
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        assertEquals(1, reaped)
        assertTrue(seconds < 10, "reap must not wait the 120 s sleep out: ${seconds}s elapsed")
        val error = assertThrows(JsonRpcException::class.java) {
            runBlocking { ops.terminalOutput(id, null) }
        }
        assertEquals(-32002, error.code, "a reaped terminal is gone from the registry")
    }

    @Test
    fun `terminal output past the cap is bounded and flagged truncated`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, _, _) = operations(root)
        val id = runBlocking {
            ops.terminalCreate(
                "python3",
                listOf("-I", "-c", "import sys\nfor i in range(2000): print('%04d-line-of-output-padding' % i)"),
                null,
                listOf(EnvVariable("PATH", System.getenv("PATH") ?: "/usr/bin:/bin")),
                2048uL,
                null,
            ).terminalId
        }
        runBlocking { ops.terminalWaitForExit(id, null) }
        // The drain thread writes line by line; the process exited, so a settle is bounded.
        var output = runBlocking { ops.terminalOutput(id, null) }
        var waited = 0
        while (!output.truncated && waited < 50) {
            Thread.sleep(50)
            output = runBlocking { ops.terminalOutput(id, null) }
            waited++
        }
        assertTrue(output.truncated, "2000 long lines under a 2 KiB cap must flag truncation")
        assertTrue(output.output.length <= 4096, "the kept tail is bounded by the cap, got ${output.output.length}")
    }

    @Test
    fun `terminal env crosses only the allowlist - a secret-named variable is dropped`() {
        val root = Files.createTempDirectory("acp-rev-term-")
        val (ops, _, _) = operations(root)
        val id = runBlocking {
            ops.terminalCreate(
                "python3",
                listOf("-I", "-c", "import os\nprint('CANARY=' + os.environ.get('ACP_CANARY','absent'))"),
                null,
                listOf(EnvVariable("ACP_CANARY", "leaked")),
                null,
                null,
            ).terminalId
        }
        runBlocking { ops.terminalWaitForExit(id, null) }
        var output = runBlocking { ops.terminalOutput(id, null) }
        var waited = 0
        while ("CANARY" !in output.output && waited < 50) {
            Thread.sleep(50)
            output = runBlocking { ops.terminalOutput(id, null) }
            waited++
        }
        assertTrue("CANARY=absent" in output.output, "allowlist must drop ACP_CANARY: ${output.output}")
    }

    // ---------------------------------------------------------------- elicitation AC#4

    @Test
    fun `an elicitation asked without a human seam is answered with the protocol cancel, not silence`() {
        val root = Files.createTempDirectory("acp-rev-elic-")
        val (ops, journal, _) = operations(root)
        val response = runBlocking {
            ops.createElicitation(
                CreateElicitationRequest(
                    scope = ElicitationScope.Session(session),
                    mode = com.agentclientprotocol.model.ElicitationMode.Form(
                        com.agentclientprotocol.model.ElicitationSchema(),
                    ),
                    message = "give me your token",
                ),
            )
        }
        assertEquals(com.agentclientprotocol.model.ElicitationAction.Cancel, response.action)
        assertTrue(
            journal.snapshot().any { it.contains("elicitation refused") },
            journal.snapshot().toString(),
        )
    }

    // ---------------------------------------------------------------- session machinery

    @Test
    fun `the reverse session advertises only capabilities its authority can grant`() {
        val root = Files.createTempDirectory("acp-rev-cap-")
        val readOnly = AcpReverseSession(root, AcpTaskProfile.READONLY, AcpAuthority(listOf(AcpToolEffect.READ)))
        assertTrue(readOnly.fsCapabilities().readTextFile, "READ authority grants reads; advertise them")
        assertEquals(false, readOnly.fsCapabilities().writeTextFile)
        assertEquals(false, readOnly.terminalAdvertised())
        val none = AcpReverseSession(root, AcpTaskProfile.READONLY, null)
        assertEquals(false, none.fsCapabilities().readTextFile)
        assertEquals(false, none.terminalAdvertised())
    }

    @Test
    fun `cancel unwind answers the session's pending permission requests through the ledger`() {
        val root = Files.createTempDirectory("acp-rev-ledger-")
        val reverse = AcpReverseSession(root, AcpTaskProfile.READONLY, AcpAuthority(listOf(AcpToolEffect.READ)))
        // Register a pending entry the way an in-flight decision would (before answering):
        // the instance the factory builds shares this session's ledger by construction.
        val pending = kotlinx.coroutines.CompletableDeferred<com.agentclientprotocol.model.RequestPermissionResponse>()
        reverse.ledger.register(ToolCallId("tc-open"), pending)
        assertEquals(1, reverse.cancelOutstandingPermissions())
        assertTrue(pending.isCompleted)
        assertTrue(pending.getCompleted().outcome is RequestPermissionOutcome.Cancelled)
        assertEquals(0, reverse.cancelOutstandingPermissions(), "the ledger drains; a second unwind answers nothing")
    }
}
