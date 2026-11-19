package pipelinek.acp

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The permission authority of AC#1 (PKH-134): the effective answer is the INTERSECTION of
 * the profile ceiling, the runtime authority declared by the pipeline, and the option the
 * agent actually offered — never an invented one, and never a grant without a declaration.
 *
 * The human leg of that intersection is deliberately fail-closed: the published seam offers
 * no authorized-interaction capability to plugins, so a rule that demands a human denies —
 * the safe reading of "política humana autorizada" while the seam does not exist (the
 * PKACP-SDK-02 pattern: absent machinery is named, never pretended).
 */
class AcpAuthorityTest {

    private fun option(id: String, kind: PermissionOptionKind) =
        PermissionOption(PermissionOptionId(id), id, kind, null)

    private val grantable = listOf(
        option("opt-reject", PermissionOptionKind.REJECT_ONCE),
        option("opt-allow", PermissionOptionKind.ALLOW_ONCE),
    )

    private fun decide(
        profile: AcpTaskProfile,
        authority: AcpAuthority?,
        kind: ToolKind,
        options: List<PermissionOption> = grantable,
    ): RequestPermissionOutcome =
        runBlocking { AcpPermissionPolicy.decide(profile, authority, kind, options).outcome }

    @Test
    fun `without a declared authority every kind is denied`() {
        for (kind in ToolKind.entries) {
            assertTrue(
                decide(AcpTaskProfile.READONLY, null, kind) is RequestPermissionOutcome.Cancelled,
                "deny-by-default must cover $kind with no authority",
            )
        }
    }

    @Test
    fun `an authority that allows the effect grants a VALID offered option, preferring allow-once`() {
        val read = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ))
        val outcome = decide(AcpTaskProfile.READONLY, read, ToolKind.READ)
        assertTrue(outcome is RequestPermissionOutcome.Selected, "the authority must grant: $outcome")
        assertEquals("opt-allow", (outcome as RequestPermissionOutcome.Selected).optionId.value)
    }

    @Test
    fun `a grant never invents an optionId the agent did not offer`() {
        val read = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ))
        val onlyReject = listOf(option("opt-reject", PermissionOptionKind.REJECT_ONCE))
        assertTrue(
            decide(AcpTaskProfile.READONLY, read, ToolKind.READ, onlyReject)
                is RequestPermissionOutcome.Cancelled,
            "with no allow-style option offered the only honest answer is cancelled",
        )
        // A rejected decision must not smuggle an option id either way.
        val outcome = decide(AcpTaskProfile.READONLY, read, ToolKind.EDIT) as RequestPermissionOutcome.Cancelled
        assertNotNull(outcome)
    }

    @Test
    fun `the profile ceiling overrides the authority - READONLY never lets effects past it`() {
        val everything = AcpAuthority(
            allowedEffects = AcpToolEffect.entries.toList(),
        )
        assertTrue(decide(AcpTaskProfile.READONLY, everything, ToolKind.READ) is RequestPermissionOutcome.Selected)
        for (kind in listOf(ToolKind.EDIT, ToolKind.DELETE, ToolKind.MOVE)) {
            assertTrue(
                decide(AcpTaskProfile.READONLY, everything, kind) is RequestPermissionOutcome.Cancelled,
                "MUTATE kinds must hit the READONLY ceiling even with full authority: $kind",
            )
        }
        assertTrue(
            decide(AcpTaskProfile.READONLY, everything, ToolKind.EXECUTE) is RequestPermissionOutcome.Cancelled,
            "EXECUTE must hit the READONLY ceiling even with full authority",
        )
    }

    @Test
    fun `WRITE_RESTRICTED models a wider ceiling, and authority still gates it`() {
        val mutations = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE))
        assertTrue(
            decide(AcpTaskProfile.WRITE_RESTRICTED, mutations, ToolKind.EDIT)
                is RequestPermissionOutcome.Selected,
            "the confined-mutation profile honours a declared mutate authority",
        )
        val readsOnly = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ))
        assertTrue(
            decide(AcpTaskProfile.WRITE_RESTRICTED, readsOnly, ToolKind.EDIT)
                is RequestPermissionOutcome.Cancelled,
            "the profile widening does not grant: the authority still must",
        )
    }

    @Test
    fun `a human-required rule fails closed while no interaction seam exists`() {
        val humanRead = AcpAuthority(
            allowedEffects = listOf(AcpToolEffect.READ),
            humanRequiredEffects = listOf(AcpToolEffect.READ),
        )
        assertTrue(
            decide(AcpTaskProfile.READONLY, humanRead, ToolKind.READ) is RequestPermissionOutcome.Cancelled,
            "without an authorized-interaction seam the human leg denies, it never auto-approves",
        )
    }

    @Test
    fun `switch-mode and other effects are never granted by a data-effect authority`() {
        val read = AcpAuthority(allowedEffects = listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE, AcpToolEffect.EXECUTE))
        for (kind in listOf(ToolKind.SWITCH_MODE, ToolKind.OTHER)) {
            assertTrue(
                decide(AcpTaskProfile.WRITE_RESTRICTED, read, kind) is RequestPermissionOutcome.Cancelled,
                "MISC kinds need their own declaration, got a grant for $kind",
            )
        }
        val withMisc = AcpAuthority(allowedEffects = listOf(AcpToolEffect.MISC))
        assertTrue(
            decide(AcpTaskProfile.WRITE_RESTRICTED, withMisc, ToolKind.OTHER)
                is RequestPermissionOutcome.Selected,
        )
    }

    @Test
    fun `the effect mapping is total over the v1 kinds`() {
        val mapped = ToolKind.entries.associateWith { AcpPermissionPolicy.effectOf(it) }
        assertEquals(ToolKind.entries.size, mapped.size)
        assertEquals(AcpToolEffect.READ, mapped[ToolKind.READ])
        assertEquals(AcpToolEffect.READ, mapped[ToolKind.SEARCH])
        assertEquals(AcpToolEffect.MUTATE, mapped[ToolKind.EDIT])
        assertEquals(AcpToolEffect.MUTATE, mapped[ToolKind.DELETE])
        assertEquals(AcpToolEffect.EXECUTE, mapped[ToolKind.EXECUTE])
        assertEquals(AcpToolEffect.MISC, mapped[ToolKind.SWITCH_MODE])
    }

    @Test
    fun `pending permission requests are answered Cancelled when the session cancels`() {
        // AC#1's last clause, and the gap the published SDK leaves: 0.34.0 does not answer a
        // pending session/request_permission on session/cancel (upstream fixed it only after
        // this release), so the plugin owns the ledger. The decision is normally immediate —
        // the ledger exists for the unwind race and is proven on it directly.
        val broker = AcpPermissionLedger()
        val pending = CompletableDeferred<com.agentclientprotocol.model.RequestPermissionResponse>()
        broker.register(ToolCallId("tc-hang"), pending)
        assertEquals(1, broker.cancelOutstanding())
        val answered = pending.getCompleted()
        assertTrue(
            answered.outcome is RequestPermissionOutcome.Cancelled,
            "a pending request must resolve to the Cancelled outcome after cancel",
        )
        assertEquals(0, broker.cancelOutstanding(), "the ledger drains: a second cancel answers nothing")
        broker.register(ToolCallId("tc-2"), pending)
        broker.release(ToolCallId("tc-2"))
        assertEquals(0, broker.cancelOutstanding(), "an answered request must leave the ledger")
    }

    @Test
    fun `the authority serializes inside the task input with defaults stable`() {
        val withAuthority = AcpTaskInput(
            command = listOf("agent"), prompt = "p",
            authority = AcpAuthority(
                allowedEffects = listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE),
                humanRequiredEffects = listOf(AcpToolEffect.EXECUTE),
            ),
        )
        val encoded = AcpTaskStepDefinition.InputCodec.encode(withAuthority)
        assertEquals(withAuthority, AcpTaskStepDefinition.InputCodec.decode(encoded))
        // A journal row written BEFORE the authority existed must still decode: the field is
        // additive with a null default, so the durable vocabulary of PKH-133 does not break.
        val legacy = encoded.value.replace(Regex(",\"authority\":\\{.*?\\}"), "")
        assertEquals(withAuthority.copy(authority = null),
            AcpTaskStepDefinition.InputCodec.decode(
                dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(legacy),
            ),
        )
    }
}
