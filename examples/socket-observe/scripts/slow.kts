// Built-in-DSL script served by `pipelinek serve` for the PKH-143 socket example.
// Long enough to still be LIVE when the socket client cancels it; bounded so a forgotten
// serve never keeps a sleeper forever.
pipeline {
    stages {
        stage("work nobody waits for") {
            sh("sleep 240")
        }
    }
}
