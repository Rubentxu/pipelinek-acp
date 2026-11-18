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
    }
}
