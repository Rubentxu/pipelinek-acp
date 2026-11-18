---
id: PKACP-12
title: '[PKACP-12] Publicar ejemplos agenticos consumibles por PipelineK y Fabric'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-12
  - batch:2026-11-18
dependencies:
  - PKACP-8
  - PKACP-9
  - PKACP-10
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ServiceOperations.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ServiceObservation.kt
  - https://github.com/Rubentxu/pipelinek-fabric
  - https://github.com/Rubentxu/hodei-meta-harness
priority: medium
ordinal: 12000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: medium

Entregar artefacto del plugin con release/API range verificable, guía perfiles e interacción, UAT reproducible local-first. Validar Fabric como consumidor de los eventos disponibles y Hodei como invocador de pipelines de verificación, sin trasladar coordinación ni requerir cambios en repos ajenos. Generalizar Reactive SDK solo si segundo consumidor real lo demanda. La regla de CI local de PipelineK Home (PKH-080) se toma como referencia de compatibilidad, no se duplica. Fabric y Hodei no son dependencias de entrega.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Ejemplos de revisión de solo lectura, test-repair acotado, dos revisores Goldfish independientes y gateway MCP funcionan con agente fixture sin tokens; agentes reales opt-in documentados.
- [ ] #2 Con PipelineK distribuido (no clases internas del workspace), plugin instalado, manifest/rango, firma e instrucciones de ejecución se comprueban mediante harness vigente o validación equivalente acordada.
- [ ] #3 Un cliente socket observa estado/permiso/eventos/resultado reales y cancela run; Fabric usa proyección existente o se registra el gap concreto como tarea separada en su autoridad, sin dependencia ficticia.
- [ ] #4 Hodei puede invocar pipeline de verificación por contrato existente y consumir evidencia sin acceso a su memoria, scheduler o backlog; ownership y límites documentados.
- [ ] #5 Guía pública incluye matriz de soporte, defaults restrictivos, costes observables, límites y resultados de pruebas ejecutadas; no publicar una promesa de soporte no probado.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
