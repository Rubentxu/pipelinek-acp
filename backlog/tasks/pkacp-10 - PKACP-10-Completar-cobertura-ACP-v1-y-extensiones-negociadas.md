---
id: PKACP-10
title: '[PKACP-10] Completar cobertura ACP v1 y extensiones negociadas'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-10
  - batch:2026-11-18
dependencies:
  - PKACP-3
  - PKACP-4
  - PKACP-5
references:
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/meta.json
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/meta.unstable.json
  - https://github.com/agentclientprotocol/kotlin-sdk
  - https://github.com/agentclientprotocol/acp-tck
priority: medium
ordinal: 10000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: medium

Inventario exacto de meta.json, meta.unstable.json y schema v1 fijados por commit. Cubrir métodos estables del cliente e inventariar extensiones draft con flags y adapter apropiado. No confundir protocolo v1 con capacidades que todos los agentes deben ofrecer. Métodos proxy corresponden a otro rol y se documentan como no aplicables salvo adaptador explícito.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Matriz ejecutable enumera todos los métodos, notifications, capabilities y tipos de update; cada fila tiene prueba, Unsupported por política/capability o exclusión de rol justificada. Ninguna fila queda sin clasificación.
- [ ] #2 Funciones de gestión adicionales (fork, archive/unarchive, status, options, title), proveedores/auth status y mcp/message se implementan si son negociables en el schema/SDK fijado; experimental se activa explícitamente.
- [ ] #3 Métodos de extensión namespaced y _meta desconocidos preservan compatibilidad sin conceder autoridad; métodos desconocidos contestan error protocolario según dirección.
- [ ] #4 Pruebas de contrato cliente cubren request/response y cancelación; comprobar con dos adaptadores ACP reales de forma opt-in y registrar soporte por agente, sin afirmar conformance si no se ejecutó.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
