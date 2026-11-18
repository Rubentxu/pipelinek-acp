---
id: PKACP-7
title: '[PKACP-07] Recuperar reacciones y prompts sin duplicar efectos inciertos'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-07
  - batch:2026-11-18
dependencies:
  - PKACP-6
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodySlot.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v1/session-setup.mdx
priority: high
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp + core solo si falta seam
Prioridad propuesta: high

Construir reconciliación de hechos y reacciones sobre el journal existente; si no hay port durable genérico, ampliarlo de forma mínima. No crear una BBDD de runs alternativa. EventKey = identidad externa cuando fiable o clave persistida por conexión; histórico v1 no vuelve a disparar efectos. Nunca prometer exactly-once para efectos externos sin idempotencia remota. Contrato externo: PKACP-SDK-03 y PKACP-SDK-04.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Crash antes de persistir intent, después de intent y después de efecto antes de acknowledgement se prueban con inyección de fallos; Unknown no se reenvía automáticamente.
- [ ] #2 Reload/reconnect con secuencias renumeradas no duplica reacciones históricas; si no se distingue historia de live de forma fiable, suspende reacciones con efectos hasta reconciliar.
- [ ] #3 Identidad run/op/session/reaction/event no colisiona entre runs ni branches; persiste cursor y resultado en autoridad durable existente; si no se puede probar, falla cerrado.
- [ ] #4 once, take, distinct y debounce (solo telemetría o hechos coalescibles) tienen límites, reglas de reloj y replay deterministas; ninguna solicitud de permiso se descarta.
- [ ] #5 Cancelación de scope cierra suscripciones, espera o cancela cuerpos hijos y marca efectos desconocidos según corresponda; no quedan workers huérfanos.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
