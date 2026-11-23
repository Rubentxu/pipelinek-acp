@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionUpdate

/**
 * The decoder: one ACP session update into one normalized observation.
 *
 * ## The redaction rule is a whitelist, field by field
 *
 * The SDK's update objects are typed, but typed does not mean safe: `ToolCall` carries the
 * tool's raw input and output as JSON, `UnknownSessionUpdate` carries the raw JSON of a
 * variant this plugin does not know, and every update carries a `_meta` envelope. Reading a
 * "typed" field is how a raw payload sneaks into a durable log. So this decoder reads a
 * NAMED set of fields per variant and nothing else: the matrix's content rows say which
 * fields of a content block may be read, and the variants' rows name the fields this file
 * takes.
 *
 * What never enters a fact: a tool call's raw input or output, the raw JSON of an unknown
 * variant, the `_meta` envelope of anything, and the BYTES of an image or an audio block.
 *
 * ## The unknown variant
 *
 * A variant this plugin does not know becomes the explicit `unknown_update` fact, carrying
 * the DISCRIMINATOR and nothing else. It is a fact about the protocol drift, and the raw JSON
 * it came with is exactly the place where anything could hide.
 */
internal object AcpObservationDecoder {

    /** The most characters of text one fact may carry. */
    const val MAX_TEXT_LENGTH: Int = 4096

    /**
     * The normalized observation of [update], or the explicit unknown fact when the update is
     * a variant this plugin does not know.
     *
     * The exchange identity travels with the fact because a run may hold several exchanges,
     * and a reader that cannot tell them apart cannot tell whose tool call completed.
     */
    fun decode(update: SessionUpdate, exchangeRef: String): AcpObservation = when (update) {
        is SessionUpdate.AgentMessageChunk -> {
            val block = readBlock(update.content)
            AcpObservation.AgentMessageChunk(
                exchangeRef = exchangeRef,
                evidence = AcpEvidence.AGENT_CLAIM,
                text = block.text,
                messageId = update.messageId?.value ?: "",
                content = block.summary,
                truncated = block.truncated,
            )
        }

        is SessionUpdate.AgentThoughtChunk -> {
            val block = readBlock(update.content)
            AcpObservation.AgentThoughtChunk(
                exchangeRef = exchangeRef,
                evidence = AcpEvidence.AGENT_CLAIM,
                text = block.text,
                messageId = update.messageId?.value ?: "",
                content = block.summary,
                truncated = block.truncated,
            )
        }

        is SessionUpdate.UserMessageChunk -> {
            val block = readBlock(update.content)
            AcpObservation.UserMessageChunk(
                exchangeRef = exchangeRef,
                evidence = AcpEvidence.AGENT_CLAIM,
                text = block.text,
                messageId = update.messageId?.value ?: "",
                content = block.summary,
                truncated = block.truncated,
            )
        }

        is SessionUpdate.ToolCall -> AcpObservation.ToolCall(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            toolCallId = update.toolCallId.value,
            title = update.title,
            status = update.status?.name ?: "",
            toolKind = update.kind?.name ?: "",
        )

        is SessionUpdate.ToolCallUpdate -> AcpObservation.ToolCallChanged(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            toolCallId = update.toolCallId.value,
            // An empty field means "this update did not carry it": the reducer keeps the value
            // the earlier update gave it, so a partial update never erases what was known.
            title = update.title ?: "",
            status = update.status?.name ?: "",
            toolKind = update.kind?.name ?: "",
        )

        is SessionUpdate.PlanUpdate -> AcpObservation.Plan(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            entries = update.entries.map { entry ->
                AcpPlanEntry(
                    content = entry.content,
                    priority = entry.priority.name,
                    status = entry.status.name,
                )
            },
        )

        is SessionUpdate.PlanUpdateV2 -> AcpObservation.Plan(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            entries = entriesOf(update.plan),
        )

        is SessionUpdate.PlanRemoved -> AcpObservation.PlanRemoved(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            planId = update.id,
        )

        is SessionUpdate.UsageUpdate -> AcpObservation.Usage(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            used = update.used,
            size = update.size,
            cost = update.cost?.let { "${it.amount} ${it.currency}" } ?: "",
        )

        is SessionUpdate.CurrentModeUpdate -> AcpObservation.ModeChanged(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            modeId = update.currentModeId.value,
        )

        is SessionUpdate.AvailableCommandsUpdate -> AcpObservation.CommandsChanged(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            commands = update.availableCommands.map {
                AcpCommand(name = it.name, description = it.description)
            },
        )

        is SessionUpdate.ConfigOptionUpdate -> AcpObservation.ConfigurationChanged(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            options = update.configOptions.map { option ->
                AcpConfigOption(
                    id = option.id.value,
                    name = option.name,
                    currentValue = currentValueOf(option),
                )
            },
        )

        is SessionUpdate.SessionInfoUpdate -> AcpObservation.SessionInfoChanged(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.AGENT_CLAIM,
            title = update.title ?: "",
            updatedAt = update.updatedAt ?: "",
        )

        is SessionUpdate.UnknownSessionUpdate -> AcpObservation.UnknownUpdate(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.OBSERVED,
            // The discriminator and NOTHING else: the raw JSON of an unknown variant is the
            // one place where an agent could hide anything at all.
            discriminator = update.sessionUpdateType,
        )

        else -> AcpObservation.UnknownUpdate(
            exchangeRef = exchangeRef,
            evidence = AcpEvidence.OBSERVED,
            discriminator = update::class.simpleName ?: "unknown",
        )
    }

    /** The plan entries a v2 plan carries, whichever shape it has. */
    private fun entriesOf(plan: PlanVariant): List<AcpPlanEntry> = when (plan) {
        is PlanVariant.Items -> plan.entries.map { entry ->
            AcpPlanEntry(
                content = entry.content,
                priority = entry.priority.name,
                status = entry.status.name,
            )
        }
        is PlanVariant.Markdown -> listOf(AcpPlanEntry(content = plan.content))
        is PlanVariant.File -> listOf(AcpPlanEntry(content = safeUri(plan.uri)))
    }

    /**
     * A URI reduced to what locates and never what authenticates: scheme, host and path.
     *
     * A URI is the one whitelisted field an agent controls end to end — `user:token@host`
     * with a `?access=` query is a legal URI and a credential. The locator's identity is the
     * authority host plus the path; userinfo, query and fragment select or authenticate, so
     * they are cut. A URI with no scheme (opaque or relative) keeps its path under the same
     * cut.
     */
    private fun safeUri(raw: String): String {
        val schemeSep = raw.indexOf("://")
        if (schemeSep < 0) return raw.substringBefore('?').substringBefore('#')
        val rest = raw.substring(schemeSep + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
        return raw.substring(0, schemeSep) + "://" + authority.substringAfterLast('@') +
            tail.substringBefore('?').substringBefore('#')
    }

    /** The current value of a configuration option, as text. */
    private fun currentValueOf(option: SessionConfigOption): String = when (option) {
        is SessionConfigOption.Select -> option.currentValue.value
        is SessionConfigOption.BooleanOption -> option.currentValue.toString()
    }

    /** What one content block yields: its text (clipped), a one-line summary, and the clip. */
    private data class BlockRead(val text: String, val summary: String, val truncated: Boolean)

    /**
     * Read a content block under the whitelist.
     *
     * The bytes of an image or an audio block are never read: the fact says what KIND of
     * content arrived, and the payload itself stays where the agent put it. A text block is
     * clipped at [MAX_TEXT_LENGTH] and the clip is MARKED, because a silent truncation would
     * read as a complete sentence.
     */
    private fun readBlock(block: ContentBlock): BlockRead = when (block) {
        is ContentBlock.Text -> {
            val clipped = block.text.take(MAX_TEXT_LENGTH)
            BlockRead(
                text = clipped,
                summary = "text",
                truncated = clipped.length < block.text.length,
            )
        }
        is ContentBlock.Image -> BlockRead("", "image/${block.mimeType}", false)
        is ContentBlock.Audio -> BlockRead("", "audio/${block.mimeType}", false)
        is ContentBlock.Resource -> {
            val text: String
            val mimeType: String
            val fullLength: Int
            when (val resource = block.resource) {
                is EmbeddedResourceResource.TextResourceContents -> {
                    text = resource.text.take(MAX_TEXT_LENGTH)
                    mimeType = resource.mimeType ?: ""
                    fullLength = resource.text.length
                }
                is EmbeddedResourceResource.BlobResourceContents -> {
                    // The bytes of a blob are never read: only its kind is a fact.
                    text = ""
                    mimeType = resource.mimeType ?: ""
                    fullLength = 0
                }
            }
            BlockRead(
                text = text,
                summary = "resource/$mimeType",
                truncated = text.length < fullLength,
            )
        }
        is ContentBlock.ResourceLink -> BlockRead("", "link/${safeUri(block.uri)}", false)
    }
}
