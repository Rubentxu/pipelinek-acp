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

## Filas autoasertivas (la puerta las ejecuta)

Cada fila de `scripts/e2e-examples.sh` es un script que corre el ejemplo contra el
host instalado y **aserta sobre el journal durable** (`operation_journal`), no sobre
el exit code: un `ok=false` con forma de exito no pasa verde.

| Fila | Que demuestra |
| --- | --- |
| `readonly-review/run.sh` | ley de `01` verificada en el journal: contexto que viaja, close sin delete |
| `goldfish-review/run.sh` | ley de `03`: dos revisores, cero contexto compartido, cero efectos duplicados |
| `mcp-gateway/run.sh` | ley de `04` completa: ledger tipado de las seis llamadas y gateway MUERTO al acabar el scope |
| `test-repair/run.sh` | `agenticRepair` sobre dos proyectos descartables: el que repara acaba VERIFIED con patch ARCHIVADO (nunca commiteado); el mentiroso acaba NO_PROGRESS/verificado=no |
| `hodei-verify/verify.sh` | el contrato de verificacion de un orquestador externo (AC#4) |
| `socket-observe/observe-and-cancel.sh` | cliente del socket de servicio: estado/permiso/eventos/resultado de un run real y cancelacion; el token de solo-lectura es REFUSADO al cancelar (AC#3) |

## Hodei como orquestador externo (AC#4): ownership y limites

`hodei-verify/verify.sh` reproduce el papel de cualquier planificador externo
(Hodei, un CI, un humano con una shell) **sin Hodei**: el contrato invocado es el
`pipelinek` publicado — argv hacia fuera, status de salida y archivos hacia dentro.

- **Ownership.** El orquestador es dueno del directorio candidato y de su propio
  estado (memoria, scheduler, backlog): nada de eso se le pide ni se le toca. El
  plugin es dueno del bucle de reparacion/verificacion; el host es dueno del journal
  donde queda la evidencia tipada (`operation_journal`) y del archivo del candidato
  (`build/examples/hodei-evidence/`).
- **Consumo de evidencia.** Lo unico que lee el orquestador es una linea JSON con el
  veredicto (`ok`, `outcome`, `agentTurns`, `evidence`, `reportPath`) extraida del
  journal en modo solo-lectura. La decision la toma el check, no las palabras del
  agente: la fila verde verifica SIN despertar al agente (`agentTurns == 0`); la roja
  queda `ok=false` con outcome tipado aunque el agente afirme lo contrario.
- **Limites documentados.** (a) El run puede terminar rojo en la fila negativa: la
  evidencia es el veredicto del journal, no el exit code — por eso el script encaja el
  `|| true` y despues lee el ledger. (b) La via `run.start` por socket del servicio no
  admite nominar el jar del plugin: un orquestador que quiera el plugin cargado invoca
  `pipelinek run --plugin-jar` (CLI), no el servicio; ese limite es del host y queda
  anotado aqui, no se pide cambio a repo ajeno.

## Matriz de soporte (AC#5)

La matriz NO se reescribe a mano: `./scripts/export-support-matrix.sh` la deriva de
`AcpCoverageMatrix.kt` (la fuente ejecutable que el gate de PKH-141 ya rechaza si una
fila queda sin clasificar). Default restrictivo: lo no `TESTED` es `UNSUPPORTED` o
`ROLE_EXCLUDED` y no se promete. Costes observables y limites: los `timeoutSeconds` y
`maxRepairAttempts` de cada pipeline son el presupuesto publicado; los resultados de
las filas ejecutadas quedan en los recibos de la puerta.
