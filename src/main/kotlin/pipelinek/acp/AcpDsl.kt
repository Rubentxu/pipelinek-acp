package pipelinek.acp

import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepEmitter

/**
 * The plugin teaching the DSL its own step; the core compiler never learns the name `acpPrompt`.
 *
 * ```kotlin
 * import pipelinek.acp.acpPrompt
 * pipeline {
 *     stages {
 *         stage("Agent") {
 *             acpPrompt(listOf("my-agent", "--acp"), "summarize the diff")
 *         }
 *     }
 * }
 * ```
 *
 * The call lowers to a generic registry step — the same primitive every external plugin uses —
 * and the typed [AcpPromptOutput] the handler returns is read by whoever bound the step's name,
 * exactly as with any other step. This function declares; it never executes.
 *
 * The contract route works in every scope that hosts steps; the [StageScope] overload exists
 * because the receiver of a Kotlin extension is part of the JVM descriptor a PRECOMPILED
 * script names — published scripts compiled against this plugin bind to it, so removing it
 * would break them while the generic route still resolves for source scripts. The historic
 * route delegates to the contract route; there is one lowering, never two.
 */
fun <B : StepEmitter<B>> StepEmitter<B>.acpPrompt(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
) {
    registryStep(
        stepKey = AcpPromptStepDefinition.KEY,
        encodedInput = AcpPromptStepDefinition.InputCodec.encode(
            AcpPromptInput(
                command = command,
                prompt = prompt,
                cwd = cwd,
                timeoutSeconds = timeoutSeconds,
            ),
        ),
    )
}

/** The historic `StageScope` route; see the contract route for why both exist and are one. */
fun StageScope.acpPrompt(
    command: List<String>,
    prompt: String,
    cwd: String = "",
    timeoutSeconds: Long = 300,
) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.acpPrompt(command, prompt, cwd, timeoutSeconds)
}
