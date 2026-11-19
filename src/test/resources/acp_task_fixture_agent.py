#!/usr/bin/env python3
"""Deterministic ACP agent used by the acpTask contract tests (PKH-133).

MODE (argv[1]) selects the behaviour; unused argv slots are ignored:

  reply      argv[2]=reply text, argv[3]=session-counter file. Each session/new reads,
             increments and rewrites the file, and the reply is prefixed
             "task-session-<n>:" — two delegations sharing the counter file prove the
             plugin opened one fresh session per task instead of reusing a parked one.
  hang       answers initialize and session/new, then ignores session/prompt forever:
             the absolute budget must classify it, never wait silently.
  hang-init  answers nothing at all: startup must fall inside the deadline too.
  cancel     on the session/cancel notification appends "CANCEL_SEEN" to argv[2] and
             answers the pending prompt with stopReason "cancelled" — argv[2] is the
             witness that session/cancel reached the AGENT before its process was reaped.
  env-probe  replies with an observation of its own environment (CANARY/NVARS/PATHKEY):
             the plugin's child-environment allowlist must show CANARY=absent.
  malicious  asks for a permission during the prompt and replies with what the client
             answered: PERM=cancelled proves the deny-by-default seam refused the agent.

Speaks just enough NDJSON-RPC for initialize -> session/new -> session/prompt over
stdio. TEST scaffolding, not shipped; same independence guarantee as the PKH-132
fixture: a second implementation of the wire, no network, no credentials.
"""
import sys
import json
import os
import subprocess


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def chunk(session_id, text):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id,
        "update": {"sessionUpdate": "agent_message_chunk",
                   "content": {"type": "text", "text": text}},
    }})


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "reply"
    arg2 = sys.argv[2] if len(sys.argv) > 2 else ""
    arg3 = sys.argv[3] if len(sys.argv) > 3 else ""

    prompt_id = None
    prompt_session = None
    session_serial = 0

    def counter_next():
        n = 1
        if arg3:
            try:
                with open(arg3) as f:
                    n = int((f.read().strip() or "0")) + 1
            except (OSError, ValueError):
                n = 1
            with open(arg3, "w") as f:
                f.write(str(n))
        return n

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        mid = msg.get("id")

        if method is None and mid is not None:
            # A RESPONSE to one of our own requests: only the permission answer exists.
            # Robust to the outcome's serialization shape: a GRANT names an option, the
            # deny-by-default answer (Cancelled) never does.
            decision = json.dumps(msg.get("result", {}).get("outcome", {})).lower()
            granted = "optionid" in decision or "selected" in decision or "allow" in decision
            text = "PERM=granted" if granted else "PERM=denied"
            chunk(prompt_session, text)
            write({"jsonrpc": "2.0", "id": prompt_id, "result": {"stopReason": "end_turn"}})
            prompt_id = None
            continue

        if mid is None:
            # Notification. Only session/cancel is answered; anything else is dropped.
            if method == "session/cancel" and mode == "cancel":
                if arg2:
                    with open(arg2, "a") as f:
                        f.write("CANCEL_SEEN\n")
                if prompt_id is not None:
                    write({"jsonrpc": "2.0", "id": prompt_id, "result": {"stopReason": "cancelled"}})
                    prompt_id = None
            continue

        if method == "initialize":
            if mode == "hang-init":
                continue
            write({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": 1,
                "agentCapabilities": {
                    "loadSession": False,
                    "promptCapabilities": {"audio": False, "image": False, "embeddedContext": False},
                },
                "authMethods": [],
                "agentInfo": {"name": "acp-task-fixture", "version": "0.1.0"},
            }})
        elif method == "session/new":
            session_serial = counter_next() if mode == "reply" else session_serial + 1
            prompt_session = "task-session-%d" % session_serial
            write({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": prompt_session}})
        elif method == "session/prompt":
            prompt_id = mid
            if mode == "hang":
                continue  # never answer: the client's deadline must fire
            if mode == "tree":
                # Start a survivor the CLIENT never sees, report its pid, then hang: only a
                # tree-aware cleanup (not a single destroy of the root) reaps the sleeper.
                child = subprocess.Popen(["sleep", "30"])
                with open(arg2, "w") as f:
                    f.write(str(child.pid))
                continue
            if mode == "cancel":
                chunk(prompt_session, "waiting-for-cancel")
                continue  # answered only through session/cancel
            if mode == "env-probe":
                text = "CANARY=%s;NVARS=%d;PATHKEY=%s" % (
                    "present" if os.environ.get("ACP_TASK_CANARY") else "absent",
                    len(os.environ),
                    "yes" if os.environ.get("PATH") else "no",
                )
            elif mode == "malicious":
                write({"jsonrpc": "2.0", "id": 9001, "method": "session/request_permission",
                       "params": {"sessionId": prompt_session,
                                  "toolCall": {"toolCallId": "tc-1", "title": "rm -rf outside"},
                                  "options": [{"optionId": "allow", "name": "Allow",
                                               "kind": "allow_once"}]}})
                continue  # the reply is produced when the client's answer frame arrives
            elif mode == "reply":
                text = "task-session-%d:%s" % (session_serial, arg2 or "fixture-reply")
            else:
                text = arg2 or "fixture-reply"
            chunk(prompt_session, text)
            write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
            prompt_id = None
        else:
            write({"jsonrpc": "2.0", "id": mid,
                   "error": {"code": -32601, "message": "task fixture has no method " + str(method)}})


main()
