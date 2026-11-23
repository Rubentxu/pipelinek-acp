package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The `agenticRepair` step: the PKH-140 recipe wired to production seams — the repair slot is
 * the COMPOSED SESSION of PKH-135 (one `acpSession`-shaped exchange per attempt, the recipe
 * lives over it), the verify slot is the plan's own argv run as a subprocess, and every change
 * the agent makes is archived as a CANDIDATE patch plus a JSON report. Nothing is committed,
 * nothing is promoted: the step's [AcpRepairOutput] is the verdict and the archive is the offer.
 *
 * Like the other steps of this plugin the handler never throws to signal a step failure: every
 * stop lands as [AcpRepairOutcome] with [AcpRepairOutput.ok] true for exactly one of them.
 */
object AcpRepairStepDefinition : StepDefinition<AcpRepairInput, AcpRepairOutput> {

    val KEY: PluginStepId = PluginStepId("pipelinek.acp.repair")

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal object InputCodec : StepCodec<AcpRepairInput> {
        override fun encode(value: AcpRepairInput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpRepairInput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpRepairInput =
            json.decodeFromString(AcpRepairInput.serializer(), encoded.value)
    }

    internal object OutputCodec : StepCodec<AcpRepairOutput> {
        override fun encode(value: AcpRepairOutput): EncodedStepValue =
            EncodedStepValue(json.encodeToString(AcpRepairOutput.serializer(), value))

        override fun decode(encoded: EncodedStepValue): AcpRepairOutput =
            json.decodeFromString(AcpRepairOutput.serializer(), encoded.value)
    }

    override val contract: StepContract<AcpRepairInput, AcpRepairOutput> by lazy {
        StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "agenticRepair",
                jenkinsDivergence = "Jenkins has no ACP client, and a repair loop whose verdict " +
                    "is an independent check rather than the worker's own words belongs to no " +
                    "pipeline language the product predates",
                configRef = "",
                pluginId = AcpPluginDeclaration.CONTRIBUTOR_ID,
                pluginVersion = AcpPluginDeclaration.declaredVersion(),
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
                // Repair is a conversation with a third party over files that exist nowhere else:
                // re-executing it after a crash is a second repair the run never asked for, and
                // the honest recovery route is the ledger (priorLedgerRows), not a replay.
                replayPolicy = ReplayPolicy.NEVER,
            ),
            inputCodec = InputCodec,
            outputCodec = OutputCodec,
            // The repair turns are session exchanges: their lifecycle facts ride PKH-136's
            // published seam like every other exchange this plugin runs.
            requiredCapabilities = setOf(PLUGIN_EVENT_EMISSION_CAPABILITY),
        )
    }

    override val handler = StepHandler<AcpRepairInput, AcpRepairOutput> { input, context ->
        val emission: PluginEventEmission =
            context.capabilities.get(PLUGIN_EVENT_EMISSION_CAPABILITY, PluginEventEmission::class)
        withObservationPublisher(emission, AcpObservationPublisher.exchangeRefOf(context)) { publisher ->
            execute(input)
        }
    }

    /**
     * The production route of the recipe: loop with the process verifier and the session repair
     * turn, then the candidate archive. Internal so the fixture rows drive the SAME wiring the
     * handler does, without the engine's capability bag in the test's way.
     */
    internal suspend fun execute(input: AcpRepairInput): AcpRepairOutput {
        val cwd = input.cwd.ifBlank { System.getProperty("user.dir") }
        val loop = AcpRepairLoop(
            input = input,
            verifier = AcpProcessVerifier(
                cwd = cwd,
                digestRoot = input.digestRoot.ifBlank { cwd },
                verifyCommand = input.verifyCommand,
                excludeDirs = input.digestExcludeDirs.toSet(),
                revisionCommand = input.revisionCommand,
            )::verify,
            repairTurn = AcpSessionRepairTurn(
                command = input.command,
                cwd = cwd,
                timeoutSeconds = input.timeoutSeconds,
            )::run,
        )
        val report = loop.run()
        // The patch is only worth producing once an attempt moved content; a green-first run has
        // no candidate, and an unattested turn must not smuggle one in anyway.
        val patch = if (report.agentTurns > 0 && input.diffCommand.isNotEmpty()) {
            acpRunArgv(cwd, input.diffCommand).text
        } else {
            ""
        }
        val artifacts = if (input.archiveDir.isNotBlank()) {
            AcpRepairArchive(input.archiveDir).archive(report, patch)
        } else {
            AcpRepairArtifacts(reportPath = "", patchPath = "")
        }
        return AcpRepairOutput(
            ok = report.ok,
            outcome = report.outcome,
            detail = report.detail,
            agentTurns = report.agentTurns,
            evidence = report.evidence,
            ledgerRows = report.ledgerRows,
            finalDigest = report.finalDigest,
            patchPath = artifacts.patchPath,
            reportPath = artifacts.reportPath,
        )
    }
}

/** What the recipe produced, in durable vocabulary: the verdict, the evidence and the candidate. */
@kotlinx.serialization.Serializable
data class AcpRepairOutput(
    /** VERIFIED and nothing else (AC#3): no other outcome may claim the build is fixed. */
    val ok: Boolean,
    /** one of [AcpRepairOutcome.VOCABULARY], always set. */
    val outcome: String = "",
    val detail: String = "",
    /** repair turns THIS run invoked, read off the ledger rows it wrote. */
    val agentTurns: Int = 0,
    /** one row per check, in order: the first is the pre-agent verification (AC#1). */
    val evidence: List<AcpRepairEvidence> = emptyList(),
    /** the PKH-138 intent/ack/unknown rows of this run's attempts, in write order. */
    val ledgerRows: List<AcpReactionLedgerRow> = emptyList(),
    val finalDigest: String = "",
    /** where the candidate patch was archived ("" when nothing was archived). */
    val patchPath: String = "",
    /** where the JSON report was archived ("" when nothing was archived). */
    val reportPath: String = "",
)

/**
 * The production verify slot: the plan's argv as a subprocess, with the verified content
 * digested BEFORE and AFTER the check. The digests are the invalidation oracle (AC#2): content
 * that moved while the check ran means the check said nothing about the content it started with.
 */
class AcpProcessVerifier(
    private val cwd: String,
    private val digestRoot: String,
    private val verifyCommand: List<String>,
    private val excludeDirs: Set<String>,
    private val revisionCommand: List<String> = emptyList(),
    private val checkTimeoutSeconds: Long = 300,
) {
    suspend fun verify(): AcpVerifyObservation {
        val revision = if (revisionCommand.isNotEmpty()) {
            acpRunArgv(cwd, revisionCommand, checkTimeoutSeconds).text.trim()
        } else {
            ""
        }
        val root = Paths.get(digestRoot)
        val digestBefore = acpTreeDigest(root, excludeDirs)
        val check = acpRunArgv(cwd, verifyCommand, checkTimeoutSeconds)
        return AcpVerifyObservation(
            revision = revision,
            digestBefore = digestBefore,
            digestAfter = acpTreeDigest(root, excludeDirs),
            exitCode = check.exitCode,
            outputTail = check.text,
        )
    }
}

/**
 * The production repair slot: one attempt as one composed-session exchange (the recipe-over-
 * session AC#4 names). The session's typed answer maps onto the turn verdicts; the agent's
 * REPLY never maps onto the run's outcome — only the verifier may say VERIFIED.
 */
class AcpSessionRepairTurn(
    private val command: List<String>,
    private val cwd: String,
    private val timeoutSeconds: Long,
    private val pluginVersion: String = runCatching { AcpPluginDeclaration.declaredVersion() }
        .getOrDefault("0.0.0-dev"),
) {
    suspend fun run(prompt: String, attemptIndex: Int): AcpRepairTurnVerdict {
        val out = AcpSessionConnection(pluginVersion).exchange(
            AcpSessionInput(
                command = command,
                turns = listOf(AcpTurnInput(text = prompt)),
                cwd = cwd,
                timeoutSeconds = timeoutSeconds,
            ),
        )
        return when {
            !out.ok && out.outcome == AcpSessionOutcome.CAPABILITY_MISSING ->
                AcpRepairTurnVerdict.Denied("session capability refused at attempt $attemptIndex: ${out.detail}")
            !out.ok && out.outcome == AcpSessionOutcome.SESSION_REFUSED ->
                AcpRepairTurnVerdict.Denied("the session refused the repair at attempt $attemptIndex: ${out.detail}")
            !out.ok ->
                // transport, budget or unknown: whether the files were half-touched is not
                // knowable from here — the PKH-138 honest unknown, never an automatic re-drive.
                AcpRepairTurnVerdict.OutcomeUnknown(
                    "the repair exchange at attempt $attemptIndex did not complete: " +
                        "${out.failureKind} ${out.detail}",
                )
            out.turns.isEmpty() ->
                AcpRepairTurnVerdict.OutcomeUnknown("the session completed with no turn: attempt $attemptIndex")
            out.turns.last().stopReason == "CANCELLED" ->
                AcpRepairTurnVerdict.Cancelled("the agent turn ended cancelled at attempt $attemptIndex")
            out.turns.last().stopReason == "REFUSAL" ->
                AcpRepairTurnVerdict.Denied("the agent refused the repair at attempt $attemptIndex")
            else -> AcpRepairTurnVerdict.Attested(out.turns.last().reply, out.turns.last().stopReason)
        }
    }
}

/**
 * The candidate archive (AC#4): the report as JSON and the patch as a file, under [dir]. A
 * candidate is exactly this — bytes on disk for a human or a later step to weigh. The archive
 * commits nothing and promotes nothing.
 */
class AcpRepairArchive(private val dir: String) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun archive(report: AcpRepairReport, patch: String): AcpRepairArtifacts {
        val root = Paths.get(dir)
        Files.createDirectories(root)
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
            .format(java.time.LocalDateTime.now())
        val reportPath = root.resolve("repair-report-$stamp.json")
        Files.writeString(reportPath, json.encodeToString(AcpRepairReportDocument.serializer(), report.toDocument()))
        val patchPath = if (patch.isBlank()) "" else {
            val file = root.resolve("repair-candidate-$stamp.patch")
            Files.writeString(file, patch)
            file.toString()
        }
        return AcpRepairArtifacts(reportPath = reportPath.toString(), patchPath = patchPath)
    }
}

/** The durable document the archive writes: the report, encodable without transient seams. */
@kotlinx.serialization.Serializable
data class AcpRepairReportDocument(
    val outcome: String,
    val detail: String,
    val ok: Boolean,
    val agentTurns: Int,
    val finalDigest: String,
    val evidence: List<AcpRepairEvidence>,
    val ledgerRows: List<AcpReactionLedgerRow>,
)

internal fun AcpRepairReport.toDocument() = AcpRepairReportDocument(
    outcome = outcome,
    detail = detail,
    ok = ok,
    agentTurns = agentTurns,
    finalDigest = finalDigest,
    evidence = evidence,
    ledgerRows = ledgerRows,
)

/** One argv run as the recipe's slots see it: exit code plus combined output, tail-bounded. */
internal data class AcpArgvOutcome(val exitCode: Int, val text: String)

/**
 * Run argv directly — never through a shell (the verifier and diff policies are argv by AC#2's
 * own law) — capture combined output and honour a wall budget. A launch failure answers as exit
 * -1 with the reason in the text: the recipe turns that into typed evidence, it does not throw.
 */
internal suspend fun acpRunArgv(
    cwd: String,
    argv: List<String>,
    timeoutSeconds: Long = 120,
): AcpArgvOutcome = withContext(Dispatchers.IO) {
    if (argv.isEmpty() || argv.first().isBlank()) return@withContext AcpArgvOutcome(-1, "empty argv")
    val process = try {
        ProcessBuilder(argv).directory(File(cwd)).redirectErrorStream(true).start()
    } catch (e: Exception) {
        return@withContext AcpArgvOutcome(-1, "launch failed: ${e.message}")
    }
    val text = AtomicReference("")
    val reader = Thread {
        text.set(
            runCatching { process.inputStream.readBytes().toString(Charsets.UTF_8) }
                .getOrDefault("")
                .take(8_000),
        )
    }
    reader.isDaemon = true
    reader.start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        reader.join(1_000)
        return@withContext AcpArgvOutcome(-1, "${text.get()}\n(timeout after ${timeoutSeconds}s)")
    }
    reader.join(5_000)
    AcpArgvOutcome(process.exitValue(), text.get())
}
