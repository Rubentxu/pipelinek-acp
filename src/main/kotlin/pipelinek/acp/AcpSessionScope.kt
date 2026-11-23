package pipelinek.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The opaque identity of a session this client owns (PKH-135 AC#4).
 *
 * `acpSession` is a LEXICAL scope: the id the agent minted is not safe to hand out as a
 * capability, because another run cannot tell where the session lives. The ref binds the
 * agent's id to THIS connection's epoch — a per-JVM monotonic serial plus a per-connection
 * nonce — so a ref produced by one connection is provably foreign to another even after the
 * process that minted it is gone. The ref is a plain opaque string the durable output can
 * carry; its security property is the nonce comparison [exchange] enforces, NOT the base64
 * wrapping (an eavesdropper can decode the token — decoding still buys nothing outside the
 * connection that minted it, and a stolen token replayed here answers
 * [AcpRefusal.ForeignSessionRef] before a process launches).
 */
class SessionRef private constructor(
    /** the opaque token safe to put in step output. */
    val value: String,
    /** the owner connection epoch that minted this ref. */
    val connectionEpoch: Long,
    /** the per-connection nonce; a ref whose nonce differs belongs to another connection. */
    val ownerNonce: String,
    /** the agent-minted id, kept only inside the plugin for operations. */
    val agentSessionId: String,
) {
    companion object {
        private val JVM_EPOCH = java.util.concurrent.atomic.AtomicLong(0)

        /** Mint the ref for a session opened on the connection with [connectionNonce]. */
        fun mint(connectionNonce: String, agentSessionId: String): SessionRef {
            val epoch = JVM_EPOCH.incrementAndGet()
            val token = encode("v1|$epoch|$connectionNonce|$agentSessionId")
            return SessionRef(token, epoch, connectionNonce, agentSessionId)
        }

        /** Decode an opaque token, or null when it is not a well-formed session ref at all. */
        fun parse(value: String): SessionRef? {
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val parts = decoded.split("|")
            if (parts.size != 4 || parts[0] != "v1") return null
            val epoch = parts[1].toLongOrNull() ?: return null
            return SessionRef(value, epoch, parts[2], parts[3])
        }

        /** Whether [ref] names a session the connection carrying [connectionNonce] opened. */
        fun ownedBy(ref: SessionRef, connectionNonce: String): Boolean =
            ref.ownerNonce == connectionNonce

        /**
         * Resolve a declared load/resume id against the connection carrying [connectionNonce]
         * (PKH-135 AC#4). The plan may name a session three ways, and each has an answer:
         * a plain agent id is external knowledge and rides the wire unchanged; a token THIS
         * connection minted resolves to the agent id it carries; a token another connection
         * minted is [SessionIdResolution.Foreign] — never sent, refused before launch.
         */
        fun resolve(raw: String, connectionNonce: String): SessionIdResolution {
            val ref = parse(raw) ?: return SessionIdResolution.Accepted(wireId = raw, declared = raw)
            return if (ownedBy(ref, connectionNonce)) {
                SessionIdResolution.Accepted(wireId = ref.agentSessionId, declared = raw)
            } else {
                SessionIdResolution.Foreign(declared = raw)
            }
        }

        private fun encode(s: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    }
}

/** The decision of resolving one declared session id against the asking connection (AC#4). */
sealed class SessionIdResolution {
    /** the id exactly as the plan declared it — the diagnosis names this, never a rewrite. */
    abstract val declared: String

    /** safe to send: [wireId] is the agent id to put on the wire (equal to [declared] unless a token of this connection resolved into it). */
    data class Accepted(
        val wireId: String,
        override val declared: String,
    ) : SessionIdResolution()

    /** a ref token minted by ANOTHER connection: joining it would be an unaccounted second holder of the same agent-side state. */
    data class Foreign(
        override val declared: String,
    ) : SessionIdResolution()
}

/**
 * The session plan's declared needs against the agent's negotiated capabilities, as plain
 * booleans — the input for [AcpHandshake.sessionRefusal] so the gate is a decision the
 * plugin can test without a process, a pipe, or an SDK object.
 */
data class AcpSessionNeeds(
    val image: Boolean = false,
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
    val mcpHttp: Boolean = false,
    val mcpSse: Boolean = false,
    val load: Boolean = false,
    val resume: Boolean = false,
    val list: Boolean = false,
    val delete: Boolean = false,
    val logout: Boolean = false,
)

/**
 * The composed ACP session: one connection, one lexical scope, N sequential prompt turns
 * plus the negotiated session operations (PKH-135).
 *
 * NOT a daemon: the object owns the agent process for exactly one [exchange]; when the
 * exchange unwinds — completed, refused, cancelled or timed out — the scope's finally
 * releases everything: a pending prompt gets session/cancel over the wire while the
 * protocol still lives, the coroutine tree is cancelled, and the process tree is reaped.
 * That is AC#1's release clause and it is structural, not a convention: there is no field
 * where a session could be parked past the call.
 *
 * One prompt ACTIVE per session in v1: [AcpSessionInput.turns] run in order, each awaited
 * to its terminal event, while independent sessions parallelize because they are different
 * scopes over different connections. A second [exchange] attempted on the same live
 * instance is refused [AcpRefusal.ActivePromptInProgress] before a second process is
 * launched — incompatible concurrency is a typed answer, not a race (AC#4).
 *
 * Every failure path answers with a typed [AcpSessionOutput] `ok = false`; nothing throws
 * to signal a step failure (the execution boundary would re-classify a throw as ENGINE and
 * lose the declared kind). The one exception that propagates is the caller's genuine
 * cancellation, which belongs to the engine, not to this plugin.
 */
class AcpSessionConnection(private val pluginVersion: String) {

    /** Nonce identifying THIS connection instance as the owner of every ref it mints. */
    val connectionNonce: String = java.util.UUID.randomUUID().toString()
    private val inFlight = AtomicBoolean(false)

    /**
     * A typed refusal is also a lifecycle fact: the stream carries the refusal's CLASSIFICATION
     * (the same kindToken the durable output answers with), not its detail — the detail may
     * quote the agent's stderr, and the stderr is not a fact about the session.
     */
    private fun AcpUpdateSink?.exchangeRefused(refusal: AcpRefusal) {
        this?.exchangeRefused(refusal.kindToken)
    }

    /** The blocking entry point for tests and callers outside a coroutine. */
    fun run(input: AcpSessionInput, observation: AcpUpdateSink? = null): AcpSessionOutput =
        runBlocking { exchange(input, observation) }

    /**
     * Run the whole scope. AC#4's single-active-prompt rule applies to the scope itself: at
     * most one exchange is in flight per instance, and a second concurrent attempt is
     * refused before anything is launched.
     */
    suspend fun exchange(
        input: AcpSessionInput,
        observation: AcpUpdateSink? = null,
    ): AcpSessionOutput {
        val badMcp = input.mcpServers.firstOrNull { it.transport !in VALID_MCP_TRANSPORTS }
        if (badMcp != null) {
            val refusal = AcpRefusal.ConfigInvalid(
                "mcp server '${badMcp.name}' declares transport '${badMcp.transport}', " +
                    "v1 speaks " + VALID_MCP_TRANSPORTS.sorted().joinToString("/"),
            )
            observation?.exchangeRefused(refusal.kindToken)
            return AcpSessionOutcome.toOutput(refusal = refusal)
        }
        // AC#4's foreign-ref gate, BEFORE anything is launched: a declared load/resume id is
        // resolved against THIS connection — a plain agent id rides unchanged, a token this
        // connection minted resolves to the agent id it carries, and a token another
        // connection minted is refused typed. Sending it anyway would hand a persisting agent
        // a second, unaccounted holder of one session's state.
        val loadResolution = SessionRef.resolve(input.loadSessionId, connectionNonce)
        val resumeResolution = SessionRef.resolve(input.resumeSessionId, connectionNonce)
        val foreign = (listOf(loadResolution, resumeResolution)
            .filterIsInstance<SessionIdResolution.Foreign>()).firstOrNull()
        if (foreign != null) {
            val refusal = AcpRefusal.ForeignSessionRef(foreign.declared)
            observation?.exchangeRefused(refusal.kindToken)
            return AcpSessionOutcome.toOutput(refusal = refusal)
        }
        val wireInput = input.copy(
            loadSessionId = wireIdOf(loadResolution, input.loadSessionId),
            resumeSessionId = wireIdOf(resumeResolution, input.resumeSessionId),
        )
        if (!inFlight.compareAndSet(false, true)) {
            val refusal = AcpRefusal.ActivePromptInProgress(
                "this session scope already has an exchange running",
            )
            observation?.exchangeRefused(refusal.kindToken)
            return AcpSessionOutcome.toOutput(refusal = refusal)
        }
        try {
            return runOnce(wireInput, observation)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runOnce(input: AcpSessionInput, observation: AcpUpdateSink?): AcpSessionOutput {
        val process = try {
            AcpAgentProcess.open(input.command)
        } catch (e: AgentLaunchFailed) {
            observation.exchangeRefused(e.refusal)
            return AcpSessionOutcome.toOutput(refusal = e.refusal)
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            withTimeout(input.timeoutSeconds * 1000) { runScope(input, process, scope, observation) }
        } catch (e: TimeoutCancellationException) {
            val refusal = AcpRefusal.AgentTimeout(input.timeoutSeconds)
            observation.exchangeRefused(refusal)
            AcpSessionOutcome.toOutput(refusal = refusal)
        } catch (e: UnsupportedProtocolVersionException) {
            val refusal = AcpHandshake.refusal(e)
            observation.exchangeRefused(refusal)
            AcpSessionOutcome.toOutput(refusal = refusal)
        } catch (e: SessionOperationSignal) {
            // the sessionOp helper labels the wire failure with the operation the plan
            // declared; the answer keeps that name instead of a generic "acp" rejection.
            val refusal = AcpRefusal.SessionOperationFailed(e.operation, e.reason)
            observation.exchangeRefused(refusal)
            AcpSessionOutcome.toOutput(refusal = refusal)
        } catch (e: JsonRpcException) {
            val refusal = AcpRefusal.AgentRefusedRequest("acp", "${e.message} (code ${e.code})")
            observation.exchangeRefused(refusal)
            AcpSessionOutcome.toOutput(refusal = refusal)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                val exit = process.exitCodeOrNull()
                val refusal = if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
                else AcpRefusal.TransportClosed("the session scope ended before the exchange completed")
                observation.exchangeRefused(refusal)
                AcpSessionOutcome.toOutput(refusal = refusal)
            } else {
                throw e
            }
        } catch (e: Exception) {
            val exit = process.exitCodeOrNull()
            val refusal = if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
            else AcpRefusal.TransportFailure(e.message ?: e.toString())
            observation.exchangeRefused(refusal)
            AcpSessionOutcome.toOutput(refusal = refusal)
        } finally {
            // AC#1's release clause, and it runs through exception and cancellation too:
            // the coroutine tree dies and the process tree is reaped here, in the scope
            // that started them.
            job.cancel()
            process.destroyTree()
        }
    }

    private suspend fun runScope(
        input: AcpSessionInput,
        process: AcpAgentProcess,
        scope: CoroutineScope,
        observation: AcpUpdateSink? = null,
    ): AcpSessionOutput {
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines,
            output = { line -> process.writeLine(line) },
            name = "acp-session-pipelinek-acp",
        )
        val protocol = Protocol(scope, transport)
        protocol.start()
        val client = Client(protocol)
        // AC#3's load/live separation: updates routed to [CapturingOperations] are captured
        // ONLY while the sink holds a list — which is exactly the window of an in-flight
        // session/load, closed before anything else can happen. Live prompt events therefore
        // never mix into the historic list, and the historic list is a plain local of this
        // exchange: two scopes running in parallel cannot see each other's history.
        val historicSink = AtomicReference<MutableList<String>?>(null)
        var historic: List<String> = emptyList()
        try {
            val agent = client.initialize(AcpHandshake.clientInfo(pluginVersion))
            AcpHandshake.sessionRefusal(agent, needsOf(input))?.let {
                observation.exchangeRefused(it)
                return AcpSessionOutcome.toOutput(refusal = it)
            }
            // The exchange EXISTS from here on: the negotiation held, so every turn, refusal
            // and cancel that follows is a fact about an opened exchange — and the stream can
            // tell them apart from a run that never got this far.
            observation?.exchangeStarted()

            val operationsFactory = ClientOperationsFactory { _, _ -> CapturingOperations(historicSink) }
            val parameters = SessionCreationParameters(
                cwd = input.cwd.ifEmpty { System.getProperty("user.dir") },
                mcpServers = input.mcpServers.map { it.toWire() },
            )

            // AC#3: a session operation that fails on the wire is diagnosed as the operation
            // that failed, not as the generic agent-rejected answer the outer catch would
            // give — the plan declared a named operation, the refusal names it back.
            suspend fun <T> sessionOp(name: String, block: suspend () -> T): T = try {
                block()
            } catch (e: JsonRpcException) {
                throw SessionOperationSignal(name, "${e.message} (code ${e.code})")
            }

            val session: ClientSession = if (input.loadSessionId.isNotBlank()) {
                val history = mutableListOf<String>()
                historicSink.set(history)
                try {
                    sessionOp("session/load") {
                        client.loadSession(
                            sessionId = SessionId(input.loadSessionId),
                            sessionParameters = parameters,
                            operations = CapturingOperations(historicSink),
                        )
                    }
                } finally {
                    historicSink.set(null)
                    historic = history.toList()
                }
            } else if (input.resumeSessionId.isNotBlank()) {
                sessionOp("session/resume") {
                    client.resumeSession(
                        sessionId = SessionId(input.resumeSessionId),
                        sessionParameters = parameters,
                        operationsFactory = operationsFactory,
                    )
                }
            } else {
                client.newSession(sessionParameters = parameters, operationsFactory = operationsFactory)
            }

            val sessionRef = SessionRef.mint(connectionNonce, session.sessionId.value)

            fun refused(
                refusal: AcpRefusal,
                turns: List<AcpTurnOutput> = emptyList(),
                listed: List<String> = emptyList(),
            ): AcpSessionOutput {
                observation.exchangeRefused(refusal)
                return AcpSessionOutcome.toOutput(
                    turns = turns,
                    refusal = refusal,
                    sessionRef = sessionRef.value,
                    historicEvents = historic,
                    sessionsListed = listed,
                )
            }

            // Modes and configuration are "available negotiated": the agent decides whether
            // the session carries modes/config at all; asking for either when the session
            // says it has none is a typed refusal, not a silent no-op (AC#3, AC#4). An
            // unknown mode id or a value outside the offered option set is refused here,
            // before the agent's own error code can stand in for our diagnosis (AC#4).
            if (input.modeId.isNotBlank()) {
                if (!session.modesSupported) {
                    return refused(AcpRefusal.SessionCapabilityMissing("modes"))
                }
                val known = session.availableModes.any { it.id.value == input.modeId }
                if (!known) {
                    return refused(
                        AcpRefusal.ConfigInvalid(
                            "mode '${input.modeId}' is not among the modes this session offers",
                        ),
                    )
                }
                session.setMode(SessionModeId(input.modeId))
            }
            val appliedConfig = mutableListOf<String>()
            for (config in input.configOptions) {
                if (!session.configOptionsSupported) {
                    return refused(AcpRefusal.SessionCapabilityMissing("configOptions"))
                }
                val advertised = session.configOptions.value.firstOrNull {
                    it.id.value == config.configId
                } ?: return refused(
                    AcpRefusal.ConfigInvalid(
                        "config option '${config.configId}' was never advertised by this agent",
                    ),
                )
                val wire = when (advertised) {
                    is SessionConfigOption.Select -> {
                        val offered = when (val options = advertised.options) {
                            is SessionConfigSelectOptions.Flat ->
                                options.options.map { it.value.value }
                            is SessionConfigSelectOptions.Grouped ->
                                options.groups.flatMap { group -> group.options.map { it.value.value } }
                        }
                        if (config.value !in offered) {
                            return refused(
                                AcpRefusal.ConfigInvalid(
                                    "value '${config.value}' is not among the options " +
                                        "'${config.configId}' offers",
                                ),
                            )
                        }
                        SessionConfigOptionValue.StringValue(config.value)
                    }
                    is SessionConfigOption.BooleanOption -> when (config.value) {
                        "true" -> SessionConfigOptionValue.BoolValue(true)
                        "false" -> SessionConfigOptionValue.BoolValue(false)
                        else -> return refused(
                            AcpRefusal.ConfigInvalid(
                                "boolean option '${config.configId}' takes true/false, " +
                                    "got '${config.value}'",
                            ),
                        )
                    }
                }
                session.setConfigOption(SessionConfigId(config.configId), wire)
                appliedConfig.add("${config.configId}=${config.value}")
            }

            val listed = mutableListOf<String>()
            if (input.listSessions) {
                sessionOp("session/list") {
                    client.listSessions(cwd = parameters.cwd, additionalDirectories = emptyList())
                        .collect { info -> listed.add(info.sessionId.value) }
                }
            }

            // AC#1: sequential turns, each awaited to its terminal event — the agent's own
            // history carries the context between them; the scope never replays it by hand.
            val turns = mutableListOf<AcpTurnOutput>()
            for (turn in input.turns) {
                val reply = StringBuilder()
                var stopReason = ""
                try {
                    promptInto(session, turn, reply, observation) { stopReason = it }
                } catch (e: CancellationException) {
                    // The cancel the stream reports is the one said next: the reason classifies
                    // WHO ended the turn — the budget or the engine — because the wire cannot.
                    observation?.exchangeCancelled(
                        if (e is TimeoutCancellationException) "budget" else "withdrawn",
                    )
                    // Budget or engine withdrawal: say session/cancel while the protocol
                    // still lives, then let the unwind rethrow — the tree reap in runOnce's
                    // finally is the backstop, not the promise.
                    cancelUnderNonCancellable(session)
                    throw e
                }
                if (stopReason.isEmpty()) {
                    return refused(
                        AcpRefusal.TransportClosed("a prompt stream ended without a response"),
                        turns = turns.toList(), listed = listed.toList(),
                    )
                }
                turns.add(AcpTurnOutput(reply.toString(), stopReason))
                observation?.turnEnded(turns.size, stopReason)
            }

            var logoutOk = false
            if (input.logout) {
                sessionOp("logout") { client.logout() }
                logoutOk = true
            }

            // AC#3's release order: a NORMAL exit never deletes — session/close is said only
            // when the agent announced the close capability, and deletion happens solely when
            // the plan declared it. Closing a scope is not erasing history.
            if (agent.capabilities.sessionCapabilities?.close != null) {
                runCatching { session.close() }
            }
            if (input.deleteOnExit) {
                sessionOp("session/delete") {
                    client.deleteSession(SessionId(session.sessionId.value))
                }
            }

            return AcpSessionOutcome.toOutput(
                turns = turns.toList(),
                refusal = null,
                sessionRef = sessionRef.value,
                historicEvents = historic,
                sessionsListed = listed.toList(),
                appliedMode = input.modeId,
                appliedConfig = appliedConfig.toList(),
                logoutOk = logoutOk,
            )
        } finally {
            protocol.close()
        }
    }

    /** The plan's declared needs, read off the input without touching any wire type. */
    private fun needsOf(input: AcpSessionInput): AcpSessionNeeds {
        val contents = input.turns.flatMap { it.content }
        return AcpSessionNeeds(
            image = contents.any { it.capability == "image" },
            audio = contents.any { it.capability == "audio" },
            embeddedContext = contents.any { it.capability == "embeddedContext" },
            mcpHttp = input.mcpServers.any { it.transport == "http" },
            mcpSse = input.mcpServers.any { it.transport == "sse" },
            load = input.loadSessionId.isNotBlank(),
            resume = input.resumeSessionId.isNotBlank(),
            list = input.listSessions,
            delete = input.deleteOnExit,
            logout = input.logout,
        )
    }

    /** One prompt turn: text chunks accumulate in [reply], the terminal event sets [onStop]. */
    private suspend fun promptInto(
        session: ClientSession,
        turn: AcpTurnInput,
        reply: StringBuilder,
        observation: AcpUpdateSink? = null,
        onStop: (String) -> Unit,
    ) {
        val blocks = mutableListOf<ContentBlock>(ContentBlock.Text(text = turn.text))
        turn.content.forEach { blocks.add(it.toWire()) }
        session.prompt(blocks).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    observation?.accept(event.update)
                    (event.update as? SessionUpdate.AgentMessageChunk)?.let { chunk ->
                        (chunk.content as? ContentBlock.Text)?.let { reply.append(it.text) }
                    }
                }
                is Event.PromptResponseEvent -> onStop(event.response.stopReason.name)
            }
        }
    }

    private suspend fun cancelUnderNonCancellable(session: ClientSession) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(2_000) { session.cancel() }
            } catch (e: Exception) {
                // The tree reap in the caller's finally is the backstop; a loud cancel
                // failure here would replace the real outcome on the way out.
            }
        }
    }

    companion object {
        val VALID_MCP_TRANSPORTS: Set<String> = setOf("http", "sse", "stdio")
    }
}

private fun Set<String>.joinSortedToString(): String = sorted().joinToString("/")

/** The id to put on the wire for one resolved declaration: its agent id, blank left blank. */
private fun wireIdOf(resolution: SessionIdResolution, declared: String): String =
    (resolution as? SessionIdResolution.Accepted)?.wireId ?: declared

/**
 * A wire failure inside one labeled session operation. Internal control flow, never an
 * answer: [runOnce] converts it into the typed [AcpRefusal.SessionOperationFailed] the
 * plan's declared operation deserves.
 */
private class SessionOperationSignal(val operation: String, val reason: String) :
    Exception("session operation '$operation' failed: $reason")

/** The declared MCP descriptor mapped onto the SDK's wire type — the only place it appears. */
internal fun AcpMcpInput.toWire(): McpServer = when (transport) {
    "http" -> McpServer.Http(
        name = name, url = url,
        headers = headers.map { HttpHeader(name = it.name, value = it.value) },
    )
    "sse" -> McpServer.Sse(
        name = name, url = url,
        headers = headers.map { HttpHeader(name = it.name, value = it.value) },
    )
    else -> McpServer.Stdio(
        name = name,
        command = command.firstOrNull() ?: "",
        args = command.drop(1),
        env = emptyList(),
    )
}

/** The declared content block mapped onto the SDK's wire type; the gate already ran. */
internal fun AcpContentInput.toWire(): ContentBlock = when (this) {
    is AcpContentInput.Image -> ContentBlock.Image(data = data, mimeType = mimeType, uri = null)
    is AcpContentInput.Audio -> ContentBlock.Audio(data = data, mimeType = mimeType)
    is AcpContentInput.Resource -> ContentBlock.Resource(
        resource = EmbeddedResourceResource.TextResourceContents(uri = uri, text = text, mimeType = mimeType),
    )
    is AcpContentInput.ResourceLink -> ContentBlock.ResourceLink(
        uri = uri, name = name, mimeType = mimeType.ifEmpty { null },
    )
}

/**
 * The session's own answers to agent-initiated traffic: permissions DENIED (the PKH-132
 * default kept — the composed session declares no reverse authority), notifications routed
 * by the load-history separation rule. While [sink] holds a list the scope is inside
 * `session/load` and the updates ARE the history (AC#3); at every other moment live updates
 * are dropped exactly as [DenyingOperations] drops them, so historic and live never mix.
 */
internal class CapturingOperations(
    private val sink: AtomicReference<MutableList<String>?>,
) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
        val history = sink.get() ?: return
        (notification as? SessionUpdate.AgentMessageChunk)?.let { chunk ->
            (chunk.content as? ContentBlock.Text)?.let { history.add(it.text) }
        }
    }
}
