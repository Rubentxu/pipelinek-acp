// =============================================================================
// The gate of this repository, run locally with PipelineK.
// =============================================================================
//
// **What this pays.** The plugin builds against the PUBLISHED SDK only — that is the premise
// this front validates — so the gate is: (1) compile and unit-test against the sealed bundle,
// (2) the assembled artifact carries the two documents admission reads, and (3) the installed
// host actually admits the operator jar and runs a step that talks ACP to a deterministic
// fixture agent over a pipe. No network, no credentials anywhere in the run.
//
// **No hosted workflow runs this.** GitHub Actions, if it ever does anything here, is
// distribution — never the tests and never the gate.
//
// To run it:
//
//   root="$(git rev-parse --show-toplevel)"
//   state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/$(basename "$root")"
//   mkdir -p "$state"
//   pipelinek run --workspace "$root" --db "$state/run.sqlite" \
//     --control-root "$state/control" "$root/pipeline.kts"
//
// The SDK bundle directory the build resolves against is read from ACP_SDK_REPO (default
// /tmp/pksdk); the host version the provenance range is derived from is measured from the
// installed `pipelinek` itself, never hardcoded here — a number written in this file would be
// a second answer, and a stale one.

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
            // is missing or whose SPI registration does not name the contributor.
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
