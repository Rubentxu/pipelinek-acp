---
id: PKACP-11
title: '[PKACP-11] Añadir adaptador ACP v2 versionado sin romper v1'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-11
  - batch:2026-11-18
dependencies:
  - PKACP-7
  - PKACP-10
references:
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v2/meta.json
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v2/meta.unstable.json
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v2/migration.mdx
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/announcements/acp-v2-draft.mdx
priority: medium
ordinal: 11000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: medium

Aislar v2 como adapter experimental mientras su especificación no sea estable; negociación explícita y dual stack probado. No traducir prompt accepted a completed; idle foreground puede coexistir con trabajo de fondo. Usar MCP para recursos cliente que desaparecen de v2.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 El adapter enruta los métodos de meta v2 y marca las extensiones de meta.unstable; matriz actualizable por diff upstream sin cambios en DSL público.
- [ ] #2 Prompt accepted y finalización foreground son hechos separados; await especifica condición e incluye política de background y cierre, sin confundir idle con éxito.
- [ ] #3 Pruebas cubren diferencias de auth, resume/replay cursor, cancelación de tool/terminal y MCP cliente; no se anuncia v1 capability retirada en v2.
- [ ] #4 Sesión v1 y v2 producen el mismo vocabulario normalizado donde semánticamente equivalente; casos no equivalentes se conservan como tales. Adapter experimental desactivado por defecto si aún no estable.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
