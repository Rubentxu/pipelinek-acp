package pipelinek.acp

/**
 * How one v1 method relates to the v2 dialect (PKH-142 AC#4). The kinds are mutually
 * exclusive BY CONSTRUCTION of the table, and the test enforces that a pair is never filed
 * under two kinds — "the non-equivalent cases stay as they are (explicit rows, never
 * merged)" is exactly this discipline.
 */
enum class AcpV2EquivalenceKind {
    /** Same wire name, same semantics: the normalized vocabulary is ONE operation for both. */
    EQUIVALENT,

    /** Same operation, different wire name (authenticate → auth/login): one operation, two
     * names — the rows stay separate, the normalization joins them. */
    RENAMED,

    /** The v1 method is GONE and another v1 mechanism absorbs its job (session/load →
     * session/resume + replayFrom cursor). NOT equivalent: fusing them into one row would
     * hide the cursor that carries the meaning. */
    REPLACED_BY_MECHANISM,

    /** The v1 method has no v2 successor at all (fs/..., terminal/..., session/set_mode): the
     * plugin's reverse-leg features exist in the v1 dialect only. */
    REMOVED_IN_V2,

    /** A v2 method v1 never spoke (tool_call/cancel, terminal/cancel): no v1 row to fuse with. */
    NEW_IN_V2,
}

/**
 * One explicit relation between the dialects. [operation] is the NORMALIZED name: v1 and v2
 * methods that are semantically equivalent carry the same operation string, which is the
 * whole claim AC#4 makes — one vocabulary where the dialects agree, kept apart where they
 * don't. [v1] / [v2] are wire names; a null side is a dialect that does not speak it.
 */
data class AcpV2EquivalenceRow(
    val operation: String,
    val v1: String?,
    val v2: String?,
    val kind: AcpV2EquivalenceKind,
    val note: String,
)

/**
 * The explicit mapping between the v1 and v2 method vocabularies (PKH-142 AC#4).
 *
 * The table is the answer to "same normalized vocabulary where equivalent, non-equivalent
 * cases kept as such". The normalization is ONE string per operation ([normalize]); where the
 * dialects are equivalent both wire names map to it, and where they are not (renames, the
 * replay-cursor replacement, removals, new per-entity cancels) the rows say which kind of
 * difference it is instead of quietly folding one name into the other. [AcpV2VocabularyEquivalenceTest]
 * checks the table against BOTH shipped vocabularies (the SDK's V1 and V2 method groups), so
 * a name present in either dialect with no row here is red — the mapping cannot fall behind
 * the protocol it claims to relate.
 */
object AcpV2VocabularyEquivalence {

    val ROWS: List<AcpV2EquivalenceRow> = listOf(
        // --- equivalent: the same name means the same thing in both dialects -----------------
        AcpV2EquivalenceRow("initialize", "initialize", "initialize", AcpV2EquivalenceKind.EQUIVALENT,
            "same operation, but v2 REQUIRES the response to echo the chosen protocolVersion " +
                "and the version-negotiation rules bind a v2-capable agent"),
        AcpV2EquivalenceRow("session_new", "session/new", "session/new", AcpV2EquivalenceKind.EQUIVALENT,
            "v2 drops the legacy SSE MCP transport from the descriptor list"),
        AcpV2EquivalenceRow("session_prompt", "session/prompt", "session/prompt", AcpV2EquivalenceKind.EQUIVALENT,
            "same dial, different meaning of the answer: v1 returns the turn (stopReason), v2 " +
                "returns acceptance (messageId) — the operation is the request, the OUTCOME " +
                "shape is the AC#2 split, kept a separate fact"),
        AcpV2EquivalenceRow("session_cancel", "session/cancel", "session/cancel", AcpV2EquivalenceKind.EQUIVALENT,
            "v2 cancels the foreground lane only; background work keeps running"),
        AcpV2EquivalenceRow("session_list", "session/list", "session/list", AcpV2EquivalenceKind.EQUIVALENT,
            "required in v2 whenever the session group is advertised — the v1 marker moved " +
                "into baseline"),
        AcpV2EquivalenceRow("session_delete", "session/delete", "session/delete", AcpV2EquivalenceKind.EQUIVALENT,
            "required in v2 when the session group is advertised"),
        AcpV2EquivalenceRow("session_close", "session/close", "session/close", AcpV2EquivalenceKind.EQUIVALENT,
            "v2 also drops queued prompts with -32800; the close ANSWER ordering (final " +
                "updates and idle before the response) is what AC#2's close policy consumes"),
        AcpV2EquivalenceRow("session_set_config_option", "session/set_config_option",
            "session/set_config_option", AcpV2EquivalenceKind.EQUIVALENT,
            "modes, model and thinking level are config options in both dialects (v2 made it " +
                "the only way)"),
        AcpV2EquivalenceRow("session_request_permission", "session/request_permission",
            "session/request_permission", AcpV2EquivalenceKind.EQUIVALENT,
            "v2 outcome adds `cancelled` to the option outcomes; the operation is the same ask"),
        AcpV2EquivalenceRow("session_update", "session/update", "session/update", AcpV2EquivalenceKind.EQUIVALENT,
            "the notification lane is shared; v2 adds variants (state_update, replay_complete) " +
                "and removes others (tool_call first-sight, current_mode_update) — the update " +
                "AXIS inventory of that union is what the schema matrix will carry when v2 is " +
                "no longer draft"),
        AcpV2EquivalenceRow("elicitation_create", "elicitation/create", "elicitation/create",
            AcpV2EquivalenceKind.EQUIVALENT,
            "v2 requires exactly one of sessionId/requestId and drops `pattern` from string " +
                "schemas"),
        AcpV2EquivalenceRow("elicitation_complete", "elicitation/complete", "elicitation/complete",
            AcpV2EquivalenceKind.EQUIVALENT, "same completion signal, notification in both"),
        AcpV2EquivalenceRow("cancel_request", "$/cancel_request", "$/cancel_request",
            AcpV2EquivalenceKind.EQUIVALENT, "protocol layer; declined in BOTH dialects (the " +
                "plugin answers at session level, not at slot level)"),
        // unstable in both dialects, same exclusion in both:
        AcpV2EquivalenceRow("providers_list", "providers/list", "providers/list",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only on both sides; the plugin reads it"),
        AcpV2EquivalenceRow("providers_set", "providers/set", "providers/set",
            AcpV2EquivalenceKind.EQUIVALENT, "declined by policy in both dialects"),
        AcpV2EquivalenceRow("providers_disable", "providers/disable", "providers/disable",
            AcpV2EquivalenceKind.EQUIVALENT, "declined by policy in both dialects"),
        AcpV2EquivalenceRow("mcp_message", "mcp/message", "mcp/message",
            AcpV2EquivalenceKind.EQUIVALENT, "relay position, excluded by role in both dialects"),
        AcpV2EquivalenceRow("auth_status", "auth/status", "auth/status",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable read-side status; same policy both sides"),
        AcpV2EquivalenceRow("auth_status_update", "auth/status_update", "auth/status_update",
            AcpV2EquivalenceKind.EQUIVALENT,
            "no handler carrier in the pinned SDK in either dialect; dropped as unknown"),

        // --- renamed: one operation, two wire names, rows kept separate -----------------------
        AcpV2EquivalenceRow("auth_login", "authenticate", "auth/login", AcpV2EquivalenceKind.RENAMED,
            "v2 renamed and reshaped the request (method ids on an authMethods list); the " +
                "plugin declines the OPERATION in both dialects, so the rename is a vocabulary " +
                "fact, not a policy change"),
        AcpV2EquivalenceRow("auth_logout", "logout", "auth/logout", AcpV2EquivalenceKind.RENAMED,
            "v1 logout is TESTED over the wire; the v2 name rides the same decline, stated as " +
                "its own row"),

        // --- replaced by a mechanism: never fused into one operation --------------------------
        AcpV2EquivalenceRow("session_load", "session/load", null,
            AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
            "v2 removed the method: the job belongs to session/resume with " +
                "`replayFrom: {type: start}`. One row for the v1 method, and resume's " +
                "EQUIVALENT row keeps the cursor as the differentiator — merging them would " +
                "erase the no-replay resume v1 also had"),
        AcpV2EquivalenceRow("session_set_mode", "session/set_mode", null,
            AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
            "v2 removed the modes API; session/set_config_option carries it (its EQUIVALENT " +
                "row above is where the operation lives now)"),
        AcpV2EquivalenceRow("session_set_model", "session/set_model", null,
            AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
            "v2 never spoke a model verb; model selection is a config option"),
        AcpV2EquivalenceRow("session_status", "session/status", "session/status",
            AcpV2EquivalenceKind.REPLACED_BY_MECHANISM,
            "unstable-only on both sides; v2 answers liveness with state_update snapshots on " +
                "the update lane, not a polling method"),

        // --- removed: v1-only features, no v2 successor ---------------------------------------
        AcpV2EquivalenceRow("fs_read_text_file", "fs/read_text_file", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2,
            "client file-system methods are removed from v2 entirely; file access goes through " +
                "MCP servers. The v1 reverse leg keeps its own tested row"),
        AcpV2EquivalenceRow("fs_write_text_file", "fs/write_text_file", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same removal as fs/read_text_file"),
        AcpV2EquivalenceRow("terminal_create", "terminal/create", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2,
            "client terminal control is removed from v2; agent-owned terminal display stays " +
                "on the update lane"),
        AcpV2EquivalenceRow("terminal_output", "terminal/output", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same removal as terminal/create"),
        AcpV2EquivalenceRow("terminal_wait_for_exit", "terminal/wait_for_exit", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same removal as terminal/create"),
        AcpV2EquivalenceRow("terminal_kill", "terminal/kill", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same removal as terminal/create"),
        AcpV2EquivalenceRow("terminal_release", "terminal/release", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same removal as terminal/create"),

        // --- new in v2: no v1 counterpart to fuse with -----------------------------------------
        AcpV2EquivalenceRow("tool_call_cancel", null, "tool_call/cancel",
            AcpV2EquivalenceKind.NEW_IN_V2,
            "per-entity cancel of one advertised tool call; v1's only lever was the whole " +
                "session"),
        AcpV2EquivalenceRow("terminal_cancel", null, "terminal/cancel",
            AcpV2EquivalenceKind.NEW_IN_V2,
            "per-entity cancel of one agent-owned terminal"),
        AcpV2EquivalenceRow("session_fork", "session/fork", "session/fork",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only on both sides; same fork job"),
        AcpV2EquivalenceRow("session_resume", "session/resume", "session/resume",
            AcpV2EquivalenceKind.EQUIVALENT,
            "v2 requires it when the session group is advertised and adds the replayFrom " +
                "cursor — the cursor is what REPLACES session/load next to it"),
        AcpV2EquivalenceRow("proxy_initialize", "proxy/initialize", "proxy/initialize",
            AcpV2EquivalenceKind.EQUIVALENT, "third role, excluded in both dialects"),
        AcpV2EquivalenceRow("proxy_successor", "proxy/successor", "proxy/successor",
            AcpV2EquivalenceKind.EQUIVALENT, "third role, excluded in both dialects"),
        AcpV2EquivalenceRow("session_archive", "session/archive", "session/archive",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only, no SDK carrier in either dialect"),
        AcpV2EquivalenceRow("session_unarchive", "session/unarchive", "session/unarchive",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only, no SDK carrier in either dialect"),
        AcpV2EquivalenceRow("session_options", "session/options", "session/options",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only, no SDK carrier in either dialect"),
        AcpV2EquivalenceRow("session_set_title", "session/set_title", "session/set_title",
            AcpV2EquivalenceKind.EQUIVALENT, "unstable-only, no SDK carrier in either dialect"),
        // v1-only vocabulary the v2 metas never speak, neither under their own name nor another:
        AcpV2EquivalenceRow("nes_start", "nes/start", null, AcpV2EquivalenceKind.REMOVED_IN_V2,
            "the whole NES dialect is v1-unstable; v2's metas do not speak it at all"),
        AcpV2EquivalenceRow("nes_suggest", "nes/suggest", null, AcpV2EquivalenceKind.REMOVED_IN_V2,
            "same dialect as nes/start"),
        AcpV2EquivalenceRow("nes_accept", "nes/accept", null, AcpV2EquivalenceKind.REMOVED_IN_V2,
            "same dialect as nes/start"),
        AcpV2EquivalenceRow("nes_reject", "nes/reject", null, AcpV2EquivalenceKind.REMOVED_IN_V2,
            "same dialect as nes/start"),
        AcpV2EquivalenceRow("nes_close", "nes/close", null, AcpV2EquivalenceKind.REMOVED_IN_V2,
            "same dialect as nes/start"),
        AcpV2EquivalenceRow("document_did_open", "document/didOpen", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "document sync feeds the NES role; v2 dropped it"),
        AcpV2EquivalenceRow("document_did_change", "document/didChange", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same dialect as document/didOpen"),
        AcpV2EquivalenceRow("document_did_focus", "document/didFocus", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same dialect as document/didOpen"),
        AcpV2EquivalenceRow("document_did_save", "document/didSave", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same dialect as document/didOpen"),
        AcpV2EquivalenceRow("document_did_close", "document/didClose", null,
            AcpV2EquivalenceKind.REMOVED_IN_V2, "same dialect as document/didOpen"),
    )

    /** The normalized operation a wire name belongs to, in either dialect; null when the
     * mapping carries no row for it — which the totality test treats as red, not as a name
     * that may be skipped. */
    fun normalize(method: String): String? =
        ROWS.firstOrNull { it.v1 == method || it.v2 == method }?.operation

    /** The row relating one wire name of either dialect; null when the table has not decided. */
    fun rowFor(method: String): AcpV2EquivalenceRow? =
        ROWS.firstOrNull { it.v1 == method || it.v2 == method }

    /**
     * The equivalence check AC#4 asks for: do the two dialects' names for one operation
     * normalize to the SAME vocabulary? True for EQUIVALENT and RENAMED (one operation, the
     * rename is a vocabulary fact the row records); FALSE for every other kind — a replacement
     * or removal is not an equivalence, and stating it as one would be the silent merge the
     * AC forbids.
     */
    fun sameNormalizedVocabulary(operation: String): Boolean =
        ROWS.firstOrNull { it.operation == operation }?.let {
            it.kind == AcpV2EquivalenceKind.EQUIVALENT || it.kind == AcpV2EquivalenceKind.RENAMED
        } ?: false
}
