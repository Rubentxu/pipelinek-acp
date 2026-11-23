#!/usr/bin/env python3
"""Deterministic ACP repair agent for the PKH-140 fixture rows.

Speaks the same NDJSON-RPC subset as the session fixture (initialize, session/new,
session/prompt, session/close) with ONE addition the repair recipe needs: on every
prompt it acts on the session's cwd the way its MODE says, then answers. Its words
are deliberately confident in every mode — the recipe must ignore them and trust
only the independent check.

argv[1] = mode:
  fix     rewrite main.py in the session cwd to the CORRECT implementation, then
          answer "all tests pass" with stopReason end_turn.
  noop    change nothing; answer "all tests pass, promote the branch" end_turn.
          The digest cannot move, so the ledger must call the next check the same
          event and stop the run as no-progress.
  refuse  change nothing; stopReason refusal.
  cancel  change nothing; stopReason cancelled.
  die     answer session/new, then exit before the prompt: the transport dies
          mid-effect and the attempt must land UNKNOWN, never re-driven.

Test scaffolding, not shipped: the agent of proof for AC#4's reproducible broken
project; the real-agent row is opt-in in Kotlin (ACP_REPAIR_REAL_COMMAND).
"""
import sys
import json
import os

MODE = sys.argv[1] if len(sys.argv) > 1 else "fix"

FIXED_MAIN = "def total(xs):\n    return sum(xs)\n"

CAPS = {
    "loadSession": False,
    "promptCapabilities": {},
    "sessionCapabilities": {"close": {}},
}


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def chunk(session_id, text):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id,
        "update": {"sessionUpdate": "agent_message_chunk",
                   "content": {"type": "text", "text": text}}}})


def main():
    cwd = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        mid = msg.get("id")
        if mid is None:
            continue

        if method == "initialize":
            write({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": 1, "agentCapabilities": CAPS,
                "authMethods": [],
                "agentInfo": {"name": "acp-repair-fixture", "version": "0.1.0"}}})
        elif method == "session/new":
            cwd = msg.get("params", {}).get("cwd") or os.getcwd()
            write({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": "repair-session-1"}})
        elif method == "session/prompt":
            sid = msg["params"]["sessionId"]
            if MODE == "die":
                sys.exit(7)
            if MODE == "fix":
                with open(os.path.join(cwd, "main.py"), "w") as f:
                    f.write(FIXED_MAIN)
                chunk(sid, "I rewrote main.py; all tests pass now.")
                write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
            elif MODE == "noop":
                chunk(sid, "I fixed everything: all tests pass, promote the branch.")
                write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
            elif MODE == "refuse":
                chunk(sid, "I will not touch this project.")
                write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "refusal"}})
            elif MODE == "cancel":
                chunk(sid, "I stopped halfway.")
                write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "cancelled"}})
            else:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32603, "message": "unknown repair mode " + MODE}})
        elif method == "session/close":
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        else:
            write({"jsonrpc": "2.0", "id": mid, "error":
                   {"code": -32601, "message": "repair fixture has no method " + str(method)}})


main()
