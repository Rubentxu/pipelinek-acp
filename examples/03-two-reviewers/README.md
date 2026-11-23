# Ejemplo 03 — Dos revisores Goldfish independientes (`acpReactive`) ×2

Dos pasos `acpReactive` corren agentes fixture DISTINTOS (dos witness, dos
conexiones). Cada uno reacciona a su primer `agent_message_chunk` con un efecto
registrado en su propio ledger. La prueba no es que "funcionen dos veces": es
que el `EventKey` de uno jamás aparece en el ledger del otro, los tokens de
conexión son disjuntos y el `seen:` del witness B no contiene el contexto de A.

**Requisitos:** igual que el ejemplo 01, sin tokens.

**Ejecutar:**

```bash
pipelinek run --workspace . \
  --db build/pkh143-examples/db.sqlite --control-root build/pkh143-examples/control \
  --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
  examples/03-two-reviewers/two-reviewers.pipeline.kts
```

**Qué mirar:** las dos filas `reactionLedger` del journal — cada clave con su par
exacto `INTENT`+`ACKED`, y cero claves compartidas entre revisor A y revisor B
(`ac1-03-two-reviewers.json` lo comprueba, incluida la fuga de contexto por el
witness).

**Límite honesto:** un efecto solo se re-intenta si es declaradamente
re-conducible; el exactly-once cruzado contra el remoto no se promete sin
idempotencia demostrada ahí (ver `docs/agentic-examples-guide.md`).
