package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-139 AC#1/AC#2/AC#3 measured at the gateway itself: what it lists, what it dispatches,
 * what it refuses, and what survives its close. The slot under test is the published
 * [BodySlotInvoker] seam (a recording fake stands in for the engine's dispatcher — the rows
 * claim nothing about the engine's own journaling, only that the gateway asks it correctly),
 * and the ledger is the real PKH-138 one with an injected journal.
 */
class AcpMcpGatewayTest {

    private class RecordingSlot : BodySlotInvoker {
        @Volatile
        var outcome: BodyOutcome = BodyOutcome.Completed(StepOutcome.Success)
        val invocations = mutableListOf<Pair<PluginStepId, BodyInvocationContext>>()
        private val lock = Any()

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            synchronized(lock) { invocations += slot to context }
            return outcome
        }
    }

    private class HangingSlot(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : BodySlotInvoker {
        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private fun gateway(
        tools: List<AcpCompiledTool>,
        slot: BodySlotInvoker,
        ledger: AcpReactionLedger? = null,
    ): AcpMcpGateway = AcpMcpGateway.start(
        tools = tools,
        slotInvoker = slot,
        ledger = ledger,
        runId = "run-gateway",
        operationId = "op-3",
    ) ?: error("a non-empty tool plan must open a gateway")

    private fun runTestsTool(
        maxConcurrent: Int = 1,
        maxCalls: Int = 16,
    ): AcpCompiledTool = compileAcpToolPlan(
        listOf(
            acpExpose(
                "runTests",
                "runs the suite",
                AcpToolSchema(listOf(acpToolArgument("filter", "string", required = true), acpToolArgument("repeat", "integer"))),
                AcpToolPolicy(maxConcurrent, maxCalls),
            ),
        ),
    ).let { (it as AcpToolPlanCompilation.Compiled).tools.single() }

    private fun compiledTools(vararg ids: String): List<AcpCompiledTool> =
        compileAcpToolPlan(ids.map { acpExpose(it, "step $it") })
            .let { (it as AcpToolPlanCompilation.Compiled).tools }

    private fun post(gateway: AcpMcpGateway, body: String, pathToken: String = gateway.token): HttpResponse<String> {
        val url = "http://127.0.0.1:${URI.create(gateway.url).port}/mcp/$pathToken"
        return client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun callBody(name: String, arguments: String, meta: String = "", id: Int = 1): String {
        val metaPart = if (meta.isEmpty()) "" else ",\"_meta\":$meta"
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call",""" +
            """"params":{"name":"$name","arguments":$arguments$metaPart}}"""
    }

    private fun parsed(response: HttpResponse<String>): JsonObject =
        json.parseToJsonElement(response.body()).jsonObject

    private fun resultPayload(response: JsonObject): JsonObject =
        json.parseToJsonElement(
            response["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject

    @Test
    fun `tools list returns exactly the declared steps with their typed schema`() {
        val gateway = gateway(listOf(runTestsTool()) + compiledTools("lint"), RecordingSlot())
        try {
            val listed = parsed(post(gateway, """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""))
                .getValue("result").jsonObject.getValue("tools").jsonArray

            assertEquals(
                listOf("lint", "runTests"),
                listed.map { it.jsonObject.getValue("name").jsonPrimitive.content }.sorted(),
                "the agent finds the steps the plan exposed — and nothing else",
            )
            val runTests = listed.first { it.jsonObject.getValue("name").jsonPrimitive.content == "runTests" }
                .jsonObject
            assertEquals("runs the suite", runTests.getValue("description").jsonPrimitive.content)
            val schema = runTests.getValue("inputSchema").jsonObject
            assertEquals("object", schema.getValue("type").jsonPrimitive.content)
            assertEquals(
                "string",
                schema.getValue("properties").jsonObject.getValue("filter").jsonObject.getValue("type").jsonPrimitive.content,
                "the advertised type is the declared one — the validator speaks the same vocabulary",
            )
            assertEquals(
                listOf("filter"),
                schema.getValue("required").jsonArray.map { it.jsonPrimitive.content },
                "only the required arguments are required",
            )
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `a tool call dispatches its predeclared slot with its own attempt identity and answers typed`() {
        val slot = RecordingSlot()
        val ledger = AcpReactionLedger()
        val gateway = gateway(listOf(runTestsTool()), slot, ledger)
        try {
            val payload = resultPayload(parsed(post(gateway, callBody("runTests", """{"filter":"acp","repeat":2}"""))))

            assertEquals("executed", payload["status"]!!.jsonPrimitive.content)
            assertEquals("acp-tool.runTests", payload["attemptKey"]!!.jsonPrimitive.content)
            assertEquals(1, slot.invocations.size, "one call, one slot invocation")
            assertEquals(PluginStepId("runTests"), slot.invocations.single().first)
            assertEquals(1, slot.invocations.single().second.attempt?.index)
            val ref = payload["artifactRef"]!!.jsonPrimitive.content
            assertTrue(
                "run/run-gateway" in ref && "op/op-3" in ref && "/attempt/1" in ref,
                "the reference is the durable path of THIS scope: $ref",
            )
            assertTrue(
                "/event/conn:${gateway.token}:1" in ref,
                "the event half of the reference is the gateway-persisted key: $ref",
            )
            assertEquals(2, ledger.rows().size, "intent and ack rode the ledger: ${ledger.rows()}")
            assertEquals(
                AcpEffectPhase.ACKED,
                ledger.rows().last().phase,
                "a completed dispatch is acknowledged",
            )
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `arguments outside the declared schema never reach the slot`() {
        val slot = RecordingSlot()
        val gateway = gateway(listOf(runTestsTool()), slot)
        try {
            val missing = parsed(post(gateway, callBody("runTests", """{"repeat":1}""")))
            val unknown = parsed(post(gateway, callBody("runTests", """{"filter":"x","surprise":"y"}""", id = 2)))
            val wrongType = parsed(post(gateway, callBody("runTests", """{"filter":42}""", id = 3)))

            listOf(missing, unknown, wrongType).forEach { response ->
                assertEquals(
                    -32602,
                    response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
                    "a schema violation is a typed denial, not a dispatched call: $response",
                )
            }
            assertTrue(
                "required" in missing["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
                "the refusal names what is missing",
            )
            assertEquals(0, slot.invocations.size, "a denied argument is not an engine request")
            assertEquals(
                List(3) { AcpToolCallStatus.SCHEMA_DENIED },
                gateway.callRecords().map { it.status },
                "every denial is recorded, never silent",
            )
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `an unknown tool is denied and the denial is recorded`() {
        val slot = RecordingSlot()
        val gateway = gateway(listOf(runTestsTool()), slot)
        try {
            val response = parsed(post(gateway, callBody("dropDatabase", "{}")))

            assertEquals(-32602, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertTrue("unknown tool" in response["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
            assertEquals(0, slot.invocations.size, "an unknown name dispatches nothing")
            assertEquals(AcpToolCallStatus.UNKNOWN_TOOL_DENIED, gateway.callRecords().single().status)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `a request carrying another session's token is refused before its body is read`() {
        val slot = RecordingSlot()
        val own = gateway(listOf(runTestsTool()), slot)
        val other = gateway(listOf(runTestsTool()), slot)
        try {
            val stolenList = post(other, callBody("runTests", """{"filter":"x"}"""), pathToken = own.token)
            val ownCall = parsed(post(own, callBody("runTests", """{"filter":"x"}""")))

            assertEquals(403, stolenList.statusCode(), "another scope's token opens nothing: ${stolenList.body()}")
            assertEquals(
                "executed",
                resultPayload(ownCall)["status"]!!.jsonPrimitive.content,
                "the same call under its OWN token still works — the gate is the binding, not a blockage",
            )
            assertEquals(1, slot.invocations.size, "the foreign request never reached the engine")
        } finally {
            own.close()
            other.close()
        }
    }

    @Test
    fun `a repeated call with the same idempotency key executes the slot once`() {
        val slot = RecordingSlot()
        val ledger = AcpReactionLedger()
        val gateway = gateway(listOf(runTestsTool()), slot, ledger)
        val meta = """{"${AcpMcpGateway.IDEMPOTENCY_META_KEY}":"deploy-42"}"""
        try {
            val first = resultPayload(parsed(post(gateway, callBody("runTests", """{"filter":"a"}""", meta))))
            val secondResponse = parsed(post(gateway, callBody("runTests", """{"filter":"a"}""", meta, id = 2)))
            val second = resultPayload(secondResponse)

            assertEquals("executed", first["status"]!!.jsonPrimitive.content)
            assertEquals(
                "already_acknowledged",
                second["status"]!!.jsonPrimitive.content,
                "the journal already paid for this effect: $second",
            )
            assertEquals(
                false,
                secondResponse["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean,
                "the repeat is a legitimate typed answer, not an error to guess at",
            )
            assertEquals(1, slot.invocations.size, "the repeat did NOT reach the engine")
            assertTrue(
                ledger.rows().any { it.eventKey is EventKey.External && it.eventKey.value == "deploy-42" },
                "the client's key is the durable identity, not a renumbered serial",
            )
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `a repeated call whose effect is unknown is refused and never re-driven`() {
        // The crash window: the ack write fails after the body ran, so the row lands UNKNOWN.
        // The honest answer to the repeat is a typed refusal — re-running would bet the run's
        // idempotency on a remote that never declared it (PKH-138's law, now guarding a tool).
        var writes = 0
        val ledger = AcpReactionLedger(persist = { row ->
            writes += 1
            if (row.phase == AcpEffectPhase.ACKED) error("simulated crash before the ack")
        })
        val slot = RecordingSlot()
        val gateway = gateway(listOf(runTestsTool()), slot, ledger)
        val meta = """{"${AcpMcpGateway.IDEMPOTENCY_META_KEY}":"half-dead"}"""
        try {
            val first = resultPayload(parsed(post(gateway, callBody("runTests", """{"filter":"a"}""", meta))))
            assertEquals("effect_unknown", first["status"]!!.jsonPrimitive.content, "the ack write failed: $first")

            val repeat = parsed(post(gateway, callBody("runTests", """{"filter":"a"}""", meta, id = 2)))
            assertEquals(-32000, repeat["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertTrue(
                "never re-driven" in repeat["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
                "the refusal says why: $repeat",
            )
            assertEquals(1, slot.invocations.size, "the unknown effect was not run a second time")
            assertEquals(2, writes, "intent and the failed ack only — the refusal appended nothing")
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `the concurrency limit refuses a simultaneous call instead of queueing it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = gateway(listOf(runTestsTool(maxConcurrent = 1)), HangingSlot(entered, release))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit(
                java.util.concurrent.Callable { post(gateway, callBody("runTests", """{"filter":"a"}""")) },
            )
            assertTrue(entered.await(15, TimeUnit.SECONDS), "the first call entered the slot")

            val second = parsed(post(gateway, callBody("runTests", """{"filter":"b"}""", id = 2)))
            assertEquals(-32000, second["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertTrue(
                "simultaneous" in second["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
                "refused, not queued: $second",
            )

            release.countDown()
            val answered = first.get(30, TimeUnit.SECONDS)
            assertEquals(
                "executed",
                resultPayload(parsed(answered))["status"]!!.jsonPrimitive.content,
                "the in-flight call completes on its own; the limit only refused the SECOND",
            )
            assertTrue(
                gateway.callRecords().any { it.status == AcpToolCallStatus.CONCURRENCY_REFUSED },
                "the refusal is recorded: ${gateway.callRecords()}",
            )
        } finally {
            release.countDown()
            pool.shutdownNow()
            gateway.close()
        }
    }

    @Test
    fun `the call budget refuses beyond maxCalls`() {
        val slot = RecordingSlot()
        val gateway = gateway(listOf(runTestsTool(maxCalls = 2)), slot)
        try {
            post(gateway, callBody("runTests", """{"filter":"one"}"""))
            post(gateway, callBody("runTests", """{"filter":"two"}""", id = 2))
            val third = parsed(post(gateway, callBody("runTests", """{"filter":"three"}""", id = 3)))

            assertEquals(-32000, third["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertTrue("limited to 2 calls" in third["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
            assertEquals(2, slot.invocations.size, "the budget stopped dispatch, not just the answer")
            assertEquals(AcpToolCallStatus.CALL_LIMIT_REFUSED, gateway.callRecords().last().status)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `a failing body under the ledger answers the honest unknown, never success`() {
        val slot = RecordingSlot().apply {
            outcome = BodyOutcome.Completed(
                StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "the tests failed")),
            )
        }
        val ledger = AcpReactionLedger()
        val gateway = gateway(listOf(runTestsTool()), slot, ledger)
        try {
            val payload = resultPayload(parsed(post(gateway, callBody("runTests", """{"filter":"x"}"""))))

            // PKH-138's law applied to a tool: a body that says Failure ran, and whether its
            // external effect half-happened is not knowable from here — the call is NOT a
            // success, and the row stops where recovery will call it unknown.
            assertEquals("effect_unknown", payload["status"]!!.jsonPrimitive.content)
            assertEquals(
                AcpToolCallStatus.EFFECT_UNKNOWN,
                gateway.callRecords().single().status,
                "a body that says Failure under the ledger is an unknown effect, not an executed one",
            )
            assertEquals(
                listOf(AcpEffectPhase.UNKNOWN),
                ledger.rows().map { it.phase },
                "the failed effect stops at intent/unknown — recovery must not see a paid effect",
            )
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `a failing body without a ledger still answers failed, not executed`() {
        val slot = RecordingSlot().apply {
            outcome = BodyOutcome.Completed(
                StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "the tests failed")),
            )
        }
        val gateway = gateway(listOf(runTestsTool()), slot)
        try {
            val payload = resultPayload(parsed(post(gateway, callBody("runTests", """{"filter":"x"}"""))))

            assertEquals("failed", payload["status"]!!.jsonPrimitive.content)
            assertEquals(AcpToolCallStatus.FAILED, gateway.callRecords().single().status)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `closing expires the gateway with the scope`() {
        val gateway = gateway(listOf(runTestsTool()), RecordingSlot())
        val port = URI.create(gateway.url).port
        gateway.close()

        assertThrows(Exception::class.java) {
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp/${gateway.token}"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        // A second close is a no-op, so the turn's finally can run it from every unwind path.
        gateway.close()
    }

    @Test
    fun `a plan without tools opens no gateway at all`() {
        // AC#5's structural half: no declared step, no port, no surface to guess at.
        assertEquals(null, AcpMcpGateway.start(emptyList(), RecordingSlot(), null, "", ""))
    }
}
