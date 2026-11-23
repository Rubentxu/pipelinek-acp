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
 */
@Serializable
data class AcpReactionDeclaration(
    val id: String,
    val selector: AcpEventSelector,
    val once: Boolean = false,
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
fun acpOn(id: String, selector: AcpEventSelector, once: Boolean = false): AcpReactionDeclaration =
    AcpReactionDeclaration(id, selector, once)

/** Declare one await: the turn observes [condition] until [timeoutMillis] elapses. */
fun acpAwait(condition: AcpEventSelector, timeoutMillis: Long): AcpAwaitDeclaration =
    AcpAwaitDeclaration(condition, timeoutMillis)

/** One declared reaction, resolved to the static address the handler will dispatch. */
class AcpCompiledReaction(
    val id: String,
    val slot: PluginStepId,
    val selector: AcpEventSelector,
    val once: Boolean,
)

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
            reactions = listOf(AcpCompiledReaction(default.value, default, AcpEventSelector.AnyUpdate, once = false)),
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
        AcpCompiledReaction(
            id = declaration.id,
            slot = PluginStepId(declaration.id),
            selector = declaration.selector,
            once = declaration.once,
        )
    }
    reasons += awaits.mapIndexedNotNull { index, await -> awaitRefusal(index, await) }

    return if (reasons.isEmpty()) {
        AcpPlanCompilation.Compiled(compiled, awaits)
    } else {
        AcpPlanCompilation.Rejected(reasons)
    }
}

private fun awaitRefusal(index: Int, await: AcpAwaitDeclaration): String? =
    if (await.timeoutMillis <= 0) {
        "await[$index]: timeoutMillis must be positive, got ${await.timeoutMillis} — an await that cannot time out is an unbounded wait"
    } else {
        null
    }
