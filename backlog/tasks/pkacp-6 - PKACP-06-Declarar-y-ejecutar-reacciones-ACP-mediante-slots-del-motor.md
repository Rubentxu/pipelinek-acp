---
id: PKACP-6
title: '[PKACP-06] Declarar y ejecutar reacciones ACP mediante slots del motor'
status: To Do
assignee: []
created_date: '2026-11-18T19:32:00Z'
labels:
  - repo:pipelinek-acp
  - area:acp
  - source:PKACP-06
  - batch:2026-11-18
dependencies:
  - PKACP-4
  - PKACP-5
references:
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodySlot.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/examples/example-block-plugin/src/main/kotlin/example/block/SlotPlanStepDefinition.kt
  - >-
    https://github.com/Rubentxu/pipeline-kotlin/blob/245ee0c635d1d92487a29f44d41d4d49b63e448a/pipelinek/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt
priority: high
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Componente: pipelinek-acp + core solo si falta seam
Prioridad propuesta: high

Probar on(id, selector) y await(condición, timeout) dentro de sesión. Compilar a slots estáticos con selectores serializables, never lambdas runtime arbitrarias. Primero fixtures deterministas y acciones read-only; identidad de invocación por evento y reacción requiere prueba real del SDK. Si falta seam, registrar cambio mínimo en backlog core existente. Contrato externo: PKACP-SDK-03.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Dos eventos distintos activan dos invocaciones del mismo slot con identidades distintas; replay del mismo evento no vuelve a ejecutar un cuerpo confirmado.
- [ ] #2 Las reacciones son steps reales del motor, con scopes, observación y resultado original; no ejecutan código Kotlin directamente desde el hilo receptor ACP.
- [ ] #3 Selectores tipados, once y await tienen semántica documentada; await observa estado actual y cursor sin perder el evento entre snapshot y suscripción, y respeta timeout.
- [ ] #4 Desacoplar reader, control y runner evita deadlock si el cuerpo o el agente espera permiso o un tool; ejecución secuencial por defecto con cola limitada.
- [ ] #5 Fitness: plugin sin import de internals ni casos ACP en compiler; si falta slot identity/context, test de contrato y ampliación SDK genérica mínima con compatibilidad anterior.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Criterios comprobados con pruebas proporcionales al riesgo y resultado registrado en Backlog.md.
- [ ] #2 Revisar contratos, seguridad y documentación afectados; NOT_RUN nunca equivale a PASS.
<!-- DOD:END -->
