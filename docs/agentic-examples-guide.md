# Guía pública: perfiles, interacción y costes observables (PKH-143)

Esta guía documenta LO QUE ESTA PROBADADO en este repositorio. Ninguna promesa de
soporte sin prueba: donde no hay evidencia, dice refusal o `NOT_RUN`. Las filas de
soporte del wire NO se reescriben aqui: se derivan de la fuente ejecutable
[`AcpCoverageMatrix.kt`](../src/main/kotlin/pipelinek/acp/AcpCoverageMatrix.kt)
con [`scripts/export-support-matrix.sh`](../scripts/export-support-matrix.sh),
y el gate ya rechaza la fila sin clasificar (`AcpCoverageMatrixTest`).

## Los ejemplos

[`examples/`](../examples/README.md) contiene cuatro pipelines runnable contra el
PipelineK distribuido y sin tokens (agente fixture determinista del repo):
revision de solo lectura (`acpSession`), test-repair acotado (`agenticRepair`),
dos revisores Goldfish independientes (dos `acpReactive` cuyo ledger `EventKey`
demuestra que ni contexto ni efectos se comparten) y el gateway MCP de sesión
(`acpExpose`, con un agente que llama `tools/call` durante su propio turno).
Se verifican con una fila:

```bash
./scripts/run-examples.sh        # AC#1: los cuatro, aserciones sobre operation_journal
./scripts/verify-distributed-artifact.sh   # AC#2: manifest, apiRange, firma, admission
./scripts/e2e-socket-observe.sh            # AC#3: cliente de socket observa y cancela
```

## Perfiles de delegación y defaults restrictivos

| Perfil | Que promete | Que niega | Prueba |
| --- | --- | --- | --- |
| `READONLY` (default) | nada escribe el paso; los `session/request_permission` del agente se niegan a nivel de protocolo (`DenyingOperations`) | confinamiento OS: el plugin NO lo afirma — no reclama lo que el SDK publicado no le deja atestiguar | `e2e-acp-task.sh` (fuga de cwd rechazada ANTES del launch: `ACP_PROFILE_REFUSED`), `AcpAuthorityTest` |
| `WRITE_RESTRICTED` | — | el target que no puede atestar confinamiento REFUSA el perfil en vez de degradar en silencio (`IsolationProvider` es interno al host; sin seam de atestación no hay promesa) | `AcpTaskIsolationTest`/`AcpAuthorityTest` (matriz de refusals) |

Un `AcpAuthority` declarado cambia lo que la pata inversa responde (permiso
concedido = optionId del agente, journalizado); su ausencia lo niega todo — la
ausencia ES la regla (`AcpHumanInteractionTest`, `e2e-acp-authority.sh`).

## Interacción humana

Los pasos que pueden recibir una pregunta del agente declaran la capacidad de
decisión humana y la ponen a una PERSONA por el port que lleva el SDK publicado;
la respuesta de la persona LLEGA al agente y no otorga nada: el permiso sigue
siendo del authority declarado, nunca se concede en nombre de la persona
(`AcpHumanInteractionTest`). Por el alambre del servicio la pregunta se ve y se
responde en las calles `inputs.list` / `input.answer` (medido por
`scripts/e2e-socket-observe.sh`).

## Costes observables y límites duros

* **Budget por delegación**: `budgetSeconds`/`timeoutSeconds` en cada paso; el
  avalanche de chunks se corta por budget y deja el refusal tipado
  `ACP_AGENT_TIMEOUT` con los hechos de ciclo de vida intactos en la lane de
  control (`e2e-acp-task.sh`, fila flood).
* **Reparación acotada**: `maxRepairAttempts` con techo
  `AcpRepairLoop.MAX_REPAIR_ATTEMPTS`; sin `diffCommand` no hay candidato; el
  veredicto lo decide SOLO el exit code del check del plan (ejemplo 02).
* **Gateway MCP**: `maxConcurrent`/`maxCalls` por herramienta con refusals
  nombrados (nunca cola ilimitada); el port del gateway muere en el `finally`
  del turno (el ejemplo 02/04 y `AcpMcpGatewayTest` lo miden); sin
  `mcpCapabilities.http` negociado el agente recibe
  `ACP_SESSION_CAPABILITY_MISSING` y no existe superficie de llamada.
* **Ledger**: un par INTENT/ACK por evento y por sesión; `EventKey` = id externo
  del agente cuando el run responde por la fuente, o nonce-de-conexión + cursor
  en caso contrario; crash entre intent y ack = `UNKNOWN` honesto, nunca se
  re-conduce automáticamente. El exactly-once para efectos externos NO se
  promete sin idempotencia demostrada en el remoto.

## Resultados de pruebas REALMENTE ejecutadas (23-nov-2026)

Con `pipelinek` 0.99.0 (host del SDK publicado 0.99.0 del bundle), JDK 21:

| Fila | Veredicto | Evidencia |
| --- | --- | --- |
| `./scripts/build-and-test.sh` (gate del repo: test + operatorJar) | PASS (GATE_EXIT=0, ver recibo) | `/var/tmp/pkh143-recibos/gate-final.log` |
| `scripts/run-examples.sh` (los 4 ejemplos, journal durable) | PASS | `ac1-0[1-4]-*.json` y `ac1-0[1-4]-run.log` en el recibo |
| `scripts/verify-distributed-artifact.sh` | PASS: manifest del jar identico al emitido; apiRange [0.99.0, 0.100.0) admite el host; firma manifest==release-properties; el CLI distribuido admitio el jar y ejecuto `acpPrompt` | `ac2-distributed-artifact.log`, `ac2-run.log` |
| `scripts/e2e-socket-observe.sh` | PASS parcial honesto: `version`/`health`/`run.state`/`inputs.list`/`events.follow`/`output.follow` respondieron sobre el run del ejemplo con el plugin cargado (los eventos llevan `pipelinek.acp.exchange_started`, `turn_ended`, chunks); el `run.state` final devolvio `terminal/succeeded`; el CANCEL por el socket de un run EXTERNO vivo se nego con `the-lease-is-held-by-another` (proteccion del dueno del lease) y el de un run SUPERVISADO por el servicio (`run.start` → `run.cancel`) devolvio `outcome: cancelled` | `ac3-socket.log` |
| `examples/hodei-verify/verify.sh` (contrato del orquestador externo, AC#4) | PASS dos filas: candidato verde `VERIFIED` con `agentTurns == 0` (verify-FIRST nunca despierta al agente) y candidato rojo `ok=false` con outcome tipado `DENIED` aunque el agente afirme lo contrario; la evidencia leida del journal en solo-lectura | `ac4-hodei-verify.log`, `build/examples/hodei-evidence/` |
| Adaptadores reales (claude-agent-acp / Gemini) | NOT_RUN — opt-in por diseño (`PKH141_ACP_ADAPTER_*`); sin configurarse no afirman nada | README raiz, `AcpRealAdapterProbeTest` |

## Limites y gaps conocidos (medidos, no inferidos)

1. **El servicio distribuido no puede nominar el plugin**: `pipelinek serve` no
   acepta `--plugin-jar` (la opcion se rechaza en su parser y no hay plumbing de
   jars hacia `run.start`), asi que un run SUPERSERVADO no puede ejecutar pasos
   de este plugin; la observacion del run con plugin se hizo contra el mismo
   db/control-root desde fuera. Gap del host (pipeline-kotlin), no del plugin.
2. **Cancelar un run ajeno vivo por el socket esta protegido a proposito**
   (`the-lease-is-held-by-another`): la cancelacion por el socket funciona sobre
   los runs que el propio servicio supervisa. Quien necesite cancelar un run de
   plugin por el socket debe lanzarlo via `run.start`… lo que choca con (1).
3. **Hodei**: el CONTRATO de invocacion esta medido — `examples/hodei-verify/verify.sh`
   reproduce el papel del orquestador (argv hacia fuera, veredicto tipado leido del
   journal, cero acceso a memoria/scheduler/backlog del llamante) sin tocar repo ajeno.
   Lo que NO se afirma aqui es la integracion contra una instancia viva de
   `hodei-meta-harness`: no hay clon alcanzable en esta maquina, y el obrero no edita
   repos ajenos. Quien la enchufe repite exactamente la fila, cambiando el dueno del
   candidato de `build/examples/` al directorio que Hodei ya posee.
4. `WRITE_RESTRICTED` sigue sin target que ateste confinamiento (SDK 0.98/0.99
   no publican el seam) — refusal tipado, no promesa muda.

## Matriz de soporte

Derivada, nunca reescrita a mano: ver [`support-matrix-derived.md`](support-matrix-derived.md)
(generado por `scripts/export-support-matrix.sh`; regenerarlo es parte de la
revision de docs). Las pruebas que sustentan cada fila TESTED son las clases
nombradas en la propia matriz; `AcpCoverageMatrixTest` rechaza la fila huerfana.
