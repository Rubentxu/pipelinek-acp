---
id: PKACP-1
title: '[PKACP-01] Ejecutar un cliente ACP externo desde el Step SDK'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-01
  - batch:2026-11-18
dependencies: []
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StepEmitter.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/RuntimeCapabilityContributor.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/PluginManifest.kt
  - https://github.com/agentclientprotocol/kotlin-sdk
priority: high
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: high

Crear Rubentxu/pipelinek-acp como repositorio independiente, usando el SDK público y el cliente Kotlin oficial con versiones fijadas. Primer incremento: agente simulado por STDIO, inicialización y prompt de solo lectura en un entorno de prueba. Identidad propuesta pipelinek.acp; validar naming del manifiesto y groupId antes de publicar. No introducir un segundo motor ni dependencias de pipeline-application. Backlog exclusivo de este producto; no incorporarlo al board PipelineK Home. Puerta CI local con pipeline.kts y SDK sellado; no duplicar puertas en GitHub Actions.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 El plugin se empaqueta y admite mediante el manifiesto y API range reales del SDK y se descubre sin cambios al compilador ni al registro core.
- [ ] #2 Un pipeline compilado con el DSL del plugin invoca initialize y session/new y completa session/prompt frente a un agente determinista; version incompatible o capability obligatoria ausente se rechaza de forma tipada.
- [ ] #3 STDOUT queda reservado para JSON-RPC y STDERR se drena acotadamente; errores de framing y procesos terminados no se confunden con éxito.
- [ ] #4 La suite de contratos ejecuta mediante PipelineK local sin red ni credenciales; registra versión SDK, schema ACP y artefacto probado; no añadir puertas GitHub Actions.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
