---
id: PKACP-3
title: >-
  [PKACP-03] Resolver permisos y operaciones inversas ACP con autoridad del
  runtime
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-03
  - batch:2026-11-18
dependencies:
  - PKACP-2
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/InputDecisionsCapabilityKey.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ServiceInputAnswers.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ServiceAuthorization.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/schema.json
priority: high
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: high

Implementar client callbacks v1: permission, filesystem, terminal, elicitation y cancelación. Reusar capacidades de workspace, ejecución, credenciales e interacción; en v2 filesystem/terminal se exponen vía MCP cuando corresponda. No confundir respuesta humana con autorización ni aceptar comandos generados como policy.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Los permisos ACP incluyen optionId válido, rechazo por defecto y respuesta Cancelled a solicitudes pendientes tras cancel; la decisión efectiva es intersección perfil/runtime/política humana autorizada.
- [ ] #2 fs/read_text_file y fs/write_text_file aplican raíces, límites y controles contra symlinks y TOCTOU; rutas externas denegadas por defecto.
- [ ] #3 Los cinco métodos terminal/create, output, wait_for_exit, kill y release respetan owner, quotas, salida acotada y cancelación; terminal de otra sesión es inaccesible.
- [ ] #4 Elicitation form/URL según capacidad negociada utiliza interacción autorizada; no expone secrets ni inventa campos; sin soporte se rechaza o cancela explícitamente.
- [ ] #5 Pruebas negativas de traversal, permisos, cancel concurrente y owner mismatch sin contenedor salvo pruebas de aislamiento seleccionadas.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
