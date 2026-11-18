package pipelinek.acp

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * One launched agent process and the two rules its streams obey.
 *
 * STDOUT is reserved: every line read from it is handed to the ACP transport as a potential
 * JSON-RPC frame, and nothing else may be written into the process's stdin from anywhere but
 * the transport's writer. STDERR is drained, continuously and with a bound: an agent that logs
 * to stderr must never block on a full pipe, and a plugin must never hold an unbounded log in
 * memory — only the tail is kept, and only the tail is ever quoted into a refusal.
 *
 * This type owns no policy about what the lines MEAN; framing and interpretation belong to the
 * ACP SDK transport it feeds.
 */
class AcpAgentProcess internal constructor(
    private val process: Process,
    private val stderrRing: BoundedLineRing,
) {
    val pid: Long = process.pid()

    /** Cold flow of stdout lines. Ends at EOF — which is how a dead agent becomes observable. */
    val stdoutLines: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) emit(line)
        }
    }

    /** Writes one outbound frame. An exited agent surfaces here as an IOException. */
    fun writeLine(line: String) {
        val stdin = process.outputStream
        stdin.write(line.toByteArray(Charsets.UTF_8))
        stdin.write('\n'.code)
        stdin.flush()
    }

    /** The exit code if the process died, null while it lives. Never blocks. */
    fun exitCodeOrNull(): Int? = if (process.waitFor(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.exitValue()
    } else {
        null
    }

    val stderrTail: String get() = stderrRing.joined()

    /** Kills the process and stops the stderr drain. Idempotent. */
    fun destroy() {
        process.destroy()
    }

    companion object {
        /** The most stderr bytes kept for diagnostics; older lines are evicted first. */
        const val STDERR_CAP_BYTES: Int = 64 * 1024

        /**
         * Launches [command] as a child process with piped stdio.
         *
         * The command is an argv list, never a shell string: a shell would reinterpret the
         * plugin's arguments and open an injection seam the step's input should not have.
         * A missing or non-executable entry arrives back as [AcpRefusal.AgentUnreachable].
         */
        fun launch(command: List<String>): Result<AcpAgentProcess> {
            require(command.isNotEmpty()) { "the agent command is empty; there is nothing to launch" }
            return runCatching {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
                val ring = BoundedLineRing(STDERR_CAP_BYTES)
                // A daemon thread, not a coroutine: the drain must outlive any dispatcher
                // the caller happens to be on, and must stop on its own at EOF, which
                // `useLines` guarantees once the pipe closes.
                val drained = Thread({
                    process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) ring.add(line)
                    }
                }, "acp-stderr-drain-${process.pid()}")
                drained.isDaemon = true
                drained.start()
                AcpAgentProcess(process, ring)
            }
        }

        /** [launch] translated into the plugin's vocabulary: a failure is a typed refusal. */
        fun open(command: List<String>): AcpAgentProcess {
            try {
                return launch(command).getOrThrow()
            } catch (e: Exception) {
                throw AgentLaunchFailed(AcpRefusal.AgentUnreachable(e.message ?: e.toString()), e)
            }
        }
    }
}

/** Signals [AcpAgentProcess.open] could not start the agent; carries the typed refusal. */
class AgentLaunchFailed(val refusal: AcpRefusal.AgentUnreachable, cause: Throwable) :
    Exception(refusal.detail, cause)

/**
 * A ring of whole lines with a byte bound on its total content.
 *
 * The bound is what keeps the drain bounded: once full, the oldest line leaves. Lines are kept
 * whole because a truncated tail is exactly the diagnostic a reader cannot use.
 */
internal class BoundedLineRing(private val capBytes: Int) {
    private val lines = ConcurrentLinkedDeque<String>()
    private val bytes = AtomicLong(0)

    fun add(line: String) {
        lines.addLast(line)
        bytes.addAndGet(line.length.toLong())
        while (bytes.get() > capBytes) {
            val evicted = lines.pollFirst() ?: break
            bytes.addAndGet(-evicted.length.toLong())
        }
    }

    fun joined(): String = lines.joinToString("\n")
}
