package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusedException
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The isolation decisions of `acpTask`, pure and testable without a process or a protocol.
 *
 * Everything here answers "may this delegation run as asked?" from the facts the handler
 * already holds — the run's workspace root and the input the script declared — so the gate
 * rows in AcpTaskIsolationTest need no fixture. The rule the class exists for: a promise the
 * target cannot keep (confinement, argv shape, budget band, workspace fence) is a typed
 * refusal BEFORE launch, never a silent downgrade and never an attempt.
 *
 * ## The credential seam is reached structurally, and that is the honest shape
 * The host provisions `credentials.basic` on every run path, but its contract type
 * (`BasicCredentialSource`) lives in `pipeline-credentials-api`, a module NOT shipped in the
 * published SDK bundle. The plugin therefore cannot name the type at compile time without
 * either patching the SDK bundle (forbidden: this front validates the PUBLISHED seam) or
 * importing the host's internals (forbidden by the independent-build rule). Reaching the seam
 * by member name is the only route left; the absence of the seam fails closed with a typed
 * refusal. This is the demonstrated PKACP-SDK-02 gap, recorded in the task's handoff note.
 */
object AcpTaskIsolation {

    /** Budget band accepted in seconds. The ceiling is AC#1's "profiles never widen the host":
     * an unbounded delegation is an unbounded host process, and no profile of this plugin
     * may ask for that. */
    const val BUDGET_MIN_SECONDS: Long = 1
    const val BUDGET_MAX_SECONDS: Long = 3600

    /** The two environment slots a well-behaved CLI agent expects; nothing else crosses. */
    val CREDENTIAL_USERNAME_VAR: String = "PIPELINEK_ACP_CREDENTIAL_USERNAME"
    val CREDENTIAL_PASSWORD_VAR: String = "PIPELINEK_ACP_CREDENTIAL_PASSWORD"

    /** Names that reinterpret argv themselves — handing a delegation to one is AC#1's "argv
     * without shell" broken at the door. Public because the terminal reverse leg (PKH-134
     * AC#3) refuses the same names for agent-requested commands: one list, one policy. */
    val SHELL_NAMES: Set<String> = setOf(
        "sh", "bash", "zsh", "dash", "ksh", "fish", "cmd", "cmd.exe", "powershell", "pwsh",
    )

    /** A resolved credential: username plus secret, held only long enough to build the child env. */
    data class ResolvedCredential(val username: String, val password: String)

    /**
     * Why this input may not be delegated, or null when the exchange may launch.
     *
     * Checks, in order, all decidable from [input] plus the lease root: profile honouring,
     * argv shape, budget band, workspace fence. None of them starts a process.
     */
    fun validate(input: AcpTaskInput, leaseRoot: Path): AcpRefusal? {
        if (input.profile == AcpTaskProfile.WRITE_RESTRICTED) {
            return AcpRefusal.ProfileRefusal(
                "the WRITE_RESTRICTED profile demands effective confinement of the workspace; " +
                    "this host offers no isolation attestation seam to plugins, and a plugin " +
                    "building confinement on its own would be a second isolation authority " +
                    "outside the host's policy",
            )
        }
        // PKH-134 AC#1: an authority wider than the profile's ceiling is a contradiction the
        // seam names BEFORE launch (the ACP_PROFILE_REFUSED precedent) — not a grant silently
        // narrowed at decision time, because a script that declared mutate-under-readonly
        // deserves to hear its own declaration was refused, not to watch it vanish.
        if (input.authority != null && AcpPermissionPolicy.ceilingViolation(input.profile, input.authority)) {
            return AcpRefusal.ProfileRefusal(
                "the declared authority grants effects the ${input.profile} profile cannot " +
                    "host; a READONLY delegation declares at most READ effects",
            )
        }
        if (input.command.isEmpty()) {
            return AcpRefusal.ProfileRefusal("the agent argv is empty; there is nothing to launch")
        }
        val executable = Paths.get(input.command.first()).fileName?.toString()?.lowercase() ?: ""
        if (executable in SHELL_NAMES) {
            return AcpRefusal.ProfileRefusal(
                "argv[0] is the shell '$executable'; a shell reinterprets the delegated argv " +
                    "and opens an injection seam the profile never asked to widen",
            )
        }
        if (input.budgetSeconds !in BUDGET_MIN_SECONDS..BUDGET_MAX_SECONDS) {
            return AcpRefusal.ProfileRefusal(
                "the budget ${input.budgetSeconds}s is outside the band " +
                    "${BUDGET_MIN_SECONDS}s..${BUDGET_MAX_SECONDS}s; a delegation may never ask " +
                    "to outlive the run that hosts it",
            )
        }
        if (input.cwd.isNotBlank()) {
            val requested = Path.of(input.cwd)
            if (requested.isAbsolute) {
                return AcpRefusal.ProfileRefusal(
                    "cwd '${input.cwd}' is absolute; the delegated workspace is fenced by the " +
                        "run's lease root, and absolute paths bypass the fence by construction",
                )
            }
            val resolved = leaseRoot.normalize().resolve(requested).normalize()
            if (!resolved.startsWith(leaseRoot.normalize())) {
                return AcpRefusal.ProfileRefusal(
                    "cwd '${input.cwd}' resolves outside the run's workspace; the delegation " +
                        "cannot see beyond the lease root the host granted",
                )
            }
        }
        return null
    }

    /**
     * The child environment: the fixed allowlist, plus the credential slots when one resolved.
     *
     * Inheritance is replaced wholesale at launch (see [AcpAgentProcess.launch]), so the only
     * variables that ever reach the agent are named here. PATH is required (argv[0] must
     * resolve) and LANG is defaulted rather than dropped, because two CLI conventions are
     * worth an identical posture on every box; the inherited value is kept when present.
     */
    fun childEnvironment(credential: ResolvedCredential?): Map<String, String> {
        val inherited = System.getenv()
        val env = linkedMapOf(
            "PATH" to (inherited["PATH"] ?: "/usr/local/bin:/usr/bin:/bin"),
            "LANG" to (inherited["LANG"] ?: "C.UTF-8"),
        )
        if (credential != null) {
            env[CREDENTIAL_USERNAME_VAR] = credential.username
            env[CREDENTIAL_PASSWORD_VAR] = credential.password
        }
        return env
    }

    private val CREDENTIALS_BASIC: StepCapability = StepCapability("credentials.basic")

    /**
     * Resolve [credentialRef] through the host's credential seam, structurally.
     *
     * Returns either the resolved credential or a typed refusal; a seam that cannot be found,
     * cannot be read or answers with a shape the plugin does not recognise is a refusal, never
     * a guess. Secret material travels only into [childEnvironment]; the refusal strings name
     * the reference and the CLOSED reason, never the lookup's content.
     */
    fun resolveCredential(context: StepHandlerContext, credentialRef: String): Pair<ResolvedCredential?, AcpRefusal?> {
        val seam: Any = try {
            context.capabilities.get(CREDENTIALS_BASIC, Any::class)
        } catch (e: CapabilityRefusedException) {
            return null to AcpRefusal.CredentialSeamAbsent(credentialRef, e.refusal.describe())
        }
        val resolve = sequenceOf(seam.javaClass.methods.asSequence(), generateSequence(seam.javaClass.superclass) { it.superclass }
            .flatMap { it.methods.asSequence() })
            .flatten()
            .firstOrNull { it.name.startsWith("resolve") && it.parameterCount == 1 }
            ?: return null to AcpRefusal.CredentialSeamAbsent(
                credentialRef, "the seam offered under 'credentials.basic' has no resolve() member",
            )
        val argument = if (resolve.parameterTypes[0] == String::class.java) credentialRef
        else CredentialsId(credentialRef)
        val outcome = try {
            resolve.invoke(seam, argument)
        } catch (e: Exception) {
            return null to AcpRefusal.CredentialSeamAbsent(credentialRef, "the lookup threw ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
        }
            ?: return null to AcpRefusal.CredentialSeamAbsent(credentialRef, "the lookup answered null")

        val username = readMember(outcome, "getUsername") as? String
        val password = readMember(outcome, "getPassword")
        if (username != null && password != null) {
            val secret = when (password) {
                is ByteArray -> String(password, Charsets.UTF_8)
                else -> password.toString()
            }
            return ResolvedCredential(username, secret) to null
        }
        // Not the Resolved shape: report the CLOSED case. The class simpleName is the case name
        // in the host's sealed hierarchy (NotFound, StoreUnavailable, KindUnsupported), and the
        // `reason` member, when present, is a closed enum — both are safe to journal.
        val case = outcome.javaClass.simpleName.removePrefix("Fake")
        val reason = readMember(outcome, "getReason")?.toString()?.let { it.substringAfterLast('.') }
        return null to AcpRefusal.CredentialNotResolved(
            credentialRef, if (reason != null) "$case:$reason" else case,
        )
    }

    private fun readMember(target: Any, getter: String): Any? = try {
        target.javaClass.getMethod(getter).invoke(target)
    } catch (e: Exception) {
        null
    }
}
