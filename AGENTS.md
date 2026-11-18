# pipelinek-acp — instrucciones del agente

Este repositorio es un **producto** del workspace PipelineK. No mantiene backlog propio.

## Autoridad de seguimiento

La única autoridad para tareas, decisiones y prioridades de este producto es el tablero
Backlog.md de [`pipelinek-home/`](../pipelinek-home/). Las tareas de este repo llevan la
etiqueta `repo:pipelinek-acp`; las de contratos del SDK que consume, `repo:pipeline-kotlin`.

Desde `pipelinek-home/`:

```bash
backlog task list --labels repo:pipelinek-acp          # trabajo de este plugin
backlog task list --labels batch:2026-11-18            # el intake ACP completo (16 tareas)
backlog search "ACP" --plain
```

**No ejecutes `backlog init` aquí ni creas un segundo tablero, roadmap o tracker.**
No edites el tablero del home sin leer antes su `AGENTS.md`.

## Al trabajar en este repo

1. Lee tu tarea en el home por su ID `PKH-…` (busca el marcador `[PKACP-…]` del título).
2. La CI es PipelineK en local (`pipeline.kts`), no GitHub Actions.
3. Entrega código, pruebas y documentación específica del producto en este repositorio.
