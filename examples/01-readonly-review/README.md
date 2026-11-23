# Ejemplo 01 — Revisión de SOLO LECTURA (`acpSession`)

Una sesión con dos turnos contra un agente fixture que responde con
`agent_message_chunk`. El perfil de la conversación es de lectura: el plugin
niega a nivel de protocolo cualquier `session/request_permission` del agente, y
el witness JSONL del fixture prueba que no hubo ni un `DELETE` contra el cwd.

**Requisitos:** el jar del operador construido
(`./scripts/build-and-test.sh`) y el `pipelinek` distribuido emparejado con el
SDK (ver `examples/README.md`). Sin tokens: el agente es
`src/test/resources/acp_session_fixture_agent.py`.

**Ejecutar:**

```bash
pipelinek run --workspace . \
  --db build/pkh143-examples/db.sqlite --control-root build/pkh143-examples/control \
  --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
  examples/01-readonly-review/review.pipeline.kts
```

**Qué mirar:** la fila del journal para `revision` — `ok=true`, las dos preguntas
ecoadas en los reply del witness. `./scripts/run-examples.sh` hace esa aserción
por ti (recibo `ac1-01-readonly.json`).
