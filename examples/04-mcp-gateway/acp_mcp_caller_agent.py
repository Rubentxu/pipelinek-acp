#!/usr/bin/env python3
"""Deterministic ACP agent that CALLS the session's MCP gateway mid-turn (PKH-143).

A second, independent implementation of the wire (stdlib only, no tokens, no
network beyond loopback): negotiates the http MCP transport, reads the gateway URL
the plugin carried into session/new's mcpServers, and during the prompt turn
speaks MCP to it — initialize, tools/list, a valid tools/call, an out-of-schema
call, and a repeat under the same idempotency key — reporting what it got back in
its own text stream, so the pipeline's typed output can assert on each half.

Mirrors the fixture the repo's own AcpSessionMcpTest wires in Kotlin; the test is
the law, this file is the consumable example.
"""
import sys, json, urllib.request


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def update(payload):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": "mcp-example-session", "update": payload,
    }})


def say(text):
    update({"sessionUpdate": "agent_message_chunk",
            "content": {"type": "text", "text": text}})


def rpc(url, mid, method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}).encode()
    request = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    })
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode())


def answer(message):
    return json.loads(message["result"]["content"][0]["text"])


urls = []

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
        result = {
            "protocolVersion": 1,
            "agentCapabilities": {
                "loadSession": False,
                "mcpCapabilities": {"http": True, "sse": False},
                "promptCapabilities": {"audio": False, "image": False, "embeddedContext": False},
            },
            "authMethods": [],
            "agentInfo": {"name": "acp-mcp-example-agent", "version": "0.1.0"},
        }
    elif method == "session/new":
        servers = msg.get("params", {}).get("mcpServers", [])
        urls = [server["url"] for server in servers if server.get("url")]
        result = {"sessionId": "mcp-example-session"}
    elif method == "session/prompt":
        try:
            gateway = urls[0]
            rpc(gateway, 1, "initialize", {"protocolVersion": "2025-03-26",
                                           "capabilities": {},
                                           "clientInfo": {"name": "example-agent", "version": "0"}})
            listing = rpc(gateway, 2, "tools/list", {})
            names = sorted(tool["name"] for tool in listing["result"]["tools"])
            first = answer(rpc(gateway, 3, "tools/call", {"name": "runTests",
                                                          "arguments": {"filter": "unit"},
                                                          "_meta": {"pipelinek.acp/idempotencyKey": "turn-key-1"}}))
            bad = rpc(gateway, 4, "tools/call", {"name": "runTests", "arguments": {"filter": 7}})
            again = answer(rpc(gateway, 5, "tools/call", {"name": "runTests",
                                                          "arguments": {"filter": "unit"},
                                                          "_meta": {"pipelinek.acp/idempotencyKey": "turn-key-1"}}))
            say("tools:" + ",".join(names))
            say("first:" + first["status"] + " artifact:" + first["artifactRef"])
            say("bad:" + str(bad["error"]["code"]))
            say("again:" + again["status"])
        except Exception as failure:
            say("mcp-failure:" + repr(failure))
        result = {"stopReason": "end_turn"}
    else:
        write({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "no method"}})
        continue
    write({"jsonrpc": "2.0", "id": mid, "result": result})
