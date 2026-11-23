El proyecto roto del ejemplo de reparacion (PKH-143).

main.py trae un total() deliberadamente mal: check.py es la verificacion
INDEPENDIENTE del recipe (argv del plan, codigo de salida unico oraculo) y el
repair fixture agent (modo "fix") lo reescribe en el cwd de la sesion. run-examples.sh
restaura la rotura antes de cada fila.
