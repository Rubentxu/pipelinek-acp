---
id: PKACP-4
title: '[PKACP-04] Componer sesiones ACP y prompts tipados con configuración negociada'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-04
  - batch:2026-11-18
dependencies:
  - PKACP-2
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StepEmitter.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/examples/example-block-plugin/src/main/kotlin/example/block/BlockDsl.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v1/session-setup.mdx
priority: high
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: high

acpSession es un scope léxico, no un daemon. prompt, await de finalización, opciones, modos, comandos, contexto multimodal y MCP descriptors. Identidad SessionRef opaca con owner/connection epoch. Un prompt activo por sesión en v1; sesiones independientes pueden ejecutarse en paralelo.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Sesión con dos prompts secuenciales conserva contexto y libera recursos al salir, también por excepción o cancelación.
- [ ] #2 Soporta content blocks de v1 y comprueba promptCapabilities antes de enviar imágenes/audio/resources; sin soporte no degrada silenciosamente.
- [ ] #3 load/resume/list/close/delete y logout, modos/configuración se implementan según el esquema v1 fijado y disponibilidad negociada; load histórico se separa de eventos live; no borrar como parte de cierre normal.
- [ ] #4 Rechazo tipado para capability no anunciada, IDs ajenos, configuración inválida o prompts concurrentes incompatibles.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
