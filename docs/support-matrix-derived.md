# Matriz de soporte derivada de AcpCoverageMatrix (src/main/kotlin/pipelinek/acp/AcpCoverageMatrix.kt)

| Metodo | Direccion | Clasificacion | Evidencia/Razon |
| --- | --- | --- | --- |
| initialize | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| authenticate | AGENT_SIDE | UNSUPPORTED |  |
| logout | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| auth/status | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| providers/list | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| providers/set | AGENT_SIDE | UNSUPPORTED |  |
| providers/disable | AGENT_SIDE | UNSUPPORTED |  |
| session/new | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/load | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/list | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/resume | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/close | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/delete | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/fork | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/prompt | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/cancel | AGENT_SIDE | TESTED | AcpTaskTimeTest |
| AcpSessionRoundTripTest | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| AcpSessionRoundTripTest | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| AcpSessionRoundTripTest | AGENT_SIDE | TESTED | AcpSessionRoundTripTest |
| session/archive | AGENT_SIDE | UNSUPPORTED |  |
| session/unarchive | AGENT_SIDE | UNSUPPORTED |  |
| session/status | AGENT_SIDE | UNSUPPORTED |  |
| session/options | AGENT_SIDE | UNSUPPORTED |  |
| mcp/message | AGENT_SIDE | ROLE_EXCLUDED |  |
| nes/start | AGENT_SIDE | ROLE_EXCLUDED |  |
| nes/suggest | AGENT_SIDE | ROLE_EXCLUDED |  |
| nes/accept | AGENT_SIDE | ROLE_EXCLUDED |  |
| nes/reject | AGENT_SIDE | ROLE_EXCLUDED |  |
| nes/close | AGENT_SIDE | ROLE_EXCLUDED |  |
| document/didOpen | AGENT_SIDE | ROLE_EXCLUDED |  |
| document/didChange | AGENT_SIDE | ROLE_EXCLUDED |  |
| document/didFocus | AGENT_SIDE | ROLE_EXCLUDED |  |
| document/didSave | AGENT_SIDE | ROLE_EXCLUDED |  |
| document/didClose | AGENT_SIDE | ROLE_EXCLUDED |  |
| session/update | CLIENT_SIDE | TESTED | AcpSchemaMatrixTest |
| AcpReverseOperationsTest | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| AcpReverseOperationsTest | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| terminal/create | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| terminal/output | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| AcpReverseOperationsTest | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| terminal/kill | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| terminal/release | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| elicitation/create | CLIENT_SIDE | TESTED | AcpReverseOperationsTest |
| elicitation/complete | CLIENT_SIDE | TESTED | AcpCoverageMatrixTest |
| mcp/message | CLIENT_SIDE | ROLE_EXCLUDED |  |
| proxy/initialize | PROXY | ROLE_EXCLUDED |  |
| proxy/successor | PROXY | ROLE_EXCLUDED |  |
