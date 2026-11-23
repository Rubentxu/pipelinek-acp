package pipelinek.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.UnsupportedProtocolVersionException
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import java.nio.file.Path
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
 * The whole ACP client for one step invocation: launch, connect, initialize, open a session,
 * prompt once, release.
 *
 * The lifecycle is one-shot on purpose. PipelineK steps are individually durable and the ACP
 * connection is not durable state — a connection parked in a field would belong to whichever
 * run opened it and could not be replayed by another. Each invocation owns its process and
 * releases it here, in the same scope that started it.
 *
 * Every failure path returns [AcpExchange.Refused] with a typed [AcpRefusal]. Nothing throws
 * to signal a step failure, because the published handler contract says a thrown exception is
 * re-classified as `ENGINE` by the execution boundary and the declared kind is lost. The one
 * exception that propagates is a genuine cancellation of the CALLER's coroutine, which is not
 * a step failure but the engine withdrawing the step.
 */
class AcpConnection(private val pluginVersion: String) {

    companion object {
        /** How long the agent gets to act on session/cancel before its tree is reaped. */
        const val CANCEL_GRACE_MILLIS: Long = 500
    }

    /** The blocking entry point for tests and callers outside a coroutine. */
    fun run(input: AcpPromptInput): AcpExchange = runBlocking { exchange(input) }

    /** The step handler's entry point; suspend so the process I/O never parks an engine thread. */
    suspend fun exchange(
        input: AcpPromptInput,
        reverse: AcpReverseSession? = null,
        reaction: AcpReaction? = null,
        observation: AcpUpdateSink? = null,
    ): AcpExchange {
        val process = try {
            AcpAgentProcess.open(input.command)
        } catch (e: AgentLaunchFailed) {
            return AcpExchange.Refused(e.refusal)
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            withTimeout(input.timeoutSeconds * 1000) {
                runExchange(
                    prompt = input.prompt,
                    cwd = input.cwd.ifEmpty { System.getProperty("user.dir") },
                    process = process,
                    scope = scope,
                    cancelOnUnwind = false,
                    reverse = reverse,
                    reaction = reaction,
                    observation = observation,
                )
            }
        } catch (e: TimeoutCancellationException) {
            AcpExchange.Refused(AcpRefusal.AgentTimeout(input.timeoutSeconds))
        } catch (e: UnsupportedProtocolVersionException) {
            AcpExchange.Refused(AcpHandshake.refusal(e))
        } catch (e: JsonRpcException) {
            AcpExchange.Refused(AcpRefusal.AgentRefusedRequest("acp", "${e.message} (code ${e.code})"))
        } catch (e: CancellationException) {
            refuseUnderCancellationOrRethrow(e, process)
        } catch (e: Exception) {
            classifyUnexpected(e, process)
        } finally {
            job.cancel()
            process.destroy()
        }
    }

    /**
     * One delegated task under the isolation rules of `acpTask` (PKH-133).
     *
     * Same one-shot lifecycle as [exchange], three rules stricter: the process starts with the
     * CALLER-SUPPLIED environment and working directory (never the host's inherited world),
     * [input].budgetSeconds bounds the whole exchange INCLUDING the cleanup that follows it,
     * and when the exchange ends by budget or by the engine's cancellation, [runExchange] has
     * already said session/cancel to the agent while the protocol still lived — the reap that
     * follows here is the tree-wide backstop, so the budget cannot be widened by a deaf agent.
     *
     * PKH-134 adds the reverse leg: [reverse] is the declared authority's session machinery —
     * permissions answered by policy, fs and terminal fenced to [workingDirectory]. Its
     * terminals die INSIDE this exchange's budget like every other effect the delegation
     * produced, and its bounded journal rides out in the completed exchange. Null keeps
     * PKH-132's behaviour verbatim: everything in reverse denied, nothing advertised.
     */
    suspend fun exchangeTask(
        input: AcpTaskInput,
        environment: Map<String, String>,
        workingDirectory: Path,
        reverse: AcpReverseSession? = null,
        observation: AcpUpdateSink? = null,
    ): AcpExchange {
        val process = AcpAgentProcess.launch(input.command, environment, workingDirectory)
            .getOrElse { e -> return AcpExchange.Refused(AcpRefusal.AgentUnreachable(e.message ?: e.toString())) }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            withTimeout(input.budgetSeconds * 1000) {
                runExchange(
                    prompt = input.prompt,
                    cwd = workingDirectory.toString(),
                    process = process,
                    scope = scope,
                    cancelOnUnwind = true,
                    reverse = reverse,
                    observation = observation,
                )
            }
        } catch (e: TimeoutCancellationException) {
            AcpExchange.Refused(AcpRefusal.AgentTimeout(input.budgetSeconds))
        } catch (e: UnsupportedProtocolVersionException) {
            AcpExchange.Refused(AcpHandshake.refusal(e))
        } catch (e: JsonRpcException) {
            AcpExchange.Refused(AcpRefusal.AgentRefusedRequest("acp", "${e.message} (code ${e.code})"))
        } catch (e: CancellationException) {
            // The engine withdrew the step; session/cancel was said inside the unwinding.
            refuseUnderCancellationOrRethrow(e, process)
        } catch (e: Exception) {
            classifyUnexpected(e, process)
        } finally {
            job.cancel()
            // AC#2: the TREE dies inside the budget. The agent's own children are the
            // delegation's effects; leaving them alive would widen the run past its deadline.
            process.destroyTree()
            // The plugin's OWN terminals (the terminal/* reverse leg, AC#3) are effects the
            // client created, so the client kills them too — inside the same budget, bounded,
            // never left for a later exchange to find.
            reverse?.reapTerminals()
        }
    }

    /**
     * session/cancel with the caller's fate sealed: neither the timeout path nor the
     * cancellation path may be interrupted out of telling the agent to stop before its pipes
     * are closed. [CANCEL_GRACE_MILLIS] is spent waiting for the agent to ACT on the
     * notification — resolve its pending work, flush whatever it writes — because the reap in
     * the caller's finally would otherwise race the very message we just sent. Bounded, so a
     * deaf transport delays cleanup by the grace and never parks it.
     */
    private suspend fun sendCancelUnderNonCancellable(session: ClientSession) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(2_000) { session.cancel() }
                withTimeoutOrNull(CANCEL_GRACE_MILLIS + 500) {
                    kotlinx.coroutines.delay(CANCEL_GRACE_MILLIS)
                }
            } catch (e: Exception) {
                // The tree reap in the caller's finally is the backstop; a loud cancel failure
                // here would replace the real outcome on the way out.
            }
        }
    }

    /** The PKH-132 rule, verbatim: a cancellation from the other side of the pipe is a step
     * failure; one from the engine is the engine's to answer. */
    private suspend fun refuseUnderCancellationOrRethrow(
        e: CancellationException,
        process: AcpAgentProcess,
    ): AcpExchange {
        return if (currentCoroutineContext().isActive) {
            val exit = process.exitCodeOrNull()
            AcpExchange.Refused(
                if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
                else AcpRefusal.TransportClosed("the connection ended before the exchange completed"),
            )
        } else {
            throw e
        }
    }

    private fun classifyUnexpected(e: Exception, process: AcpAgentProcess): AcpExchange {
        val exit = process.exitCodeOrNull()
        return if (exit != null) {
            AcpExchange.Refused(AcpRefusal.AgentExited(exit, process.stderrTail))
        } else {
            AcpExchange.Refused(AcpRefusal.TransportFailure(e.message ?: e.toString()))
        }
    }

    /**
     * The exchange every entry point runs: initialize, the capability gate, one session, one
     * prompt turn. [cancelOnUnwind] is the delegated-task rule (AC#2): when the prompt turn
     * ends by cancellation of OUR coroutine — the budget firing or the engine withdrawing the
     * run — the agent is told session/cancel in ACP words before the stack unwinds out, so
     * its pending work resolves in the protocol rather than only in a kill.
     *
     * [reverse] is the PKH-134 session machinery: when present, the client answers
     * agent-initiated requests by the declared authority instead of [DenyingOperations],
     * announces the capability intersection it can actually keep, and its pending permission
     * requests are resolved to Cancelled BEFORE session/cancel is said — the agent resolves
     * its own open request instead of hanging until the reap. The prompt path passes null
     * and keeps PKH-132's deny-everything behaviour verbatim; the reactive path passes a
     * session whose only extra duty is to hand the agent's updates to the reaction hook.
     */
    private suspend fun runExchange(
        prompt: String,
        cwd: String,
        process: AcpAgentProcess,
        scope: CoroutineScope,
        cancelOnUnwind: Boolean,
        reverse: AcpReverseSession?,
        reaction: AcpReaction? = null,
        observation: AcpUpdateSink? = null,
    ): AcpExchange {
        val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.stdoutLines,
            output = { line -> process.writeLine(line) },
            name = "acp-pipelinek-acp",
        )
        val protocol = Protocol(scope, transport)
        protocol.start()
        val client = Client(protocol)
        try {
            val agentInfo = client.initialize(
                if (reverse != null) AcpHandshake.clientInfo(pluginVersion, reverse)
                else AcpHandshake.clientInfo(pluginVersion),
            )
            AcpHandshake.refusal(agentInfo, needsImage = false)?.let {
                return AcpExchange.Refused(it)
            }
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(
                    cwd = cwd.ifEmpty { System.getProperty("user.dir") },
                    mcpServers = emptyList(),
                ),
                operationsFactory = reverse?.factory()
                    ?: ClientOperationsFactory { _, _ -> ObservingOperations(observation) },
            )
            val reply = StringBuilder()
            var stopReason = ""
            try {
                promptInto(session, prompt, reply, reaction, observation) { stopReason = it }
            } catch (e: CancellationException) {
                if (cancelOnUnwind) {
                    // AC#1's last clause: the open permission requests get their Cancelled
                    // answer over the wire first; session/cancel is said only after.
                    reverse?.cancelOutstandingPermissions()
                    sendCancelUnderNonCancellable(session)
                }
                throw e
            }
            if (stopReason.isEmpty()) {
                return AcpExchange.Refused(
                    AcpRefusal.TransportClosed("the prompt stream ended without a response"),
                )
            }
            // A non-END_TURN stop is still a completed exchange: the agent answered, and what
            // it answered with is recorded. The stop reason travels in the output and the
            // pipeline branches on it; discarding a MAX_TOKENS reply here would be the plugin
            // deciding the answer was not worth delivering.
            return AcpExchange.Completed(
                reply.toString(),
                stopReason,
                reverse?.journal?.snapshot() ?: emptyList(),
            )
        } finally {
            protocol.close()
        }
    }

    /**
     * One prompt turn: text chunks accumulate in [reply], the terminal event sets [onStop].
     *
     * [reaction] is the reactive client's hook: the SDK routes the updates an agent sends
     * DURING a prompt to the prompt's own stream — not to the session operations, which only
     * see the updates that arrive with no prompt in flight — so the stream is the one place
     * where the turn's events can be reacted to as they happen.
     */
    private suspend fun promptInto(
        session: ClientSession,
        prompt: String,
        reply: StringBuilder,
        reaction: AcpReaction?,
        observation: AcpUpdateSink? = null,
        onStop: (String) -> Unit,
    ) {
        session.prompt(listOf(ContentBlock.Text(text = prompt))).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    // The observation is offered BEFORE the reaction and never waits for it:
                    // this is the reader's thread, and the lane it hands to is bounded so the
                    // flood of chunks cannot park the cancellation behind itself.
                    observation?.accept(event.update)
                    reaction?.react(event.update)
                    (event.update as? SessionUpdate.AgentMessageChunk)?.let { chunk ->
                        (chunk.content as? ContentBlock.Text)?.let { reply.append(it.text) }
                    }
                }
                is Event.PromptResponseEvent -> onStop(event.response.stopReason.name)
            }
        }
    }
}

/**
 * The client's answer to agent-initiated requests during a prompt.
 *
 * Permission requests are DENIED, never auto-approved: an autonomous agent that mutates a
 * workspace needs an authority in PipelineK behind the answer, and that authority is a later
 * task of this front — until it exists, the honest answer is no. Reverse notifications are
 * dropped: projecting them into the run's observability is its own task, and inventing a sink
 * here would be a second, unrouted event authority.
 */
internal object DenyingOperations : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): RequestPermissionResponse =
        RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
        // Intentionally empty: event projection is a later task; there is no sink yet.
    }
}

/**
 * The operations of an exchange whose updates are [observation]-projected: the permission
 * default is unchanged (DENIED — an observation is a report, never a grant), and the reverse
 * notifications the SDK delivers outside the prompt stream reach the projection instead of
 * being dropped.
 *
 * The sink is offered from the SDK's notification thread and never blocks it: that is the
 * lane's contract, not this class's.
 */
internal class ObservingOperations(private val observation: AcpUpdateSink?) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: kotlinx.serialization.json.JsonElement?,
    ): RequestPermissionResponse =
        RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
        observation?.accept(notification)
    }
}
