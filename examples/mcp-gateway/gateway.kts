// =============================================================================
// example: the session MCP gateway, end to end (PKH-143 / PKH-139)
// =============================================================================
//
// What this pipeline says and what the engine then does:
//
//   * `acpExpose("audit", ...)` compiles to a STATIC SLOT address. The agent never sees a
//     lambda, a shell, or a step key — only the tool's name, description and closed-type
//     schema, offered over the session's temporary loopback gateway.
//   * The policy AcpToolPolicy(maxConcurrent = 1, maxCalls = 2) is the gate: the gateway
//     refuses the third dispatch-bearing call of the scope rather than queueing it.
//   * acpOn("thought", ...) keeps a declared reaction beside the tool slots, so the same
//     turn proves reactions and tools share one body, one seam, one ledger.
//
// Run it (from the repository root, with the built operator jar):
//
//   pipelinek run --workspace . --db <state>/run.sqlite --control-root <state>/control \
//     --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
//     examples/mcp-gateway/gateway.kts
//
// examples/mcp-gateway/run.sh is the self-asserting version of those commands: it drives the
// fixture agent (no tokens, no network beyond loopback) through the six calls of the law
// showcase and checks BOTH the agent's reply and the gateway's durable exposedToolCalls.
import pipelinek.acp.AcpEventSelector
import pipelinek.acp.AcpToolPolicy
import pipelinek.acp.AcpToolSchema
import pipelinek.acp.acpExpose
import pipelinek.acp.acpOn
import pipelinek.acp.acpReactive
import pipelinek.acp.acpToolArgument

pipeline {
    stages {
        stage("gateway showcase") {
            acpReactive(
                command = listOf("python3", "-I",
                    "examples/fixtures/acp_gateway_fixture_agent.py",
                    "build/examples/gateway-witness.txt"),
                prompt = "review this change through the exposed steps",
                timeoutSeconds = 90,
                reactions = listOf(acpOn("thought", AcpEventSelector.UpdateKind("agent_thought_chunk"))),
                exposedTools = listOf(
                    acpExpose(
                        id = "audit",
                        description = "record a review note for this run",
                        inputSchema = AcpToolSchema(
                            properties = listOf(
                                acpToolArgument("note", "string", required = true,
                                    description = "the reviewer's own words"),
                            ),
                        ),
                        policy = AcpToolPolicy(maxConcurrent = 1, maxCalls = 2),
                    ),
                ),
            )
        }
    }
}
