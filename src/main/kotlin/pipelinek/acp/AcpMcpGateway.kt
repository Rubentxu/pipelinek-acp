package pipelinek.acp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The temporary session MCP gateway of PKH-139: a loopback HTTP server that speaks just enough
 * of the MCP streamable transport (`initialize`, `tools/list`, `tools/call`) to offer the
 * compiled tools of [compileAcpToolPlan] to the agent of ONE ACP session.
 *
 * ## A request to the engine, never arbitrary execution
 *
 * The gateway executes NOTHING of its own. A `tools/call` validates its arguments against the
 * declared schema and then dispatches the pre-declared body slot through the same
 * [BodySlotInvoker] seam the reaction runner uses, with its own [AttemptSegment] — so the
 * engine journals each call the way it journals any slot invocation. No shell, no command
 * strings: arguments are validated as data and never interpolated into anything.
 *
 * ## Bound to one scope, dead with it (AC#2)
 *
 * The gateway is minted with a random token that keys its ONLY route (`/mcp/<token>`);
 * [runId] and [operationId] label every durable reference it answers with. A request under any
 * other token — another session's gateway, a guessed path — is refused with 403 BEFORE its body
 * is read, and a call that arrives after [close] is refused too: the server is stopped with the
 * turn's scope, so nothing listens on the port once the exchange is gone. There is no second,
 * tokenless surface.
 *
 * ## The ledger is the idempotency authority (AC#3)
 *
 * When a [ledger] is supplied (PKH-138's rows), each dispatch is driven under the intent/ack
 * protocol with an [EventKey] derived from the client's idempotency key (trusted: it arrived
 * through THIS gateway's token gate) or from the gateway's own token and a request serial. A
 * repeated call whose effect the ledger already acknowledged answers with the recorded verdict
 * and dispatches nothing; one whose effect is UNKNOWN refuses re-execution — the never-re-drive
 * law applied to a new caller. Without a ledger the gateway still dispatches, but it promises
 * no dedupe: that is the honest difference, and it is said in the record.
 */
class AcpMcpGateway private constructor(
    private val server: HttpServer,
    /** The path secret binding every request to this gateway's scope. */
    val token: String,
    /** The URL the session's MCP descriptor carries. */
    val url: String,
    private val tools: List<AcpCompiledTool>,
    private val slotInvoker: BodySlotInvoker,
    private val ledger: AcpReactionLedger?,
    private val runId: String,
    private val operationId: String,
) {

    companion object {
        /** The MCP protocol version this gateway speaks. */
        const val MCP_PROTOCOL_VERSION: String = "2025-03-26"

        /** The `_meta` entry an MCP client that supports idempotency keys sends with a call. */
        const val IDEMPOTENCY_META_KEY: String = "pipelinek.acp/idempotencyKey"

        /** JSON-RPC: the request shape or a tool name the plan does not offer. */
        const val INVALID_PARAMS: Int = -32602

        /** JSON-RPC: a method the gateway does not speak. */
        const val METHOD_NOT_FOUND: Int = -32601

        /** JSON-RPC: a policy or ledger refusal — real, typed, and never a silent drop. */
        const val POLICY_REFUSED: Int = -32000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Open a gateway on the loopback interface, ephemeral port, for [tools]. An empty
         * [tools] list opens NOTHING and answers null: a gateway without a declared tool is
         * exactly the generic surface AC#5 refuses.
         */
        fun start(
            tools: List<AcpCompiledTool>,
            slotInvoker: BodySlotInvoker,
            ledger: AcpReactionLedger? = null,
            runId: String = "",
            operationId: String = "",
        ): AcpMcpGateway? {
            if (tools.isEmpty()) return null
            val token = java.util.UUID.randomUUID().toString()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val gateway = AcpMcpGateway(
                server = server,
                token = token,
                url = "http://127.0.0.1:${server.address.port}/mcp/$token",
                tools = tools,
                slotInvoker = slotInvoker,
                ledger = ledger,
                runId = runId,
                operationId = operationId,
            )
            server.createContext("/mcp/") { exchange -> gateway.handle(exchange) }
            server.start()
            return gateway
        }
    }

    private val closed = AtomicBoolean(false)
    private val executor: java.util.concurrent.ExecutorService

    init {
        // The DEFAULT executor serves one exchange at a time: a blocked slot would park every
        // other call, which is the mutual-blocking AC#4 refuses. One daemon thread per
        // concurrent call, bounded by each tool's own policy below — the thread never queues
        // work the plan did not allow.
        executor = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "acp-mcp-gateway-$token").apply { isDaemon = true }
        }
        server.executor = executor
    }
    private val serialLock = Any()
    private var requestSerial = 0L
    private val records: MutableList<AcpToolCallRecord> = Collections.synchronizedList(mutableListOf())
    private val attemptsLock = Any()
    private val attemptsByTool = mutableMapOf<String, Int>()
    private val callsByTool = tools.associate { it.id to AtomicInteger(0) }
    private val inFlightByTool = tools.associate { it.id to Semaphore(it.policy.maxConcurrent) }

    /** Every call the gateway answered, allowed or refused — the turn's evidence list. */
    fun callRecords(): List<AcpToolCallRecord> = records.toList()

    /**
     * End the scope: stop listening so no further call is served. The turn's finally calls
     * this so the gateway CANNOT outlive the session that was handed its URL — AC#2's expiry
     * law and AC#4's cleanup, measured by a connection that must fail.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdown()
    }

    /** The session's MCP descriptor for this gateway: an http server, token in the path. */
    fun descriptor(): AcpMcpInput =
        AcpMcpInput(name = "pipelinek-acp-steps", transport = "http", url = url)

    private fun handle(exchange: HttpExchange) {
        if (closed.get()) {
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
            return
        }
        // AC#2: the token gate runs before any body is read. Another session's token (or a
        // guessed path) is refused without the gateway ever naming its tools.
        if (exchange.requestURI.path != "/mcp/$token") {
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
            return
        }
        val request = try {
            json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            respondRaw(exchange, errorResponse(JsonNull, INVALID_PARAMS, "the request is not JSON-RPC"))
            return
        }
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: ""
        val id = request["id"] ?: run {
            // A notification: the transport defines only notifications/initialized here, and
            // it changes nothing the gateway must answer.
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }
        val params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap())
        when (method) {
            "initialize" -> respondRaw(exchange, okResponse(id, initializeResult()))
            "tools/list" -> respondRaw(exchange, okResponse(id, toolsListResult()))
            "tools/call" -> {
                val answer = handleToolCall(id, params)
                respondRaw(exchange, answer)
            }
            else -> respondRaw(
                exchange,
                errorResponse(id, METHOD_NOT_FOUND, "the gateway speaks initialize, tools/list and tools/call only"),
            )
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", MCP_PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        put("serverInfo", buildJsonObject {
            put("name", "pipelinek-acp-steps")
            put("version", AcpPluginDeclaration.declaredVersion())
        })
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.forEach { add(it.advertised()) }
            },
        )
    }

    private fun AcpCompiledTool.advertised(): JsonObject = buildJsonObject {
        put("name", id)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    schema.properties.forEach { argument ->
                        put(
                            argument.name,
                            buildJsonObject {
                                put("type", argument.type)
                                if (argument.description.isNotEmpty()) put("description", argument.description)
                            },
                        )
                    }
                },
            )
            put(
                "required",
                buildJsonArray {
                    schema.properties.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
                },
            )
        })
    }

    /** The whole of AC#1: validate as data, dispatch the slot, answer typed. */
    private fun handleToolCall(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val tool = tools.firstOrNull { it.id == name }
        if (tool == null) {
            record(AcpToolCallRecord(name, AcpToolCallStatus.UNKNOWN_TOOL_DENIED))
            return errorResponse(
                id,
                INVALID_PARAMS,
                "unknown tool '$name' — the gateway offers only the steps the plan exposed",
            )
        }
        // AC#2: arguments are validated against the DECLARED schema before anything is asked
        // of the engine; a value outside it never reaches a slot, and nothing interpolates them.
        val validation = validateArguments(tool, params["arguments"])
        if (validation != null) {
            record(AcpToolCallRecord(name, AcpToolCallStatus.SCHEMA_DENIED, detail = validation))
            return errorResponse(id, INVALID_PARAMS, validation)
        }
        val idempotency = (params["_meta"] as? JsonObject)
            ?.get(IDEMPOTENCY_META_KEY)?.jsonPrimitive?.contentOrNull.orEmpty()
        val key = mintEventKey(idempotency)
        if (key == null) {
            record(AcpToolCallRecord(name, AcpToolCallStatus.DENIED, detail = "no identity to journal the effect"))
            return errorResponse(id, POLICY_REFUSED, "the call has no identity to journal its effect")
        }
        val ledger = ledger
        if (ledger != null) {
            // AC#3's never-re-drive law: an acknowledged effect answers with what the journal
            // knows, and an unknown one refuses — neither dispatches the slot a second time.
            if (ledger.isUnknown(tool.id, key)) {
                record(
                    AcpToolCallRecord(
                        tool.id,
                        AcpToolCallStatus.UNKNOWN_EFFECT_REFUSED,
                        eventKeyCanonical = key.canonical,
                    ),
                )
                return errorResponse(
                    id,
                    POLICY_REFUSED,
                    "the journal holds an unacknowledged intent for '$name': the effect is never " +
                        "re-driven automatically",
                )
            }
            if (ledger.isAcknowledged(tool.id, key)) {
                record(
                    AcpToolCallRecord(
                        tool.id,
                        AcpToolCallStatus.ALREADY_ACKNOWLEDGED,
                        eventKeyCanonical = key.canonical,
                    ),
                )
                return okResponse(
                    id,
                    callResult(
                        buildJsonObject {
                            put("tool", tool.id)
                            put("status", AcpToolCallStatus.ALREADY_ACKNOWLEDGED)
                            put("artifactRef", artifactRefOf(tool, key, attemptIndex = 0))
                        },
                        isError = false,
                    ),
                )
            }
        }
        // AC#3's limits, enforced per tool over this scope: a flood through the session is
        // refused with a typed reason, never queued without bound.
        val budget = callsByTool.getValue(tool.id)
        if (budget.incrementAndGet() > tool.policy.maxCalls) {
            budget.decrementAndGet()
            record(AcpToolCallRecord(tool.id, AcpToolCallStatus.CALL_LIMIT_REFUSED))
            return errorResponse(
                id,
                POLICY_REFUSED,
                "tool '${tool.id}' is limited to ${tool.policy.maxCalls} calls per scope",
            )
        }
        val permit = inFlightByTool.getValue(tool.id)
        if (!permit.tryAcquire()) {
            budget.decrementAndGet()
            record(AcpToolCallRecord(tool.id, AcpToolCallStatus.CONCURRENCY_REFUSED))
            return errorResponse(
                id,
                POLICY_REFUSED,
                "tool '${tool.id}' allows ${tool.policy.maxConcurrent} simultaneous call(s); " +
                    "this one is refused, not queued",
            )
        }
        try {
            return dispatchUnderLedger(id, tool, key)
        } finally {
            permit.release()
        }
    }

    private fun dispatchUnderLedger(id: JsonElement, tool: AcpCompiledTool, key: EventKey): JsonObject {
        val attemptIndex = synchronized(attemptsLock) {
            val next = (attemptsByTool[tool.id] ?: 0) + 1
            attemptsByTool[tool.id] = next
            next
        }
        val attempt = AttemptSegment(index = attemptIndex, key = tool.attemptKey)
        var bodyOutcome: BodyOutcome? = null
        val verdict: AcpDriveVerdict? = try {
            ledger?.let {
                runBlocking {
                    it.drive(tool.id, key, cursorOf(key), attemptIndex) {
                        slotInvoker.invoke(tool.slot, BodyInvocationContext(attempt = attempt))
                            .also { outcome -> bodyOutcome = outcome }
                            .let { o -> (o as? BodyOutcome.Completed)?.outcome == StepOutcome.Success }
                    }
                }
            } ?: run { bodyOutcome = runBlocking { slotInvoker.invoke(tool.slot, BodyInvocationContext(attempt = attempt)) }; null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            verdictOfCrashedBody(e)
        }
        val status = when {
            verdict is AcpDriveVerdict.Executed -> AcpToolCallStatus.EXECUTED
            verdict is AcpDriveVerdict.AlreadyAcknowledged -> AcpToolCallStatus.ALREADY_ACKNOWLEDGED
            verdict is AcpDriveVerdict.EffectUnknown -> AcpToolCallStatus.EFFECT_UNKNOWN
            verdict is AcpDriveVerdict.IntentNotPersisted -> AcpToolCallStatus.INTENT_NOT_PERSISTED
            (bodyOutcome as? BodyOutcome.Completed)?.outcome == StepOutcome.Success -> AcpToolCallStatus.EXECUTED
            else -> AcpToolCallStatus.FAILED
        }
        val artifactRef = artifactRefOf(tool, key, attemptIndex)
        record(
            AcpToolCallRecord(
                tool = tool.id,
                status = status,
                attemptIndex = attemptIndex,
                eventKeyCanonical = key.canonical,
                artifactRef = artifactRef,
            ),
        )
        val failed = status == AcpToolCallStatus.EFFECT_UNKNOWN ||
            status == AcpToolCallStatus.INTENT_NOT_PERSISTED ||
            status == AcpToolCallStatus.FAILED
        return okResponse(
            id,
            callResult(
                buildJsonObject {
                    put("tool", tool.id)
                    put("status", status)
                    if (verdict != null) put("verdict", verdict.token())
                    put("attemptKey", attempt.key.value)
                    put("attemptIndex", attemptIndex)
                    put("artifactRef", artifactRef)
                },
                isError = failed,
            ),
        )
    }

    /** A body that threw after its intent is the honest unknown, never a silent retry. */
    private fun verdictOfCrashedBody(e: Exception): AcpDriveVerdict? =
        if (ledger == null) null else AcpDriveVerdict.EffectUnknown

    private fun mintEventKey(idempotency: String): EventKey? = synchronized(serialLock) {
        requestSerial += 1
        EventKey.mint(
            externalValue = idempotency,
            externalSource = token,
            externalTrustworthy = idempotency.isNotEmpty(),
            connectionNonce = token,
            sequence = requestSerial,
        )
    }

    private fun cursorOf(key: EventKey): Long = synchronized(serialLock) { requestSerial }

    /**
     * The typed artifact reference of one call: the same durable path vocabulary the PKH-138
     * identity uses (run, operation, session scope, slot, event), so the agent's result names
     * WHERE the engine journalled the effect instead of carrying a payload through the wire.
     */
    private fun artifactRefOf(tool: AcpCompiledTool, key: EventKey, attemptIndex: Int): String =
        AcpReactionIdentity.mint(
            runId = runId,
            operationId = operationId,
            sessionRef = token,
            reactionId = tool.id,
            eventKey = key,
        )?.let { "${it.canonicalPath}/attempt/$attemptIndex" } ?: ""

    /** The closed-vocabulary validation: object shape, declared names, declared types. */
    private fun validateArguments(tool: AcpCompiledTool, arguments: JsonElement?): String? {
        if (arguments == null || arguments == JsonNull) {
            return if (tool.schema.properties.any { it.required }) {
                "tool '${tool.id}' requires arguments; none were sent"
            } else {
                null
            }
        }
        val obj = arguments as? JsonObject
            ?: return "tool '${tool.id}' takes an object of arguments, got ${arguments.typeName()}"
        val declared = tool.schema.properties.associateBy { it.name }
        obj.keys.filter { it !in declared }.firstOrNull()?.let {
            return "tool '${tool.id}': argument '$it' is outside the declared schema"
        }
        for (argument in tool.schema.properties) {
            val value = obj[argument.name]
            if (value == null || value == JsonNull) {
                if (argument.required) {
                    return "tool '${tool.id}': required argument '${argument.name}' is missing"
                }
                continue
            }
            val primitive = value as? JsonPrimitive
                ?: return "tool '${tool.id}': argument '${argument.name}' must be a ${argument.type}"
            if (!primitive.conformsTo(argument.type)) {
                return "tool '${tool.id}': argument '${argument.name}' is not a ${argument.type}"
            }
        }
        return null
    }

    private fun JsonPrimitive.conformsTo(type: String): Boolean = when (type) {
        "string" -> isString
        "boolean" -> !isString && (content == "true" || content == "false")
        "integer" -> !isString && content.toLongOrNull() != null
        "number" -> !isString && content.toDoubleOrNull() != null
        else -> false
    }

    private fun JsonElement.typeName(): String = when (this) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> if (isString) "string" else content
        JsonNull -> "null"
    }

    private fun callResult(payload: JsonObject, isError: Boolean): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", payload.toString())
                    },
                )
            },
        )
        put("isError", isError)
    }

    private fun record(entry: AcpToolCallRecord) {
        records.add(entry)
    }

    private fun okResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun respondRaw(exchange: HttpExchange, response: JsonObject) {
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.use { it.responseBody.use { body -> body.write(bytes) } }
    }
}

/** What the gateway answered to one `tools/call`, in the turn's durable evidence list. */
object AcpToolCallStatus {
    const val EXECUTED = "executed"
    const val FAILED = "failed"
    const val UNKNOWN_TOOL_DENIED = "unknown_tool_denied"
    const val SCHEMA_DENIED = "schema_denied"
    const val CALL_LIMIT_REFUSED = "call_limit_refused"
    const val CONCURRENCY_REFUSED = "concurrency_refused"
    const val UNKNOWN_EFFECT_REFUSED = "unknown_effect_refused"
    const val ALREADY_ACKNOWLEDGED = "already_acknowledged"
    const val EFFECT_UNKNOWN = "effect_unknown"
    const val INTENT_NOT_PERSISTED = "intent_not_persisted"
    const val DENIED = "denied"
}

/**
 * One call the gateway answered, kept in [AcpReactiveOutput.exposedToolCalls]: the tool, the
 * typed status, the attempt the slot travelled under, and the durable identity and artifact
 * reference that tie it to a journal row. Argument VALUES never travel — the reference says
 * WHERE the engine recorded the call, not what it was sent.
 */
@Serializable
data class AcpToolCallRecord(
    val tool: String,
    val status: String,
    val attemptIndex: Int = 0,
    val eventKeyCanonical: String = "",
    val artifactRef: String = "",
    val detail: String = "",
)

private fun AcpDriveVerdict.token(): String = when (this) {
    is AcpDriveVerdict.Executed -> "Executed"
    is AcpDriveVerdict.IntentNotPersisted -> "IntentNotPersisted"
    is AcpDriveVerdict.EffectUnknown -> "EffectUnknown"
    is AcpDriveVerdict.AlreadyAcknowledged -> "AlreadyAcknowledged"
}
