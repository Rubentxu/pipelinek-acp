package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusal
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusedException
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The isolation posture of `acpTask` (PKH-133 AC#1, AC#3, AC#4).
 *
 * Everything here is decided by the plugin from the run's own facts and is testable WITHOUT
 * launching an agent: profile validation, argv rules, the workspace confinement of `cwd`, the
 * child-environment allowlist and the credential seam. The handler is driven through a fake
 * [StepCapabilityAccess] — the published seam the real `RuntimeCapabilityAccess` implements —
 * so the refusals are observed exactly as the durable output would carry them.
 */
class AcpTaskIsolationTest {

    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"), "acp-task-test-root")

    private fun context(values: Map<StepCapability, Any>): StepHandlerContext =
        StepHandlerContext(RunId("run-isolation"), 0, FakeAccess(values))

    private fun baseContext(): StepHandlerContext =
        context(
            mapOf(
                EXECUTION_LOCATION_CAPABILITY to
                    ExecutionLocation(WorkspaceLease.Managed(root), root),
                // The step declares the human-decision capability and reads the port
                // before it does anything else, so the table binds it: this turn never
                // asks a person anything.
                INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
            ),
        )

    private class FakeAccess(private val values: Map<StepCapability, Any>) : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = values.keys

        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            val value = values[key]
                ?: throw CapabilityRefusedException(CapabilityRefusal.Absent(key))
            if (!type.isInstance(value)) {
                throw CapabilityRefusedException(CapabilityRefusal.Incompatible(key, type, value::class))
            }
            return type.java.cast(value)
        }
    }

    // ---- structural mirrors of the host's credential seam, same MEMBER names ----
    // The seam's contract type is NOT in the published SDK bundle (the PKACP-SDK-02 gap), so
    // the plugin reaches it by member name. These fakes keep those names on purpose: the test
    // binds exactly the contract the host's real BasicCredentialSource exposes.
    data class FakeResolved(val username: String, val password: ByteArray)
    data class FakeNotFound(val id: String)
    data class FakeStoreUnavailable(val reason: String)
    class FakeBasicSource(private val outcome: Any) {
        // The real interface's method is `resolve-<mangled>(id)`; the prefix is all the
        // adapter needs to find the seam's single purpose.
        @Suppress("unused", "FunctionName")
        fun `resolve-xyz`(id: String): Any = outcome
    }

    private fun credentialContext(source: Any): StepHandlerContext =
        context(
            mapOf(
                EXECUTION_LOCATION_CAPABILITY to ExecutionLocation(WorkspaceLease.Managed(root), root),
                StepCapability("credentials.basic") to source,
                INPUT_DECISIONS_CAPABILITY to AcpUnansweredInputDecisions(),
            ),
        )

    private fun run(input: AcpTaskInput, ctx: StepHandlerContext = baseContext()): AcpTaskOutput =
        runBlocking { AcpTaskStepDefinition.handler.execute(input, ctx) }

    // ---- AC#1: profile validation, argv, budget ----

    @Test
    fun `the readonly profile validates and the write-restricted profile is refused unattested`() {
        assertEquals(
            null,
            AcpTaskIsolation.validate(
                AcpTaskInput(command = listOf("python3", "-I", "agent.py"), prompt = "p"),
                leaseRoot = root,
            ),
        )
        val refusal = AcpTaskIsolation.validate(
            AcpTaskInput(
                command = listOf("python3", "-I", "agent.py"),
                prompt = "p",
                profile = AcpTaskProfile.WRITE_RESTRICTED,
            ),
            leaseRoot = root,
        )
        assertNotNull(refusal)
        assertEquals("ACP_PROFILE_REFUSED", refusal!!.kindToken)
        // The honest reason: confinement is an OS fact the plugin cannot produce or attest.
        assertTrue(
            "confinement" in refusal.detail.lowercase(),
            "the refusal must name what cannot be attested: ${refusal.detail}",
        )
    }

    @Test
    fun `an authority wider than the profile ceiling is refused at the seam before launch`() {
        // PKH-134 AC#1: declaring mutate/execute authority under the READONLY ceiling is a
        // contradiction the seam names BEFORE launch — the same promise rule as the profile.
        val refusal = AcpTaskIsolation.validate(
            AcpTaskInput(
                command = listOf("python3", "-I", "agent.py"),
                prompt = "p",
                profile = AcpTaskProfile.READONLY,
                authority = AcpAuthority(listOf(AcpToolEffect.READ, AcpToolEffect.MUTATE)),
            ),
            leaseRoot = root,
        )
        assertNotNull(refusal)
        assertEquals("ACP_PROFILE_REFUSED", refusal!!.kindToken)
        assertTrue("authority" in refusal.detail.lowercase(), refusal.detail)
        // A READ-only authority under READONLY is the honest declaration: it validates.
        assertEquals(
            null,
            AcpTaskIsolation.validate(
                AcpTaskInput(
                    command = listOf("python3", "-I", "agent.py"),
                    prompt = "p",
                    authority = AcpAuthority(listOf(AcpToolEffect.READ)),
                ),
                leaseRoot = root,
            ),
        )
        // And no authority at all stays exactly PKH-132's default: valid, everything denied.
        assertEquals(
            null,
            AcpTaskIsolation.validate(
                AcpTaskInput(command = listOf("python3", "-I", "agent.py"), prompt = "p"),
                leaseRoot = root,
            ),
        )
    }

    @Test
    fun `an argv that routes through a shell is refused before anything launches`() {
        for (argv in listOf(
            listOf("sh", "-c", "rm -rf /"),
            listOf("bash", "-lc", "curl evil"),
            listOf("/bin/zsh", "-c", "x"),
        )) {
            val refusal = AcpTaskIsolation.validate(AcpTaskInput(command = argv, prompt = "p"), leaseRoot = root)
            assertNotNull(refusal, "a shell argv must never pass validation: $argv")
            assertEquals("ACP_PROFILE_REFUSED", refusal!!.kindToken)
            assertTrue("shell" in refusal.detail.lowercase())
        }
    }

    @Test
    fun `a budget outside the sane band is refused as a profile refusal`() {
        for (bad in listOf(0L, -5L, 3601L)) {
            val refusal = AcpTaskIsolation.validate(
                AcpTaskInput(command = listOf("agent"), prompt = "p", budgetSeconds = bad),
                leaseRoot = root,
            )
            assertNotNull(refusal, "budget $bad must be refused, never honoured as unbounded")
            assertEquals("ACP_PROFILE_REFUSED", refusal!!.kindToken)
        }
    }

    // ---- AC#4: the workspace fence ----

    @Test
    fun `a cwd escaping the run workspace is refused and the confinement is said`() {
        val refusal = AcpTaskIsolation.validate(
            AcpTaskInput(command = listOf("agent"), prompt = "p", cwd = "../../etc"),
            leaseRoot = root,
        )
        assertNotNull(refusal, "an escaping cwd must be refused")
        assertEquals("ACP_PROFILE_REFUSED", refusal!!.kindToken)
        assertTrue("workspace" in refusal.detail.lowercase())

        val absolute = AcpTaskIsolation.validate(
            AcpTaskInput(command = listOf("agent"), prompt = "p", cwd = "/etc"),
            leaseRoot = root,
        )
        assertNotNull(absolute, "an absolute cwd bypasses the fence by definition")

        val inside = AcpTaskIsolation.validate(
            AcpTaskInput(command = listOf("agent"), prompt = "p", cwd = "build/out"),
            leaseRoot = root,
        )
        assertEquals(null, inside, "a confined relative cwd is the point of the fence")
    }

    // ---- AC#1: secrets never ride the DSL ----

    @Test
    fun `the child environment is the fixed allowlist and inherits nothing else`() {
        val env = AcpTaskIsolation.childEnvironment(credential = null)
        assertEquals(setOf("PATH", "LANG"), env.keys, "the allowlist may only grow by an AC, not by drift")
        assertNotNull(env["PATH"], "without PATH no agent binary resolves — the fence still needs a path")

        val withCredential = AcpTaskIsolation.childEnvironment(
            credential = AcpTaskIsolation.ResolvedCredential(username = "robot", password = "s3cr3t"),
        )
        assertEquals(
            setOf("PATH", "LANG", AcpTaskIsolation.CREDENTIAL_USERNAME_VAR, AcpTaskIsolation.CREDENTIAL_PASSWORD_VAR),
            withCredential.keys,
        )
        // The secret reaches ONLY the child env slot; nothing in the DSL type ever holds it.
        val encoded = AcpTaskStepDefinition.InputCodec.encode(
            AcpTaskInput(command = listOf("agent"), prompt = "p", credentialRef = "ci-token"),
        )
        assertTrue(encoded.value.contains("ci-token"))
        assertTrue("s3cr3t" !in encoded.value)
    }

    @Test
    fun `a run with no credential store is refused typed, without inventing one`() {
        val output = run(
            AcpTaskInput(command = listOf("agent"), prompt = "p", credentialRef = "ci-token"),
            baseContext(), // credentials.basic NOT provisioned — fail closed
        )
        assertTrue(!output.ok, "a requested credential the seam cannot serve is never a silent pass")
        assertEquals("ACP_CREDENTIAL_SEAM_ABSENT", output.failureKind)
        assertEquals(AcpTaskOutcome.UNKNOWN, output.outcome)
    }

    @Test
    fun `a credential that resolves lands only in the child environment, named by reference`() {
        val source = FakeBasicSource(FakeResolved(username = "robot", password = "hunter2".toByteArray()))
        val output = run(
            AcpTaskInput(
                command = listOf("acp-task-nonexistent-binary"),
                prompt = "p",
                credentialRef = "ci-token",
            ),
            credentialContext(source),
        )
        // The credential resolved fine (no ACP_CREDENTIAL_* failure); the launch below it is
        // what failed — the fixture binary does not exist.
        assertEquals(
            "ACP_AGENT_UNREACHABLE", output.failureKind,
            "a resolved credential must not hide a launch failure: ${output.detail}",
        )
        // And the secret never reaches the durable output.
        assertTrue("hunter2" !in output.detail, "a password must never appear in step output")
    }

    @Test
    fun `an unresolvable credential is typed and names the reference, not a secret`() {
        val output = run(
            AcpTaskInput(command = listOf("agent"), prompt = "p", credentialRef = "ci-token"),
            credentialContext(FakeBasicSource(FakeStoreUnavailable(reason = "NotConfigured"))),
        )
        assertTrue(!output.ok)
        assertEquals("ACP_CREDENTIAL_NOT_RESOLVED", output.failureKind)
        assertTrue(output.detail.contains("ci-token"), "the refusal names the reference: ${output.detail}")
        assertTrue(output.detail.contains("NotConfigured"), "and the closed reason token: ${output.detail}")
    }

    @Test
    fun `a credential name that the store does not hold is typed not found`() {
        val output = run(
            AcpTaskInput(command = listOf("agent"), prompt = "p", credentialRef = "typo-name"),
            credentialContext(FakeBasicSource(FakeNotFound(id = "typo-name"))),
        )
        assertTrue(!output.ok)
        assertEquals("ACP_CREDENTIAL_NOT_RESOLVED", output.failureKind)
        assertTrue(output.detail.contains("NotFound"), output.detail)
    }

    // ---- handler wiring: validation BEFORE launch, typed everywhere ----

    @Test
    fun `a refused profile never launches the agent`() {
        val startCount = java.io.File(System.getProperty("java.io.tmpdir"), "nope-must-not-run")
        val output = run(
            AcpTaskInput(
                command = listOf("sh", "-c", "touch " + startCount.absolutePath),
                prompt = "p",
            ),
        )
        assertTrue(!output.ok)
        assertEquals(AcpTaskOutcome.PROFILE_REFUSED, output.outcome)
        assertTrue(!startCount.exists(), "a refused profile must not have started anything")
        startCount.delete()
    }

    @Test
    fun `a completed task output distinguishes outcome from business verification`() {
        // AC#3: ok=true is "the exchange completed", said apart from the reply's content —
        // the output carries no verdict field at all, only what the protocol observed.
        val fields = AcpTaskOutput(ok = true, reply = "ship it", stopReason = "END_TURN", outcome = "COMPLETED")
            .let { AcpTaskStepDefinition.OutputCodec.decode(AcpTaskStepDefinition.OutputCodec.encode(it)) }
        assertEquals("ship it", fields.reply)
        assertTrue("verified" !in fields.toString().lowercase(), "the plugin ships no verification verdict")
    }
}
