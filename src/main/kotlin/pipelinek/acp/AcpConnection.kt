package pipelinek.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    /** The blocking entry point for tests and callers outside a coroutine. */
    fun run(input: AcpPromptInput): AcpExchange = runBlocking { exchange(input) }

    /** The step handler's entry point; suspend so the process I/O never parks an engine thread. */
    suspend fun exchange(input: AcpPromptInput): AcpExchange {
        val process = try {
            AcpAgentProcess.open(input.command)
        } catch (e: AgentLaunchFailed) {
            return AcpExchange.Refused(e.refusal)
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            withTimeout(input.timeoutSeconds * 1000) { runExchange(input, process, scope) }
        } catch (e: TimeoutCancellationException) {
            AcpExchange.Refused(AcpRefusal.AgentTimeout(input.timeoutSeconds))
        } catch (e: UnsupportedProtocolVersionException) {
            AcpExchange.Refused(AcpHandshake.refusal(e))
        } catch (e: JsonRpcException) {
            AcpExchange.Refused(AcpRefusal.AgentRefusedRequest("acp", "${e.message} (code ${e.code})"))
        } catch (e: CancellationException) {
            // The SDK unwinds a pending request as a CancellationException when the transport
            // dies under it. If OUR job is still active, that cancellation came from the other
            // side of the pipe and it is a step failure; if it is not, the engine withdrew the
            // step and rethrowing is the only honest answer.
            if (currentCoroutineContext().isActive) {
                val exit = process.exitCodeOrNull()
                AcpExchange.Refused(
                    if (exit != null) AcpRefusal.AgentExited(exit, process.stderrTail)
                    else AcpRefusal.TransportClosed("the connection ended before the exchange completed"),
                )
            } else {
                throw e
            }
        } catch (e: Exception) {
            val exit = process.exitCodeOrNull()
            if (exit != null) {
                AcpExchange.Refused(AcpRefusal.AgentExited(exit, process.stderrTail))
            } else {
                AcpExchange.Refused(AcpRefusal.TransportFailure(e.message ?: e.toString()))
            }
        } finally {
            job.cancel()
            process.destroy()
        }
    }

    private suspend fun runExchange(
        input: AcpPromptInput,
        process: AcpAgentProcess,
        scope: CoroutineScope,
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
            val agentInfo = client.initialize(AcpHandshake.clientInfo(pluginVersion))
            AcpHandshake.refusal(agentInfo, needsImage = false)?.let {
                return AcpExchange.Refused(it)
            }
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(
                    cwd = input.cwd.ifEmpty { System.getProperty("user.dir") },
                    mcpServers = emptyList(),
                ),
                operationsFactory = ClientOperationsFactory { _, _ -> DenyingOperations },
            )
            val reply = StringBuilder()
            var stopReason = ""
            session.prompt(listOf(ContentBlock.Text(text = input.prompt))).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent ->
                        (event.update as? SessionUpdate.AgentMessageChunk)?.let { chunk ->
                            (chunk.content as? ContentBlock.Text)?.let { reply.append(it.text) }
                        }
                    is Event.PromptResponseEvent -> stopReason = event.response.stopReason.name
                }
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
            return AcpExchange.Completed(reply.toString(), stopReason)
        } finally {
            protocol.close()
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
