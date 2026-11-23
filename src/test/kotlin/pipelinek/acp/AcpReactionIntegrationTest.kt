package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlots
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.SlotResolution
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-137 AC#2 and AC#5 end to end: declared reactions lower to NAMED static slots, survive
 * as serializable data in the encoded input, and a turn against a REAL agent dispatches each
 * reaction through its own slot address with its own attempt identity.
 *
 * The lowerings here go through the actual published [StageScope], not a fake emitter, because
 * the claim is about the artifact the core compiler consumes: `slot("alert") { ... }` must
 * produce a `StepSpec.Slot` whose name the engine's own [BodySlots] seam resolves — a fake
 * could only show what the plugin hoped, not what the DSL wrote.
 */
class AcpReactionIntegrationTest {

    private class RecordingSlot : BodySlotInvoker {
        val invocations = mutableListOf<Pair<PluginStepId, BodyInvocationContext>>()

        override suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome {
            invocations += slot to context
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

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

    private object TestConfig : RuntimeConfig {
        override fun env(name: String): String = System.getenv(name) ?: ""
        override fun property(name: String): String = System.getProperty(name) ?: ""
        override fun property(name: String, defaultValue: String): String = System.getProperty(name) ?: defaultValue
        override fun osName(): String = System.getProperty("os.name")
        override fun userDir(): String = System.getProperty("user.dir")
    }

    @Test
    fun `declared reactions lower to named slots around the plugin reaction step`() {
        val scope = StageScope("Agent", TestConfig)
        val reactions = listOf(
            acpOn("alert", AcpEventSelector.TextContains("ERROR")),
            acpOn("kick", AcpEventSelector.AnyUpdate, once = true),
        )
        scope.acpReactive(
            command = listOf("agent", "--acp"),
            prompt = "react to me",
            reactions = reactions,
            awaits = listOf(acpAwait(AcpEventSelector.UpdateKind("plan"), 5_000)),
        )

        val block = assertInstanceOf(StepSpec.RegistryBlockSpec::class.java, scope.steps().single())
        assertEquals(
            listOf("alert", "kick"),
            block.body.map { assertInstanceOf(StepSpec.Slot::class.java, it).slotName },
            "each declared reaction names its child; the engine compiles that name to the slot address",
        )
        assertTrue(
            block.body.all {
                (it as StepSpec.Slot).step is StepSpec.RegistryStepSpec &&
                    (it.step as StepSpec.RegistryStepSpec).stepKey == AcpReactionStepDefinition.KEY
            },
            "the named child is the plugin's own reaction step, never an author-written body",
        )
        val decoded = AcpReactiveStepDefinition.InputCodec.decode(block.encodedInput)
        assertEquals(reactions.map { it.id }, decoded.reactions.map { it.id })
        assertEquals(
            AcpEventSelector.TextContains("ERROR"),
            decoded.reactions.first().selector,
            "the selector is serializable DATA — the journal reads the same routing back",
        )
    }

    @Test
    fun `an empty declaration keeps the shipped unnamed child - no slot wrapper is invented`() {
        val scope = StageScope("Agent", TestConfig)
        scope.acpReactive(command = listOf("agent", "--acp"), prompt = "react to me")

        val block = assertInstanceOf(StepSpec.RegistryBlockSpec::class.java, scope.steps().single())
        assertEquals(
            listOf(AcpReactionStepDefinition.KEY),
            block.body.map { assertInstanceOf(StepSpec.RegistryStepSpec::class.java, it).stepKey },
        )
    }

    @Test
    fun `the names the DSL writes are the addresses the published seam resolves`() {
        // AC#5's contract test against the SDK's own resolver: what slot() lowers to, BodySlots
        // finds — and nothing else. This is the whole reason no core change was needed.
        val addresses = listOf(PluginStepId("alert"), PluginStepId("kick"))
        assertEquals(SlotResolution.Found(0), BodySlots.resolve(addresses, PluginStepId("alert")))
        assertEquals(SlotResolution.Found(1), BodySlots.resolve(addresses, PluginStepId("kick")))
        assertEquals(
            SlotResolution.Missing(PluginStepId("nope")),
            BodySlots.resolve(addresses, PluginStepId("nope")),
        )
    }

    @Test
    fun `a plan the compiler refuses never launches the agent`() {
        val slot = RecordingSlot()
        val output = runBlocking {
            AcpReactiveStepDefinition.handler.execute(
                AcpReactiveInput(
                    command = listOf("python3", "-I", "/dev/null"),
                    prompt = "react",
                    reactions = listOf(
                        acpOn("same", AcpEventSelector.AnyUpdate),
                        acpOn("same", AcpEventSelector.UpdateKind("plan")),
                    ),
                ),
                StepHandlerContext(
                    runId = RunId("run-plan-rejected"),
                    stepIndex = 0,
                    capabilities = BoundCapabilities(mapOf(BODY_SLOT_CAPABILITY to slot)),
                ),
            )
        }

        assertTrue(output.failureKind.isNotEmpty())
        assertEquals("ACP_PLAN_REJECTED", output.failureKind)
        assertTrue("same" in output.detail, "the refusal names the duplicated address: ${output.detail}")
        assertEquals(0, slot.invocations.size, "a refused plan dispatched nothing — the refusal came before the turn")
    }

    @Test
    fun `a real agent's mixed updates route to their named slots with distinct identities`() {
        // The pairing the unit rows cannot prove: the agent's updates travel the real wire, the
        // real SDK demuxes them, and each declared reaction answers through ITS OWN slot.
        val agent = mixedUpdateAgent()
        val slot = RecordingSlot()
        val workDir = Files.createTempDirectory("acp-reaction-integration-")
        val output = runBlocking {
            AcpReactiveStepDefinition.handler.execute(
                AcpReactiveInput(
                    command = listOf("python3", "-I", agent.toString()),
                    prompt = "react",
                    cwd = workDir.toString(),
                    timeoutSeconds = 60,
                    reactions = listOf(
                        acpOn("alert", AcpEventSelector.TextContains("ERROR")),
                        acpOn("thoughts", AcpEventSelector.UpdateKind("agent_thought_chunk")),
                        acpOn("kick", AcpEventSelector.AnyUpdate, once = true),
                    ),
                    awaits = listOf(acpAwait(AcpEventSelector.TextContains("ERROR"), 10_000)),
                ),
                StepHandlerContext(
                    runId = RunId("run-reactions"),
                    stepIndex = 0,
                    capabilities = BoundCapabilities(
                        mapOf(
                            BODY_SLOT_CAPABILITY to slot,
                            INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
                            PLUGIN_EVENT_EMISSION_CAPABILITY to AcpUnregisteredEventEmission(),
                        ),
                    ),
                ),
            )
        }

        assertTrue(output.ok, "the turn must complete: ${output.failureKind} ${output.detail}")
        assertEquals(
            listOf(PluginStepId("kick"), PluginStepId("thoughts"), PluginStepId("alert")),
            slot.invocations.map { it.first },
            "each update fired exactly the reactions whose selector matched, in event order",
        )
        assertEquals(
            listOf("acp-reaction.kick", "acp-reaction.thoughts", "acp-reaction.alert"),
            slot.invocations.map { it.second.attempt?.key?.value },
            "each reaction's durable identity is derived from its OWN name — two reactions cannot share a journal row",
        )
        assertEquals(
            listOf(
                AcpReactionSummary("alert", invocations = 1),
                AcpReactionSummary("thoughts", invocations = 1),
                AcpReactionSummary("kick", invocations = 1, skipped = listOf(2L, 3L)),
            ).sortedBy { it.id },
            output.reactionSummary.sortedBy { it.id },
            "the output tells per reaction what it did: 'kick' answered the first event and skipped the rest",
        )
        assertEquals(listOf(AcpAwaitVerdict.Satisfied(3L)), output.awaitVerdicts)
        assertEquals(3, output.reactions)
    }

    /**
     * A real ACP agent (over stdio, through the real client) that sends THREE different update
     * kinds during one prompt: a quiet chunk, a thought, and a chunk carrying "ERROR". The mix
     * is the point — one selector must fire on one of them and the await must see the third.
     */
    private fun mixedUpdateAgent(): java.nio.file.Path {
        val script = Files.createTempFile("acp-mixed-agent-", ".py")
        Files.writeString(
            script,
            """
            import sys, json

            def write(message):
                sys.stdout.write(json.dumps(message) + "\n")
                sys.stdout.flush()

            def update(payload):
                write({"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": "mixed-session", "update": payload,
                }})

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
                        "agentInfo": {"name": "acp-mixed-fixture", "version": "0.1.0"},
                    }
                elif method == "session/new":
                    result = {"sessionId": "mixed-session"}
                elif method == "session/prompt":
                    update({"sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": "thinking out loud"}})
                    update({"sessionUpdate": "agent_thought_chunk",
                            "content": {"type": "text", "text": "a private consideration"}})
                    update({"sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": "found an ERROR here"}})
                    result = {"stopReason": "end_turn"}
                else:
                    write({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "no method"}})
                    continue
                write({"jsonrpc": "2.0", "id": mid, "result": result})
            """.trimIndent(),
        )
        return script
    }

    @Test
    fun `a body-emitter route that cannot name children refuses declared reactions`() {
        // The fake below is a bare StepEmitter, not a StepBodyScopeWrappers: lowering named
        // slots on it would silently DROP the names, so the DSL refuses at composition.
        val emitter = UnnamableEmitter()
        val failure = assertThrows(IllegalStateException::class.java) {
            emitter.acpReactive(
                command = listOf("agent", "--acp"),
                prompt = "react",
                reactions = listOf(acpOn("alert", AcpEventSelector.AnyUpdate)),
            )
        }
        assertTrue("can name body" in failure.message.orEmpty(), "the refusal says why: ${failure.message}")
    }

    private class UnnamableEmitter : dev.rubentxu.pipeline.v2.dsl.StepEmitter<UnnamableEmitter> {
        override fun registryStep(stepKey: PluginStepId, encodedInput: EncodedStepValue) = Unit

        override fun registryBlock(
            stepKey: PluginStepId,
            encodedInput: EncodedStepValue,
            block: UnnamableEmitter.() -> Unit,
        ) {
            block()
        }
    }
}
