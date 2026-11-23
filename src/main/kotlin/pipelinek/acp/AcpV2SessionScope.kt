package pipelinek.acp

import com.agentclientprotocol.client.ClientNegotiator
import com.agentclientprotocol.client.NegotiatedClient
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.client.V1ClientConfig
import com.agentclientprotocol.client.V2ClientConfig
import com.agentclientprotocol.model.v2.AgentMessage
import com.agentclientprotocol.model.v2.ContentBlock
import com.agentclientprotocol.model.v2.ContentChunk
import com.agentclientprotocol.model.v2.CreateElicitationRequest
import com.agentclientprotocol.model.v2.CreateElicitationResponse
import com.agentclientprotocol.model.v2.ElicitationAction
import com.agentclientprotocol.model.v2.RequestPermissionOutcome
import com.agentclientprotocol.model.v2.RequestPermissionRequest
import com.agentclientprotocol.model.v2.RequestPermissionResponse
import com.agentclientprotocol.model.v2.SessionUpdate
import com.agentclientprotocol.model.v2.StateUpdate
import com.agentclientprotocol.model.v2.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the v2 foreground wait conditions mean (PKH-142 AC#2).
 *
 * v2 splits what v1 fused: `session/prompt` ANSWERS when the prompt is inserted, and the
 * turn ENDS later, with a `state_update` to idle. An await that wants the insertion fact and
 * an await that wants the turn to end are therefore different waits, and the caller declares
 * which one — [INSERTED] stops at the ack, [FOREGROUND_IDLE] also waits for the idle
 * `state_update` and classifies its stop reason.
 */
enum class AcpV2Await {
    /** stop once the insertion ack (the messageId) is in hand; the turn may still be running. */
    INSERTED,

    /** also wait for the `state_update` to idle and record its stop reason. */
    FOREGROUND_IDLE,
}

/** What to do with the session once the foreground turn is idle (AC#2's close/background policy). */
enum class AcpV2OnIdle {
    /** say `session/close` at idle: background work the agent has stops with the session. */
    CLOSE,

    /** leave the session open and keep collecting: activity that arrives AFTER idle is
     * reported as background facts, never folded into the foreground verdict. */
    KEEP_RUNNING,
}

/**
 * How the awaited foreground ended, AS THE PROTOCOL STATES IT (AC#2: idle is not success).
 * The SDK's own `StopReason` keeps an unrecognized wire string as `Unknown` instead of
 * coercing it — v2's draft still adds reasons after this pin — so the split the wire makes
 * survives into the verdict instead of being flattened into SUCCEEDED.
 */
enum class AcpV2IdleVerdict {
    /** the agent said idle with `end_turn`. */
    SUCCEEDED,

    /** idle with `cancelled` — the turn ended without producing output. */
    CANCELLED,

    /** idle with `refusal`. */
    REFUSED,

    /** idle with `max_tokens`. */
    STOPPED_MAX_TOKENS,

    /** idle with `max_turn_requests`. */
    STOPPED_MAX_TURNS,

    /** idle with a stop reason this vocabulary does not name: never success; the raw string rides. */
    UNRECOGNIZED_STOP_REASON,

    /** an idle `state_update` with NO stop reason at all. */
    NO_STOP_REASON,

    /** [AcpV2Await.INSERTED] was declared: the foreground was never awaited. */
    NOT_AWAITED,
}

/** The input of one experimental v2 scope. Mirrors [AcpSessionInput]'s shape, v2 vocabulary. */
@kotlinx.serialization.Serializable
data class AcpV2SessionInput(
    /** argv of the agent to launch. */
    val command: List<String>,
    /** the prompt text for the one turn this scope runs. */
    val prompt: String,
    /** working directory advertised to the agent. Empty means the plugin's own. */
    val cwd: String = "",
    /** total budget for negotiation, the turn, the awaited condition and unwinding. */
    val timeoutSeconds: Long = 120,
    /**
     * A declared `session/resume` of an agent id instead of `session/new`. v2 replaced
     * `session/load` with resume + replay (AC#3): there is no load in this vocabulary.
     */
    val resumeSessionId: String = "",
    /**
     * The replay cursor (AC#3). "" sends no `replayFrom` (the default replay semantics);
     * "start" sends `{"type":"start"}` (replay from the beginning). Anything else is refused
     * before launch: the cursor vocabulary is v2's, not a free string.
     */
    val replayFrom: String = "",
    /**
     * MCP servers to offer. v2 cut `sse` from the transport list, requires absolute stdio
     * commands and unique names, and gates each transport by its own marker
     * (`session.mcp.stdio` / `session.mcp.http`) instead of v1's single mcp announcement.
     */
    val mcpServers: List<AcpMcpInput> = emptyList(),
    /** which foreground condition to wait for (AC#2). */
    val await: AcpV2Await = AcpV2Await.FOREGROUND_IDLE,
    /** what happens after the foreground is idle. */
    val onIdle: AcpV2OnIdle = AcpV2OnIdle.CLOSE,
    /** how long to keep collecting after idle when [onIdle] keeps the session open. */
    val backgroundWindowSeconds: Long = 2,
    /** ask `auth/status` once negotiated; gated by the agent's `auth.status` flag. */
    val authStatus: Boolean = false,
    /** ask `providers/list` once negotiated; an unstable extension, dials only when declared. */
    val listProviders: Boolean = false,
    /** ask `session/list` once negotiated. */
    val listSessions: Boolean = false,
    /** end the connection with `auth/logout` (v2's renamed logout; gate per AC#3 below). */
    val logout: Boolean = false,
    /** delete the session before unwinding (`session/delete`, gated by the delete marker). */
    val deleteOnExit: Boolean = false,
)

/** One collected v2 update: the wire fact plus the lifecycle phase it arrived in. */
@kotlinx.serialization.Serializable
data class AcpV2UpdateFact(
    /** the v2 discriminator: agent_message_chunk, state_update, tool_call_update, … */
    val kind: String,
    /** text carried by message/chunk variants, empty otherwise. */
    val text: String = "",
    /** for state_update: running, requires_action, idle — empty otherwise. */
    val state: String = "",
    /** for idle state_update: the raw stop-reason string, whatever the wire said. */
    val stopReason: String = "",
    /** the phase this update arrived in: opening (replay/snapshot), foreground, background. */
    val phase: String = "",
)

/**
 * The typed answer of one experimental v2 scope. Same discipline as [AcpSessionOutput]:
 * failures are `ok = false` with a refusal kind, never a throw across the execution boundary.
 *
 * AC#2's separation of facts lives in three fields: [acceptedMessageId] is the insertion ack
 * and lands as soon as `session/prompt` answers; [sawRunning] is the lifecycle fact that the
 * agent reported foreground work; [idleVerdict] (with [idleStopReason]) is the FOREGROUND
 * condition, recorded only where [AcpV2Await.FOREGROUND_IDLE] awaited it. None implies
 * another: a run can hold the ack while the turn still runs, and can end idle without
 * having succeeded.
 */
@kotlinx.serialization.Serializable
data class AcpV2SessionOutput(
    val ok: Boolean = true,
    val outcome: String = "COMPLETED",
    val failureKind: String = "",
    val detail: String = "",
    /** the protocol version the negotiation actually agreed on (2 on every green row). */
    val negotiatedProtocolVersion: Int = 0,
    /** the plugin ref minted for this session (same mint as v1's, AC#4's shared vocabulary). */
    val sessionRef: String = "",
    /** the v2 `PromptResponse.messageId` — the insertion ack (fact #1). */
    val acceptedMessageId: String = "",
    /** a `state_update` to running was seen after the prompt was sent (fact #2). */
    val sawRunning: Boolean = false,
    /** a `state_update` to requires_action was seen (the auth-needed lifecycle). */
    val sawRequiresAction: Boolean = false,
    /** how the awaited foreground ended (fact #3); NOT_AWAITED when only insertion was asked. */
    val idleVerdict: AcpV2IdleVerdict = AcpV2IdleVerdict.NOT_AWAITED,
    /** the raw stop-reason string of the idle state_update, "" when none/never awaited. */
    val idleStopReason: String = "",
    /** message/chunk texts collected while the foreground was running. */
    val replyText: String = "",
    /** updates that arrived AFTER the foreground went idle, while collection stayed open. */
    val backgroundUpdates: List<AcpV2UpdateFact> = emptyList(),
    /** updates replayed by a declared resume, before the current-state snapshot. */
    val replayedUpdates: List<AcpV2UpdateFact> = emptyList(),
    /** a `session/close` was dialled and answered. */
    val sessionClosed: Boolean = false,
    /** a `session/delete` was dialled and answered. */
    val sessionDeleted: Boolean = false,
    /** an `auth/logout` was dialled and answered. */
    val loggedOut: Boolean = false,
    /** the answer of `auth/status` (unauthenticated when never asked). */
    val authAuthenticated: Boolean = false,
    /** provider ids reported by `providers/list`. */
    val providers: List<String> = emptyList(),
    /** session ids reported by `session/list`. */
    val listedSessions: List<String> = emptyList(),
    /** how many `session/request_permission` dials the client answered, and with what. */
    val permissionRequests: Int = 0,
    val permissionAnswer: String = "",
    /** how many `elicitation/create` dials the client declined. */
    val elicitationRequests: Int = 0,
)

/**
 * The client side of the reverse v2 leg, minimal by policy: every `session/request_permission`
 * is answered and counted — `Cancelled` when the agent offered no selectable option, the
 * first offered option otherwise — so a v2 agent that dials the reverse leg gets a
 * protocol-correct answer instead of a hang. The declared stance for the whole surface is in
 * [AcpV2CoverageMatrix].
 */
internal class AcpV2DecliningOperations(
    private val permissionCount: AtomicInteger,
    val lastAnswer: AtomicReference<String>,
) : com.agentclientprotocol.client.v2.ClientSessionOperations {
    override suspend fun requestPermission(request: RequestPermissionRequest): RequestPermissionResponse {
        permissionCount.incrementAndGet()
        val selected = request.options.firstOrNull()
        return if (selected == null) {
            lastAnswer.set("Cancelled")
            RequestPermissionResponse(outcome = RequestPermissionOutcome.Cancelled, _meta = null)
        } else {
            lastAnswer.set("Selected:" + selected.optionId)
            RequestPermissionResponse(
                outcome = RequestPermissionOutcome.Selected(selected.optionId),
                _meta = null,
            )
        }
    }
}

/** The elicitation decline for the v2 leg: the adapter declares no elicitation capability. */
internal class AcpV2DecliningElicitation(
    private val elicitationCount: AtomicInteger,
) : com.agentclientprotocol.client.v2.ElicitationHandler {
    override suspend fun createElicitation(
        request: CreateElicitationRequest,
    ): CreateElicitationResponse {
        elicitationCount.incrementAndGet()
        return CreateElicitationResponse(action = ElicitationAction.Decline, _meta = null)
    }
}

/** A protocol-level failure relabeled with the v2 operation the plan declared. */
private class V2OperationSignal(val operation: String, val reason: String) :
    Exception("session operation '$operation' failed: $reason")

/**
 * The experimental v2 session scope (PKH-142): one connection, one negotiation to protocol
 * version 2, one turn, released in the same scope that opened it — the same lexical rule as
 * the frozen v1 [AcpSessionConnection], because v2 changes the vocabulary, not the lifecycle.
 *
 * ## Opt-in, disabled by default (AC#4)
 *
 * [run] answers [AcpRefusal.ConfigInvalid] naming [AcpV2Adapter.ENABLE_PROPERTY] unless the
 * operator enabled the adapter. The v1 path never consults this class; enabling v2 cannot
 * change what a v1 step does, and disabling it leaves v2 unreachable.
 *
 * ## Version routing (AC#1)
 *
 * Negotiation goes through the SDK's `ClientNegotiator`, which speaks the v2 initialize
 * shape first and falls back to v1 only for an agent that answers 1. A v1 fallback is
 * refused HERE as [AcpRefusal.VersionMismatch] — this scope OWES version 2 and never silently
 * runs v1 work through it; every dial obeys [AcpV2Adapter.dialsOn], so a name v2 renamed
 * (`authenticate`, `logout`) or removed (`session/load`, `session/set_mode`, the reverse
 * `fs/*` and `terminal/*`) is unreachable from this scope by law, not by omission.
 *
 * ## The two facts (AC#2)
 *
 * `session/prompt` answers with the insertion ack; a collector on `session.updates` records
 * running, requires_action and the idle state_update SEPARATELY, tagging every update with
 * the lifecycle phase it arrived in, and the output fields mirror that split. An idle without
 * a recognized stop reason is not success ([AcpV2IdleVerdict.NO_STOP_REASON] /
 * [AcpV2IdleVerdict.UNRECOGNIZED_STOP_REASON]). [AcpV2OnIdle] decides whether the session
 * closes at idle or keeps collecting; post-idle activity lands in
 * [AcpV2SessionOutput.backgroundUpdates] and never folds into the foreground verdict.
 *
 * ## Auth gate (AC#3, the v2 difference)
 *
 * v2 declares NO logout capability marker: the announcement that there is something to log
 * out of IS the non-empty `authMethods` list in the initialize response. A declared logout
 * against an agent with no methods is refused [AcpRefusal.MissingCapability] naming
 * `authMethods` — v1's boolean-flag gate would be the wrong test here, and copying it would
 * hide the difference this AC exists to name.
 */
class AcpV2SessionConnection(private val pluginVersion: String) {

    /** Nonce identifying THIS connection as the owner of every ref it mints (shared with v1). */
    val connectionNonce: String = java.util.UUID.randomUUID().toString()
    private val inFlight = AtomicBoolean(false)

    /** The blocking entry point for tests and callers outside a coroutine. */
    fun run(input: AcpV2SessionInput): AcpV2SessionOutput = runBlocking { exchange(input) }

    /**
     * Run the whole scope. At most one exchange in flight per instance, like v1: a second
     * concurrent attempt is a typed refusal, never a race.
     */
    suspend fun exchange(input: AcpV2SessionInput): AcpV2SessionOutput {
        if (!AcpV2Adapter.enabled()) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ConfigInvalid(
                    "the experimental v2 adapter is disabled; set ${AcpV2Adapter.ENABLE_PROPERTY}" +
                        " (or ${AcpV2Adapter.ENABLE_ENV}) to \"true\" to negotiate protocol version 2",
                ),
            )
        }
        val resumeResolution = SessionRef.resolve(input.resumeSessionId, connectionNonce)
        if (resumeResolution is SessionIdResolution.Foreign) {
            return AcpV2Outcome.toOutput(AcpRefusal.ForeignSessionRef(resumeResolution.declared))
        }
        // v2's mcp transport list dropped sse; the refusal is this scope's own vocabulary,
        // stated before launch. (Renamed/removed REQUEST names are unreachable here by law:
        // this scope literally has no code path that dials `session/load` or `authenticate`.)
        val badTransport = input.mcpServers.firstOrNull { it.transport !in V2_MCP_TRANSPORTS }
        if (badTransport != null) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ConfigInvalid(
                    "mcp server '${badTransport.name}' declares transport '${badTransport.transport}', " +
                        "v2 speaks " + V2_MCP_TRANSPORTS.sorted().joinToString("/"),
                ),
            )
        }
        // v2 requires the stdio command to be absolute (argv of AcpMcpInput: the first entry
        // is the executable); a relative one is refused before the server could be launched.
        val relativeStdio = input.mcpServers.firstOrNull { server ->
            server.transport == "stdio" &&
                server.command.firstOrNull().orEmpty().let { argv0 ->
                    argv0.isNotEmpty() && !argv0.startsWith("/") && !WINDOWS_ABSOLUTE.matches(argv0)
                }
        }
        if (relativeStdio != null) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ConfigInvalid(
                    "mcp server '${relativeStdio.name}' declares a relative stdio command " +
                        "'${relativeStdio.command.firstOrNull()}'; v2 requires an absolute executable",
                ),
            )
        }
        if (input.mcpServers.map { it.name }.distinct().size != input.mcpServers.size) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ConfigInvalid("v2 requires every mcp server name to be unique"),
            )
        }
        if (input.replayFrom.isNotEmpty() && input.replayFrom !in VALID_REPLAY_CURSORS) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ConfigInvalid(
                    "replay cursor '${input.replayFrom}' is not v2 vocabulary; " +
                        "the cursor this scope speaks is " + VALID_REPLAY_CURSORS.sorted().joinToString("/"),
                ),
            )
        }
        if (!inFlight.compareAndSet(false, true)) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.ActivePromptInProgress("this v2 scope already has an exchange running"),
            )
        }
        try {
            val wireResume = (resumeResolution as? SessionIdResolution.Accepted)?.wireId
                ?: input.resumeSessionId
            return runOnce(input.copy(resumeSessionId = wireResume))
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runOnce(input: AcpV2SessionInput): AcpV2SessionOutput {
        val process = try {
            AcpAgentProcess.open(input.command)
        } catch (e: AgentLaunchFailed) {
            return AcpV2Outcome.toOutput(e.refusal)
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            withTimeout(input.timeoutSeconds * 1000) { runScope(input, process, scope) }
        } catch (e: TimeoutCancellationException) {
            AcpV2Outcome.toOutput(AcpRefusal.AgentTimeout(input.timeoutSeconds))
        } catch (e: UnsupportedProtocolVersionException) {
            AcpV2Outcome.toOutput(
                AcpRefusal.VersionMismatch(e.requestedVersion, e.offeredVersion, e.supportedVersions),
            )
        } catch (e: V2OperationSignal) {
            AcpV2Outcome.toOutput(AcpRefusal.SessionOperationFailed(e.operation, e.reason))
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                val exit = process.exitCodeOrNull()
                AcpV2Outcome.toOutput(
                    if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
                    else AcpRefusal.TransportClosed("the v2 scope ended before the exchange completed"),
                )
            } else {
                throw e
            }
        } catch (e: Exception) {
            val exit = process.exitCodeOrNull()
            AcpV2Outcome.toOutput(
                if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
                else AcpRefusal.TransportFailure(e.message ?: e.toString()),
            )
        } finally {
            // the release clause, v1's wording verbatim: the coroutine tree dies and the
            // process tree is reaped in the scope that started them.
            job.cancel()
            process.destroyTree()
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun runScope(
        input: AcpV2SessionInput,
        process: AcpAgentProcess,
        scope: CoroutineScope,
    ): AcpV2SessionOutput {
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines,
            output = { line -> process.writeLine(line) },
            name = "acp-v2-pipelinek-acp",
        )
        val protocol = Protocol(scope, transport)
        protocol.start()
        val permissionCount = AtomicInteger(0)
        val permissionAnswer = AtomicReference("")
        val elicitationCount = AtomicInteger(0)
        val negotiator = ClientNegotiator(
            protocol = protocol,
            v1 = V1ClientConfig(),
            v2 = V2ClientConfig(
                clientInfo = AcpV2Adapter.clientInfo(pluginVersion),
                elicitationHandler = AcpV2DecliningElicitation(elicitationCount),
            ),
        )
        val negotiated = negotiator.negotiate()
        // AC#1's routing law in force: a v1 fallback means THIS scope has no dialect — the
        // version is refused typed, and no v2 name is ever dialled on a v1 connection.
        if (negotiated !is NegotiatedClient.V2) {
            return AcpV2Outcome.toOutput(
                AcpRefusal.VersionMismatch(
                    requested = AcpProtocolVersion.V2.wireVersion,
                    offered = negotiated.protocolVersion,
                    supported = setOf(AcpProtocolVersion.V2.wireVersion),
                ),
            )
        }
        val client = negotiated.client
        val agent = negotiated.agentInfo
        v2Refusal(input, agent)?.let { return AcpV2Outcome.toOutput(it) }

        val operations = AcpV2DecliningOperations(permissionCount, permissionAnswer)
        val facts = AtomicReference<List<AcpV2UpdateFact>>(emptyList())
        val promptSent = AtomicBoolean(false)
        val phase = AtomicReference(PHASE_OPENING)
        val idleVerdictRaw = AtomicReference("")
        val idleReached = CompletableDeferred<Unit>()

        val session = if (input.resumeSessionId.isNotBlank()) {
            v2Op("session/resume") {
                client.resumeSession(
                    input.resumeSessionId,
                    cwdOf(input),
                    v2McpServers(input.mcpServers),
                    emptyList(),
                    replayFromOf(input.replayFrom),
                    operations,
                    null,
                )
            }
        } else {
            v2Op("session/new") {
                client.newSession(
                    cwdOf(input),
                    v2McpServers(input.mcpServers),
                    emptyList(),
                    operations,
                    null,
                )
            }
        }

        // The collector is the foreground/background split made concrete: it runs for the
        // whole life of the session object and TAGS every update with the phase it was seen
        // in, so replay, the resume snapshot, live output and post-idle background activity
        // stay four different facts.
        val collector = scope.launch {
            session.updates.collect { withMeta ->
                val base = factOf(withMeta.update)
                val state = when (base.kind) {
                    "state_update" -> if (promptSent.get()) phase.get() else base.state
                    else -> phase.get()
                }
                facts.updateAndGet { current -> current + base.copy(phase = tag(base, state)) }
                if (base.kind == "state_update" && promptSent.get()) {
                    when (base.state) {
                        "running" -> phase.compareAndSet(PHASE_OPENING, PHASE_FOREGROUND)
                        "requires_action" -> phase.compareAndSet(PHASE_OPENING, PHASE_FOREGROUND)
                        "idle" -> if (phase.getAndSet(PHASE_BACKGROUND) != PHASE_BACKGROUND) {
                            idleVerdictRaw.set(base.stopReason)
                            idleReached.complete(Unit)
                        }
                    }
                }
            }
        }
        var openSession: com.agentclientprotocol.client.v2.ClientSession = session
        try {
            // FACT #1: insertion. suspend prompt() returns with the PromptResponse — the ack,
            // while the turn may still be running (the collector sees `running` here).
            promptSent.set(true)
            val acceptedMessageId = v2Op("session/prompt") {
                openSession.prompt(listOf(ContentBlock.Text(text = input.prompt)), null).value
            }
            var sawRequiresAction = false
            var idleVerdict = AcpV2IdleVerdict.NOT_AWAITED
            var idleStopReason = ""
            if (input.await == AcpV2Await.FOREGROUND_IDLE) {
                // FACT #2: the foreground condition — a wait on the collector's idle latch,
                // never an inference from the ack.
                idleReached.await()
                idleStopReason = idleVerdictRaw.get()
                idleVerdict = verdictOfStopReason(idleStopReason)
                if (input.onIdle == AcpV2OnIdle.KEEP_RUNNING) {
                    delay(input.backgroundWindowSeconds * 1000)
                }
            }
            sawRequiresAction = facts.get().any {
                it.kind == "state_update" && it.state == "requires_action"
            }
            val observed = facts.get()
            val backgroundFacts = observed.filter { it.phase == PHASE_BACKGROUND }
            val replayed = observed.filter { it.phase == PHASE_OPENING }
            val reply = observed
                .filter { it.phase == PHASE_FOREGROUND && it.text.isNotEmpty() }
                .joinToString("") { it.text }

            var sessionClosed = false
            var sessionDeleted = false
            var loggedOut = false
            var authAuthenticated = false
            var providers: List<String> = emptyList()
            var listedSessions: List<String> = emptyList()

            if (input.authStatus) {
                authAuthenticated = v2Op("auth/status") { client.authStatus(null) }.authenticated
            }
            if (input.listProviders) {
                providers = v2Op("providers/list") { client.listProviders(null) }
                    .providers.map { it.providerId }
            }
            if (input.listSessions) {
                listedSessions = v2Op("session/list") {
                    client.listSessions(cwdOf(input), null, null)
                }.sessions.map { it.sessionId }
            }
            if (input.logout) {
                v2Op("auth/logout") { client.logout(null) }
                loggedOut = true
            }
            if (input.deleteOnExit) {
                v2Op("session/delete") { client.deleteSession(openSession.sessionId, null) }
                sessionDeleted = true
            }
            if (input.onIdle == AcpV2OnIdle.CLOSE || input.await == AcpV2Await.INSERTED) {
                if (!sessionDeleted) {
                    v2Op("session/close") { openSession.close(null) }
                    sessionClosed = true
                }
            }

            return AcpV2SessionOutput(
                negotiatedProtocolVersion = negotiated.protocolVersion,
                sessionRef = SessionRef.mint(connectionNonce, openSession.sessionId.value),
                acceptedMessageId = acceptedMessageId,
                sawRunning = observed.any {
                    it.kind == "state_update" && it.state == "running" && it.phase != PHASE_OPENING
                },
                sawRequiresAction = sawRequiresAction,
                idleVerdict = idleVerdict,
                idleStopReason = idleStopReason,
                replyText = reply,
                backgroundUpdates = backgroundFacts,
                replayedUpdates = replayed,
                sessionClosed = sessionClosed,
                sessionDeleted = sessionDeleted,
                loggedOut = loggedOut,
                authAuthenticated = authAuthenticated,
                providers = providers,
                listedSessions = listedSessions,
                permissionRequests = permissionCount.get(),
                permissionAnswer = permissionAnswer.get(),
                elicitationRequests = elicitationCount.get(),
            )
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // the delegated-task rule, v2 edition: a prompt cancelled by the budget or the
                // engine is said as session/cancel over the wire before the stack unwinds —
                // in v2 that notification is the foreground cancel, the renamed leg.
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { openSession.cancel(null) }
                }
            }
            throw e
        } finally {
            collector.cancel()
        }
    }

    /**
     * The v2 capability gates, each refusal typed and named. They differ from v1 where v2
     * moved the announcement — the session block replaces v1's top-level flags, authMethods
     * replaces the logout boolean (AC#3) — and every dial below consults [AcpV2Adapter.route]
     * for unstable names, so a provider call cannot be dialed against an agent that never
     * announced the providers capability.
     */
    private fun v2Refusal(
        input: AcpV2SessionInput,
        agent: com.agentclientprotocol.agent.v2.AgentInfo,
    ): AcpRefusal? {
        val session = agent.capabilities.session
        for (server in input.mcpServers) {
            if (session == null) return AcpRefusal.MissingCapability("session.mcp.${server.transport}")
            val marker = when (server.transport) {
                "stdio" -> session.mcp?.stdio
                "http" -> session.mcp?.http
                else -> null
            }
            if (marker == null) {
                return AcpRefusal.MissingCapability("session.mcp.${server.transport}")
            }
        }
        if (input.resumeSessionId.isNotBlank() && session == null) {
            return AcpRefusal.MissingCapability("session (resume needs the session capability block)")
        }
        if (input.deleteOnExit && session?.delete == null) {
            return AcpRefusal.MissingCapability("session.delete")
        }
        if (input.authStatus && agent.capabilities.auth?.status != true) {
            return AcpRefusal.MissingCapability("auth.status")
        }
        // AC#3's auth difference: v2 declares no logout marker — the non-empty authMethods
        // list IS the announcement that there is something to log out of.
        if (input.logout && agent.authMethods.isEmpty()) {
            return AcpRefusal.MissingCapability("authMethods (v2's logout announcement)")
        }
        if (input.listProviders && agent.capabilities.providers == null) {
            return AcpRefusal.MissingCapability("providers")
        }
        if (input.listSessions && session == null) {
            return AcpRefusal.MissingCapability("session (list needs the session capability block)")
        }
        return null
    }

    companion object {
        internal const val PHASE_OPENING = "opening"
        internal const val PHASE_FOREGROUND = "foreground"
        internal const val PHASE_BACKGROUND = "background"

        /** The v2 mcp transport vocabulary (AC#3: sse was cut from v1's http/sse/stdio). */
        internal val V2_MCP_TRANSPORTS: Set<String> = setOf("http", "stdio")

        /** The replay cursors the pinned v2 schema names. */
        internal val VALID_REPLAY_CURSORS: Set<String> = setOf("start")

        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

        /**
         * Map one v2 `SessionUpdate` onto the plugin's normalized fact row. The kind string
         * IS the wire discriminator, pinned by the SDK's own DISCRIMINATOR constants — the
         * equivalence law (AC#4) compares these against v1's [AcpFactToken] observations.
         */
        internal fun factOf(update: SessionUpdate): AcpV2UpdateFact = when (update) {
            is SessionUpdate.UserMessageChunk ->
                AcpV2UpdateFact(kind = "user_message_chunk", text = textOf(update.chunk))
            is SessionUpdate.UserMessage ->
                AcpV2UpdateFact(kind = "user_message", text = textOfMessage(update.message))
            is SessionUpdate.AgentMessageChunk ->
                AcpV2UpdateFact(kind = "agent_message_chunk", text = textOf(update.chunk))
            is SessionUpdate.AgentMessage ->
                AcpV2UpdateFact(kind = "agent_message", text = textOfMessage(update.message))
            is SessionUpdate.AgentThoughtChunk ->
                AcpV2UpdateFact(kind = "agent_thought_chunk", text = textOf(update.chunk))
            is SessionUpdate.AgentThought ->
                AcpV2UpdateFact(kind = "agent_thought", text = textOfMessage(update.message))
            is SessionUpdate.StateUpdate -> AcpV2UpdateFact(
                kind = "state_update",
                state = when (update.state) {
                    is StateUpdate.Running -> "running"
                    is StateUpdate.RequiresAction -> "requires_action"
                    is StateUpdate.Idle -> "idle"
                    else -> "unknown"
                },
                stopReason = (update.state as? StateUpdate.Idle)?.stopReason.wireName(),
            )
            is SessionUpdate.ToolCallContentChunk -> AcpV2UpdateFact(kind = "tool_call_content_chunk")
            is SessionUpdate.ToolCallUpdate -> AcpV2UpdateFact(kind = "tool_call_update")
            is SessionUpdate.PlanUpdate -> AcpV2UpdateFact(kind = "plan_update")
            is SessionUpdate.PlanRemoved -> AcpV2UpdateFact(kind = "plan_removed")
            is SessionUpdate.AvailableCommandsUpdate -> AcpV2UpdateFact(kind = "available_commands_update")
            is SessionUpdate.ConfigOptionUpdate -> AcpV2UpdateFact(kind = "config_option_update")
            is SessionUpdate.SessionInfoUpdate -> AcpV2UpdateFact(kind = "session_info_update")
            is SessionUpdate.UsageUpdate -> AcpV2UpdateFact(kind = "usage_update")
            is SessionUpdate.Notice -> AcpV2UpdateFact(kind = "notice")
            is SessionUpdate.CompactionUpdate -> AcpV2UpdateFact(kind = "compaction_update")
            is SessionUpdate.CompactionSummaryChunk -> AcpV2UpdateFact(kind = "compaction_summary_chunk")
            is SessionUpdate.TerminalUpdate -> AcpV2UpdateFact(kind = "terminal_update")
            is SessionUpdate.TerminalOutputChunk -> AcpV2UpdateFact(kind = "terminal_output_chunk")
            else -> AcpV2UpdateFact(kind = "unknown_update")
        }

        /** The phase tag stored per fact: the state name only for lifecycle facts. */
        private fun tag(base: AcpV2UpdateFact, state: String): String =
            if (base.kind == "state_update") state else base.phase.ifEmpty { state }

        private fun textOf(chunk: ContentChunk): String =
            (chunk.content as? ContentBlock.Text)?.text.orEmpty()

        private fun textOfMessage(message: AgentMessage): String =
            message.content.valueOrNull().orEmpty().joinToString("") { block ->
                (block as? ContentBlock.Text)?.text.orEmpty()
            }

        /** The wire string of a stop reason, Unknown keeping the raw value it arrived with. */
        internal fun StopReason?.wireName(): String = when (this) {
            is StopReason.EndTurn -> "end_turn"
            is StopReason.Cancelled -> "cancelled"
            is StopReason.Refusal -> "refusal"
            is StopReason.MaxTokens -> "max_tokens"
            is StopReason.MaxTurnRequests -> "max_turn_requests"
            is StopReason.Unknown -> value
            null -> ""
        }

        internal fun verdictOfStopReason(raw: String): AcpV2IdleVerdict = when (raw) {
            "end_turn" -> AcpV2IdleVerdict.SUCCEEDED
            "cancelled" -> AcpV2IdleVerdict.CANCELLED
            "refusal" -> AcpV2IdleVerdict.REFUSED
            "max_tokens" -> AcpV2IdleVerdict.STOPPED_MAX_TOKENS
            "max_turn_requests" -> AcpV2IdleVerdict.STOPPED_MAX_TURNS
            else -> AcpV2IdleVerdict.UNRECOGNIZED_STOP_REASON
        }
    }
}

/**
 * The v2 mcp mapping: [AcpMcpInput]s onto `model.v2.McpServer`. v2's variants are stdio and
 * http only (sse was cut); stdio carries the single absolute executable plus argv tail, http
 * carries url and headers.
 */
private fun v2McpServers(inputs: List<AcpMcpInput>): List<com.agentclientprotocol.model.v2.McpServer> =
    inputs.map { input ->
        when (input.transport) {
            "stdio" -> com.agentclientprotocol.model.v2.McpServer.Stdio(
                name = input.name,
                command = input.command.firstOrNull().orEmpty(),
                args = input.command.drop(1),
                env = emptyList(),
                _meta = null,
            )
            else -> com.agentclientprotocol.model.v2.McpServer.Http(
                name = input.name,
                url = input.url,
                headers = input.headers.map {
                    com.agentclientprotocol.model.HttpHeader(name = it.name, value = it.value, _meta = null)
                },
                _meta = null,
            )
        }
    }

private fun cwdOf(input: AcpV2SessionInput): String =
    input.cwd.ifEmpty { System.getProperty("user.dir") }

/** The v2 replay cursor of [declared]: null sends none (the agent's default), "start" the named one. */
private fun replayFromOf(declared: String): com.agentclientprotocol.model.v2.ReplayFrom? =
    if (declared.isEmpty()) null
    else com.agentclientprotocol.model.v2.ReplayFrom.Start(_meta = null)

/** Runs one v2 wire call, relabeling a protocol-level failure with the operation's name. */
private suspend fun <T> v2Op(operation: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: JsonRpcException) {
        throw V2OperationSignal(operation, "${e.message} (code ${e.code})")
    }

/** The outcome mapping of the v2 scope, mirroring [AcpSessionOutcome]'s kindToken discipline. */
object AcpV2Outcome {
    fun toOutput(refusal: AcpRefusal): AcpV2SessionOutput = AcpV2SessionOutput(
        ok = false,
        outcome = "REFUSED",
        failureKind = refusal.kindToken,
        detail = refusal.detail,
    )
}
