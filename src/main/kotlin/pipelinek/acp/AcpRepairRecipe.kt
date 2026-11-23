package pipelinek.acp

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * The `agenticRepair` recipe (PKACP-09 / PKH-140): repair driven as a reaction over the composed
 * session, with the verification owned ENTIRELY by the pipeline and an attempt control that is
 * the PKH-138 ledger itself.
 *
 * ## The shape, and what it refuses to be
 *
 * The loop is: verify → (repair turn → verify) × ≤ budget. The verifier is a slot the PLAN fills
 * with its own argv ([AcpRepairInput.verifyCommand]); the agent never selects, edits or reruns
 * the check, and its reply text carries zero verdict weight (AC#2's independence, AC#3's refusal
 * to declare success by model words). Every change the agent makes ends as a CANDIDATE — the
 * archived patch and report — never a commit, never an auto-promotion. There is no scheduler
 * here: the loop is bounded by attempts, digests and signatures, and every path out is one of
 * the closed [AcpRepairOutcome] tokens (AC#3), so a run cannot decay into an infinite decline.
 *
 * ## The attempt control is the 138 ledger, not a second counter
 *
 * Each repair is one reaction driven through [AcpReactionLedger.drive]: intent BEFORE the agent
 * turn, ack only when the turn attested an outcome it can vouch for, and the failure windows land
 * as [AcpDriveVerdict.EffectUnknown] / [AcpDriveVerdict.IntentNotPersisted] exactly as the
 * reactive turn already behaves. The failed CHECK is the event: its [EventKey] is minted from the
 * digest of the content that was actually verified, so "the agent repaired nothing" is not a
 * judgement call — the same digest is the same event, and the ledger answers AlreadyAcknowledged
 * (no-progress) or suspends the dispatch (unknown effect), never re-drives it automatically.
 * A recovered run passes [AcpRepairInput.priorLedgerRows] read back from its journal and the
 * next attempt index continues from the ledger's rows (attemptIndex is THEIRS; this file keeps
 * no copy of it).
 */

/** The plan an `agenticRepair` step runs: everything below is the pipeline's declaration, never the agent's. */
@Serializable
data class AcpRepairInput(
    /** argv of the ACP agent to launch for each repair turn; first entry is the executable. */
    val command: List<String>,
    /**
     * The bounded brief the agent gets, once per attempt (see [AcpRepairLoop.promptForAttempt]):
     * [AcpRepairLoop.MAX_PROMPT_FAILURE_CHARS] of the last failure ride with it, nothing else.
     */
    val repairPrompt: String,
    /**
     * The INDEPENDENT check, as argv — never a shell string, never a name the agent can steer.
     * Its exit code is the only success oracle the recipe knows (AC#1, AC#2).
     */
    val verifyCommand: List<String>,
    /** working directory for agent, check and digests. Empty means "the plugin's own". */
    val cwd: String = "",
    /** root whose content is digested; empty means [cwd]. */
    val digestRoot: String = "",
    /** argv producing the candidate patch (e.g. a git diff); empty archives no patch. */
    val diffCommand: List<String> = emptyList(),
    /** argv naming the base revision recorded on every evidence row; empty records "". */
    val revisionCommand: List<String> = emptyList(),
    /** where the candidate patch and the report are archived; empty archives nothing. */
    val archiveDir: String = "",
    /** hard ceiling on agent repair turns; beyond it the run STOPS, verified or not. */
    val maxRepairAttempts: Int = 3,
    /** budget of each repair turn, carried into the session scope. */
    val timeoutSeconds: Long = 300,
    /** directory NAMES pruned from the verified-content digest (build debris is not content). */
    val digestExcludeDirs: List<String> = AcpRepairLoop.DEFAULT_DIGEST_EXCLUDES.toList(),
    /**
     * The ledger rows a recovered run read back from its journal: the attempt index, the dedupe
     * and the never-re-drive-unknown law all continue from THESE (PKH-138 reuse), not from a
     * fresh counter of this file's invention.
     */
    val priorLedgerRows: List<AcpReactionLedgerRow> = emptyList(),
)

/** One observation of the independent check: what it said, over which content, at which revision. */
data class AcpVerifyObservation(
    val revision: String,
    /** digest of the verified content BEFORE the check ran. */
    val digestBefore: String,
    /** digest of the same tree AFTER the check ran; unequal means the check verified moving content. */
    val digestAfter: String,
    val exitCode: Int,
    val outputTail: String,
) {
    val passed: Boolean get() = exitCode == 0
}

/** The verify slot: whoever fills it runs the plan's check and reports what it saw. */
fun interface AcpVerifier {
    suspend fun verify(): AcpVerifyObservation
}

/** What one repair turn came back as, from the caller's side of the seam. */
@Serializable
sealed class AcpRepairTurnVerdict {

    /** The turn completed and its outcome is ATTESTED: the agent ran and reported an end. */
    @Serializable
    data class Attested(val reply: String, val stopReason: String = "END_TURN") : AcpRepairTurnVerdict()

    /** The agent or the session refused: a witnessed "no", which needs no re-drive. */
    @Serializable
    data class Denied(val reason: String) : AcpRepairTurnVerdict()

    /** The turn ended cancelled (the agent's own stop reason): a witnessed stop. */
    @Serializable
    data class Cancelled(val reason: String) : AcpRepairTurnVerdict()

    /**
     * The transport or budget died with the turn half-run: whether the files were touched is NOT
     * knowable from here, which is precisely the PKH-138 unknown and lands the row as UNKNOWN.
     */
    @Serializable
    data class OutcomeUnknown(val reason: String) : AcpRepairTurnVerdict()

    /** Whether the effect of this turn can be vouched for — the ack predicate of the ledger. */
    val attested: Boolean get() = this !is OutcomeUnknown
}

/** The repair slot: one agent turn, asked with the bounded prompt for attempt [attemptIndex]. */
fun interface AcpRepairTurn {
    suspend fun run(prompt: String, attemptIndex: Int): AcpRepairTurnVerdict
}

/** One durable evidence row (AC#2): the check, the revision and the content it really verified. */
@Serializable
data class AcpRepairEvidence(
    /** observation serial in run order; the same number the ledger row carries as its cursor. */
    val serial: Int,
    /** the base revision this check ran over (empty when the plan declared no revision command). */
    val revision: String,
    /** sha256 of the verified content at check time — the event identity of the failure. */
    val contentDigest: String,
    /** the same digest re-taken after the check; unequal to [contentDigest] voids the evidence. */
    val digestAfterCheck: String,
    val verifyExitCode: Int,
    /** signature of the failure itself (exit + normalized tail): repeats mean no progress. */
    val failureSignature: String,
    val passed: Boolean,
    val mutatedDuringCheck: Boolean,
) {
    /** A check whose content moved while it ran says nothing about the content it started with. */
    val evidenceValid: Boolean get() = !mutatedDuringCheck
}

/** What the loop concluded, in the closed vocabulary of AC#3. */
object AcpRepairOutcome {
    const val VERIFIED = "VERIFIED"
    const val BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    const val NO_PROGRESS = "NO_PROGRESS"
    const val CANCELLED = "CANCELLED"
    const val DENIED = "DENIED"
    const val UNKNOWN = "UNKNOWN"
    const val PLAN_REJECTED = "PLAN_REJECTED"

    /** Total over the stop reasons of AC#3: an outcome outside this set is a bug, not a run. */
    val VOCABULARY: Set<String> = setOf(
        VERIFIED, BUDGET_EXHAUSTED, NO_PROGRESS, CANCELLED, DENIED, UNKNOWN, PLAN_REJECTED,
    )
}

/** The whole verdict of one loop run: durable-ready data, journalled through the step output. */
data class AcpRepairReport(
    val outcome: String,
    val detail: String,
    /** ok is VERIFIED and nothing else (AC#3): no budget/no-progress run ever claims success. */
    val ok: Boolean,
    /** repair turns THIS run invoked — read off the ledger, not from a side counter. */
    val agentTurns: Int,
    val evidence: List<AcpRepairEvidence>,
    val ledgerRows: List<AcpReactionLedgerRow>,
    val finalDigest: String,
)

/** The archived half of the candidate (AC#4): patch and report paths, nothing committed. */
data class AcpRepairArtifacts(
    val reportPath: String,
    val patchPath: String,
)

/**
 * The recipe itself: verify first, drive bounded repair turns under the 138 ledger, re-verify
 * after EVERY attempt including the last, and stop into one of [AcpRepairOutcome]'s tokens.
 *
 * The seams ([verifier], [repairTurn]) are what makes every stop reason measurable without a
 * process; production fills them with [AcpProcessVerifier] and [AcpSessionRepairTurn] (the
 * recipe-over-session of AC#4), tests fill them with recordings.
 */
class AcpRepairLoop(
    private val input: AcpRepairInput,
    private val verifier: AcpVerifier,
    private val repairTurn: AcpRepairTurn,
    /** The PKH-138 ledger; seeded from [AcpRepairInput.priorLedgerRows] when none is supplied. */
    private val ledger: AcpReactionLedger = AcpReactionLedger(priorRows = input.priorLedgerRows),
    /** The connection nonce half of every minted key — this run owns its fallback identities. */
    private val connectionNonce: String = java.util.UUID.randomUUID().toString(),
) {

    companion object {
        /** The reaction id every repair attempt is journalled under — one ledger namespace. */
        const val REACTION_ID: String = "agenticRepair.attempt"

        /** The source half of a digest-derived event key: the key came from the CHECK. */
        const val VERIFY_EVENT_SOURCE: String = "acp-verify"

        /** Beyond this, a budget is a scheduler with extra steps; the plan is refused instead. */
        const val MAX_REPAIR_ATTEMPTS: Int = 8

        /** Build debris is not verified content: these directory NAMES are pruned from digests. */
        val DEFAULT_DIGEST_EXCLUDES: Set<String> = setOf(
            ".git", ".gradle", "build", "target", "__pycache__", ".pytest_cache", ".mypy_cache",
        )

        /** The prompt is acotado: this many chars of the last failure are ALL the agent learns. */
        const val MAX_PROMPT_FAILURE_CHARS: Int = 2_000

        /**
         * Why this plan may not run, as reasons (empty means it may). All decidable from the
         * input, before a process exists: the verifier argv is policy, so it obeys the same
         * shape law as every delegated command of this plugin — argv, never a shell.
         */
        fun rejectPlan(input: AcpRepairInput): List<String> {
            val reasons = mutableListOf<String>()
            if (input.command.isEmpty() || input.command.first().isBlank()) {
                reasons += "the agent command is empty: the recipe cannot repair without an agent"
            }
            if (input.repairPrompt.isBlank()) {
                reasons += "the repair prompt is blank: an agent cannot be asked to fix nothing"
            }
            if (input.verifyCommand.isEmpty() || input.verifyCommand.first().isBlank()) {
                reasons += "no verify command: without an independent check there is nothing to " +
                    "verify against, and the recipe would be the agent grading itself"
            } else if (input.verifyCommand.first() in AcpTaskIsolation.SHELL_NAMES) {
                reasons += "verifyCommand[0] is the shell '${input.verifyCommand.first()}': a " +
                    "verifier that reinterprets argv is not the independent policy AC#2 demands"
            }
            if (input.maxRepairAttempts < 1 || input.maxRepairAttempts > MAX_REPAIR_ATTEMPTS) {
                reasons += "maxRepairAttempts ${input.maxRepairAttempts} is outside 1..$MAX_REPAIR_ATTEMPTS: " +
                    "an unbounded repair budget is the infinite scheduler this recipe refuses to be"
            }
            if (input.timeoutSeconds < 1) {
                reasons += "timeoutSeconds ${input.timeoutSeconds} is not a budget"
            }
            if (input.diffCommand.isNotEmpty() && input.diffCommand.first() in AcpTaskIsolation.SHELL_NAMES) {
                reasons += "diffCommand[0] is the shell '${input.diffCommand.first()}': one argv law, one list"
            }
            return reasons
        }

        /**
         * The bounded brief for attempt [attemptIndex] (AC: prompt acotado): the plan's words, the
         * attempt number, the candidate-only and don't-touch-the-check demands, and at most
         * [MAX_PROMPT_FAILURE_CHARS] of the last failure. The agent learns what to fix, never how
         * it will be judged — and it cannot answer its way out of either demand.
         */
        fun promptForAttempt(base: String, attemptIndex: Int, maxAttempts: Int, failureTail: String): String {
            val tail = failureTail.take(MAX_PROMPT_FAILURE_CHARS)
            return buildString {
                append(base.trimEnd())
                append(" (repair attempt ").append(attemptIndex).append(" of at most ").append(maxAttempts).append(").")
                append(" Leave your changes in the working tree as a CANDIDATE: do not commit, ")
                append("do not push, and do not modify or rerun the verification itself — ")
                append("the pipeline runs it and only its exit code decides.")
                if (tail.isNotBlank()) {
                    append("\nLast failure:\n").append(tail)
                }
            }
        }
    }

    /**
     * Run the recipe. It never throws to answer a step failure — every stop lands as a report —
     * with exactly one exception, the coroutine law: the CALLER's cancellation propagates, after
     * the ledger closed its unacked intents as UNKNOWN (the cancellation law of PKH-138, reused).
     */
    suspend fun run(): AcpRepairReport {
        val rejected = rejectPlan(input)
        if (rejected.isNotEmpty()) {
            return report(AcpRepairOutcome.PLAN_REJECTED, rejected.joinToString("; "), emptyList())
        }
        val evidence = mutableListOf<AcpRepairEvidence>()
        // The failure tails live only for the loop's lifetime: the durable evidence row stays
        // small and secret-free, while the bounded prompt may still quote the LAST failure.
        val tails = mutableMapOf<Int, String>()
        val cwd = input.cwd.ifBlank { System.getProperty("user.dir") }
        return try {
            runLoop(cwd, evidence, tails)
        } catch (e: CancellationException) {
            ledger.markPendingUnknown()
            throw e
        }
    }

    private suspend fun runLoop(
        cwd: String,
        evidence: MutableList<AcpRepairEvidence>,
        tails: MutableMap<Int, String>,
    ): AcpRepairReport {
        // The serial continues from the ledger's own cursors: a recovered run does not renumber
        // the observations of the one it recovered from.
        var serial = (ledger.rows().maxOfOrNull { it.cursor } ?: 0L).toInt()
        val digestExcludes = input.digestExcludeDirs.toSet()

        // AC#1, first half: the check runs BEFORE any agent is asked.
        var check = record(digestExcludes, ++serial, evidence, tails)
        if (!check.evidenceValid) {
            return report(
                AcpRepairOutcome.UNKNOWN,
                "the initial check was invalidated: the content moved while it ran " +
                    "(${check.contentDigest} -> ${check.digestAfterCheck})",
                evidence,
            )
        }
        if (check.passed) {
            return report(
                AcpRepairOutcome.VERIFIED,
                "the check was already green; the agent was never called",
                evidence,
            )
        }

        while (true) {
            // The attempt index is read off the ledger rows (PKH-138 reuse — no second counter).
            val nextAttempt = (ledger.rows().filter { it.reactionId == REACTION_ID }
                .maxOfOrNull { it.attemptIndex } ?: 0) + 1
            if (nextAttempt > input.maxRepairAttempts) {
                return report(
                    AcpRepairOutcome.BUDGET_EXHAUSTED,
                    "the repair budget of ${input.maxRepairAttempts} attempts is spent and the " +
                        "last re-check still fails",
                    evidence,
                )
            }
            // The failed CHECK is the event: its key is the digest of the content verified.
            val eventKey = EventKey.mint(
                externalValue = check.contentDigest,
                externalSource = VERIFY_EVENT_SOURCE,
                externalTrustworthy = true,
                connectionNonce = connectionNonce,
                sequence = serial.toLong(),
            ) ?: return report(
                AcpRepairOutcome.UNKNOWN,
                "the check reported no digest to key the attempt under: fail-closed, no dispatch",
                evidence,
            )
            when (val decision = AcpReactionReconciler(ledger, connectionNonce)
                .decide(REACTION_ID, eventKey, AcpStreamOrigin.LIVE, effectful = true)) {
                is AcpReconciledDecision.SuppressedAcknowledged -> return report(
                    AcpRepairOutcome.NO_PROGRESS,
                    "the repair moved nothing: digest ${check.contentDigest} is the failure this " +
                        "ledger already paid an attempt for",
                    evidence,
                )
                is AcpReconciledDecision.Suspended -> return report(
                    AcpRepairOutcome.UNKNOWN,
                    decision.reason,
                    evidence,
                )
                else -> Unit
            }

            var turnVerdict: AcpRepairTurnVerdict? = null
            val drive = ledger.drive(REACTION_ID, eventKey, serial.toLong(), nextAttempt) {
                turnVerdict = repairTurn.run(
                    promptForAttempt(
                        input.repairPrompt, nextAttempt, input.maxRepairAttempts,
                        tails[check.serial] ?: "",
                    ),
                    nextAttempt,
                )
                turnVerdict?.attested == true
            }
            when (drive) {
                AcpDriveVerdict.IntentNotPersisted -> return report(
                    AcpRepairOutcome.UNKNOWN,
                    "the intent never reached the journal: the attempt did not run and the run " +
                        "refuses to continue on an unwitnessed effect",
                    evidence,
                )
                AcpDriveVerdict.EffectUnknown -> return report(
                    AcpRepairOutcome.UNKNOWN,
                    "the repair turn's outcome is unknown (${turnVerdict?.unknownReason ?: "drive refused"}) " +
                        "and an unknown is never re-driven automatically",
                    evidence,
                )
                AcpDriveVerdict.AlreadyAcknowledged -> return report(
                    AcpRepairOutcome.NO_PROGRESS,
                    "an ack for this exact failure digest was already durable: no second attempt " +
                        "is owed for one fact",
                    evidence,
                )
                is AcpDriveVerdict.Executed -> Unit
            }
            when (val verdict = turnVerdict) {
                is AcpRepairTurnVerdict.Denied -> return report(
                    AcpRepairOutcome.DENIED, verdict.reason, evidence,
                )
                is AcpRepairTurnVerdict.Cancelled -> return report(
                    AcpRepairOutcome.CANCELLED, verdict.reason, evidence,
                )
                else -> Unit
            }

            // AC#1, second half: the check runs again after EVERY repair — including the attempt
            // the budget still allowed, whose re-verification is what lands BUDGET_EXHAUSTED above.
            check = record(digestExcludes, ++serial, evidence, tails)
            if (!check.evidenceValid) {
                return report(
                    AcpRepairOutcome.UNKNOWN,
                    "the post-repair check was invalidated: the content moved while it ran " +
                        "(${check.contentDigest} -> ${check.digestAfterCheck})",
                    evidence,
                )
            }
            if (check.passed) {
                return report(
                    AcpRepairOutcome.VERIFIED,
                    "attempt ${nextAttempt}: the independent check turned green",
                    evidence,
                )
            }
            // AC#3's other no-progress form: the FAILURE repeats even though the content moved.
            val earlier = evidence.take(evidence.size - 1).firstOrNull {
                !it.passed && it.failureSignature == check.failureSignature
            }
            if (earlier != null) {
                return report(
                    AcpRepairOutcome.NO_PROGRESS,
                    "the failure signature of check ${earlier.serial} repeats at check " +
                        "${check.serial}: the repairs are moving content, not fixing it",
                    evidence,
                )
            }
        }
    }

    private suspend fun record(
        digestExcludes: Set<String>,
        serial: Int,
        evidence: MutableList<AcpRepairEvidence>,
        tails: MutableMap<Int, String>,
    ): AcpRepairEvidence {
        val observation = verifier.verify()
        tails[serial] = observation.outputTail
        return AcpRepairEvidence(
            serial = serial,
            revision = observation.revision,
            contentDigest = observation.digestBefore,
            digestAfterCheck = observation.digestAfter,
            verifyExitCode = observation.exitCode,
            failureSignature = acpRepairFailureSignature(observation.exitCode, observation.outputTail),
            passed = observation.passed,
            mutatedDuringCheck = observation.digestBefore != observation.digestAfter,
        ).also { evidence += it }
    }

    private fun report(outcome: String, detail: String, evidence: List<AcpRepairEvidence>): AcpRepairReport {
        val rows = ledger.rows()
        // Un intento deja dos filas (INTENT y ACKED) bajo el mismo eventKey: lo que se cuenta son
        // las claves que esta corrida añadió, no las filas — dos filas no son dos turnos del agente.
        val ownKeys = rows.filter { it.reactionId == REACTION_ID }
            .map { it.eventKey.canonical }.toSet()
        val priorKeys = input.priorLedgerRows.filter { it.reactionId == REACTION_ID }
            .map { it.eventKey.canonical }.toSet()
        val ownTurns = (ownKeys - priorKeys).size
        return AcpRepairReport(
            outcome = outcome,
            detail = detail,
            ok = outcome == AcpRepairOutcome.VERIFIED,
            agentTurns = ownTurns,
            evidence = evidence,
            ledgerRows = rows,
            finalDigest = evidence.lastOrNull()?.contentDigest ?: "",
        )
    }
}

/** The digest of one tree: sorted relative paths and contents, build debris pruned (AC#2). */
internal fun acpTreeDigest(root: java.nio.file.Path, excludeDirNames: Set<String>): String = try {
    val files = mutableListOf<java.nio.file.Path>()
    java.nio.file.Files.walkFileTree(
        root,
        object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                if (dir != root && dir.fileName.toString() in excludeDirNames) {
                    java.nio.file.FileVisitResult.SKIP_SUBTREE
                } else {
                    java.nio.file.FileVisitResult.CONTINUE
                }

            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) files.add(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) =
                java.nio.file.FileVisitResult.CONTINUE
        },
    )
    val hash = MessageDigest.getInstance("SHA-256")
    for (file in files.sortedBy { root.relativize(it).toString() }) {
        hash.update(root.relativize(file).toString().toByteArray(Charsets.UTF_8))
        hash.update(PATH_SEPARATOR)
        runCatching { java.nio.file.Files.readAllBytes(file) }.onSuccess(hash::update).onFailure {
            hash.update("<unreadable>".toByteArray(Charsets.UTF_8))
        }
        hash.update(PATH_SEPARATOR)
    }
    hexOf(hash.digest())
} catch (e: Exception) {
    ""
}

/** The failure signature: exit code plus normalized output — whitespace is not a new failure. */
internal fun acpRepairFailureSignature(exitCode: Int, output: String): String {
    val normalized = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(4_000)
    return acpRepairSha256Hex("$exitCode\n$normalized")
}

/** Domain separator so a path ending in bytes cannot fake another file's digest. */
private val PATH_SEPARATOR: ByteArray = byteArrayOf(0)

internal fun acpRepairSha256Hex(text: String): String =
    hexOf(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private val AcpRepairTurnVerdict?.unknownReason: String?
    get() = (this as? AcpRepairTurnVerdict.OutcomeUnknown)?.reason
