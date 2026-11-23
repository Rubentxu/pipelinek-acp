package pipelinek.acp

import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bounded, typed record of what the agent asked the client to do (PKH-134 AC#1..#4).
 *
 * The events are the reverse operations seen from the client's chair, in closed form:
 * `perm granted (read)` or `fs.read refused (path escape)`. They travel into
 * [AcpTaskOutput.reverseEvents] — the SAME durable authority every other typed answer of
 * the step uses. The cap exists because a journal row is written by the run and read by a
 * person: an agent looping on filesystem requests must not widen the step's output without
 * bound.
 */
class AcpReverseJournal {
    companion object {
        const val MAX_EVENTS: Int = 24
        const val MAX_EVENT_CHARS: Int = 160
    }

    @Volatile
    var permissionRequests: Int = 0
        private set

    @Volatile
    var permissionsGranted: Int = 0
        private set

    @Volatile
    var permissionsDenied: Int = 0
        private set

    private val events = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun record(event: String) {
        events.addLast(event.take(MAX_EVENT_CHARS))
        while (events.size > MAX_EVENTS) events.pollFirst()
    }

    fun permissionAnswered(granted: Boolean, effect: AcpToolEffect) {
        permissionRequests += 1
        if (granted) permissionsGranted += 1 else permissionsDenied += 1
        record("perm ${if (granted) "granted" else "denied"} (${effect.name.lowercase()})")
    }

    fun snapshot(): List<String> = events.toList()
}

/**
 * The client's answer to every reverse operation the agent may start (PKH-134).
 *
 * Where PKH-132's [DenyingOperations] refused everything and PKH-133 added the profile and
 * the fence OUTSIDE the exchange, this class runs the exchange's own reverse leg under the
 * authority DECLARED by the pipeline: permissions follow [AcpPermissionPolicy]; filesystem
 * and terminal operations follow the same intersection PLUS the workspace fence the lease
 * root draws. A policy denial the protocol can express is answered with a typed JSON-RPC
 * error — the handler never throws for policy at the step level either, but the agent's
 * side of a reverse request DOES carry the error, because that is ACP's own vocabulary for
 * "no"; what the step reports stays a typed [AcpExchange].
 *
 * ## The fence (AC#2)
 * [workspaceRoot] is the execution lease root the host granted — the same authority
 * [AcpTaskIsolation] fences `cwd` with, now applied to what the agent can reach THROUGH
 * the client. An absolute path, a `..` escape, a symlink leaving the tree, or a TOCTOU swap
 * to one are refusals with a JSON-RPC error code, never a fallback into the host's tree.
 *
 * ## The terminal (AC#3)
 * Terminals are owned BY THIS SESSION: the ids the client mints carry the session and the
 * registry is per-instance, so another session's terminal id is not even resolvable here —
 * the owner mismatch is structural, not a checked field. Output is capped at launch time,
 * processes that outlive release are killed, and [reapTerminals] runs inside the
 * exchange's cleanup budget: an agent-requested terminal is the delegation's effect.
 *
 * ## Elicitation
 * An elicitation is the agent asking the client for a person's input, so it is the one
 * reverse operation that is answered by a PERSON: the client asks through the human-decision
 * port and reports what the person said. The answer travels to the agent as the answer to
 * the agent's own question and grants nothing — a permission is still the declared
 * authority's to decide.
 *
 * A client with no human-decision seam (a reverse leg built without one) still answers the
 * protocol's explicit `cancel`: a refusal said in the vocabulary, not a silence, and never
 * an auto-answer in a person's name. No schema field is invented and no secret is asked for.
 */
class AcpReverseOperations(
    private val sessionId: com.agentclientprotocol.model.SessionId,
    private val workspaceRoot: Path,
    private val profile: AcpTaskProfile,
    private val authority: AcpAuthority?,
    private val journal: AcpReverseJournal,
    private val ledger: AcpPermissionLedger,
    private val humanDecision: AcpHumanDecision? = null,
    private val waitBudgetMillis: () -> Long? = { null },
    private val observer: AcpUpdateSink? = null,
) : ClientSessionOperations {

    private val terminals = ConcurrentHashMap<String, TerminalHandle>()
    private val terminalSerial = AtomicInteger(0)

    companion object {
        /** Largest file the client will read or write for an agent; a deny, not a truncation. */
        const val MAX_FS_BYTES: Long = 1024 * 1024

        /** Default cap on terminal output the client keeps; the agent may ask for less. */
        const val MAX_TERMINAL_OUTPUT_BYTES: Long = 64 * 1024

        /** Concurrent terminals per session — the quota is per session, not per agent. */
        const val MAX_ACTIVE_TERMINALS: Int = 4

        /** The two environment slots terminal commands may receive; same posture as the child env. */
        val TERMINAL_ENV_ALLOWLIST: Set<String> = setOf("PATH", "LANG")
    }

    // ----------------------------------------------------------------------------------
    // Permissions — AC#1
    // ----------------------------------------------------------------------------------

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): RequestPermissionResponse {
        // A request without a declared kind is effect MISC: the closed mapping has no
        // "unknown grants", so an agent that omits the kind gets the door its kind would
        // never have opened — which is exactly OTHER's meaning.
        val kind = toolCall.kind ?: com.agentclientprotocol.model.ToolKind.OTHER
        val answer = AcpPermissionPolicy.decide(profile, authority, kind, permissions)
        // The ledger exists for the window where an answer is NOT immediate. Today the
        // decision is pure and synchronous, but 0.34.0 does not answer pending permission
        // requests when the client cancels or closes (fixed upstream only after this
        // release), so the plugin carries the pending set itself: the cancel unwind drains
        // it to the Cancelled outcome BEFORE session/cancel is said. When the human seam
        // arrives, the same deferred is what parks the answer on the person — the shape is
        // the protocol's, not ours, so it never has to change. An answered request leaves
        // the ledger: the map stays bounded by what is genuinely pending.
        ledger.register(toolCall.toolCallId, kotlinx.coroutines.CompletableDeferred(answer))
        val granted = answer.outcome is RequestPermissionOutcome.Selected
        journal.permissionAnswered(granted, AcpPermissionPolicy.effectOf(kind))
        ledger.release(toolCall.toolCallId)
        return answer
    }

    override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
        // Typed projection, not a second event bus: the durable sink is the step output
        // (AcpTaskOutput.reverseEvents), and only what the prompt flow does not already
        // deliver lands here. Tool-call lifecycle updates are exactly that.
        if (notification is SessionUpdate.ToolCallUpdate) {
            journal.record(
                "tool ${notification.title?.take(40).orEmpty()} ${notification.status?.name?.lowercase().orEmpty()}",
            )
        }
        // The notification is ALSO an update the agent sent: what the SDK routes here arrives
        // with no prompt in flight, so this is its only way into the run's event stream. The
        // journal stays the step-output record; the observer is the stream record — one is not
        // paid with the loss of the other.
        observer?.accept(notification)
    }

    // ----------------------------------------------------------------------------------
    // Filesystem — AC#2
    // ----------------------------------------------------------------------------------

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): ReadTextFileResponse {
        requireEffect(AcpToolEffect.READ, "fs.read")
        val resolved = fence(path, "fs.read")
        // Resolve through toRealPath and use THAT for the open: the containment check and
        // the bytes read are the same file — a symlink swapped after the check cannot widen
        // a read the check already refused (TOCTOU).
        val real = try {
            resolved.toRealPath()
        } catch (e: java.io.IOException) {
            journal.record("fs.read refused (missing) ${path.take(50)}")
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.read '$path' does not resolve")
        }
        if (!real.startsWith(safeRealRoot())) {
            journal.record("fs.read refused (symlink escape) ${path.take(50)}")
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.read '$path' escapes the workspace root")
        }
        val size = try {
            Files.size(real)
        } catch (e: java.io.IOException) {
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.read '$path' does not resolve")
        }
        if (size > MAX_FS_BYTES) {
            journal.record("fs.read refused (too large)")
            throw jsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, "fs.read '$path' exceeds the $MAX_FS_BYTES byte cap")
        }
        val text = String(Files.readAllBytes(real), Charsets.UTF_8)
        val lines = text.split("\n")
        val from = if (line != null) (line.toInt() - 1).coerceAtLeast(0) else 0
        val taken = if (limit != null) lines.drop(from).take(limit.toInt()) else lines.drop(from)
        journal.record("fs.read ok ${path.take(60)}")
        return ReadTextFileResponse(taken.joinToString("\n"), null)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): WriteTextFileResponse {
        requireEffect(AcpToolEffect.MUTATE, "fs.write")
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_FS_BYTES) {
            journal.record("fs.write refused (too large)")
            throw jsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, "fs.write '$path' exceeds the $MAX_FS_BYTES byte cap")
        }
        val resolved = fence(path, "fs.write")
        val realRoot = safeRealRoot()
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            // The check itself does not follow links: a symlink is NOT a regular file under
            // NOFOLLOW_LINKS, so a link the agent planted is refused whether it points out
            // of the tree or back into it — writing THROUGH a link the delegation chose is
            // exactly the TOCTOU shape AC#2 names, and a legit agent never needs one.
            if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                journal.record("fs.write refused (symlink/escape) ${path.take(50)}")
                throw jsonRpcError(
                    JsonRpcErrorCode.RESOURCE_NOT_FOUND,
                    "fs.write '$path' is not a regular file inside the root",
                )
            }
            val real = try {
                resolved.toRealPath()
            } catch (e: java.io.IOException) {
                journal.record("fs.write refused (broken link) ${path.take(50)}")
                throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.write '$path' does not resolve")
            }
            if (!real.startsWith(realRoot)) {
                journal.record("fs.write refused (symlink/escape) ${path.take(50)}")
                throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.write '$path' escapes the workspace root")
            }
            Files.write(real, content.toByteArray(Charsets.UTF_8))
        } else {
            // The protocol creates missing parent directories, so the fence cannot stop at
            // the target: a LINKED ancestor component (root/link -> /outside) would widen
            // a create the target check waved through. The deepest existing ancestor is
            // resolved and checked BEFORE anything is created — a create through a planted
            // link is refused while nothing outside the root has grown yet.
            var ancestor = resolved.parent
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.parent
            }
            val realAncestor = try {
                ancestor?.toRealPath()
            } catch (e: java.io.IOException) {
                throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.write '$path' has no reachable parent")
            }
                ?: throw jsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, "the workspace root vanished under fs.write")
            if (!realAncestor.startsWith(realRoot)) {
                journal.record("fs.write refused (parent escape)")
                throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "fs.write '$path' escapes the workspace root")
            }
            Files.createDirectories(resolved.parent)
            Files.write(resolved, content.toByteArray(Charsets.UTF_8))
        }
        journal.record("fs.write ok ${path.take(60)}")
        return WriteTextFileResponse(null)
    }

    // ----------------------------------------------------------------------------------
    // Terminal — AC#3
    // ----------------------------------------------------------------------------------

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): CreateTerminalResponse {
        requireEffect(AcpToolEffect.EXECUTE, "terminal.create")
        if (command.isBlank()) {
            throw jsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, "terminal.create names no command")
        }
        val executable = Path.of(command).fileName?.toString()?.lowercase() ?: command.lowercase()
        if (executable in AcpTaskIsolation.SHELL_NAMES) {
            journal.record("terminal.create refused (shell)")
            throw jsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, "terminal.create names a shell '$executable'")
        }
        if (terminals.size >= MAX_ACTIVE_TERMINALS) {
            journal.record("terminal.create refused (quota)")
            throw jsonRpcError(
                JsonRpcErrorCode.INVALID_PARAMS,
                "the session's terminal quota ($MAX_ACTIVE_TERMINALS) is full",
            )
        }
        val workingDir: Path = if (cwd.isNullOrBlank()) workspaceRoot else fence(cwd, "terminal.create cwd")
        if (!Files.isDirectory(workingDir)) {
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "terminal.create cwd is not a directory inside the root")
        }
        val cap = (outputByteLimit?.toLong() ?: MAX_TERMINAL_OUTPUT_BYTES).coerceIn(1L, MAX_TERMINAL_OUTPUT_BYTES)
        val builder = ProcessBuilder(listOf(command) + args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
        builder.environment().clear()
        for (variable in env) {
            if (variable.name in TERMINAL_ENV_ALLOWLIST) builder.environment()[variable.name] = variable.value
        }
        if ("PATH" !in builder.environment()) {
            builder.environment()["PATH"] = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
        }
        val process = try {
            builder.start()
        } catch (e: Exception) {
            journal.record("terminal.create refused (launch)")
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "terminal.create could not launch: ${e.message}")
        }
        val id = "term-${sessionId.value}-${terminalSerial.incrementAndGet()}"
        val handle = TerminalHandle(id, process, BoundedByteRing(cap))
        handle.attachDrain()
        terminals[id] = handle
        journal.record("terminal.create ${command.take(40)} id=${id.take(40)}")
        return CreateTerminalResponse(id, null)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): TerminalOutputResponse {
        val handle = owned(terminalId, "terminal.output")
        val (bytes, truncated) = handle.snapshot()
        val exit = if (handle.process.waitFor(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            TerminalExitStatus(handle.process.exitValue().toUInt(), null, null)
        } else {
            null
        }
        journal.record("terminal.output ${terminalId.take(30)} ${bytes.length}b")
        return TerminalOutputResponse(bytes, truncated, exit, null)
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): WaitForTerminalExitResponse {
        val handle = owned(terminalId, "terminal.wait_for_exit")
        val exit = handle.process.waitFor()
        journal.record("terminal.wait ${terminalId.take(30)} exit=$exit")
        return WaitForTerminalExitResponse(exit.toUInt(), null, null)
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): KillTerminalCommandResponse {
        val handle = owned(terminalId, "terminal.kill")
        handle.process.destroyForcibly()
        journal.record("terminal.kill ${terminalId.take(30)}")
        return KillTerminalCommandResponse(null)
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): ReleaseTerminalResponse {
        val handle = owned(terminalId, "terminal.release")
        if (handle.process.isAlive) handle.process.destroyForcibly()
        terminals.remove(terminalId)
        journal.record("terminal.release ${terminalId.take(30)}")
        return ReleaseTerminalResponse(null)
    }

    // ----------------------------------------------------------------------------------
    // Elicitation
    // ----------------------------------------------------------------------------------

    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
        val human = humanDecision
        if (human == null) {
            // No seam to ask through: the honest answer is the protocol's explicit cancel —
            // never an invented field value, never a silent drop, and never a response
            // given "in the user's name".
            journal.record("elicitation refused (no human-decision seam)")
            return CreateElicitationResponse(ElicitationAction.Cancel, null)
        }
        // The question the person receives is the agent's own question: what it asked, and
        // the URL it wants the person to visit when it asked with one.
        val answer = human.ask(questionOf(request), waitBudgetMillis())
        journal.record("elicitation ${answer.detail}")
        return CreateElicitationResponse(answer.action, null)
    }

    /**
     * The question a person is asked: the agent's message, plus the URL when the agent
     * asked with one. A person cannot answer a question nobody can read.
     */
    private fun questionOf(request: CreateElicitationRequest): String {
        val url = (request.mode as? com.agentclientprotocol.model.ElicitationMode.Url)?.url
        return if (url.isNullOrBlank()) request.message else "${request.message}\n$url"
    }

    override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
        journal.record("elicitation.complete ${notification.elicitationId.value.take(30)} (no pending request)")
    }

    // ----------------------------------------------------------------------------------
    // Lifecycle for the connection
    // ----------------------------------------------------------------------------------

    /**
     * Kills every terminal this session still holds. Called from the exchange's cleanup
     * path INSIDE the budget: a terminal the client started for the agent is the
     * delegation's effect, and an effect that outlives the run's deadline is the PKH-133
     * rule broken at the reverse leg. Returns the number killed.
     */
    fun reapTerminals(): Int {
        var reaped = 0
        for (handle in terminals.values) {
            if (handle.process.isAlive) {
                handle.process.destroyForcibly()
                reaped++
            }
        }
        terminals.clear()
        return reaped
    }

    /** Drain the session's pending permission answers to Cancelled before the unwind. */
    fun cancelOutstandingPermissions(): Int = ledger.cancelOutstanding()

    // ----------------------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------------------

    /** The authority leg of the fs/terminal gates: the same intersection as permissions. */
    private fun requireEffect(effect: AcpToolEffect, operation: String) {
        val declared = authority != null && effect in authority.allowedEffects
        val ceiling = !(profile == AcpTaskProfile.READONLY && effect != AcpToolEffect.READ)
        if (!declared || !ceiling) {
            journal.record("$operation refused (authority/profile)")
            throw jsonRpcError(
                JsonRpcErrorCode.INVALID_PARAMS,
                "the delegation's declared authority refuses $operation for effect ${effect.name.lowercase()}",
            )
        }
    }

    private fun fence(rawPath: String, operation: String): Path {
        val requested = try {
            Path.of(rawPath)
        } catch (e: Exception) {
            throw jsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, "$operation names an unparsable path")
        }
        if (requested.isAbsolute) {
            journal.record("$operation refused (absolute path)")
            throw jsonRpcError(
                JsonRpcErrorCode.RESOURCE_NOT_FOUND,
                "$operation '$rawPath' is absolute; the workspace root fences it",
            )
        }
        val normalized = workspaceRoot.resolve(requested).normalize()
        if (!normalized.startsWith(workspaceRoot.normalize())) {
            journal.record("$operation refused (path escape)")
            throw jsonRpcError(
                JsonRpcErrorCode.RESOURCE_NOT_FOUND,
                "$operation '$rawPath' resolves outside the workspace root",
            )
        }
        return normalized
    }

    private fun safeRealRoot(): Path = try {
        workspaceRoot.toRealPath()
    } catch (e: java.io.IOException) {
        throw jsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, "the workspace root does not exist: $workspaceRoot")
    }

    private fun owned(terminalId: String, operation: String): TerminalHandle =
        terminals[terminalId] ?: run {
            // Unknown id — ANOTHER session's terminal lands here too: the ids the client
            // mints carry this session and the registry is per-session by construction, so
            // cross-session access is a not-found, not a check the agent could fool.
            journal.record("$operation refused (unknown $terminalId)")
            throw jsonRpcError(JsonRpcErrorCode.RESOURCE_NOT_FOUND, "$operation: '$terminalId' is not a terminal of this session")
        }

    private fun jsonRpcError(code: JsonRpcErrorCode, reason: String): JsonRpcException =
        JsonRpcException(code.code, reason, null)
}

/** One agent-requested terminal: its process, its capped output, and the id it was given. */
internal class TerminalHandle(
    val id: String,
    val process: Process,
    private val ring: BoundedByteRing,
) {
    fun attachDrain() {
        val drain = Thread({
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) ring.add(line + "\n")
            }
        }, "acp-terminal-$id")
        drain.isDaemon = true
        drain.start()
    }

    fun snapshot(): Pair<String, Boolean> = ring.joined() to ring.truncated
}

/** Byte-capped ring that remembers it dropped bytes — `truncated` is AC#3's own word. */
internal class BoundedByteRing(private val capBytes: Long) {
    private val parts = ArrayDeque<String>()
    private var kept = 0L
    private var dropped = 0L

    @Synchronized
    fun add(text: String) {
        var chunk = text
        kept += chunk.length
        if (kept > capBytes) {
            dropped += kept - capBytes
            // Trim from the front by whole lines when possible; the newest bytes are the
            // interesting ones for a terminal tail.
            val excess = (kept - capBytes).toInt()
            var removed = 0
            while (removed < excess && parts.isNotEmpty()) {
                val head = parts.removeFirst()
                removed += head.length
                kept -= head.length
            }
            chunk = chunk.dropLast(minOf(chunk.length, (excess - removed).coerceAtLeast(0)))
        }
        parts.addLast(chunk)
    }

    @Synchronized
    fun joined(): String = parts.joinToString("")

    val truncated: Boolean
        @Synchronized get() = dropped > 0
}

/**
 * The per-exchange holder that lets [AcpConnection] build session operations through the
 * SDK's factory while keeping the journal, the ledger and the terminal registry outside the
 * protocol types — the same "wire types never leave the connection boundary" rule.
 *
 * [humanDecision] is the port the elicitation asks through, and [budgetMillis] is the
 * turn's own budget: the person is asked with the time the turn has left, so a question
 * outlives neither the turn nor the run.
 */
class AcpReverseSession(
    private val workspaceRoot: Path,
    private val profile: AcpTaskProfile,
    private val authority: AcpAuthority?,
    private val humanDecision: AcpHumanDecision? = null,
    private val budgetMillis: Long? = null,
) {
    val ledger = AcpPermissionLedger()
    val journal = AcpReverseJournal()
    private val instances = java.util.concurrent.ConcurrentLinkedQueue<AcpReverseOperations>()
    private val startedAtMillis: Long = System.currentTimeMillis()

    /**
     * The fs capability to advertise at initialize: the client promises to answer
     * read/write ONLY for effects the declared authority actually allows — advertising a
     * capability the policy would refuse anyway invites the agent to build on a door that
     * is locked from the other side.
     */
    fun fsCapabilities(): FileSystemCapability {
        val allowsRead = authority?.allowedEffects?.contains(AcpToolEffect.READ) == true
        val allowsWrite = authority != null &&
            AcpToolEffect.MUTATE in authority.allowedEffects &&
            profile != AcpTaskProfile.READONLY
        return FileSystemCapability(allowsRead, allowsWrite, null)
    }

    /** The terminal capability to advertise; same rule: promised only when grantable. */
    fun terminalAdvertised(): Boolean = authority != null &&
        AcpToolEffect.EXECUTE in authority.allowedEffects &&
        profile != AcpTaskProfile.READONLY

    /**
     * The operations factory for one exchange. [observation] is the run's projection seam:
     * notifications the SDK delivers outside the prompt stream route to it too, so a task
     * with a reverse leg publishes what the agent said instead of journaling it locally.
     */
    fun factory(observation: AcpUpdateSink? = null): ClientOperationsFactory =
        ClientOperationsFactory { sessionId, _ ->
            AcpReverseOperations(
                sessionId = sessionId,
                workspaceRoot = workspaceRoot,
                profile = profile,
                authority = authority,
                journal = journal,
                ledger = ledger,
                humanDecision = humanDecision,
                waitBudgetMillis = ::remainingBudgetMillis,
                observer = observation,
            )
                .also { instances.add(it) }
        }

    /**
     * What is left of the turn's budget, which is what the person may still take.
     *
     * The bound is read at the moment the question is asked, not when the session was
     * built: a turn that spent its budget before the agent asked has nothing left to
     * offer, and a question that outlives the turn would ask a person about a run that
     * has already ended.
     */
    private fun remainingBudgetMillis(): Long? = budgetMillis?.let { total ->
        (total - (System.currentTimeMillis() - startedAtMillis)).coerceAtLeast(0L)
    }

    /**
     * Answer every not-yet-answered permission request before session/cancel unwinds.
     * Drained at the SESSION level: the ledger is shared by every operations instance the
     * factory built, so the unwind answers the pending set whether or not an instance was
     * ever constructed — a request registered and not yet answered is pending by fact,
     * not by the lifetime of a handler object.
     */
    fun cancelOutstandingPermissions(): Int = ledger.cancelOutstanding()

    fun reapTerminals(): Int {
        var total = 0
        instances.forEach { total += it.reapTerminals() }
        instances.clear()
        return total
    }
}
