package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The agenticRepair recipe (PKH-140): repair is a reaction the pipeline drives, verification is
 * an INDEPENDENT slot the agent never selects, and the attempt control is the PKH-138 ledger —
 * intents, acks and the honest unknown, not a second counter.
 *
 * The rows here are the acceptance criteria: verify FIRST and re-verify after EVERY repair
 * including the last allowed attempt (AC#1); every attempt bound to its revision and the digest
 * of the content actually checked, with a mutation during the check invalidating the evidence
 * (AC#2); the loop ends by verified success, budget, lack of progress, cancellation, denial or
 * Unknown — never by the model's own words (AC#3). The fixture rows (AcpRepairFixtureTest) carry
 * AC#4; this file drives the loop through injected seams so each stop reason is attributable.
 */
class AcpRepairRecipeTest {

    // ── the durable plan ──────────────────────────────────────────────────────────────────

    private fun input(
        maxRepairAttempts: Int = 3,
        priorRows: List<AcpReactionLedgerRow> = emptyList(),
    ) = AcpRepairInput(
        command = listOf("my-agent", "--acp"),
        repairPrompt = "make the check pass",
        verifyCommand = listOf("python3", "check.py"),
        maxRepairAttempts = maxRepairAttempts,
        priorLedgerRows = priorRows,
    )

    // ── AC#1 — verify first, re-verify after every repair, including the last ────────────

    @Test
    fun `the check runs before the agent is called and a green initial check costs no agent turn`() {
        val verifier = RecordingVerifier(listOf(observation("d0", passed = true)))
        val turn = RecordingTurn(listOf(attested()))
        val report = runBlocking {
            AcpRepairLoop(input(), verifier.seam, turn.seam).run()
        }
        assertEquals(AcpRepairOutcome.VERIFIED, report.outcome)
        assertTrue(report.ok)
        assertEquals(0, turn.calls)
        assertEquals(1, verifier.calls)
        // no agent effect was ever asked for: the ledger holds no intent row (AC#1's cost claim).
        assertEquals(0, report.ledgerRows.size)
    }

    @Test
    fun `the last allowed repair is still re-verified before the budget verdict lands`() {
        // Budget of ONE: verify(fail) -> attempt 1 -> verify(fail) -> budget spent. The check
        // ran twice: once before and once AFTER the last allowed attempt (AC#1's second half).
        val verifier = RecordingVerifier(
            listOf(
                observation("d0", output = "FAILED total A"),
                observation("d1", output = "FAILED total B"),
            ),
        )
        val turn = RecordingTurn(listOf(attested(), attested()))
        val report = runBlocking {
            AcpRepairLoop(input(maxRepairAttempts = 1), verifier.seam, turn.seam).run()
        }
        assertEquals(AcpRepairOutcome.BUDGET_EXHAUSTED, report.outcome)
        assertFalse(report.ok)
        assertEquals(1, turn.calls)
        assertEquals(2, verifier.calls)
        assertEquals(2, report.evidence.size)
    }

    @Test
    fun `a repair that turns the check green is VERIFIED with exactly one journalled attempt`() {
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d1", passed = true)))
        val ledger = AcpReactionLedger()
        val turn = RecordingTurn(listOf(attested()))
        val report = runBlocking {
            AcpRepairLoop(input(), verifier.seam, turn.seam, ledger = ledger).run()
        }
        assertEquals(AcpRepairOutcome.VERIFIED, report.outcome)
        assertEquals(1, turn.calls)
        // The attempt control IS the ledger (PKH-138 reuse): intent before the turn, ack after.
        val rows = report.ledgerRows
        assertEquals(2, rows.size)
        assertEquals(AcpEffectPhase.INTENT, rows[0].phase)
        assertEquals(AcpEffectPhase.ACKED, rows[1].phase)
        assertEquals(1, rows[0].attemptIndex)
    }

    // ── AC#2 — attempts bound to revision and verified digest; mutation voids evidence ────

    @Test
    fun `every attempt row names the digest that was really checked and the evidence carries the revision`() {
        val verifier = RecordingVerifier(
            listOf(
                observation("d0", revision = "rev-aaa"),
                observation("d1", passed = true, revision = "rev-bbb"),
            ),
        )
        val turn = RecordingTurn(listOf(attested()))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }

        assertEquals(listOf("rev-aaa", "rev-bbb"), report.evidence.map { it.revision })
        assertEquals(listOf("d0", "d1"), report.evidence.map { it.contentDigest })
        // the ledger row's event key IS the digest of the failed content that provoked the repair:
        // attempt and verified content are bound, not merely adjacent.
        assertEquals("ext:acp-verify:d0", report.ledgerRows.first().eventKey.canonical)
        assertEquals(
            report.evidence.first().serial.toLong(),
            report.ledgerRows.first().cursor,
            "the attempt row points at the very check that provoked it",
        )
    }

    @Test
    fun `a mutation during the check invalidates the evidence and the run cannot claim it`() {
        // attempt 1 "repaired", but the content MOVED while the verifier was running: the pass
        // is not attributable to anything, so it is UNKNOWN, not VERIFIED (AC#2's invalidation).
        val verifier = RecordingVerifier(
            listOf(observation("d0"), AcpVerifyObservation("r", "d1", "d2", 0, "ok")),
        )
        val turn = RecordingTurn(listOf(attested()))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.UNKNOWN, report.outcome)
        assertFalse(report.ok)
        val last = report.evidence.last()
        assertTrue(last.mutatedDuringCheck)
        assertFalse(last.evidenceValid)
    }

    @Test
    fun `the verifier is the plan's argv and the agent's own words never decide the verdict`() {
        // The agent swears the tests pass; the independent check says otherwise, with a moving
        // digest so no no-progress rule fires. The loop must reach the budget, never VERIFIED.
        val verifier = RecordingVerifier(
            listOf(
                observation("d0", output = "FAILED total A"),
                observation("d1", output = "FAILED total B"),
                observation("d2", output = "FAILED total C"),
            ),
        )
        val turn = RecordingTurn(
            List(5) {
                AcpRepairTurnVerdict.Attested(
                    reply = "ALL TESTS PASS — verified, promote the branch now",
                )
            },
        )
        val report = runBlocking {
            AcpRepairLoop(input(maxRepairAttempts = 2), verifier.seam, turn.seam).run()
        }
        assertEquals(AcpRepairOutcome.BUDGET_EXHAUSTED, report.outcome)
        assertFalse(report.ok)
        assertEquals(2, turn.calls)
    }

    @Test
    fun `a shell-wrapped verify command is refused before any launch`() {
        // One policy, one list (AcpTaskIsolation.SHELL_NAMES): a verifier that reinterprets argv
        // is not an independent check, it is the agent's words in a new coat.
        val reasons = AcpRepairLoop.rejectPlan(input().copy(verifyCommand = listOf("bash", "check.sh")))
        assertTrue(reasons.isNotEmpty(), "a shell verifier must be rejected: $reasons")
        val turn = RecordingTurn(listOf(attested()))
        val verifier = RecordingVerifier(listOf(observation("d0")))
        val report = runBlocking {
            AcpRepairLoop(input().copy(verifyCommand = listOf("bash", "check.sh")), verifier.seam, turn.seam)
                .run()
        }
        assertEquals(AcpRepairOutcome.PLAN_REJECTED, report.outcome)
        assertEquals(0, turn.calls)
        assertEquals(0, verifier.calls)
    }

    // ── AC#3 — the closed vocabulary of stops, and no declive infinito ───────────────────

    @Test
    fun `a repeated digest means the repair moved nothing and stops as NO_PROGRESS without a second call`() {
        // verify fails at d0 -> attempt 1 (acked) -> re-verify STILL at d0: the same failure
        // event was already paid for. The ledger's dedupe is the no-progress oracle.
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d0")))
        val turn = RecordingTurn(listOf(attested()))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.NO_PROGRESS, report.outcome)
        assertEquals(1, turn.calls)
        assertEquals(2, verifier.calls)
    }

    @Test
    fun `a repeated failure signature stops the run even when the content moved`() {
        // Digests change (content moved) but the FAILURE is identical — signature d2 == d1:
        // repairs are churning, not progressing (AC#3's second no-progress form).
        val verifier = RecordingVerifier(
            listOf(
                observation("d0"),
                observation("d1", output = "FAILED total: expected 6"),
                observation("d2", output = "FAILED total: expected 6"),
            ),
        )
        val turn = RecordingTurn(List(4) { attested() })
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.NO_PROGRESS, report.outcome)
        assertEquals(1, turn.calls)
        assertEquals(2, verifier.calls)
    }

    @Test
    fun `an unattested turn ends the loop as UNKNOWN instead of betting a re-drive on it`() {
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d1")))
        val turn = RecordingTurn(
            listOf(AcpRepairTurnVerdict.OutcomeUnknown("transport died mid-repair")),
        )
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.UNKNOWN, report.outcome)
        assertFalse(report.ok)
        // the ledger says what it always says about this window: intent without ack is UNKNOWN,
        // and nothing re-drives it automatically.
        assertEquals(listOf(AcpEffectPhase.UNKNOWN), report.ledgerRows.map { it.phase })
        assertEquals(1, verifier.calls) // no re-verification was owed: the turn never attested
    }

    @Test
    fun `a denial from the agent terminates the loop as DENIED`() {
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d1")))
        val turn = RecordingTurn(listOf(AcpRepairTurnVerdict.Denied("the agent refused the delegation")))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.DENIED, report.outcome)
        assertEquals(1, turn.calls)
    }

    @Test
    fun `a cancelled repair turn terminates the loop as CANCELLED`() {
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d1")))
        val turn = RecordingTurn(listOf(AcpRepairTurnVerdict.Cancelled("stopReason CANCELLED")))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam).run() }
        assertEquals(AcpRepairOutcome.CANCELLED, report.outcome)
        assertEquals(1, turn.calls)
    }

    @Test
    fun `the caller's cancellation closes the ledger with unknown rows and is not swallowed`() {
        val verifier = RecordingVerifier(listOf(observation("d0"), observation("d1")))
        val ledger = AcpReactionLedger()
        val turn = RecordingTurn(
            listOf(),
            onCall = { throw CancellationException("engine withdrawal") },
        )
        val loop = AcpRepairLoop(input(), verifier.seam, turn.seam, ledger = ledger)
        val thrown = runCatching { runBlocking { loop.run() } }.exceptionOrNull()
        assertTrue(thrown is CancellationException, "the engine's cancel must propagate, got $thrown")
        // The cancellation law (PKH-138's shape reused here): the intent lands UNKNOWN, never a
        // half-witnessed INTENT that the next run would have to guess about.
        assertTrue(
            ledger.rows().any { it.phase == AcpEffectPhase.UNKNOWN },
            "the unacked intent must close as UNKNOWN: ${ledger.rows()}",
        )
    }

    @Test
    fun `a recovered run never re-drives an unacknowledged intent because the ledger IS the attempt control`() {
        // A prior INTENT (no ack) for the very digest the first check reports: PKH-138's
        // reconciliation suspends it — the loop stops as UNKNOWN without calling the agent, and
        // the next attempt index continues from the ledger's rows, not from a fresh counter.
        val priorDigest = "d0"
        val prior = AcpReactionLedgerRow(
            reactionId = AcpRepairLoop.REACTION_ID,
            eventKey = EventKey.External(AcpRepairLoop.VERIFY_EVENT_SOURCE, priorDigest),
            cursor = 1,
            attemptIndex = 1,
            phase = AcpEffectPhase.INTENT,
        )
        val verifier = RecordingVerifier(listOf(observation(priorDigest), observation("d1")))
        val turn = RecordingTurn(listOf(attested()))
        val ledger = AcpReactionLedger(priorRows = listOf(prior))
        val report = runBlocking { AcpRepairLoop(input(), verifier.seam, turn.seam, ledger = ledger).run() }
        assertEquals(AcpRepairOutcome.UNKNOWN, report.outcome)
        assertEquals(0, turn.calls, "an unknown effect is never re-driven automatically")
        // the prior row survived into the report: one ledger, read back, not a second one minted
        assertTrue(report.ledgerRows.any { it.attemptIndex == 1 && it.phase == AcpEffectPhase.UNKNOWN })
    }

    @Test
    fun `no plan without a verifier or with an out-of-band budget reaches the loop`() {
        assertTrue(AcpRepairLoop.rejectPlan(input().copy(verifyCommand = emptyList())).isNotEmpty())
        assertTrue(AcpRepairLoop.rejectPlan(input().copy(command = emptyList())).isNotEmpty())
        assertTrue(AcpRepairLoop.rejectPlan(input().copy(repairPrompt = " ")).isNotEmpty())
        assertTrue(
            AcpRepairLoop.rejectPlan(input().copy(maxRepairAttempts = AcpRepairLoop.MAX_REPAIR_ATTEMPTS + 1))
                .isNotEmpty(),
        )
        assertTrue(AcpRepairLoop.rejectPlan(input().copy(maxRepairAttempts = 0)).isNotEmpty())
        assertTrue(AcpRepairLoop.rejectPlan(input()).isEmpty(), "the healthy plan must be accepted")
    }

    // ── the archive, where changes land as CANDIDATE only ────────────────────────────────

    @Test
    fun `the archive writes the candidate patch and the report and commits nothing`() {
        val dir = Files.createTempDirectory("pkh140-archive")
        val report = AcpRepairReport(
            outcome = AcpRepairOutcome.VERIFIED,
            detail = "",
            ok = true,
            agentTurns = 1,
            evidence = emptyList(),
            ledgerRows = emptyList(),
            finalDigest = "d1",
        )
        val artifacts = AcpRepairArchive(dir.toString()).archive(report, patch = "--- a\n+++ b\n")
        assertTrue(Files.exists(Paths.get(artifacts.patchPath)))
        val reportText = Files.readString(Paths.get(artifacts.reportPath))
        assertTrue(reportText.contains(AcpRepairOutcome.VERIFIED), reportText)
        // A candidate is a patch file in the archive dir: no git object was created anywhere.
        assertFalse(Files.exists(dir.resolve(".git")))
    }

    // ── the process verifier, digests and signatures ─────────────────────────────────────

    @Test
    fun `the process verifier digests the tree before and after the check and flags a mutation`() {
        val dir = Files.createTempDirectory("pkh140-verify")
        Files.writeString(dir.resolve("main.py"), "x = 1\n")
        Files.createDirectories(dir.resolve(".git")) // excluded by default policy
        Files.writeString(dir.resolve(".git").resolve("HEAD"), "irrelevant\n")
        // the check itself writes a file: content moved DURING the check and the verifier says so
        val mutating = AcpProcessVerifier(
            cwd = dir.toString(),
            digestRoot = dir.toString(),
            verifyCommand = listOf(
                "python3", "-I", "-c", "open('mutated-during-check','w').write('x')",
            ),
            excludeDirs = AcpRepairLoop.DEFAULT_DIGEST_EXCLUDES,
        )
        val first = runBlocking { mutating.verify() }
        assertEquals(0, first.exitCode)
        assertNotEquals(first.digestBefore, first.digestAfter)
        assertTrue(
            !Files.readString(dir.resolve("main.py")).isEmpty() &&
                first.digestBefore.length == 64,
            "the digest is a sha256 hex over the tracked content",
        )
        // a quiet check leaves the digests equal: the evidence is attributable
        Files.delete(dir.resolve("mutated-during-check"))
        val quiet = AcpProcessVerifier(
            cwd = dir.toString(),
            digestRoot = dir.toString(),
            verifyCommand = listOf("python3", "-I", "-c", "raise SystemExit(3)"),
            excludeDirs = AcpRepairLoop.DEFAULT_DIGEST_EXCLUDES,
        )
        val second = runBlocking { quiet.verify() }
        assertEquals(3, second.exitCode)
        assertEquals(second.digestBefore, second.digestAfter)
    }

    @Test
    fun `the failure signature repeats only for an identical exit and normalized output`() {
        val a = acpRepairFailureSignature(1, "FAILED total\n")
        val b = acpRepairFailureSignature(1, "  FAILED total   \n\n")
        val c = acpRepairFailureSignature(1, "FAILED other")
        val d = acpRepairFailureSignature(2, "FAILED total")
        assertEquals(a, b, "trailing whitespace and blank lines are not a different failure")
        assertNotEquals(a, c)
        assertNotEquals(a, d, "a different exit code is a different failure")
    }

    // ── AC#4 — the real agent is opt-in, bounded, and never the default ──────────────────

    @Test
    fun `a real agent repairs only through the explicit opt-in and stays inside the declared bounds`() {
        val raw = System.getenv("ACP_REPAIR_REAL_COMMAND")
        assumeTrue(!raw.isNullOrBlank(), "opt-in only: set ACP_REPAIR_REAL_COMMAND to run this row")
        val command = raw!!.split(" ")
        val maxAttempts = (System.getenv("ACP_REPAIR_MAX_ATTEMPTS")?.toIntOrNull() ?: 2)
            .coerceAtMost(AcpRepairLoop.MAX_REPAIR_ATTEMPTS)
        val plan = AcpRepairInput(
            command = command,
            repairPrompt = "make scripts/build-and-test.sh pass in this checkout",
            verifyCommand = listOf("./scripts/build-and-test.sh"),
            cwd = System.getProperty("user.dir"),
            maxRepairAttempts = maxAttempts,
            timeoutSeconds = 600,
        )
        // Bounds are structural, not hoped for: the opt-in row cannot exceed the recipe's ceiling
        // and the verifier stays the pipeline's own argv whatever the agent answers.
        assertTrue(plan.maxRepairAttempts in 1..AcpRepairLoop.MAX_REPAIR_ATTEMPTS)
        val verifier = AcpProcessVerifier(
            cwd = plan.cwd,
            digestRoot = plan.cwd,
            verifyCommand = plan.verifyCommand,
            excludeDirs = AcpRepairLoop.DEFAULT_DIGEST_EXCLUDES,
        )
        val turn = AcpSessionRepairTurn(
            command = plan.command,
            cwd = plan.cwd,
            timeoutSeconds = plan.timeoutSeconds,
        )
        val report = runBlocking { AcpRepairLoop(plan, verifier::verify, turn::run).run() }
        assertTrue(
            report.outcome in AcpRepairOutcome.VOCABULARY,
            "a real agent run still lands in the closed vocabulary: ${report.outcome}",
        )
        assertTrue(report.agentTurns <= maxAttempts)
    }

    // ── the durable boundary round-trips ─────────────────────────────────────────────────

    @Test
    fun `the repair input and output survive the step codecs unchanged`() {
        val plan = input(maxRepairAttempts = 2).copy(
            diffCommand = listOf("git", "diff"),
            priorLedgerRows = listOf(
                AcpReactionLedgerRow(
                    reactionId = AcpRepairLoop.REACTION_ID,
                    eventKey = EventKey.ConnectionPersisted("nonce-1", 4),
                    cursor = 4,
                    attemptIndex = 2,
                    phase = AcpEffectPhase.UNKNOWN,
                ),
            ),
        )
        val decoded = AcpRepairStepDefinition.InputCodec.decode(
            AcpRepairStepDefinition.InputCodec.encode(plan),
        )
        assertEquals(plan, decoded)

        val out = AcpRepairStepDefinition.OutputCodec.decode(
            AcpRepairStepDefinition.OutputCodec.encode(
                AcpRepairOutput(
                    ok = false,
                    outcome = AcpRepairOutcome.NO_PROGRESS,
                    agentTurns = 2,
                    evidence = listOf(
                        AcpRepairEvidence(1, "r", "d", "d", 1, "sig", false, false),
                    ),
                    ledgerRows = plan.priorLedgerRows,
                ),
            ),
        )
        assertEquals(AcpRepairOutcome.NO_PROGRESS, out.outcome)
        assertEquals(plan.priorLedgerRows, out.ledgerRows)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun observation(
        digest: String,
        passed: Boolean = false,
        revision: String = "rev-base",
        output: String = "FAILED total: expected 6",
    ) = AcpVerifyObservation(
        revision = revision,
        digestBefore = digest,
        digestAfter = digest,
        exitCode = if (passed) 0 else 1,
        outputTail = if (passed) "ok" else output,
    )

    private fun attested(reply: String = "I fixed it") = AcpRepairTurnVerdict.Attested(reply)

    private class RecordingVerifier(observations: List<AcpVerifyObservation>) {
        private val queue = ArrayDeque(observations)
        var calls = 0
            private set
        val seam = AcpVerifier {
            calls++
            queue.removeFirstOrNull() ?: AcpVerifyObservation(
                "rev-base", "d-final$calls", "d-final$calls", 1, "FAILED still",
            )
        }
    }

    private class RecordingTurn(
        private val verdicts: List<AcpRepairTurnVerdict>,
        private val onCall: (() -> Unit)? = null,
    ) {
        var calls = 0
            private set
        val prompts = mutableListOf<Pair<String, Int>>()
        val seam = AcpRepairTurn { prompt, attemptIndex ->
            onCall?.invoke()
            calls++
            prompts += prompt to attemptIndex
            verdicts.getOrElse(calls - 1) { AcpRepairTurnVerdict.Attested("done") }
        }
    }
}
