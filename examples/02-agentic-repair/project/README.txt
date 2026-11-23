"""The project the PKH-143 repair example is broken in, on purpose.

`main.py` ships with a deliberately wrong `total()`. The repair fixture agent
(mode "fix") rewrites it; THIS file never runs the agent — it is the independent
check the recipe trusts: its exit code is the only success oracle.
"""
