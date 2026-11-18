---
id: PKACP-5
title: '[PKACP-05] Proyectar eventos ACP tipados en la observabilidad de PipelineK'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-05
  - batch:2026-11-18
dependencies:
  - PKACP-1
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ServiceObservation.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/ProviderProvenance.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/schema.json
priority: high
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: high

Reducir SessionUpdate y callbacks a estado normalizado y hechos de dominio. Separar tráfico de control prioritario, hechos reactivos durables y telemetría coalescible; provenance distingue AgentClaim, Observed y Verified. Un solo stream público mediante Socket API existente; payloads sensibles no persisten por defecto. Contrato externo: PKACP-SDK-04.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 La matriz del esquema inventaría todas las variantes de session/update y ContentBlock, con decoder/fixture o Unknown explícito para novedades; los chunks no activan operaciones de mutación por defecto.
- [ ] #2 El reducer puro reproduce una transcripción y obtiene el mismo snapshot; actualizaciones parciales de tool call no pierden campos previos y completado lógico se emite una sola vez.
- [ ] #3 Colas y payloads tienen límites; control/cancel funciona ante avalancha de chunks; pérdidas de telemetría y gaps son observables, sin truncar silenciosamente hechos de control.
- [ ] #4 Eventos redacted se proyectan mediante el mecanismo público de observación y son visibles por la API de socket sin exponer JSON-RPC ni credenciales; observación no se interpreta como autoridad.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
