"""The INDEPENDENT check of the PKH-143 repair example.

Runs `total()` from main.py in this directory and exits 0 only when it is right.
The repair recipe treats this exit code as the only success oracle — the agent's
words never decide. stdlib only, no network, no tokens.
"""
import os
import sys

# El -I del argv no garantiza el directorio del script en sys.path (Python 3.15):
# el check lo declara el mismo, porque es SU directorio.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from main import total

if total([1, 2, 3]) == 6 and total([]) == 0:
    print("check: PASS")
    sys.exit(0)
print("check: FAIL")
sys.exit(1)
