// Built-in-DSL script served by `pipelinek serve` for the PKH-143 socket example.
// No plugin, no imports: a run that asks a PERSON through the runtime's own input gate.
// A socket client (examples/socket-observe/socket_client.py) watches it, answers the
// question over the service socket, follows the event street and reads the terminal.
pipeline {
    stages {
        stage("ask the person") {
            input(message = "PKH143: promote the candidate? (socket-observed question)",
                  ok = "Proceed", submitter = "pipelinek-operators", timeoutSeconds = 120) {
                sh("echo PROMOTED-EVIDENCE: the person answered through the socket")
            }
        }
        stage("close the example") {
            sh("echo PKH143-GATE-DONE")
        }
    }
}
