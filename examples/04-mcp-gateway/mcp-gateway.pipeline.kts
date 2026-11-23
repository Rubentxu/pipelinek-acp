import pipelinek.acp.AcpEventSelector
import pipelinek.acp.AcpToolPolicy
import pipelinek.acp.AcpToolSchema
import pipelinek.acp.acpExpose
import pipelinek.acp.acpOn
import pipelinek.acp.acpReactive
import pipelinek.acp.acpTake
import pipelinek.acp.acpToolArgument

// PKH-143 example (d): the MCP session gateway (PKH-139).
// `acpExpose` declares one engine step the agent may CALL as an MCP tool of its own
// session. The caller agent below negotiates the http MCP transport, reads the
// gateway URL carried in session/new, and mid-turn does: initialize, tools/list, a
// valid call (dispatched through the same slot seam and intent/ack ledger as a
// reaction), an out-of-schema call (denied on the wire as -32602), and a repeat under
// the SAME idempotency key (answered already_acknowledged — no second dispatch).
// The gateway's port is dead once the turn ends.
pipeline {
    stages {
        stage("agent calls a session tool") {
            acpReactive(
                command = listOf("python3", "-I",
                    "examples/04-mcp-gateway/acp_mcp_caller_agent.py"),
                prompt = "use your tools",
                cwd = ".",
                timeoutSeconds = 60,
                reactions = listOf(
                    acpOn("quiet", AcpEventSelector.TextContains("ZZZ"),
                        operator = acpTake(1)),
                ),
                exposedTools = listOf(
                    acpExpose(
                        id = "runTests",
                        description = "Run the project's tests with a filter.",
                        inputSchema = AcpToolSchema(
                            properties = listOf(
                                acpToolArgument("filter", "string", required = true),
                            ),
                        ),
                        policy = AcpToolPolicy(maxConcurrent = 1, maxCalls = 4),
                    ),
                ),
            )
        }
    }
}
