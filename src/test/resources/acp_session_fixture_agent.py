#!/usr/bin/env python3
"""Deterministic ACP agent for the composed-session tests (PKH-135).

Speaks NDJSON-RPC for: initialize, session/new (with mcpServers echoed into the next
prompt reply), session/prompt (multi-turn: keeps a per-session history and replies
"turn<n>|seen:<history>"), session/load (streams HISTORIC updates then responds, so the
client can prove history is separated from live events), session/resume, session/list,
session/close, session/delete, session/set_mode, session/set_config_option, logout,
authenticate.

argv[1] = caps profile:
  full    loadSession, session.list/resume/delete/close, auth.logout, prompt image/audio/
          embeddedContext, mcp http/sse, modes and config options ALL announced.
  minimal NONE of them announced (text prompts only).
argv[2] = witness file (optional): records session/delete and session/close requests the
  agent actually received, appended as "DELETE"/"CLOSE" lines. The tests use it to
  prove normal scope exit never deletes.
argv[3] = "slow" (optional): the first prompt turn sleeps 20 s before answering, so a test
  can prove a second exchange on the SAME live connection is refused before launch.

Test scaffolding, not shipped: a second, independent implementation of the wire.
"""
import sys
import json
import time

CAPS = {
    "full": {
        "loadSession": True,
        "promptCapabilities": {"image": True, "audio": True, "embeddedContext": True},
        "mcpCapabilities": {"http": True, "sse": True},
        "sessionCapabilities": {"list": {}, "resume": {}, "delete": {}, "close": {}},
        "auth": {"logout": {}},
    },
    "minimal": {
        "loadSession": False,
        "promptCapabilities": {"image": False, "audio": False, "embeddedContext": False},
    },
}

MODES = [{"id": "default", "name": "Default"}, {"id": "plan", "name": "Plan"}]
CURRENT_MODE = "default"
CONFIG_OPTIONS = [{
    "id": "verbosity",
    "name": "Verbosity",
    "type": "select",
    "currentValue": "low",
    "options": {"type": "flat", "options": [
        {"value": "low", "name": "Low"}, {"value": "high", "name": "High"}]},
}]


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def update(session_id, upd):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id, "update": upd}})


def chunk(session_id, text):
    update(session_id, {"sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text", "text": text}})


def witness(line):
    if witness_path:
        with open(witness_path, "a") as f:
            f.write(line + "\n")


def main():
    global witness_path, slow_mode
    caps_key = sys.argv[1] if len(sys.argv) > 1 else "full"
    witness_path = sys.argv[2] if len(sys.argv) > 2 else ""
    slow_mode = len(sys.argv) > 3 and sys.argv[3] == "slow"
    caps = dict(CAPS[caps_key])
    history = {}          # sessionId -> list of user texts
    mcp_seen = 0          # mcpServers count from the last session/new
    listed = ["agent-listed-7", "agent-listed-8"]

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        mid = msg.get("id")
        if mid is None:
            continue  # notification (e.g. session/cancel): nothing to answer

        if method == "initialize":
            write({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": 1, "agentCapabilities": caps,
                "authMethods": [], "agentInfo": {"name": "acp-session-fixture",
                                                 "version": "0.1.0"}}})
        elif method == "session/new":
            sid = "session-%d" % (len(history) + 1)
            mcp_seen = len(msg.get("params", {}).get("mcpServers", []))
            history[sid] = []
            result = {"sessionId": sid}
            if caps_key == "full":
                result["modes"] = {"currentModeId": CURRENT_MODE, "availableModes": MODES}
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "session/prompt":
            sid = msg["params"]["sessionId"]
            if slow_mode and len(history[sid]) == 0:
                time.sleep(20)
            texts = [b.get("text", "") for b in msg["params"]["prompt"]
                     if b.get("type") == "text"]
            kinds = sorted({b.get("type") for b in msg["params"]["prompt"]})
            history[sid].append(",".join(texts))
            prefix = "mcp%d:" % mcp_seen if len(history[sid]) == 1 else ""
            update(sid, {"sessionUpdate": "agent_thought_chunk",
                         "content": {"type": "text", "text": "thinking"}})
            chunk(sid, prefix + "turn%d|seen:%s|kinds:%s" % (
                len(history[sid]), ";".join(history[sid]), ";".join(kinds)))
            write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
        elif method == "session/load":
            sid = msg["params"]["sessionId"]
            history[sid] = []
            mcp_seen = len(msg.get("params", {}).get("mcpServers", []))
            update(sid, {"sessionUpdate": "agent_message_chunk",
                         "content": {"type": "text", "text": "historic-1"}})
            update(sid, {"sessionUpdate": "agent_message_chunk",
                         "content": {"type": "text", "text": "historic-2"}})
            result = {}
            if caps_key == "full":
                result["modes"] = {"currentModeId": CURRENT_MODE, "availableModes": MODES}
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "session/resume":
            sid = msg["params"]["sessionId"]
            history.setdefault(sid, [])
            mcp_seen = len(msg.get("params", {}).get("mcpServers", []))
            result = {}
            if caps_key == "full":
                result["modes"] = {"currentModeId": CURRENT_MODE, "availableModes": MODES}
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "session/list":
            write({"jsonrpc": "2.0", "id": mid, "result": {
                "sessions": [{"sessionId": s, "cwd": "/w", "title": "t-" + s}
                             for s in listed],
                "nextCursor": None}})
        elif method == "session/close":
            witness("CLOSE")
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/delete":
            witness("DELETE")
            sid = msg["params"]["sessionId"]
            history.pop(sid, None)
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_mode":
            mode_id = msg["params"]["modeId"]
            if mode_id not in {m["id"] for m in MODES}:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32603, "message": "unknown mode " + mode_id}})
            else:
                write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_config_option":
            p = msg["params"]
            opt = CONFIG_OPTIONS[0]
            if p.get("configId") != opt["id"] or \
               p.get("value", {}).get("value") not in {"low", "high"}:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32603, "message": "invalid config"}})
            else:
                refreshed = [dict(opt, currentValue=p["value"]["value"])]
                write({"jsonrpc": "2.0", "id": mid, "result":
                       {"configOptions": refreshed}})
        elif method == "logout":
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "authenticate":
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        else:
            write({"jsonrpc": "2.0", "id": mid, "error":
                   {"code": -32601, "message": "session fixture has no method " + str(method)}})


main()
