package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail as junitFail
import org.junit.jupiter.api.Test

/**
 * The deadline and lifecycle rules `acpTask` must hold against the REAL wire (PKH-133 AC#2,
 * plus the AC#4 simulated-malicious agent) — measured against the second-implementation
 * task fixture, exactly as the PKH-132 round trip measures the one-shot step.
 *
 * The budget is absolute: it covers launch AND cleanup, so the elapsed assertions bound
 * budget + a generous cleanup allowance rather than trusting a single outcome string.
 * Cancellation is proven two ways: the agent's own witness file (session/cancel reached it
 * before the reap) and the death of the whole process tree (no orphan sleeper survives).
 */
class AcpTaskTimeTest {

    private val fixture: String = Path.of(
        System.getProperty("user.dir"), "src/test/resources/acp_task_fixture_agent.py",
    ).toString()

    private fun agent(vararg args: String): List<String> = listOf("python3", "-I", fixture) + args

    private fun temp(suffix: String): Path = Files.createTempFile("acp-task-", suffix)

    private val defaultDir: Path = Path.of(System.getProperty("java.io.tmpdir"))

    private fun task(
        command: List<String>,
        budget: Long,
        cwd: Path = defaultDir,
        env: Map<String, String>? = null,
    ): AcpExchange = runBlocking {
        AcpConnection(pluginVersion = "0.0.0-test")
            .exchangeTask(
                input = AcpTaskInput(command = command, prompt = "ping", budgetSeconds = budget),
                environment = env ?: AcpTaskIsolation.childEnvironment(null),
                workingDirectory = cwd,
            )
    }

    @Test
    fun `a hung prompt is classified by the budget, not waited on`() {
        val budget = 3L
        val started = System.nanoTime()
        val exchange = task(agent("hang"), budget)
        val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        val refusal = (exchange as AcpExchange.Refused).refusal
        assertTrue(refusal is AcpRefusal.AgentTimeout, "expected a timeout refusal, got $refusal")
        assertEquals(
            AcpTaskOutcome.BUDGET_EXCEEDED,
            AcpTaskOutcome.toOutput(exchange).outcome,
            "AC#3: a budget exhaustion is its own class, never a silent success",
        )
        assertTrue(elapsed in budget..budget + 15, "cleanup ran wild: ${elapsed}s for a ${budget}s budget")
    }

    @Test
    fun `the deadline covers startup - an agent that never answers initialize is a budget refusal`() {
        val exchange = task(agent("hang-init"), 3)
        val refusal = (exchange as AcpExchange.Refused).refusal
        assertTrue(
            refusal is AcpRefusal.AgentTimeout,
            "a startup hang must classify as the budget, got ${refusal.kindToken}",
        )
        assertEquals(AcpTaskOutcome.BUDGET_EXCEEDED, AcpTaskOutcome.toOutput(exchange).outcome)
    }

    @Test
    fun `canceling the caller propagates session-cancel to the agent and rethrows to the engine`() {
        val witness = temp(".cancel")
        val treeDir = Path.of(System.getProperty("java.io.tmpdir"))
        runBlocking {
            val deferred = async(Dispatchers.IO) {
                AcpConnection(pluginVersion = "0.0.0-test").exchangeTask(
                    input = AcpTaskInput(
                        command = agent("cancel", witness.toString()),
                        prompt = "hang until told",
                        budgetSeconds = 120,
                    ),
                    environment = AcpTaskIsolation.childEnvironment(null),
                    workingDirectory = treeDir,
                )
            }
            // Let the turn actually start: initialize, session/new, prompt, first chunk.
            delay(1_500)
            val started = System.nanoTime()
            deferred.cancel()
            try {
                deferred.await()
                junitFail("a cancelled delegation must rethrow the cancellation, not answer")
            } catch (expected: kotlinx.coroutines.CancellationException) {
                // the engine's withdrawal travels on — the step did not fail, the run did
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsed < 20_000, "cleanup after cancel ran wild: ${elapsed}ms")
        }
        val seen = Files.readString(witness)
        assertTrue(
            "CANCEL_SEEN" in seen,
            "session/cancel never reached the agent before the reap: '$seen'",
        )
    }

    @Test
    fun `cleanup kills the whole process tree, not only the agent`() {
        val witness = temp(".tree")
        val pidFile = temp(".pid")
        runBlocking {
            val deferred = async(Dispatchers.IO) {
                AcpConnection(pluginVersion = "0.0.0-test").exchangeTask(
                    input = AcpTaskInput(command = agent("tree", pidFile.toString()), prompt = "x", budgetSeconds = 120),
                    environment = AcpTaskIsolation.childEnvironment(null),
                    workingDirectory = Path.of(System.getProperty("java.io.tmpdir")),
                )
            }
            delay(1_500)
            val childPid = Files.readString(pidFile).trim().toLongOrNull()
                ?: error("the agent never reported its child pid to $pidFile")
            assertNotNull(ProcessHandle.of(childPid).orElse(null)?.let { if (it.isAlive) it else null })
            deferred.cancel()
            try {
                deferred.await()
            } catch (expected: kotlinx.coroutines.CancellationException) {
                Unit
            }
            // The sleeper the AGENT started must die too — a tree reaped only at the root
            // leaves orphans holding the budget's pipes open.
            val deadline = System.currentTimeMillis() + 10_000
            while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                ProcessHandle.of(childPid).orElse(null)?.isAlive != true,
                "pid $childPid survived the cleanup — the tree was not reaped",
            )
            Files.deleteIfExists(witness)
        }
    }

    @Test
    fun `one fresh session per delegated task against a shared counter`() {
        val counter = temp(".counter")
        val first = task(agent("reply", "alpha", counter.toString()), 30)
        val second = task(agent("reply", "beta", counter.toString()), 30)
        val r1 = (first as AcpExchange.Completed).reply
        val r2 = (second as AcpExchange.Completed).reply
        assertTrue(r1.startsWith("task-session-1:"), "first task must open session 1, got $r1")
        assertTrue(r2.startsWith("task-session-2:"), "second task must open session 2, got $r2")
        // AC#2 lifecycle honesty: the counter file proves a NEW session/new per delegation,
        // which only happens because no connection was parked between them.
        assertEquals("2", Files.readString(counter).trim(), "two delegations must have opened two sessions")
    }

    @Test
    fun `the child environment is the allowlist, observed from inside the agent process`() {
        val exchange = task(
            agent("env-probe"), 30,
            env = AcpTaskIsolation.childEnvironment(null),
        )
        val reply = (exchange as AcpExchange.Completed).reply
        assertTrue("CANARY=absent" in reply, "a host secret leaked into the child: $reply")
        assertTrue("NVARS=2" in reply, "the child saw more than PATH+LANG: $reply")
    }

    @Test
    fun `a simulated malicious agent asking for permission is denied, not granted`() {
        // AC#4: the agent itself requests a destructive permission; the client's authority is
        // deny-by-default (PKH-132's seam) and the exchange completes WITHOUT the escalation.
        val exchange = task(agent("malicious"), 30)
        val output = AcpTaskOutcome.toOutput(exchange)
        assertTrue(output.ok, "the exchange completed, and the answer is what matters: ${output.detail}")
        assertTrue("PERM=denied" in output.reply, "the client did not deny the agent: ${output.reply}")
    }
}
