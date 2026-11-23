package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC#4 of PKH-140, end to end: a REPRODUCIBLE broken project on disk, a deterministic test
 * agent that speaks ACP over a pipe, the pipeline's own argv as the verifier, and the whole
 * recipe driven through the production seams ([AcpRepairStepDefinition.execute]) — process
 * verifier, session repair turn, candidate archive. No engine, no mock: the check is really
 * run and its exit code really decides.
 *
 * The broken project is a two-file python tree (a wrong `main.py` and a `check.py` that only
 * exits 0 when it is right), committed to a git repo so the CANDIDATE patch is a real diff.
 * The agent's words are confident in every mode — a repair, a lie, a refusal — and in every
 * mode the verdict comes from the check, never from the reply (AC#3's no-success-by-text law).
 */
class AcpRepairFixtureTest {

    /**
     * Resuelve el ejecutable git auténtico: recorre el PATH y salta cualquier shim que declare
     * la marca `git-control` en su propio texto. Un shim de política de commits no puede mandar
     * sobre el repositorio desechable del fixture; en una máquina sin shim esto es simplemente
     * el primer `git` del PATH.
     */
    private val realGit: String by lazy {
        val dirs = System.getenv("PATH")?.split(java.io.File.pathSeparatorChar).orEmpty()
        dirs.asSequence()
            .map { java.nio.file.Paths.get(it, "git") }
            .filter { Files.isRegularFile(it) && Files.isExecutable(it) }
            .firstOrNull { !String(Files.readAllBytes(it), Charsets.ISO_8859_1).contains("git-control") }
            ?.toString()
            ?: "git"
    }

    private fun agent(mode: String): List<String> {
        val url = checkNotNull(javaClass.getResource("/acp_repair_fixture_agent.py")) {
            "the repair fixture agent is missing from the test resources"
        }
        return listOf("python3", "-I", Paths.get(url.toURI()).toString(), mode)
    }

    /** The broken project, committed: HEAD carries the bug, the tree starts clean. */
    private fun brokenProject(): Path {
        val dir = Files.createTempDirectory("pkh140-project")
        Files.writeString(dir.resolve("main.py"), "def total(xs):\n    return sum(xs) - 1\n")
        Files.writeString(
            dir.resolve("check.py"),
            """
            import sys
            sys.path.insert(0, ".")
            from main import total
            got = total([1, 2, 3])
            if got != 6:
                print("FAILED total: expected 6, got %r" % got)
                sys.exit(1)
            print("ok")
            """.trimIndent() + "\n",
        )
        // El git de verdad: la máquina puede esconder tras el nombre `git` un shim de política
        // que prohíbe comitear en un repositorio desechable. El fixture necesita git crudo, no
        // la firma del agente sobre su propio proyecto de prueba; el shim se delata por la
        // marca que él mismo escribe, y en una máquina limpia el primer PATH ya es git.
        fun git(vararg args: String) {
            val pb = ProcessBuilder(listOf(realGit, "-C", dir.toString()) + args)
                .redirectErrorStream(true)
            // Config global aislada: las ganchos de política del usuario no manda aquí.
            pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            pb.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
            val p = pb.start()
            val out = String(p.inputStream.readBytes())
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git timed out: ${args.toList()}")
            assertEquals(0, p.exitValue(), "git failed: ${args.toList()}: $out")
        }
        git("init", "-q")
        git("-c", "user.email=test@pipelinek.local", "-c", "user.name=pkh140", "add", "-A")
        git(
            "-c", "user.email=test@pipelinek.local", "-c", "user.name=pkh140",
            "-c", "commit.gpgsign=false", "commit", "-qm", "broken base",
        )
        return dir
    }

    private fun repairInput(dir: Path, mode: String, maxAttempts: Int = 3) = AcpRepairInput(
        command = agent(mode),
        repairPrompt = "make check.py pass in this project",
        verifyCommand = listOf("python3", "-I", "check.py"),
        cwd = dir.toString(),
        digestRoot = dir.toString(),
        diffCommand = listOf("git", "-C", dir.toString(), "diff", "--", "main.py"),
        revisionCommand = listOf("git", "-C", dir.toString(), "rev-parse", "HEAD"),
        archiveDir = dir.resolve("../pkh140-archive-${dir.fileName}-$mode").toString(),
        maxRepairAttempts = maxAttempts,
        timeoutSeconds = 60,
    )

    @Test
    fun `the broken project fixture — the test agent repairs, the check turns green, and diff and report are archived`() {
        val dir = brokenProject()
        val out = runBlocking { AcpRepairStepDefinition.execute(repairInput(dir, "fix")) }

        assertTrue(out.ok, "the fixture must verify green, got ${out.outcome}: ${out.detail}")
        assertEquals(AcpRepairOutcome.VERIFIED, out.outcome)
        assertEquals(1, out.agentTurns)
        // AC#1 both halves, on real processes: verify before, verify after the repair.
        assertEquals(2, out.evidence.size)
        assertFalse(out.evidence[0].passed)
        assertTrue(out.evidence[1].passed)
        // AC#2: the attempt is bound to the base revision and to the digest really checked.
        assertTrue(out.evidence[0].revision.isNotBlank(), "git rev-parse must name the base")
        assertEquals(
            "ext:acp-verify:" + out.evidence[0].contentDigest,
            out.ledgerRows.first().eventKey.canonical,
        )
        assertEquals(listOf(AcpEffectPhase.INTENT, AcpEffectPhase.ACKED), out.ledgerRows.map { it.phase })
        // AC#4: the candidate is ARCHIVED — a patch file and a report — and nothing was committed:
        // HEAD still carries the broken base while the working tree carries the fix.
        assertTrue(Files.exists(Paths.get(out.patchPath)), "the patch must be archived")
        val patch = Files.readString(Paths.get(out.patchPath))
        assertTrue(patch.contains("return sum(xs)"), "the diff carries the repair: $patch")
        val report = Files.readString(Paths.get(out.reportPath))
        assertTrue(report.contains(AcpRepairOutcome.VERIFIED), report)
        val porcelain = ProcessBuilder(
            "git", "-C", dir.toString(), "status", "--porcelain",
        ).start().let { val o = it.inputStream.readBytes(); it.waitFor(10, TimeUnit.SECONDS); String(o) }
        assertTrue(porcelain.contains("main.py"), "the change stayed uncommitted: $porcelain")
    }

    @Test
    fun `the agent that only claims success is stopped after one attempt, without a second ask`() {
        val dir = brokenProject()
        val out = runBlocking { AcpRepairStepDefinition.execute(repairInput(dir, "noop")) }
        // The reply swears the tests pass; the digest says nothing moved. One attempt was paid
        // for the failure event, and the ledger refuses to pay twice (AC#1 + AC#3 no-progress).
        assertEquals(AcpRepairOutcome.NO_PROGRESS, out.outcome)
        assertFalse(out.ok)
        assertEquals(1, out.agentTurns)
        assertEquals(2, out.evidence.size)
        assertEquals(out.evidence[0].contentDigest, out.evidence[1].contentDigest)
    }

    @Test
    fun `a refused repair ends the run as DENIED and the project stays exactly as broken`() {
        val dir = brokenProject()
        val out = runBlocking { AcpRepairStepDefinition.execute(repairInput(dir, "refuse")) }
        assertEquals(AcpRepairOutcome.DENIED, out.outcome)
        assertFalse(out.ok)
        assertEquals(1, out.agentTurns)
        assertEquals(
            "def total(xs):\n    return sum(xs) - 1\n",
            Files.readString(dir.resolve("main.py")),
            "a denied attempt must not have touched the content",
        )
    }

    @Test
    fun `a cancelled agent turn ends the run as CANCELLED, never as a retry`() {
        val dir = brokenProject()
        val out = runBlocking { AcpRepairStepDefinition.execute(repairInput(dir, "cancel")) }
        assertEquals(AcpRepairOutcome.CANCELLED, out.outcome)
        assertFalse(out.ok)
        assertEquals(1, out.agentTurns)
    }

    @Test
    fun `an agent that dies mid-exchange lands its attempt UNKNOWN and is never re-driven`() {
        val dir = brokenProject()
        val out = runBlocking { AcpRepairStepDefinition.execute(repairInput(dir, "die")) }
        // transport died inside the effect: whether files moved is unknowable from here, so the
        // ledger row stops at UNKNOWN and the loop STOPS — no automatic second drive (AC#3).
        assertEquals(AcpRepairOutcome.UNKNOWN, out.outcome)
        assertFalse(out.ok)
        assertEquals(1, out.agentTurns)
        assertEquals(listOf(AcpEffectPhase.UNKNOWN), out.ledgerRows.map { it.phase })
        assertEquals(1, out.evidence.size, "an unattested turn owes no re-verification")
    }
}
