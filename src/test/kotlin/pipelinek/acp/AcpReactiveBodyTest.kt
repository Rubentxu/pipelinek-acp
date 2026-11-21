package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC#3: one body slot, invoked repeatedly, reacting to distinct events, each invocation with
 * its own durable identity.
 *
 * The seam under test is the PUBLISHED one: `BodySlotInvoker` (the slot the handler drives),
 * `AttemptSegment` (the per-invocation identity the engine turns into a deterministic path
 * segment) and `BodySlots` (the pure resolution of a slot name against a body's children).
 * Nothing here is the plugin's own vocabulary: the rows below would be exactly as true if the
 * plugin were replaced by any other consumer of the same published contracts.
 */
class AcpReactiveBodyTest {

    /** Records what the DSL pushed, so the lowering can be read instead of assumed. */
    private class RecordingEmitter(
        private val sink: MutableList<Pair<PluginStepId, EncodedStepValue>>,
    ) : StepEmitter<RecordingEmitter> {
        override fun registryStep(stepKey: PluginStepId, encodedInput: EncodedStepValue) {
            sink += stepKey to encodedInput
        }

        override fun registryBlock(
            stepKey: PluginStepId,
            encodedInput: EncodedStepValue,
            block: RecordingEmitter.() -> Unit,
        ) {
            sink += stepKey to encodedInput
            RecordingEmitter(sink).block()
        }
    }

    /** The engine's side of the seam: it records what the handler asked for. */
    private class RecordingSlot : BodySlotInvoker {
        val invocations = mutableListOf<Pair<PluginStepId, BodyInvocationContext>>()

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            invocations += slot to context
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    /** Fail-closed capability access: a capability that was not bound is an error, not a default. */
    private class BoundCapabilities(
        private val values: Map<StepCapability, Any>,
    ) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = values.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            val value = values[key] ?: error("capability '$key' is not bound")
            require(type.isInstance(value)) { "capability '$key' does not carry a ${type.simpleName}" }
            return value as T
        }
    }

    private fun update(text: String): SessionUpdate =
        SessionUpdate.AgentMessageChunk(ContentBlock.Text(text))

    @Test
    fun `the DSL lowers acpReactive to a block whose body holds exactly the reaction`() {
        val sink = mutableListOf<Pair<PluginStepId, EncodedStepValue>>()
        RecordingEmitter(sink).acpReactive(listOf("agent", "--acp"), "react to me")

        assertEquals(2, sink.size, "the reactive step declares a block and ONE body child: $sink")
        assertEquals(AcpReactiveStepDefinition.KEY, sink[0].first, "the block is the reactive step")
        assertEquals(
            AcpReactionStepDefinition.KEY,
            sink[1].first,
            "the body child is the reaction the handler dispatches",
        )
        val decoded = AcpPromptStepDefinition.InputCodec.decode(sink[0].second)
        assertEquals("react to me", decoded.prompt)
    }

    @Test
    fun `the published policy resolver admits the declared body`() {
        // The engine's own resolver, from the published SDK: a body declaration the engine
        // cannot execute is rejected fail-closed, so a plugin that wants a body has to say one
        // the engine accepts.
        val resolution = resolveBodyExecutionPolicy(
            AcpReactiveStepDefinition,
            BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING,
        )
        assertTrue(
            resolution is BodyPolicyResolution.Resolved,
            "the published resolver must admit the declared body, got $resolution",
        )
    }

    @Test
    fun `the slot the handler names is the address the engine resolves`() {
        // The engine resolves the slot against the addresses of the body's children. The
        // plugin names exactly one child, so the name resolves to it and to nothing else.
        val resolution = BodySlots.resolve(
            listOf(AcpReactionStepDefinition.KEY),
            AcpReactiveStepDefinition.REACTION_SLOT,
        )
        assertEquals(
            SlotResolution.Found(0),
            resolution,
            "the slot the handler names must be the child the engine dispatches",
        )
    }

    @Test
    fun `every agent update dispatches the same slot once, with its own durable identity`() {
        val slot = RecordingSlot()
        val dispatcher = AcpReactionDispatcher(
            slot,
            AcpReactiveStepDefinition.REACTION_SLOT,
            AcpReactiveStepDefinition.ATTEMPT_KEY,
        )
        runBlocking {
            dispatcher.react(update("one"))
            dispatcher.react(update("two"))
            dispatcher.react(update("three"))
        }

        assertEquals(3, slot.invocations.size, "one invocation per update the agent sent")
        assertTrue(
            slot.invocations.all { (id, _) -> id == AcpReactiveStepDefinition.REACTION_SLOT },
            "every reaction must travel through the SAME slot: ${slot.invocations.map { it.first }}",
        )
        val identities = slot.invocations.map { (_, context) -> context.attempt }
        assertEquals(
            listOf(1, 2, 3),
            identities.map { it?.index },
            "each invocation carries its own attempt index: $identities",
        )
        assertEquals(
            3,
            identities.map { it?.index }.toSet().size,
            "the identities must be distinct, one per invocation",
        )
        assertTrue(
            identities.all { it?.key == AcpReactiveStepDefinition.ATTEMPT_KEY },
            "every invocation carries the declared attempt key: $identities",
        )
    }

    @Test
    fun `a turn against a real agent dispatches one reaction per update it sent`() {
        // The pairing the unit rows cannot prove: the agent's updates travel the wire, the
        // SDK routes them to the session operations, and the reaction reaches the slot.
        val agent = reactiveAgent()
        val slot = RecordingSlot()
        val workDir = Files.createTempDirectory("acp-reactive-")
        val output = runBlocking {
            AcpReactiveStepDefinition.handler.execute(
                AcpPromptInput(
                    command = listOf("python3", "-I", agent.toString()),
                    prompt = "react",
                    cwd = workDir.toString(),
                    timeoutSeconds = 60,
                ),
                StepHandlerContext(
                    runId = RunId("run-reactive"),
                    stepIndex = 0,
                    capabilities = BoundCapabilities(
                        mapOf(
                            BODY_SLOT_CAPABILITY to slot,
                            // The reactive step declares the human-decision capability and
                            // reads the port: this turn never asks a person anything.
                            INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
                        ),
                    ),
                ),
            )
        }

        assertTrue(output.ok, "the turn must complete: ${output.failureKind} ${output.detail}")
        assertEquals(3, output.reactions, "the turn reacted once per update the agent sent")
        assertEquals(3, slot.invocations.size, "the slot saw one invocation per update")
        assertEquals(
            listOf(1, 2, 3),
            slot.invocations.map { (_, context) -> context.attempt?.index },
            "each reaction travelled with its own durable identity",
        )
        assertTrue(
            slot.invocations.all { (id, _) -> id == AcpReactiveStepDefinition.REACTION_SLOT },
            "every reaction travelled through the same slot",
        )
    }

    /**
     * A minimal ACP agent that sends three updates during the prompt.
     *
     * It is written by the test into a temporary directory instead of living in the test
     * resources: the fixture that ships with the plugin sends ONE update, and a law about
     * "distinct events" needs an agent that says more than one thing.
     */
    private fun reactiveAgent(): java.nio.file.Path {
        val script = Files.createTempFile("acp-reactive-agent-", ".py")
        Files.writeString(
            script,
            """
            import sys, json

            def write(message):
                sys.stdout.write(json.dumps(message) + "\n")
                sys.stdout.flush()

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                msg = json.loads(line)
                method = msg.get("method")
                mid = msg.get("id")
                if mid is None:
                    continue
                if method == "initialize":
                    result = {
                        "protocolVersion": 1,
                        "agentCapabilities": {
                            "loadSession": False,
                            "promptCapabilities": {"audio": False, "image": False, "embeddedContext": False},
                        },
                        "authMethods": [],
                        "agentInfo": {"name": "acp-reactive-fixture", "version": "0.1.0"},
                    }
                elif method == "session/new":
                    result = {"sessionId": "reactive-session"}
                elif method == "session/prompt":
                    for index in range(3):
                        write({"jsonrpc": "2.0", "method": "session/update", "params": {
                            "sessionId": "reactive-session",
                            "update": {
                                "sessionUpdate": "agent_message_chunk",
                                "content": {"type": "text", "text": "chunk-%d" % index},
                            },
                        }})
                    result = {"stopReason": "end_turn"}
                else:
                    write({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "no method"}})
                    continue
                write({"jsonrpc": "2.0", "id": mid, "result": result})
            """.trimIndent(),
        )
        return script
    }
}
