package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import kotlinx.serialization.Serializable

/**
 * The DECLARED half of PKH-139: which engine slots a session's MCP gateway may offer, as
 * data the journal can carry.
 *
 * The shape mirrors [compileAcpReactionPlan] on purpose. A tool the plan cannot compile to a
 * static slot address is refused BEFORE the agent launches — a gateway offering an unroutable
 * name would answer `tools/list` with a promise the engine cannot keep, and the caller could
 * only discover the gap mid-turn. Nothing here executes anything: the policy bounds are counts
 * over one scope, and the schema is a closed vocabulary the validator and the compiler both
 * read. An argument type outside that vocabulary is a refusal, never a guess, because a schema
 * the plugin cannot validate is a schema it cannot promise.
 */

/** The closed vocabulary of argument types one exposed tool may declare. */
val ACP_TOOL_ARGUMENT_TYPES: Set<String> = setOf("string", "integer", "number", "boolean")

/**
 * One argument of an exposed tool's input schema: [name], its closed [type] vocabulary token,
 * and whether the caller must supply it. The description exists for the agent's reader — the
 * gateway answers `tools/list` with it verbatim.
 */
@Serializable
data class AcpToolArgument(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val description: String = "",
)

/**
 * The declared input schema of an exposed tool: an OBJECT whose properties are [properties].
 * This is deliberately not full JSON Schema: a validator cannot promise what it does not
 * implement, and the plugin promises exactly these closed types — anything richer is refused
 * at compile time rather than silently dropped from the validation.
 */
@Serializable
data class AcpToolSchema(
    val properties: List<AcpToolArgument> = emptyList(),
)

/**
 * The policy one exposed tool declares: [maxConcurrent] calls in flight at once and
 * [maxCalls] dispatches for the whole scope. These are the AC#3 limits — a gateway without
 * them would let one agent flood the engine through the session — and they are BOUNDED by
 * [AcpCompiledTool.MAX_CALL_LIMIT]: an uncapped tool is a scheduler the plan never declared.
 */
@Serializable
data class AcpToolPolicy(
    val maxConcurrent: Int = 1,
    val maxCalls: Int = 16,
)

/**
 * One `exposeStep(id, inputSchema, policy)`: the slot the session's MCP gateway may offer.
 *
 * [id] IS the slot address, exactly as a reaction's: the name the plan gives a child of the
 * step's body, so the engine resolves it the way it resolves any child. [effectful] marks a
 * tool whose dispatch performs an external effect — under the ledger, that is the class an
 * idempotency key may speak for, and a repeated call of an unknown effect is never re-driven.
 */
@Serializable
data class AcpExposedToolDeclaration(
    val id: String,
    val description: String,
    val inputSchema: AcpToolSchema = AcpToolSchema(),
    val policy: AcpToolPolicy = AcpToolPolicy(),
    val effectful: Boolean = false,
)

/** Declare one exposed tool: the gateway offers it under [id], dispatched as the body child [id]. */
fun acpExpose(
    id: String,
    description: String,
    inputSchema: AcpToolSchema = AcpToolSchema(),
    policy: AcpToolPolicy = AcpToolPolicy(),
    effectful: Boolean = false,
): AcpExposedToolDeclaration = AcpExposedToolDeclaration(id, description, inputSchema, policy, effectful)

/** Declare one argument of an exposed tool's input schema. */
fun acpToolArgument(
    name: String,
    type: String,
    required: Boolean = false,
    description: String = "",
): AcpToolArgument = AcpToolArgument(name, type, required, description)

/** One declared tool, resolved to the static slot address the gateway will dispatch. */
class AcpCompiledTool(
    val id: String,
    val slot: PluginStepId,
    val description: String,
    val schema: AcpToolSchema,
    val policy: AcpToolPolicy,
    val effectful: Boolean,
) {

    /** The segment key every invocation of this tool's attempt identity carries. */
    val attemptKey: PluginStepId = PluginStepId("${ATTEMPT_KEY_BASE.value}.$id")

    companion object {
        /** The attempt-key prefix tool dispatches derive from, kept apart from reaction keys. */
        val ATTEMPT_KEY_BASE: PluginStepId = PluginStepId("acp-tool")

        /** Beyond this, a per-tool cap is a fiction the plan cannot hold; refuse instead. */
        const val MAX_CALL_LIMIT: Int = 1_024

        /** More simultaneous dispatches than this is a pool, not a bound the gateway enforces. */
        const val MAX_CONCURRENT_LIMIT: Int = 64
    }
}

/** The verdict of compiling the exposed-tool declarations: offered, or refused with every reason. */
sealed class AcpToolPlanCompilation {

    /** The tools are routable; [tools] in declaration order. Empty means "no gateway opens". */
    data class Compiled(val tools: List<AcpCompiledTool>) : AcpToolPlanCompilation()

    /** The plan cannot be offered; [reasons] names every problem, not just the first. */
    data class Rejected(val reasons: List<String>) : AcpToolPlanCompilation()
}

/**
 * Compile the exposed-tool declarations into static slot addresses.
 *
 * [takenSlotIds] are the addresses the reaction half of the same body already claims: one name
 * addressing a reaction AND a tool is the routing ambiguity the reaction compiler refuses for
 * two reactions, and the gateway cannot resolve it any better than the seam can. An EMPTY tool
 * list compiles to an empty compiled list — no gateway opens and the session keeps exactly the
 * shipped shape. Unknown schema types, blank or duplicated argument names, and out-of-bound
 * policy numbers are refusals that name the declaration, never silent clamps.
 */
fun compileAcpToolPlan(
    tools: List<AcpExposedToolDeclaration>,
    takenSlotIds: Set<String> = emptySet(),
): AcpToolPlanCompilation {
    val reasons = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    val compiled = tools.mapIndexedNotNull { index, declaration ->
        if (declaration.id.isBlank()) {
            reasons += "tool[$index]: an exposed tool with a blank id addresses no child of the body"
            return@mapIndexedNotNull null
        }
        if (!seen.add(declaration.id)) {
            reasons += "tool '${declaration.id}': two exposed tools claim the same slot address"
        }
        if (declaration.id in takenSlotIds) {
            reasons += "tool '${declaration.id}': the same slot address is already claimed by a reaction; " +
                "the seam resolves one name to one child"
        }
        reasons += schemaRefusals(declaration)
        reasons += policyRefusals(declaration)
        AcpCompiledTool(
            id = declaration.id,
            slot = PluginStepId(declaration.id),
            description = declaration.description,
            schema = declaration.inputSchema,
            policy = declaration.policy,
            effectful = declaration.effectful,
        )
    }
    return if (reasons.isEmpty()) {
        AcpToolPlanCompilation.Compiled(compiled)
    } else {
        AcpToolPlanCompilation.Rejected(reasons)
    }
}

/** The schema is a promise the validator must keep: closed types, named unique arguments. */
private fun schemaRefusals(declaration: AcpExposedToolDeclaration): List<String> {
    val reasons = mutableListOf<String>()
    val names = mutableSetOf<String>()
    for (argument in declaration.inputSchema.properties) {
        when {
            argument.name.isBlank() ->
                reasons += "tool '${declaration.id}': an argument with a blank name cannot be validated or advertised"
            !names.add(argument.name) ->
                reasons += "tool '${declaration.id}': argument '${argument.name}' is declared twice; " +
                    "one name validates one value"
        }
        if (argument.type !in ACP_TOOL_ARGUMENT_TYPES) {
            reasons += "tool '${declaration.id}': argument '${argument.name}' declares type " +
                "'${argument.type}'; the gateway speaks " + ACP_TOOL_ARGUMENT_TYPES.sorted().joinToString("/")
        }
    }
    return reasons
}

/** A limit that is not a positive bounded number is no limit, and no limit is not offered. */
private fun policyRefusals(declaration: AcpExposedToolDeclaration): List<String> {
    val policy = declaration.policy
    val reasons = mutableListOf<String>()
    when {
        policy.maxConcurrent <= 0 ->
            reasons += "tool '${declaration.id}': maxConcurrent ${policy.maxConcurrent} admits no call — " +
                "the tool would be advertised and never dispatchable"
        policy.maxConcurrent > AcpCompiledTool.MAX_CONCURRENT_LIMIT ->
            reasons += "tool '${declaration.id}': maxConcurrent ${policy.maxConcurrent} exceeds the cap " +
                "${AcpCompiledTool.MAX_CONCURRENT_LIMIT}"
    }
    when {
        policy.maxCalls <= 0 ->
            reasons += "tool '${declaration.id}': maxCalls ${policy.maxCalls} is a dead advertisement"
        policy.maxCalls > AcpCompiledTool.MAX_CALL_LIMIT ->
            reasons += "tool '${declaration.id}': maxCalls ${policy.maxCalls} exceeds the cap " +
                "${AcpCompiledTool.MAX_CALL_LIMIT}"
    }
    return reasons
}
