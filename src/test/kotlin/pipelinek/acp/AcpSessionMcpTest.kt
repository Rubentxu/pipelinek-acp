package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-139 end to end: exposed steps lower to named body children, survive the input codec,
 * and a REAL agent speaking MCP over the session's descriptor drives them as slot requests —
 * with schema denials, idempotent repeats, a dead port after the turn, and no deadlock when a
 * reaction body is blocked while a tool call arrives.
 *
 * The pairing the unit rows cannot make: the ACP negotiation gates the MCP transport (AC#5),
 * the wire `session/new` carries the gateway URL, and the answer the agent reads back is the
 * journal's own verdict (AC#1, AC#3, AC#4 through the actual client and python fixture).
 */
class AcpSessionMcpTest {

    private class RecordingSlot : BodySlotInvoker {
        val invocations = Collections.synchronizedList(mutableListOf<Pair<PluginStepId, BodyInvocationContext>>())

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            invocations += slot to context
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    /**
     * The AC#4 shape: the reaction body stays blocked while the tool body must run, and each
     * waits on the other's marker. Both threads making progress is the proof the reader, the
     * runner and the gateway own no shared blocking point; a marker that times out says so in
     * the trace instead of hiding behind the turn timeout.
     */
    private class DeadlockSlot(
        private val kickStarted: CountDownLatch,
        private val toolDispatched: CountDownLatch,
        private val trace: MutableList<String>,
    ) : BodySlotInvoker {
        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            when (slot.value) {
                "kick" -> {
                    trace += "kick:started"
                    kickStarted.countDown()
                    if (toolDispatched.await(10, TimeUnit.SECONDS)) trace += "kick:resumed" else trace += "kick:TIMEOUT"
                }
                "step" -> {
                    if (!kickStarted.await(10, TimeUnit.SECONDS)) trace += "step:kick-never-started"
                    trace += "step:started"
                    toolDispatched.countDown()
                }
            }
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private class BoundCapabilities(
        private val values: Map<StepCapability, Any>,
    ) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = values.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            val value = values[key] ?: error("capability '$key' is not bound")
            require(type.isInstance(value)) { "capability '$key' does not carry a ${type.simpleName}" }
            return value as T
        }
    }

    private object TestConfig : RuntimeConfig {
        override fun env(name: String): String = System.getenv(name) ?: ""
        override fun property(name: String): String = System.getProperty(name) ?: ""
        override fun property(name: String, defaultValue: String): String = System.getProperty(name) ?: defaultValue
        override fun osName(): String = System.getProperty("os.name")
        override fun userDir(): String = System.getProperty("user.dir")
    }

    private fun runTestsTool(id: String = "runTests") = acpExpose(
        id = id,
        description = "runs the suite",
        inputSchema = AcpToolSchema(
            properties = listOf(acpToolArgument("filter", "string", required = true, description = "test filter")),
        ),
        policy = AcpToolPolicy(maxConcurrent = 2, maxCalls = 8),
        effectful = true,
    )

    private fun turn(
        input: AcpReactiveInput,
        slot: BodySlotInvoker,
        runId: String,
    ): AcpReactiveOutput = runBlocking {
        AcpReactiveStepDefinition.handler.execute(
            input,
            StepHandlerContext(
                runId = RunId(runId),
                stepIndex = 0,
                capabilities = BoundCapabilities(
                    mapOf(
                        BODY_SLOT_CAPABILITY to slot,
                        INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
                        PLUGIN_EVENT_EMISSION_CAPABILITY to AcpUnregisteredEventEmission(),
                    ),
                ),
            ),
        )
    }

    /** AC#2's expiry, measured on the wire: nothing listens on the port once the turn ended. */
    private fun assertGatewayDead(url: String) {
        val hostPort = url.removePrefix("http://").substringBefore("/")
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":").toInt()
        val failure = assertThrows(Exception::class.java) {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1_000) }
        }
        assertTrue(
            failure is java.net.ConnectException || failure.message.orEmpty().contains("refused"),
            "the gateway must be gone after the turn, got: ${failure.message}",
        )
    }

    @Test
    fun `exposed tools lower to named slots beside reactions and survive the input codec`() {
        val scope = StageScope("Agent", TestConfig)
        scope.acpReactive(
            command = listOf("agent", "--acp"),
            prompt = "call your steps",
            reactions = listOf(acpOn("alert", AcpEventSelector.TextContains("ERROR"))),
            exposedTools = listOf(runTestsTool()),
        )

        val block = assertInstanceOf(StepSpec.RegistryBlockSpec::class.java, scope.steps().single())
        assertEquals(
            listOf("alert", "runTests"),
            block.body.map { assertInstanceOf(StepSpec.Slot::class.java, it).slotName },
            "the tools are body children too — one dispatcher, one seam for reactions and calls",
        )
        val decoded = AcpReactiveStepDefinition.InputCodec.decode(block.encodedInput)
        assertEquals(listOf("runTests"), decoded.exposedTools.map { it.id })
        assertEquals(
            listOf(AcpToolArgument("filter", "string", required = true, description = "test filter")),
            decoded.exposedTools.single().inputSchema.properties,
            "the schema is serializable DATA — a recovered run reads back the tools it promised",
        )
        assertEquals(AcpToolPolicy(maxConcurrent = 2, maxCalls = 8), decoded.exposedTools.single().policy)
    }

    @Test
    fun `tools without declared reactions keep the unnamed update child beside their slots`() {
        // An empty reaction plan must not silently lose the shipped update hook just because a
        // tool was declared: the unnamed child stays, the tool names its own.
        val scope = StageScope("Agent", TestConfig)
        scope.acpReactive(
            command = listOf("agent", "--acp"),
            prompt = "call your steps",
            exposedTools = listOf(runTestsTool()),
        )

        val block = assertInstanceOf(StepSpec.RegistryBlockSpec::class.java, scope.steps().single())
        assertEquals(
            AcpReactionStepDefinition.KEY,
            assertInstanceOf(StepSpec.RegistryStepSpec::class.java, block.body.first()).stepKey,
        )
        assertEquals(
            listOf("runTests"),
            block.body.drop(1).map { assertInstanceOf(StepSpec.Slot::class.java, it).slotName },
        )
    }

    @Test
    fun `an exposed tool stealing a reaction name is refused before the agent launches`() {
        val slot = RecordingSlot()
        val output = turn(
            AcpReactiveInput(
                command = listOf("python3", "-I", "/dev/null"),
                prompt = "react",
                reactions = listOf(acpOn("shared", AcpEventSelector.AnyUpdate)),
                exposedTools = listOf(runTestsTool(id = "shared")),
            ),
            slot,
            "run-tool-collision",
        )

        assertEquals("ACP_PLAN_REJECTED", output.failureKind)
        assertTrue("shared" in output.detail, "the refusal names the stolen address: ${output.detail}")
        assertTrue("reaction" in output.detail, "and says whose address it was: ${output.detail}")
        assertEquals(0, slot.invocations.size, "a refused plan dispatched nothing and launched nobody")
    }

    @Test
    fun `a real agent drives the session gateway mid-turn with dedupe schema denial and expiry`() {
        // The whole facade over the real client: negotiation admits the http transport, the
        // session/new wire carries the gateway URL, the agent's tools/call executes the slot
        // (AC#1), the same idempotency key answers already_acknowledged without a second
        // invocation (AC#3), a value outside the schema is denied on the wire (AC#2), and the
        // port is dead once the turn ends (AC#2's expiry). The agent AWAITING its tool call
        // mid-prompt and getting the answer is AC#4's non-blocking half: no deadlock, real.
        val slot = RecordingSlot()
        val workDir = Files.createTempDirectory("acp-session-mcp-").also { it.toFile().deleteOnExit() }
        val output = turn(
            AcpReactiveInput(
                command = listOf("python3", "-I", mcpToolAgent(http = true).toString()),
                prompt = "use your tools",
                cwd = workDir.toString(),
                timeoutSeconds = 60,
                reactions = listOf(acpOn("quiet", AcpEventSelector.TextContains("ZZZ"))),
                exposedTools = listOf(runTestsTool()),
            ),
            slot,
            "run-session-mcp",
        )

        assertTrue(output.ok, "the turn must complete: ${output.failureKind} ${output.detail}")
        assertTrue("tools:runTests" in output.reply, "the agent listed exactly the exposed tool: ${output.reply}")
        assertTrue("first:executed" in output.reply, "the call the agent awaited was served: ${output.reply}")
        assertTrue("bad:-32602" in output.reply, "the schema denial reached the agent as a typed error: ${output.reply}")
        assertTrue(
            "again:already_acknowledged" in output.reply,
            "the repeat under the same key answered from the journal: ${output.reply}",
        )

        val toolInvocations = slot.invocations.filter { it.first.value == "runTests" }
        assertEquals(1, toolInvocations.size, "the acknowledged repeat dispatched no second invocation")
        assertEquals("acp-tool.runTests", toolInvocations.single().second.attempt?.key?.value)
        assertEquals(1, toolInvocations.single().second.attempt?.index)
        assertEquals(0, slot.invocations.count { it.first.value == "quiet" })

        assertEquals(
            listOf("executed", "schema_denied", "already_acknowledged"),
            output.exposedToolCalls.map { it.status },
            "every call the gateway answered — allowed or refused — is in the turn's evidence",
        )
        val executed = output.exposedToolCalls.first()
        val token = output.mcpGatewayUrl.substringAfter("/mcp/")
        assertTrue(
            executed.artifactRef.startsWith("run/run-session-mcp/op/step-0/session/$token/reaction/runTests/event/"),
            "the typed reference names the durable path the engine journalled: ${executed.artifactRef}",
        )

        val toolRows = output.reactionLedger.filter { it.reactionId == "runTests" }
        assertEquals(
            listOf(AcpEffectPhase.INTENT, AcpEffectPhase.ACKED),
            toolRows.map { it.phase },
            "one dispatch wrote one intent/ack pair — the ledger is shared, never duplicated",
        )
        assertEquals("ext:$token:turn-key-1", toolRows.single { it.phase == AcpEffectPhase.ACKED }.eventKey.canonical)

        assertTrue(output.mcpGatewayUrl.startsWith("http://127.0.0.1:"))
        assertGatewayDead(output.mcpGatewayUrl)
    }

    @Test
    fun `an agent without the mcp http capability gets a typed refusal and no call surface`() {
        // AC#5: the gateway may exist in the plan, but it is NEVER offered to an agent that did
        // not negotiate the transport: the refusal names the negotiation that failed, no slot
        // was dispatched, and the minted port dies with the turn.
        val slot = RecordingSlot()
        val output = turn(
            AcpReactiveInput(
                command = listOf("python3", "-I", mcpToolAgent(http = false).toString()),
                prompt = "use your tools",
                timeoutSeconds = 60,
                exposedTools = listOf(runTestsTool()),
            ),
            slot,
            "run-no-mcp",
        )

        assertTrue(!output.ok)
        assertEquals("ACP_SESSION_CAPABILITY_MISSING", output.failureKind)
        assertTrue("mcp http" in output.detail, "the refusal names the transport: ${output.detail}")
        assertEquals(0, slot.invocations.size, "no turn ran, so no slot moved — and no generic surface was offered")
        assertTrue(output.exposedToolCalls.isEmpty(), "an agent that was refused the transport called nothing")
        assertTrue(output.mcpGatewayUrl.isNotEmpty(), "the gateway existed only for the refused turn")
        assertGatewayDead(output.mcpGatewayUrl)
    }

    @Test
    fun `a blocked reaction body never stops the gateway serving the agent mid-turn`() {
        // AC#4 in its dangerous shape: the agent talks, a reaction's body parks on a latch, the
        // agent then awaits a tools/call. If reader, runner and gateway shared a blocking point
        // the call would never be served and the latches would time out. The trace proves the
        // interleaving happened WHILE the reaction was still blocked, and the agent's own
        // stream says it got the answer before the turn ended.
        val kickStarted = CountDownLatch(1)
        val toolDispatched = CountDownLatch(1)
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val workDir = Files.createTempDirectory("acp-session-mcp-deadlock-").also { it.toFile().deleteOnExit() }
        val output = turn(
            AcpReactiveInput(
                command = listOf("python3", "-I", toolAfterThoughtAgent().toString()),
                prompt = "think then call",
                cwd = workDir.toString(),
                timeoutSeconds = 60,
                reactions = listOf(acpOn("kick", AcpEventSelector.UpdateKind("agent_thought_chunk"))),
                exposedTools = listOf(
                    acpExpose(
                        id = "step",
                        description = "advances one pipeline step",
                        policy = AcpToolPolicy(maxConcurrent = 1, maxCalls = 4),
                        effectful = true,
                    ),
                ),
            ),
            DeadlockSlot(kickStarted, toolDispatched, trace),
            "run-no-deadlock",
        )

        assertTrue(output.ok, "the turn completed instead of deadlocking: ${output.failureKind} ${output.detail}")
        assertEquals(
            listOf("kick:started", "step:started", "kick:resumed"),
            trace.toList(),
            "the tool ran WHILE the reaction body was blocked, then the reaction resumed — no shared blocker",
        )
        assertTrue("resumed:executed" in output.reply, "the agent was served mid-prompt: ${output.reply}")
        assertEquals(listOf("executed"), output.exposedToolCalls.map { it.status })
        assertGatewayDead(output.mcpGatewayUrl)
    }

    /**
     * A real ACP agent that negotiates the http MCP transport (or, with [http] false, does not
     * announce it), reads the session's MCP descriptor, and speaks MCP to the gateway DURING
     * the prompt turn: initialize, tools/list, a call, an out-of-schema call, a repeat under
     * the same idempotency key — reporting what it got back in its own text stream.
     */
    private fun mcpToolAgent(http: Boolean): Path {
        val script = Files.createTempFile("acp-mcp-agent-", ".py")
        script.toFile().deleteOnExit()
        val capabilities = if (http) {
            """
            "mcpCapabilities": {"http": True, "sse": False},
            """.trimIndent()
        } else {
            ""
        }
        Files.writeString(
            script,
            """
            import sys, json, urllib.request

            def write(message):
                sys.stdout.write(json.dumps(message) + "\n")
                sys.stdout.flush()

            def update(payload):
                write({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": "mcp-session", "update": payload,
                }})

            def say(text):
                update({"sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text", "text": text}})

            def rpc(url, mid, method, params):
                body = json.dumps({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}).encode()
                request = urllib.request.Request(url, data=body, headers={
                    "Content-Type": "application/json",
                    "Accept": "application/json, text/event-stream",
                })
                with urllib.request.urlopen(request, timeout=20) as response:
                    return json.loads(response.read().decode())

            def answer(message):
                return json.loads(message["result"]["content"][0]["text"])

            urls = []

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                msg = json.loads(line)
                method = msg.get("method")
                mid = msg.get("id")
                if mid is None:
                    continue
                if method == "initialize":
                    result = {
                        "protocolVersion": 1,
                        "agentCapabilities": {
                            "loadSession": False,
                            $capabilities
                            "promptCapabilities": {"audio": False, "image": False, "embeddedContext": False},
                        },
                        "authMethods": [],
                        "agentInfo": {"name": "acp-mcp-fixture", "version": "0.1.0"},
                    }
                elif method == "session/new":
                    servers = msg.get("params", {}).get("mcpServers", [])
                    urls = [server["url"] for server in servers if server.get("url")]
                    result = {"sessionId": "mcp-session"}
                elif method == "session/prompt":
                    try:
                        gateway = urls[0]
                        rpc(gateway, 1, "initialize", {"protocolVersion": "2025-03-26",
                                                       "capabilities": {},
                                                       "clientInfo": {"name": "fixture-agent", "version": "0"}})
                        listing = rpc(gateway, 2, "tools/list", {})
                        names = sorted(tool["name"] for tool in listing["result"]["tools"])
                        first = answer(rpc(gateway, 3, "tools/call", {"name": "runTests",
                                                                      "arguments": {"filter": "unit"},
                                                                      "_meta": {"pipelinek.acp/idempotencyKey": "turn-key-1"}}))
                        bad = rpc(gateway, 4, "tools/call", {"name": "runTests", "arguments": {"filter": 7}})
                        again = answer(rpc(gateway, 5, "tools/call", {"name": "runTests",
                                                                      "arguments": {"filter": "unit"},
                                                                      "_meta": {"pipelinek.acp/idempotencyKey": "turn-key-1"}}))
                        say("tools:" + ",".join(names))
                        say("first:" + first["status"] + " artifact:" + first["artifactRef"])
                        say("bad:" + str(bad["error"]["code"]))
                        say("again:" + again["status"])
                    except Exception as failure:
                        say("mcp-failure:" + repr(failure))
                    result = {"stopReason": "end_turn"}
                else:
                    write({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "no method"}})
                    continue
                write({"jsonrpc": "2.0", "id": mid, "result": result})
            """.trimIndent(),
        )
        return script
    }

    /**
     * A real agent that sends one thought update, then AWaits the gateway's answer to a
     * tools/call inside the same prompt turn and reports the status it read. The chunk it says
     * after the awaited call is the deadlock evidence: the turn only ends if the engine served
     * the call while its own reaction body was still parked.
     */
    private fun toolAfterThoughtAgent(): Path {
        val script = Files.createTempFile("acp-mcp-thinking-agent-", ".py")
        script.toFile().deleteOnExit()
        Files.writeString(
            script,
            """
            import sys, json, urllib.request

            def write(message):
                sys.stdout.write(json.dumps(message) + "\n")
                sys.stdout.flush()

            def update(payload):
                write({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": "thinking-session", "update": payload,
                }})

            def rpc(url, mid, method, params):
                body = json.dumps({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}).encode()
                request = urllib.request.Request(url, data=body, headers={
                    "Content-Type": "application/json",
                    "Accept": "application/json, text/event-stream",
                })
                with urllib.request.urlopen(request, timeout=20) as response:
                    return json.loads(response.read().decode())

            urls = []

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                msg = json.loads(line)
                method = msg.get("method")
                mid = msg.get("id")
                if mid is None:
                    continue
                if method == "initialize":
                    result = {
                        "protocolVersion": 1,
                        "agentCapabilities": {
                            "loadSession": False,
                            "mcpCapabilities": {"http": True, "sse": False},
                            "promptCapabilities": {"audio": False, "image": False, "embeddedContext": False},
                        },
                        "authMethods": [],
                        "agentInfo": {"name": "acp-mcp-thinking-fixture", "version": "0.1.0"},
                    }
                elif method == "session/new":
                    servers = msg.get("params", {}).get("mcpServers", [])
                    urls = [server["url"] for server in servers if server.get("url")]
                    result = {"sessionId": "thinking-session"}
                elif method == "session/prompt":
                    update({"sessionUpdate": "agent_thought_chunk",
                            "content": {"type": "text", "text": "about to call the step tool"}})
                    try:
                        gateway = urls[0]
                        rpc(gateway, 1, "initialize", {"protocolVersion": "2025-03-26",
                                                       "capabilities": {},
                                                       "clientInfo": {"name": "fixture-agent", "version": "0"}})
                        message = rpc(gateway, 2, "tools/call", {"name": "step", "arguments": {}})
                        status = json.loads(message["result"]["content"][0]["text"])["status"]
                        update({"sessionUpdate": "agent_message_chunk",
                                "content": {"type": "text", "text": "resumed:" + status}})
                    except Exception as failure:
                        update({"sessionUpdate": "agent_message_chunk",
                                "content": {"type": "text", "text": "mcp-failure:" + repr(failure)}})
                    result = {"stopReason": "end_turn"}
                else:
                    write({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "no method"}})
                    continue
                write({"jsonrpc": "2.0", "id": mid, "result": result})
            """.trimIndent(),
        )
        return script
    }
}
