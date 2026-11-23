package pipelinek.acp

/**
 * One row of the session/update inventory: what the ACP schema declares, and what this plugin
 * makes of it.
 *
 * [variant] is the SDK class's simple name, so the row can be matched against the SDK itself —
 * the inventory is verified against the shipped jar, not against a document that can drift.
 *
 * [lane] decides what happens to the facts of this row when the flow outruns the reader.
 *
 * [mutating] says whether the variant may trigger a mutation operation. It is `false` for
 * every row today, and that is the plugin's answer, not an accident of the schema: the only
 * authority that mutates anything on the agent's behalf is the reverse leg, which is a
 * different path with a different owner. A chunk is a report; a report is not an order.
 *
 * [facts] are the tokens this variant can produce. A variant can produce more than one: a tool
 * call update that brings the call to its end produces the change AND the completion.
 */
data class AcpUpdateRow(
    val variant: String,
    val lane: AcpLane,
    val mutating: Boolean,
    val facts: List<String>,
)

/**
 * One row of the content block inventory: which fields of the block this plugin may read.
 *
 * The list IS the redaction rule, written as data instead of as a promise in a comment. A
 * block's bytes are never read — an image is a MIME type and a length, not a payload — and the
 * fields that could carry anything the agent wants hidden are simply not on the list.
 */
data class AcpContentRow(
    val variant: String,
    val readable: List<String>,
)

/**
 * The inventory of the ACP schema as THIS plugin understands it.
 *
 * ## Why the inventory is data and not a document
 *
 * The schema grows. An agent that speaks a newer dialect sends variants this plugin has never
 * heard of, and the plugin's answer to them must be the same whatever they are: an explicit
 * `unknown_update` that names the discriminator and nothing else. For that answer to be
 * honest, the plugin has to know what it does NOT know — and the only way to know that is to
 * compare its matrix against the SDK it was compiled with, which is what the coverage test
 * does. A matrix written in prose would drift silently and the drift would look like a plugin
 * that understood everything.
 */
object AcpSchemaMatrix {

    /** The `session/update` variants this plugin classifies, one row per SDK variant. */
    val SESSION_UPDATES: List<AcpUpdateRow> = listOf(
        AcpUpdateRow(
            "AgentMessageChunk", AcpLane.TELEMETRY, false,
            listOf(AcpFactToken.AGENT_MESSAGE_CHUNK),
        ),
        AcpUpdateRow(
            "AgentThoughtChunk", AcpLane.TELEMETRY, false,
            listOf(AcpFactToken.AGENT_THOUGHT_CHUNK),
        ),
        AcpUpdateRow(
            "UserMessageChunk", AcpLane.TELEMETRY, false,
            listOf(AcpFactToken.USER_MESSAGE_CHUNK),
        ),
        AcpUpdateRow(
            "ToolCall", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.TOOL_CALL),
        ),
        AcpUpdateRow(
            "ToolCallUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.TOOL_CALL_CHANGED, AcpFactToken.TOOL_CALL_COMPLETED),
        ),
        AcpUpdateRow(
            "PlanUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.PLAN),
        ),
        AcpUpdateRow(
            "PlanUpdateV2", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.PLAN),
        ),
        AcpUpdateRow(
            "PlanRemoved", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.PLAN_REMOVED),
        ),
        AcpUpdateRow(
            "UsageUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.USAGE),
        ),
        AcpUpdateRow(
            "CurrentModeUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.MODE_CHANGED),
        ),
        AcpUpdateRow(
            "AvailableCommandsUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.COMMANDS_CHANGED),
        ),
        AcpUpdateRow(
            "ConfigOptionUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.CONFIGURATION_CHANGED),
        ),
        AcpUpdateRow(
            "SessionInfoUpdate", AcpLane.REACTIVE, false,
            listOf(AcpFactToken.SESSION_INFO_CHANGED),
        ),
        AcpUpdateRow(
            "UnknownSessionUpdate", AcpLane.CONTROL, false,
            listOf(AcpFactToken.UNKNOWN_UPDATE),
        ),
    )

    /** The content block variants this plugin classifies, one row per SDK variant. */
    val CONTENT_BLOCKS: List<AcpContentRow> = listOf(
        AcpContentRow("Text", listOf("text")),
        AcpContentRow("Image", listOf("mimeType", "byteLength")),
        AcpContentRow("Audio", listOf("mimeType", "byteLength")),
        AcpContentRow("Resource", listOf("uri", "mimeType", "textLength")),
        AcpContentRow("ResourceLink", listOf("uri", "name")),
    )

    /**
     * The facts the plugin asserts about its OWN exchange.
     *
     * They are not schema variants — nothing in the ACP schema declares them — but they travel
     * in the same stream and under the same rules, because a reader that sees the agent's
     * facts without seeing the exchange they belong to cannot tell a quiet session from a
     * session that was never opened.
     */
    val LIFECYCLE: List<AcpUpdateRow> = listOf(
        AcpUpdateRow(
            "plugin", AcpLane.CONTROL, false,
            listOf(
                AcpFactToken.EXCHANGE_STARTED,
                AcpFactToken.TURN_ENDED,
                AcpFactToken.EXCHANGE_CANCELLED,
                AcpFactToken.EXCHANGE_REFUSED,
                AcpFactToken.OBSERVATION_GAP,
            ),
        ),
    )

    /** Every fact token the plugin can publish. */
    val tokens: List<String> =
        (SESSION_UPDATES + LIFECYCLE).flatMap { it.facts }.distinct()

    /** Every kind the plugin declares — the manifest's catalogue, derived from the matrix. */
    val kinds: List<String> = tokens.map { AcpFactToken.kindOf(it) }

    /** The row that classifies [variant], or null when the matrix does not know it. */
    fun rowOf(variant: String): AcpUpdateRow? =
        SESSION_UPDATES.firstOrNull { it.variant == variant }

    /** The lane a fact of [token] travels in. */
    fun laneOf(token: String): AcpLane =
        (SESSION_UPDATES + LIFECYCLE)
            .firstOrNull { token in it.facts }
            ?.lane
            ?: AcpLane.CONTROL
}
