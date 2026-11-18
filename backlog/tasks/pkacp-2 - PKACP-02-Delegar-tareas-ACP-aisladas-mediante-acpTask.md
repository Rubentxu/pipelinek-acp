---
id: PKACP-2
title: '[PKACP-02] Delegar tareas ACP aisladas mediante acpTask'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-02
  - batch:2026-11-18
dependencies:
  - PKACP-1
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/IsolationProvider.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ConnectedCredentialProvider.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt
  - >-
    https://github.com/agentclientprotocol/agent-client-protocol/blob/main/docs/protocol/v1/prompt-turn.mdx
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: high

Entregar acpTask con perfil, prompt, presupuesto absoluto, output tipado y proceso gestionado por el entorno de ejecución PipelineK. Comprobar el seam de transporte bidireccional y lifecycle: si no existe, crear o reutilizar una tarea core mínima, no lanzar ProcessBuilder desde el handler para eludir aislamiento. Contrato externo asociado: PKACP-SDK-01 y PKACP-SDK-02, solo si la prueba demuestra carencia; no introducir aristas entre boards.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Un perfil de lectura y un perfil de escritura restringida se validan; argv sin shell y credenciales por referencia mediante capacidades existentes, sin secretos en DSL ni logs. Los perfiles no amplían las políticas del host.
- [ ] #2 La cancelación del run propaga session/cancel cuando procede, resuelve pendientes y termina el árbol de procesos dentro de presupuesto; deadline cubre arranque y limpieza.
- [ ] #3 El resultado distingue stop reason, fallo de transporte, cancelación, presupuesto y Unknown; no equivale a verificación de negocio.
- [ ] #4 Un agente simulado malicioso no puede escapar del workspace ni de la política de red; si el target no ofrece aislamiento efectivo, se rechaza el perfil. Prueba real opt-in con agente compatible.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
