# Ejemplo 04 — Gateway MCP de sesión (`acpExpose`)

El paso `acpReactive` expone la herramienta `runTests` (schema `filter:string`
obligatorio, política `maxConcurrent=1`/`maxCalls=4`) como servidor MCP HTTP
durante el propio turno del agente. `acp_mcp_caller_agent.py` negocia
`mcpCapabilities.http`, lee la URL del gateway de `session/new` y hace tres
llamadas reales: una válida (`executed`), fuera de schema (`-32602` →
`schema_denied`) y una repetición con la misma idempotencyKey
(`already_acknowledged`) — el ledger INTENT/ACK se ve desde el agente.

**Requisitos:** igual que el ejemplo 01, sin tokens.

**Ejecutar:**

```bash
pipelinek run --workspace . \
  --db build/pkh143-examples/db.sqlite --control-root build/pkh143-examples/control \
  --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
  examples/04-mcp-gateway/mcp-gateway.pipeline.kts
```

**Qué mirar:** la fila `exposedToolCalls` — los tres statuses en orden
`["executed","schema_denied","already_acknowledged"]`, las tool rows con su par
`INTENT`/`ACKED` y la `mcpGatewayUrl` en `127.0.0.1` (el port muere en el
`finally` del turno; nada queda escuchando). Aserción: `ac1-04-mcp-gateway.json`.

**Límite honesto:** sin `mcpCapabilities.http` negociado por el agente no hay
superficie: el step falla tipado `ACP_SESSION_CAPABILITY_MISSING` en vez de
exponer a ciegas.
