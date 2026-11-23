# Ejemplo 02 — Test-repair acotado (`agenticRepair`)

`main.py` está roto a propósito y `check.py` es el oráculo. El paso
`agenticRepair` lanza el agente fixture en modo `fix`, re-ejecuta el check tras
cada turno y solo declara `repaired` si el exit code del check lo dice; el
veredicto es del plan, nunca del agente. `maxRepairAttempts=2` acota el bucle y
`diffCommand` produce el patch candidato.

**Requisitos:** igual que el ejemplo 01 (jar + host distribuido, sin tokens).
El script restaura `main.py` roto antes de cada run, así que es repetible.

**Ejecutar:**

```bash
pipelinek run --workspace . \
  --db build/pkh143-examples/db.sqlite --control-root build/pkh143-examples/control \
  --plugin-jar build/libs/pipelinek-acp-0.1.0-operator.jar \
  examples/02-agentic-repair/repair.pipeline.kts
```

**Qué mirar:** en la salida del paso — `outcome=repaired`, `agentTurns>=1`,
`patchPath` apuntando a un `.diff` real en `build/pkh143-examples/repair-archive`,
`finalDigest` distinto del estado roto y las filas del ledger con el check
`passed=true`. Aserción automática: `ac1-02-repair.json`.

**Límite honesto:** sin `diffCommand` no hay patch (el paso no inventa diffs);
el `archiveDir` es lo único que conserva artefactos.
