#!/usr/bin/env bash
# Exporta la tabla de soporte desde AcpCoverageMatrix.kt — la fuente ejecutable —
# en lugar de reescribir filas a mano (PKH-143 AC#5). Cada fila del guide deriva
# del par (metodo, clasificacion) que la matriz declara; el gate ya rechaza filas
# sin clasificar (AcpCoverageMatrixTest), asi que este listado no puede mentir sin
# romper la puerta.
set -eu
cd "$(dirname "$0")/.."
SRC=src/main/kotlin/pipelinek/acp/AcpCoverageMatrix.kt
test -f "$SRC"
echo "# Matriz de soporte derivada de AcpCoverageMatrix ($SRC)"
echo
echo "| Metodo | Direccion | Clasificacion | Evidencia/Razon |"
echo "| --- | --- | --- | --- |"
awk '
BEGIN { RS = "AcpMethodRow\\(" }
NR > 1 {
    line = $0
    method = ""; dir = ""; cls = ""; ev = ""
    if (match(line, /"[a-zA-Z\/]+"/, m)) method = substr(m[0], 2, length(m[0]) - 2)
    if (match(line, /AcpMethodDirection\.[A-Z_]+/, d)) dir = substr(d[0], 20)
    if (match(line, /AcpRowClassification\.[A-Z_]+/, c)) cls = substr(c[0], 22)
    else if (match(line, /[ (](TESTED|UNSUPPORTED|ROLE_EXCLUDED)[ ,)]/, c2)) cls = c2[1]
    if (match(line, /evidence = "[^"]*"/, e)) ev = substr(e[0], 13, length(e[0]) - 13)
    if (method != "" && cls != "")
        printf "| %s | %s | %s | %s |\n", method, dir, cls, ev
}
' "$SRC"
