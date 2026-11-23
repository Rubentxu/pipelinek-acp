package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The real-adapter contract probe, opt-in (PKH-141 AC#4).
 *
 * The fixture agents prove the wire against a second implementation this repository ships;
 * they cannot prove the plugin speaks to an agent the ecosystem actually runs. This class
 * closes that gap honestly: it holds the registry of real ACP adapters (the two the ACP
 * ecosystem carries today, `claude-agent-acp` and Gemini CLI's ACP mode), and for each one,
 * the environment names its command. A probe that is NOT configured is NOT_RUN — it SKIPS,
 * and a skip is reported as such; nothing here ever turns a not-run into a pass, and the
 * matrix does not cite this class as TESTED evidence because of exactly that.
 *
 * What a configured probe does is REGISTER SUPPORT PER AGENT, not certify conformance: it
 * runs the client request/response contract the plugin actually uses (initialize → new
 * session → one text prompt → typed answer) against the real adapter and appends one JSON
 * line to the support record (`PKH141_ACP_SUPPORT_RECORD`, default
 * `build/acp-real-adapter-support.jsonl`) naming the agent, the outcome and the refusal kind
 * if there was one. The record claims "this adapter answered this plugin's exchange THIS WAY
 * on this machine" — the honest form of support. Whether the outcome is COMPLETED or a typed
 * refusal, the probe's only assertion is that the exchange ended CLASSIFIED inside the
 * plugin's own vocabulary: a real agent the network dislikes must produce a row, not a crash.
 *
 * The record lives under build/ precisely because it is a measurement of a machine, not a
 * repository claim: it is regenerated per probe and never asserted from git.
 */
class AcpRealAdapterProbeTest {

    /** One real adapter: the ecosystem name, the env that names its command, and the hint. */
    private data class RealAdapter(val agent: String, val envName: String, val hint: String)

    /** The registry AC#4 asks for: two real adapters, both opt-in, neither assumed. */
    private val adapters = listOf(
        RealAdapter(
            "claude-agent-acp", "PKH141_ACP_ADAPTER_CLAUDE",
            "npx --yes @zed-industries/claude-agent-acp",
        ),
        RealAdapter(
            "gemini-cli-acp", "PKH141_ACP_ADAPTER_GEMINI",
            "gemini --experimental-acp",
        ),
    )

    private fun recordPath(): Path = Path.of(
        System.getenv("PKH141_ACP_SUPPORT_RECORD") ?: "build/acp-real-adapter-support.jsonl",
    )

    /** The configured command for one adapter, or null when this machine opted out. */
    private fun commandFor(adapter: RealAdapter): List<String>? {
        val raw = System.getenv(adapter.envName)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.split(" ").filter { it.isNotEmpty() }
    }

    @Test
    fun `claude-agent-acp support is registered only when the operator opts in`() {
        probe(adapters[0])
    }

    @Test
    fun `gemini-cli ACP support is registered only when the operator opts in`() {
        probe(adapters[1])
    }

    private fun probe(adapter: RealAdapter) {
        val command = commandFor(adapter)
        // NOT_RUN stays NOT_RUN: an unconfigured probe SKIPS with the reason an auditor can
        // act on — the env var, the command that would answer it, the record it owes.
        assumeTrue(
            command != null,
            "NOT_RUN: ${adapter.agent} probe not configured — set ${adapter.envName} to a real " +
                "ACP adapter command (e.g. `${adapter.hint}`); support is only claimed from a " +
                "probe that ran, never from its declaration",
        )
        val root = Files.createTempDirectory("acp-real-probe-")
        try {
            val out = AcpSessionConnection("0.0.0-test").run(
                AcpSessionInput(
                    command = command!!,
                    turns = listOf(acpTurn("Reply with exactly the word: PONG")),
                    cwd = root.toString(),
                    timeoutSeconds = PROBE_TIMEOUT_SECONDS,
                ),
            )
            // The FORM the probe owns: the exchange ended inside the plugin's vocabulary, with
            // a classification a record line can carry. The CONTENT of a real agent's answer is
            // nobody's assertion here — that would be conformance, not support.
            require(out.outcome in AcpSessionOutcome.VOCABULARY) {
                "the probe ended outside the output vocabulary: '${out.outcome}'"
            }
            require(out.ok || out.failureKind.isNotEmpty()) {
                "a refused probe must carry its refusal kind, got empty"
            }
            appendRecord(adapter, out)
        } finally {
            runCatching { Files.deleteIfExists(root) }
        }
    }

    /** Appends the one support row this probe produced; the record is append-only by design. */
    private fun appendRecord(adapter: RealAdapter, out: AcpSessionOutput) {
        val line = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("agent", adapter.agent)
                put("command", System.getenv(adapter.envName))
                put("outcome", out.outcome)
                put("failureKind", out.failureKind)
                put("turnsCompleted", out.turns.size)
                put("stopReason", out.turns.lastOrNull()?.stopReason ?: "")
                // The claim's own limit, written into the row: this is support registration.
                put("conformanceCertified", false)
            },
        )
        val path = recordPath()
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        // The row must read back as data: a record nobody can parse registers nothing.
        val last = Json.parseToJsonElement(Files.readAllLines(path).last()) as JsonObject
        check("agent" in last && "outcome" in last)
    }

    private companion object {
        /** Real adapters authenticate, spawn models and think: the fixture budgets do not fit. */
        const val PROBE_TIMEOUT_SECONDS = 180L
    }
}
