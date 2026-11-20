package pipelinek.acp

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.ToolKind
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC#4: a human interaction resolved through the capabilities that EXIST — never through a
 * second way of authorizing.
 *
 * The question this law answers is not "does the plugin answer permissions" but "can the
 * plugin ASK a human through the published SDK". The measurement is twofold:
 *
 *  - the plugin's behaviour: a human-required effect is DENIED, and an effect nobody declared
 *    is denied too. A rule that requires a person denies while the seam is absent; it never
 *    answers in the person's name. The elicitation half of the same rule travels the wire in
 *    [AcpReverseLegE2ETest];
 *  - the SDK's shape: the published bundle carries the service client that ANSWERS a question
 *    a run published, and it does NOT carry the port a plugin would need to PUBLISH one. That
 *    is the whole of the limitation, and it is measured against the bundle the plugin
 *    compiles against, not against a document.
 */
class AcpHumanInteractionTest {

    @Test
    fun `a human-required effect is denied while no human seam is published`() {
        val authority = AcpAuthority(
            allowedEffects = listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE),
            humanRequiredEffects = listOf(AcpToolEffect.MUTATE),
        )
        val answer = runBlocking {
            AcpPermissionPolicy.decide(
                profile = AcpTaskProfile.READONLY,
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
    fun `an effect nobody declared is denied even when the agent offers it`() {
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
    fun `the published SDK does NOT carry the port a plugin needs to ASK a human`() {
        // THE MEASUREMENT of the limitation: the human-interaction port lives in a module the
        // bundle does not publish, and the wire format of a published question lives there
        // too. A plugin can therefore answer a question the run published, and can publish
        // none — so an agent that asks the client for a human decision gets the protocol's
        // explicit cancel.
        assertFalse(
            published("dev.rubentxu.pipeline.v2.runtime.InputDecisions"),
            "the human-interaction port must be absent from the published bundle",
        )
        assertFalse(
            published("dev.rubentxu.pipeline.v2.runtime.InputQuestionCodec"),
            "the wire format of a published question must be absent from the published bundle",
        )
        assertFalse(
            published("dev.rubentxu.pipeline.v2.runtime.InputQuestionLayout"),
            "the layout of a published question must be absent from the published bundle",
        )
    }

    @Test
    fun `the plugin declares no capability that could carry a human answer`() {
        // The plugin's own half of the measurement: no step of this plugin asks a human,
        // because nothing published to plugins can.
        val declared = AcpPluginDeclaration.STEPS.flatMapTo(linkedSetOf()) {
            it.contract.requiredCapabilities
        }
        assertTrue(
            declared.none { it.key.contains("input", ignoreCase = true) },
            "no plugin step may declare a human-interaction capability it cannot honour: $declared",
        )
    }

    private fun published(className: String): Boolean =
        runCatching { Class.forName(className) }.isSuccess
}
