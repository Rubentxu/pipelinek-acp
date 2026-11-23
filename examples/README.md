# Ejemplos agenticos consumibles (PKH-143)

Cuatro pipelines que funcionan **sin tokens y sin red**: el agente es siempre un
fixture determinista del propio repo (`src/test/resources/acp_*.py`), que habla el
wire ACP real por tubería. Cada carpeta lleva su `*.pipeline.kts` runnable y aqui
arriba esta como ejecutarlos contra el **PipelineK distribuido** con el plugin.

## Preparacion (una vez)

```bash
./scripts/build-and-test.sh                 # compila y pasa las leyes contra el SDK publicado
GRADLE_BIN=... systemd-run --user --scope --slice=dev-jvm.slice <gradle> operatorJar \
  -PsdkRepo=/tmp/pksdk -PsdkVersion=<v> -PpipelinekHostVersion=<host>   # o simplemente:
./scripts/run-examples.sh                   # construye el artefacto si falta y corre los cuatro
```

El host que admite el artefacto es el del SDK con el que el plugin compilo
(`scripts/host-sdk.sh`); el artefacto es `build/libs/pipelinek-acp-0.1.0-operator.jar`
y se instala nominandolo con `--plugin-jar` (no hay daemon ni instalacion global).

## Ejecutar un ejemplo a mano

```bash
mkdir -p build/pkh143-examples
pipelinek run --workspace . \
  --db build/pkh143-examples/01.sqlite --control-root build/pkh143-examples/01-control \
  --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
  examples/01-readonly-review/review.pipeline.kts
pipelinek observe <runId> --db build/pkh143-examples/01.sqlite --view events
```

| Ejemplo | DSL | Que demuestra |
| --- | --- | --- |
| `01-readonly-review` | `acpSession` | revision de SOLO LECTURA: dos turnos, misma sesion, el contexto viaja; salida normal = CLOSE, nunca DELETE (witness) |
| `02-agentic-repair` | `agenticRepair` | test-repair acotado: presupuesto de intentos, verificacion INDEPENDIENTE (check.py, argv del plan), candidato + patch archivados, nunca commiteados |
| `03-two-reviewers` | `acpReactive` x2 | dos revisores Goldfish independientes: sin contexto compartido (el `seen:` de B solo ecoa su pregunta) y sin efectos duplicados (los `EventKey` del ledger llevan nonce por conexion y nunca colisionan) |
| `04-mcp-gateway` | `acpReactive` + `acpExpose` | gateway MCP de sesion: el fixture llama `tools/list` y `tools/call` durante su propio turno; schema denial tipado y dedupe por idempotency key |

## Agentes reales (opt-in)

Sustituir el argv del fixture por un agente ACP real (p. ej. `claude-agent-acp`) en
la linea `command` de cada pipeline. Requiere credenciales del agente y NO forma
parte de la puerta: las leyes que estos ejemplos afirman se miden con los fixtures.
El estado de soporte de adaptadores reales se registra aparte (variables
`PKH141_ACP_ADAPTER_*`, ver README raiz).
