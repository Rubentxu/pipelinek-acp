// =============================================================================
// The gate of this repository, run locally with PipelineK.
// =============================================================================
//
// **What this pays.** The plugin builds against the PUBLISHED SDK only — that is the premise
// this front validates — so the gate is: (1) compile and unit-test against the sealed bundle,
// (2) the assembled artifact carries the two documents admission reads and declares a range
// that admits the host that will run it, and (3) the installed host actually admits the
// operator jar and runs a step that talks ACP to a deterministic fixture agent over a pipe.
//
// No credentials anywhere in the run, and no network for the agent exchange: the fixture is
// in-repo and speaks over a pipe. The build stage resolves the published SDK and the ACP
// client from the Gradle cache or from Maven Central.
//
// **No hosted workflow runs this.** GitHub Actions, if it ever does anything here, is
// distribution — never the tests and never the gate.
//
// To run it:
//
//   scripts/run-gate.sh
//
// The script is what resolves the host and gives the run a state of its own; the
// plain `pipelinek run` leaves both to the caller, and a caller who gets them
// wrong measures a plugin no host admits.
//
// The SDK bundle directory the build resolves against is read from ACP_SDK_REPO (default
// /tmp/pksdk); the host version the provenance range is derived from is measured from the
// installed `pipelinek` itself, never hardcoded here — a number written in this file would be
// a second answer, and a stale one.
//
// The host is the one of the SDK the plugin compiles against: the plugin declares an api
// range whose floor is the newer of the two and whose ceiling is the next minor of the SDK,
// and that range is empty unless both halves sit in the same minor. scripts/host-sdk.sh
// resolves the pair — the bundle names the SDK, the installed host of that version runs the
// e2e rows — so every row below measures the same executable.

pipeline {
    stages {
        stage("build and unit-test") {
            // The plugin builds against the PUBLISHED SDK bundle only; the host version the
            // provenance range derives from is measured from the installed pipelinek, never
            // hardcoded. scripts/build-and-test.sh.
            timeout(time = 30, unit = "MINUTES") {
                sh("scripts/build-and-test.sh")
            }
        }
        stage("admission documents") {
            // Cheap and mechanical, and it can fail: admission refuses a jar whose manifest
            // is missing, whose SPI registration does not name the contributor, or whose
            // declared api range does not admit the host that will run it.
            sh("scripts/admission-documents.sh")
        }
        stage("e2e against the real host") {
            // The one row that starts the published product lives in scripts/e2e-acp.sh: the
            // installed host admits the operator jar, the script calls the plugin's own DSL,
            // and the step runs the whole ACP exchange against the deterministic fixture
            // agent. The gate asserts on the step's typed output as carried by the durable
            // journal — not on the exit code — so a refusal-shaped ok=false leaves this red.
            timeout(time = 15, unit = "MINUTES") {
                sh("scripts/e2e-acp.sh")
            }
        }
        stage("e2e isolated task") {
            // PKH-133's row, in scripts/e2e-acp-task.sh: two sequential acpTask delegations
            // against the shared session counter (one fresh session per task — no parked
            // connection) and a third run whose escaping cwd must be refused BEFORE launch,
            // both asserted on the durable journal rather than on the run's exit code.
            timeout(time = 20, unit = "MINUTES") {
                sh("scripts/e2e-acp-task.sh")
            }
        }
        stage("e2e composed session") {
            // PKH-135's row, in scripts/e2e-acp-session.sh: the installed host lowers
            // `acpSession` with TWO sequential turns whose second reply must echo BOTH
            // questions (the agent-side context survives the host's run, AC#1), the close/
            // delete witness proves the normal exit says close and never deletes (AC#3), and
            // a minimal-caps plan declaring list+logout answers ACP_SESSION_CAPABILITY_MISSING
            // before the session is even asked (AC#2/AC#4). Asserted on operation_journal.
            timeout(time = 20, unit = "MINUTES") {
                sh("scripts/e2e-acp-session.sh")
            }
        }
        stage("e2e permission authority") {
            // PKH-134's row, in scripts/e2e-acp-authority.sh: the authority DECLARED through
            // the published DSL changes what the real host's delegation answers on the
            // reverse leg — a granted permission names the optionId the agent offered and
            // rides out journaled; the same request without the declaration stays denied
            // (PKH-132's default preserved); and a READONLY profile declaring wider
            // authority is refused at the seam BEFORE launch. Asserted on operation_journal.
            timeout(time = 20, unit = "MINUTES") {
                sh("scripts/e2e-acp-authority.sh")
            }
        }
    }
}
