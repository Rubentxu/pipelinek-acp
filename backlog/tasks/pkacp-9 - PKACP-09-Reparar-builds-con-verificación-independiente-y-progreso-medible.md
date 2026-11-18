---
id: PKACP-9
title: '[PKACP-09] Reparar builds con verificación independiente y progreso medible'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-09
  - batch:2026-11-18
dependencies:
  - PKACP-3
  - PKACP-7
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/examples/example-block-plugin/src/main/kotlin/example/block/RepeatBodyStepDefinition.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/ArchiveArtifactsOperations.kt
  - https://github.com/Rubentxu/hodei-meta-harness
priority: medium
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp
Prioridad propuesta: medium

agenticRepair como recipe sobre sesión, slots de verify y prompt acotado. El verificador tiene autoridad independiente. No implementar scheduler infinito ni auto-promoción; cambios quedan como candidato/patch. Reutilizar pruebas, artefactos y control de intentos existente.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Verificar primero; éxito inicial no consume llamada al agente; tras cada reparación se verifica otra vez incluido el último intento permitido.
- [ ] #2 Cada intento queda ligado a revisión/base y digest del contenido realmente verificado; modificación durante verificación invalida evidencia; política y comandos de verificación fuera del control del agente.
- [ ] #3 Termina por éxito verificado, presupuesto, falta de progreso (digest o firma de fallo repetidos), cancelación, denegación o Unknown; no declara éxito por texto del modelo.
- [ ] #4 Fixture reproducible de proyecto roto: agente de prueba repara, tests pasan y se archiva diff e informe; agente real opt-in con límites.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
