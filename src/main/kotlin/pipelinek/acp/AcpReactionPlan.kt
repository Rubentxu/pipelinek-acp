package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The DECLARED half of a reactive turn (PKH-137): which events trigger which engine slot.
 *
 * Everything here is serializable data, and that is the whole point. The plan is lowered into
 * the step's encoded input, the journal stores that encoding, and a recovered run reads the
 * SAME routing back. A selector that was a runtime lambda has no encoding — it would make the
 * durable input lie about what the turn will do. So the vocabulary is closed ([AnyUpdate],
 * [UpdateKind], [TextContains]) and matching is a fixed function of data, never a closure.
 *
 * [compileAcpReactionPlan] turns the declarations into STATIC slot addresses BEFORE the agent
 * is launched: a plan the engine could not route (a blank or duplicated address, an await
 * without budget) is refused here, where the refusal can still name every problem at once,
 * rather than at the wire where the turn is already running.
 */
@Serializable
sealed class AcpEventSelector {

    /**
     * Whether this selector fires for [update]. Total and pure: the runner may ask it twice
     * about the same event and get the same answer.
     */
    abstract fun matches(update: SessionUpdate): Boolean

    /** Every update the agent sends during the turn. */
    @Serializable
    @SerialName("AnyUpdate")
    data object AnyUpdate : AcpEventSelector() {
        override fun matches(update: SessionUpdate): Boolean = true
    }

    /**
     * Updates whose ACP `sessionUpdate` discriminator equals [kind] — including a custom type
     * the agent invented, which the SDK carries as an unknown update with its own token. The
     * comparison is against the WIRE token, so the selector stays honest about what the agent
     * actually said rather than what this SDK's model happens to know.
     */
    @Serializable
    @SerialName("UpdateKind")
    data class UpdateKind(val kind: String) : AcpEventSelector() {
        override fun matches(update: SessionUpdate): Boolean = acpUpdateKind(update) == kind
    }

    /**
     * Text chunks containing [fragment]: agent message chunks by default, agent THOUGHT chunks
     * when [inReasoning] is set. No other update carries text this selector claims to read.
     */
    @Serializable
    @SerialName("TextContains")
    data class TextContains(val fragment: String, val inReasoning: Boolean = false) :
        AcpEventSelector() {
        override fun matches(update: SessionUpdate): Boolean {
            val block = when (update) {
                is SessionUpdate.AgentMessageChunk -> update.content.takeIf { !inReasoning }
                is SessionUpdate.AgentThoughtChunk -> update.content.takeIf { inReasoning }
                else -> null
            }
            return (block as? ContentBlock.Text)?.text?.contains(fragment) == true
        }
    }
}

/** The discriminator every ACP `session/update` payload carries; the SDK's own field name. */
private const val SESSION_UPDATE_DISCRIMINATOR = "sessionUpdate"

private val updateWireJson = Json { encodeDefaults = false }

/**
 * The wire token of one update, read through the SDK's own serializer: known variants answer
 * their spec name, an unknown variant answers the type the agent used. Empty only when the
 * encoded object carries no discriminator at all.
 */
internal fun acpUpdateKind(update: SessionUpdate): String =
    (updateWireJson.encodeToJsonElement(SessionUpdate.serializer(), update) as? kotlinx.serialization.json.JsonObject)
        ?.get(SESSION_UPDATE_DISCRIMINATOR)?.jsonPrimitive?.contentOrNull ?: ""

/**
 * One `on(id, selector)`: the reaction bound to the body child the DSL named [id].
 *
 * [id] IS the slot address — the name the plan gives a child of the step's body, so the engine
 * resolves it the way it resolves any child, and two declarations claiming one address is a
 * routing decision the plan cannot make for the engine. [AcpEventSelector] decides WHEN the
 * slot runs; [once] narrows it further: the slot fires for the FIRST matching event only and
 * every later match is recorded as skipped, never dispatched again.
 *
 * PKH-138 adds the reconciliation vocabulary to the same declaration, still as pure data:
 * [operator] is a limiting law the gate enforces at dispatch ([AcpReactionOperator.Take],
 * [AcpReactionOperator.Distinct], [AcpReactionOperator.Debounce] — the last only for a
 * [coalescible] reaction), [effectful] marks a reaction whose dispatch performs an external
 * effect, the one class the reconciler suspends when history and live cannot be told apart.
 * A reaction that declares both [once] and an [operator] would be two budgets over one slot
 * answering to different clocks; the compiler refuses it rather than pick.
 */
@Serializable
data class AcpReactionDeclaration(
    val id: String,
    val selector: AcpEventSelector,
    val once: Boolean = false,
    /** limiting operator enforced at dispatch; null means "every match dispatches". */
    val operator: AcpReactionOperator? = null,
    /** the reaction's dispatch performs an external effect — the suspension target (AC#2). */
    val effectful: Boolean = false,
    /** its events are telemetry that may legally fold; required by Debounce (AC#4). */
    val coalescible: Boolean = false,
)

/**
 * One `await(condition, timeout)`: wait inside the session until an observed update matches
 * [condition], giving up after [timeoutMillis]. Await reads the SAME selector vocabulary as
 * `on`, so "react" and "wait for" cannot drift into two event languages. The timeout must be
 * positive: an await that cannot time out is an unbounded wait wearing a budget's name.
 */
@Serializable
data class AcpAwaitDeclaration(
    val condition: AcpEventSelector,
    val timeoutMillis: Long,
)

/** Declare one reaction: slot [id] runs when [selector] matches an update the agent sends. */
fun acpOn(
    id: String,
    selector: AcpEventSelector,
    once: Boolean = false,
    operator: AcpReactionOperator? = null,
    effectful: Boolean = false,
    coalescible: Boolean = false,
): AcpReactionDeclaration = AcpReactionDeclaration(id, selector, once, operator, effectful, coalescible)

/** Take at most [limit] matches of the reaction declaring it (AC#4). */
fun acpTake(limit: Int): AcpReactionOperator = AcpReactionOperator.Take(limit)

/** Dispatch only when the event token differs from the last dispatched one (AC#4). */
fun acpDistinct(): AcpReactionOperator = AcpReactionOperator.Distinct

/** Fold same-token matches within [windowMillis]; only legal on a coalescible reaction (AC#4). */
fun acpDebounce(windowMillis: Long): AcpReactionOperator = AcpReactionOperator.Debounce(windowMillis)

/** Declare one await: the turn observes [condition] until [timeoutMillis] elapses. */
fun acpAwait(condition: AcpEventSelector, timeoutMillis: Long): AcpAwaitDeclaration =
    AcpAwaitDeclaration(condition, timeoutMillis)

/**
 * One declared reaction, resolved to the static address the handler will dispatch.
 *
 * [isDefault] marks the unnamed reaction an empty plan compiles to — the slot `acpReactive`
 * has always shipped. It keeps the shipped attempt key so a recovered pre-PKH-137 run and a
 * post-PKH-137 run of the same declaration write the SAME deterministic path segment; named
 * reactions derive `acp-reaction.<id>` and cannot collide with it.
 */
class AcpCompiledReaction(
    val id: String,
    val slot: PluginStepId,
    val selector: AcpEventSelector,
    val once: Boolean,
    val isDefault: Boolean = false,
    /** the declared limiting operator, enforced per-event by the runner's gate (AC#4). */
    val operator: AcpReactionOperator? = null,
    /** dispatching this reaction performs an external effect — the reconciler's suspension flag. */
    val effectful: Boolean = false,
) {

    /** The segment key every invocation of this reaction's attempt identity carries. */
    val attemptKey: PluginStepId
        get() = if (isDefault) {
            AcpReactiveStepDefinition.ATTEMPT_KEY
        } else {
            PluginStepId("${AcpReactiveStepDefinition.ATTEMPT_KEY.value}.$id")
        }
}

/** The verdict of compiling a reaction plan: routed, or refused with every reason at once. */
sealed class AcpPlanCompilation {

    /** The plan is routable: [reactions] in declaration order, [awaits] as declared. */
    data class Compiled(
        val reactions: List<AcpCompiledReaction>,
        val awaits: List<AcpAwaitDeclaration>,
    ) : AcpPlanCompilation()

    /** The plan cannot be routed; [reasons] names every problem, not just the first. */
    data class Rejected(val reasons: List<String>) : AcpPlanCompilation()
}

/**
 * Compile the declared reactions and awaits into static slot addresses.
 *
 * An EMPTY reaction list compiles to the one slot `acpReactive` has always dispatched — the
 * unnamed child addressed by its kind — so a plan that says nothing keeps PKH-136's shipped
 * law verbatim: one slot, every update, one attempt identity per invocation.
 *
 * Validation is about ROUTABILITY, not taste: blank names cannot address a child, duplicate
 * names make the seam's resolution ambiguous (and the seam refuses to guess, so the plan must
 * too), an empty text fragment fires on every chunk without having DECIDED to, and an await
 * without positive budget is no await at all. Unknown `UpdateKind` tokens are deliberately
 * NOT refused: an agent's custom update type is a legitimate event, and the discriminator
 * comparison is what makes it matchable.
 */
fun compileAcpReactionPlan(
    reactions: List<AcpReactionDeclaration>,
    awaits: List<AcpAwaitDeclaration>,
): AcpPlanCompilation {
    val reasons = mutableListOf<String>()

    if (reactions.isEmpty()) {
        reasons += awaits.mapIndexedNotNull { index, await -> awaitRefusal(index, await) }
        if (reasons.isNotEmpty()) return AcpPlanCompilation.Rejected(reasons)
        val default = AcpReactiveStepDefinition.REACTION_SLOT
        return AcpPlanCompilation.Compiled(
            reactions = listOf(
                AcpCompiledReaction(
                    default.value,
                    default,
                    AcpEventSelector.AnyUpdate,
                    once = false,
                    isDefault = true,
                ),
            ),
            awaits = awaits,
        )
    }

    val seen = mutableSetOf<String>()
    val compiled = reactions.mapIndexedNotNull { index, declaration ->
        if (declaration.id.isBlank()) {
            // A blank name addresses no child, and no slot can even be minted from it: the
            // reason is collected and this declaration leaves the compiled list.
            reasons += "reaction[$index]: a slot id that is blank addresses no child of the body"
            return@mapIndexedNotNull null
        }
        if (!seen.add(declaration.id)) {
            reasons += "reaction '${declaration.id}': two reactions claim the same slot address; the seam resolves one name to one child"
        }
        when (val selector = declaration.selector) {
            is AcpEventSelector.TextContains -> if (selector.fragment.isEmpty()) {
                reasons += "reaction '${declaration.id}': an empty fragment matches every text chunk — say what to look for, or use AnyUpdate if the intent is 'any update'"
            }
            is AcpEventSelector.UpdateKind -> if (selector.kind.isEmpty()) {
                reasons += "reaction '${declaration.id}': an empty kind matches no update the agent can send"
            }
            AcpEventSelector.AnyUpdate -> Unit
        }
        reasons += operatorRefusals(declaration)
        AcpCompiledReaction(
            id = declaration.id,
            slot = PluginStepId(declaration.id),
            selector = declaration.selector,
            once = declaration.once,
            operator = declaration.operator,
            effectful = declaration.effectful,
        )
    }
    reasons += awaits.mapIndexedNotNull { index, await -> awaitRefusal(index, await) }

    return if (reasons.isEmpty()) {
        AcpPlanCompilation.Compiled(compiled, awaits)
    } else {
        AcpPlanCompilation.Rejected(reasons)
    }
}

/**
 * The AC#4 limits, checked as data BEFORE the agent launches: an operator that cannot be
 * bounded at compile time is an unbounded buffer with a declarative name. `once` plus an
 * operator would be two budgets over one slot, so the compiler refuses the pair instead of
 * choosing; a Debounce outside the declared window bound, a Take outside its cap, and any
 * Debounce on a reaction that never said its events are coalescible are all refusals naming the
 * declaration, never a silent clamp.
 */
private fun operatorRefusals(declaration: AcpReactionDeclaration): List<String> {
    val operator = declaration.operator ?: return emptyList()
    val reasons = mutableListOf<String>()
    if (declaration.once) {
        reasons += "reaction '${declaration.id}': declares both once and an operator — " +
            "one slot gets one budget; drop 'once' or the operator"
    }
    when (operator) {
        is AcpReactionOperator.Take -> when {
            operator.limit <= 0 ->
                reasons += "reaction '${declaration.id}': take(${operator.limit}) dispatches nothing — " +
                    "a limit must be positive, or the reaction is dead code"
            operator.limit > AcpReactionOperator.MAX_TAKE_LIMIT ->
                reasons += "reaction '${declaration.id}': take(${operator.limit}) exceeds the cap " +
                    "${AcpReactionOperator.MAX_TAKE_LIMIT} — past it, say AnyUpdate and bound the queue"
        }
        is AcpReactionOperator.Debounce -> {
            if (operator.windowMillis <= 0) {
                reasons += "reaction '${declaration.id}': debounce(${operator.windowMillis}ms) has no " +
                    "window — a non-positive window folds nothing"
            } else if (operator.windowMillis > AcpReactionOperator.MAX_DEBOUNCE_WINDOW_MILLIS) {
                reasons += "reaction '${declaration.id}': debounce window ${operator.windowMillis}ms " +
                    "exceeds ${AcpReactionOperator.MAX_DEBOUNCE_WINDOW_MILLIS}ms — beyond that this is " +
                    "parking, not coalescing"
            }
            if (!declaration.coalescible) {
                reasons += "reaction '${declaration.id}': debounce is declared on a non-coalescible " +
                    "reaction — only telemetry may fold; say coalescible = true if its events lose " +
                    "nothing when one stands for many"
            }
        }
        is AcpReactionOperator.Distinct -> Unit
    }
    return reasons
}

private fun awaitRefusal(index: Int, await: AcpAwaitDeclaration): String? =
    if (await.timeoutMillis <= 0) {
        "await[$index]: timeoutMillis must be positive, got ${await.timeoutMillis} — an await that cannot time out is an unbounded wait"
    } else {
        null
    }
