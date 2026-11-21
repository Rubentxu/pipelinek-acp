package pipelinek.acp

import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationSchema
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.domain.step.RegistryStepInvoker
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepInvocationOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.input.InputDecision
import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import dev.rubentxu.pipeline.v2.runtime.input.InputDenialReason
import dev.rubentxu.pipeline.v2.runtime.input.InputRequest
import dev.rubentxu.pipeline.v2.runtime.input.InputResolution
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The human interaction of this plugin, measured in the two halves it has:
 *
 *  - the SDK's shape: the published bundle carries the port a plugin asks a person
 *    through, and the client that answers a question a run published;
 *  - the plugin's own: it declares the human-decision capability in the contract of its
 *    step and in its manifest, it puts the agent's question to a person through that
 *    port, and the person's answer is what the agent receives.
 *
 * The answer never authorizes anything: a permission is still the declared authority's to
 * decide, so a rule that requires a human keeps denying. A person's answer reaches the
 * agent's own question and nothing else.
 */
class AcpHumanInteractionTest {

    @Test
    fun `a human-required effect is denied, an answer is not an authorization`() {
        val authority = AcpAuthority(
            allowedEffects = listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE),
            humanRequiredEffects = listOf(AcpToolEffect.MUTATE),
        )
        val answer = runBlocking {
            AcpPermissionPolicy.decide(
                // The profile must NOT be the blocker here: under a profile that already
                // refuses the effect, the human leg would never be exercised.
                profile = AcpTaskProfile.WRITE_RESTRICTED,
                authority = authority,
                kind = ToolKind.EDIT,
                offeredOptions = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("opt-once"),
                        name = "allow once",
                        kind = PermissionOptionKind.ALLOW_ONCE,
                    ),
                ),
            )
        }
        assertEquals(
            RequestPermissionOutcome.Cancelled,
            answer.outcome,
            "an effect a person must approve is denied, never granted in the person's name",
        )
    }

    @Test
    fun `an absent authority denies everything, even when the agent offers it`() {
        val answer = runBlocking {
            AcpPermissionPolicy.decide(
                profile = AcpTaskProfile.READONLY,
                authority = null,
                kind = ToolKind.READ,
                offeredOptions = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("opt-once"),
                        name = "allow once",
                        kind = PermissionOptionKind.ALLOW_ONCE,
                    ),
                ),
            )
        }
        assertEquals(
            RequestPermissionOutcome.Cancelled,
            answer.outcome,
            "an absent authority denies everything: the absence IS the rule",
        )
    }

    @Test
    fun `an authority that exists and does not declare the effect denies it`() {
        // The case the absent authority cannot show: here the authority EXISTS and simply
        // does not declare this effect. The profile cannot be the blocker — reading is
        // allowed under READONLY — so the ONLY thing that can deny this is the clause that
        // reads the declared effects. A law that measures it with a null authority would
        // pass with that clause removed, because the absence of the authority would have
        // denied the request anyway.
        val answer = runBlocking {
            AcpPermissionPolicy.decide(
                profile = AcpTaskProfile.READONLY,
                authority = AcpAuthority(allowedEffects = emptyList()),
                kind = ToolKind.READ,
                offeredOptions = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("opt-once"),
                        name = "allow once",
                        kind = PermissionOptionKind.ALLOW_ONCE,
                    ),
                ),
            )
        }
        assertEquals(
            RequestPermissionOutcome.Cancelled,
            answer.outcome,
            "an authority that does not declare the effect must not grant it, however it is offered",
        )
    }

    @Test
    fun `the published SDK carries the service client that ANSWERS a question`() {
        // The answering half of the human interaction IS published: a client of the service
        // lists the run's pending questions and answers one.
        val client = ServiceClient(Path.of("/nonexistent/service.sock"), "token", null)
        val answer = client.pendingInputs("run-1")
        assertTrue(
            answer is ServiceAnswer.Unreachable,
            "with no service at the endpoint the client answers unreachable, not a question: $answer",
        )
        assertEquals(
            listOf("proceed", "abort"),
            ServiceInputDecision.entries.map { it.word },
            "the published decision vocabulary is the one the wire carries",
        )
    }

    @Test
    fun `the published SDK carries the port a plugin needs to ASK a human`() {
        // The asking half is published too. The plugin's own sources name this type and
        // this capability, so a bundle that did not carry them could not compile the plugin
        // at all: the class IS on the published classpath. What is measured here is that it
        // LOADS and that it is the shape the runtime binds — an interface the handler
        // awaits a decision on.
        val port: Class<*> = InputDecisions::class.java
        assertTrue(port.isInterface, "the published port is an interface the runtime binds")
        assertTrue(
            port.methods.any { it.parameterTypes.contains(Continuation::class.java) },
            "the published port awaits a decision: ${port.methods.toList()}",
        )
        assertTrue(
            INPUT_DECISIONS_CAPABILITY.key.isNotBlank(),
            "the published capability carries the key the runtime binds it under",
        )
    }

    @Test
    fun `the plugin declares a capability that could carry a human answer`() {
        // The plugin's own half of the measurement: the step that can be asked by the agent
        // for a person's input declares the capability that carries the answer. The
        // vocabulary of the check is the naming of the human seams themselves, so a
        // capability that could carry an answer under ANY of those names is found, not
        // only one called "input".
        val humanSeamWords = listOf("input", "human", "decision", "elicitation", "permission")
        val declared = AcpPluginDeclaration.STEPS.flatMapTo(linkedSetOf()) {
            it.contract.requiredCapabilities
        }
        val humanCarrying = declared.filter { capability ->
            humanSeamWords.any { word -> capability.key.contains(word, ignoreCase = true) }
        }
        assertTrue(
            humanCarrying.isNotEmpty(),
            "the plugin must declare a capability that could carry a human answer: $declared",
        )
        assertTrue(
            humanCarrying.contains(INPUT_DECISIONS_CAPABILITY),
            "the capability the SDK publishes must be among the declared ones: $humanCarrying",
        )
    }

    @Test
    fun `the manifest declares the capability the step contract requires`() {
        // The declaration lives in TWO places and the published validator is what says so:
        // the per-step declaration of the manifest must EQUAL the contract's required set,
        // and the top-level set must be the union of the contracts. A manifest that
        // declared the capability in one place only would be refused at admission.
        val manifest = AcpPluginDeclaration.manifest()
        PluginManifestValidator.validate(manifest, AcpPluginDeclaration.STEPS)
        assertTrue(
            manifest.contributions.capabilities.contains(INPUT_DECISIONS_CAPABILITY),
            "the manifest declares the human-decision capability: ${manifest.contributions.capabilities}",
        )
        val declaredForTheTask = manifest.contributions.steps
            .first { it.stepKey == AcpTaskStepDefinition.KEY }
            .declaredCapabilities
        assertTrue(
            declaredForTheTask.contains(INPUT_DECISIONS_CAPABILITY),
            "the step that can be asked declares it too: $declaredForTheTask",
        )
    }

    @Test
    fun `the question the agent asks is put to a person and the answer reaches the agent`() {
        // The whole round trip inside the plugin: the agent asks for a person's input, the
        // question is published through the port, and what the person answered is what the
        // agent receives. The action is the person's, not a default.
        val decisions = AnsweringDecisions { InputResolution.Answered(InputDecision.Proceed("ana", "go on")) }
        val response = elicit(decisions)
        assertEquals(
            1,
            decisions.asked.size,
            "the question is published through the port, once",
        )
        assertEquals(
            "which branch should I take?",
            decisions.asked.single().message,
            "the person is asked the question the agent asked",
        )
        val action = response.action
        assertTrue(action is ElicitationAction.Accept, "the person's answer reaches the agent: $action")
        val accepted = action as ElicitationAction.Accept
        assertEquals(
            ElicitationContentValue.StringValue("go on"),
            accepted.content.orEmpty()[AcpHumanDecision.ANSWER_KEY],
            "what the person said travels to the agent",
        )
        assertEquals(
            ElicitationContentValue.StringValue("ana"),
            accepted.content.orEmpty()[AcpHumanDecision.SUBMITTER_KEY],
            "who answered travels with it",
        )
    }

    @Test
    fun `a person who says no is declined, and a question nobody answers is cancelled`() {
        val declined = elicit(AnsweringDecisions { InputResolution.Answered(InputDecision.Abort("ana", "no")) })
        assertEquals(
            ElicitationAction.Decline,
            declined.action,
            "the refusal of the person is the refusal the agent hears",
        )
        val unanswered = elicit(
            AnsweringDecisions { InputResolution.Denied(InputDenialReason.TimedOut(1_000)) },
        )
        assertEquals(
            ElicitationAction.Cancel,
            unanswered.action,
            "a question nobody answered is cancelled, not answered in someone's name",
        )
    }

    @Test
    fun `the published admission admits the step with the human-decision capability`() {
        // The published seam the runtime invokes a plugin's step through: the registry, the
        // capability admission and the handler, in that order. The admission is fail-closed,
        // so it is the DECLARATION that decides whether the step runs at all.
        val registry = StepRegistryBuilder().addAll(AcpContributor().registrations()).build()
        val invoker = RegistryStepInvoker(registry)
        val root = Files.createTempDirectory("acp-admission-")
        val encoded = AcpTaskStepDefinition.InputCodec.encode(
            AcpTaskInput(command = listOf("/nonexistent/pipelinek-acp-agent"), prompt = "ask"),
        )
        val lease = ExecutionLocation(WorkspaceLease.Managed(root), root)

        val bound = RecordingAccess(
            mapOf(
                EXECUTION_LOCATION_CAPABILITY to lease,
                INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
                // The host contributes the credential seam unconditionally; this delegation
                // names no credential, so nothing reads it.
                StepCapability("credentials.basic") to Any(),
            ),
        )
        val admitted = runBlocking {
            invoker.invoke<AcpTaskInput, AcpTaskOutput>(
                AcpTaskStepDefinition.KEY,
                encoded,
                StepHandlerContext(RunId("run-admission"), 0, bound),
            )
        }
        assertTrue(
            admitted !is StepInvocationOutcome.MissingCapability,
            "the step declares the capability and the runtime supplies it: $admitted",
        )
        assertTrue(
            bound.asked.contains(INPUT_DECISIONS_CAPABILITY),
            "the port reaches the handler through the key the step declared: ${bound.asked}",
        )

        // The contrast: the admission is fail-closed, and what it reads is the DECLARATION.
        // Without the port the step does not run at all, and the refusal names the very
        // capability the plugin declared.
        val unbound = RecordingAccess(
            mapOf(
                EXECUTION_LOCATION_CAPABILITY to lease,
                StepCapability("credentials.basic") to Any(),
            ),
        )
        val refused = runBlocking {
            invoker.invoke<AcpTaskInput, AcpTaskOutput>(
                AcpTaskStepDefinition.KEY,
                encoded,
                StepHandlerContext(RunId("run-admission"), 0, unbound),
            )
        }
        assertTrue(
            refused is StepInvocationOutcome.MissingCapability &&
                refused.missing.contains(INPUT_DECISIONS_CAPABILITY),
            "a step that declares a capability the runtime does not supply is refused: $refused",
        )
        assertTrue(
            unbound.asked.isEmpty(),
            "the handler never runs when the admission refuses: ${unbound.asked}",
        )
    }

    /**
     * The capability table, watched: it records what the handler asked for, so a law can
     * say that the port arrived instead of inferring it.
     */
    private class RecordingAccess(
        private val values: Map<StepCapability, Any>,
    ) : StepCapabilityAccess {
        val asked = mutableListOf<StepCapability>()

        override fun available(): Set<StepCapability> = values.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            asked += key
            val value = values[key] ?: error("capability '$key' is not bound")
            require(type.isInstance(value)) { "capability '$key' does not carry a ${type.simpleName}" }
            return value as T
        }
    }

    /**
     * One elicitation, answered by [decisions], over the reverse leg the runtime drives.
     */
    private fun elicit(decisions: InputDecisions): CreateElicitationResponse {
        val root = Files.createTempDirectory("acp-human-")
        val ops = AcpReverseOperations(
            sessionId = SessionId("human-1"),
            workspaceRoot = root,
            profile = AcpTaskProfile.READONLY,
            authority = null,
            journal = AcpReverseJournal(),
            ledger = AcpPermissionLedger(),
            humanDecision = AcpHumanDecision(decisions),
        )
        return runBlocking {
            ops.createElicitation(
                CreateElicitationRequest(
                    scope = ElicitationScope.Session(SessionId("human-1")),
                    mode = ElicitationMode.Form(ElicitationSchema()),
                    message = "which branch should I take?",
                ),
            )
        }
    }

    /**
     * The port, answered by a person: it records what it was asked and answers with what
     * the law says the person said.
     */
    private class AnsweringDecisions(
        private val answer: (InputRequest) -> InputResolution,
    ) : InputDecisions {
        val asked = mutableListOf<InputRequest>()

        override suspend fun awaitDecision(request: InputRequest, waitMillis: Long?): InputResolution {
            asked += request
            return answer(request)
        }
    }
}
