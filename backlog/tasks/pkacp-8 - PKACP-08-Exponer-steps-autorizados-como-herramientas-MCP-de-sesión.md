---
id: PKACP-8
title: '[PKACP-08] Exponer steps autorizados como herramientas MCP de sesión'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-08
  - batch:2026-11-18
dependencies:
  - PKACP-3
  - PKACP-7
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodySlot.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/examples/example-block-plugin/src/main/kotlin/example/block/SlotPlanStepDefinition.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v1/session-setup.mdx
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v2/migration.mdx
priority: medium
ordinal: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: medium

exposeStep(name, inputSchema, policy) compila un slot predeclarado. El agente llama al gateway MCP temporal mediante la configuración de sesión ACP, solo si transporte/capabilities soportados. Es una solicitud al motor, no ejecución arbitraria ni scheduler nuevo.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 El agente encuentra runTests y llama a un slot que ejecuta un step PipelineK real; parámetros se validan y resultados se devuelven tipados con referencias de artefacto, sin interpolación shell.
- [ ] #2 Cada gateway está ligado a run/op/session y expira con el scope; tool desconocida, otra sesión y argumentos fuera de esquema se deniegan.
- [ ] #3 Idempotency key efectiva cuando el cliente la soporta; request repetida de efecto desconocido no se ejecuta de nuevo, y límites por tool controlan concurrencia y número de llamadas.
- [ ] #4 Un prompt esperando MCP y un motor ejecutando el cuerpo no se bloquean mutuamente; cancelación, errores y cleanup se prueban.
- [ ] #5 Sin capacidad MCP compatible se reporta Unsupported, sin abrir acceso genérico al shell.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
