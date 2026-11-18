#!/usr/bin/env python3
"""Deterministic ACP agent used by the plugin's contract tests.

Speaks just enough NDJSON-RPC for initialize -> session/new -> session/prompt over
stdio. argv[1] is the protocol version it answers with (set it out of the client's
supported set to exercise the version refusal); argv[2] is the reply text.

This file is TEST scaffolding, not shipped: the guarantee it earns is that the plugin's
client speaks the real wire against a second implementation, with no network and no
credentials.
"""
import sys
import json


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def main():
    protocol = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    reply_text = sys.argv[2] if len(sys.argv) > 2 else "fixture-reply"

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        mid = msg.get("id")
        if mid is None:
            continue  # notification: nothing to answer

        if method == "initialize":
            result = {
                "protocolVersion": protocol,
                "agentCapabilities": {
                    "loadSession": False,
                    "promptCapabilities": {
                        "audio": False,
                        "image": False,
                        "embeddedContext": False,
                    },
                },
                "authMethods": [],
                "agentInfo": {"name": "acp-fixture", "version": "0.1.0"},
            }
        elif method == "session/new":
            result = {"sessionId": "fixture-session"}
        elif method == "session/prompt":
            write({
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {
                    "sessionId": "fixture-session",
                    "update": {
                        "sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text", "text": reply_text},
                    },
                },
            })
            result = {"stopReason": "end_turn"}
        else:
            write({
                "jsonrpc": "2.0",
                "id": mid,
                "error": {"code": -32601, "message": "fixture has no method " + str(method)},
            })
            continue

        write({"jsonrpc": "2.0", "id": mid, "result": result})


main()
