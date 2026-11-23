#!/usr/bin/env python3
"""Deterministic ACP agent that USES the session MCP gateway (PKH-143 example).

The gateway is the plugin's loopback MCP server (PKH-139): the plan's exposed tools travel to
the agent as one `mcpServers` descriptor in `session/new`, and this fixture is the agent half
of that exchange — a second, independent implementation that DIALS the URL over real HTTP and
drives `initialize`, `tools/list` and `tools/call` exactly as an MCP client would.

argv[1] = witness file (optional): one line per MCP answer the agent actually got
          ("<tool-or-status> <detail>"), so a test can prove what the AGENT saw without
          trusting the client-side ledger alone.

On `session/prompt` the agent, in order:
  1. sends an `agent_thought_chunk` update (so a declared reaction has something to route);
  2. dials the gateway and lists its tools;
  3. makes the six calls of the PKH-139 law showcase — an executed call, a repeat of its
     idempotency key (answered from the journal, never re-driven), a second executed call, a
     fourth call the per-scope budget refuses, a call of a tool the plan never exposed, and a
     call whose argument is outside the declared schema;
  4. streams the observed statuses into the reply: "TOOLS=<n>|CALLED=<a>:<b>:..." where each
     entry is `executed`, `already_acknowledged`, `call_limit_refused`, an error code for the
     unknown tool and for the schema refusal.

The reply is what the AGENT saw; the turn's durable `exposedToolCalls` is what the GATEWAY
answered. An example that only asserted one of the two would not be measuring the wire.

Example scaffolding, not shipped: no network (loopback HTTP only), no credentials, no tokens.
"""
import json
import sys
import urllib.request

WITNESS = sys.argv[1] if len(sys.argv) > 1 else ""
SESSION_ID = "gateway-fixture-session"


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def witness(line):
    if WITNESS:
        with open(WITNESS, "a") as handle:
            handle.write(line + "\n")


def update(session_id, upd):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id, "update": upd}})


class McpClient:
    """Just enough of the MCP streamable HTTP transport to ask one question per POST."""

    def __init__(self, url):
        self.url = url
        self.serial = 0

    def _post(self, payload):
        request = urllib.request.Request(
            self.url,
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
            },
        )
        with urllib.request.urlopen(request, timeout=15) as answer:
            body = answer.read().decode()
            return json.loads(body) if body else None

    def ask(self, method, params=None):
        self.serial += 1
        payload = {"jsonrpc": "2.0", "id": self.serial, "method": method}
        if params is not None:
            payload["params"] = params
        return self._post(payload)

    def notify(self, method):
        self._post({"jsonrpc": "2.0", "method": method})


def gateway_showcase(url):
    """Drive the six calls; return the statuses the AGENT observed, in order."""
    client = McpClient(url)
    client.ask("initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "gateway-fixture-agent", "version": "0.1.0"},
    })
    client.notify("notifications/initialized")
    listing = client.ask("tools/list")
    tools = listing["result"]["tools"]
    names = sorted(tool["name"] for tool in tools)
    witness("tools " + ",".join(names))

    statuses = []

    def verdict(answer):
        if "error" in answer:
            return "err-%s" % answer["error"]["code"]
        try:
            return json.loads(answer["result"]["content"][0]["text"])["status"]
        except Exception:
            return "unreadable"

    first = client.ask("tools/call", {
        "name": "audit", "arguments": {"note": "rev-1"},
        "_meta": {"pipelinek.acp/idempotencyKey": "pkh143-a"}})
    statuses.append(("audit", verdict(first)))
    # The same idempotency key again: the journal answers, the slot never runs twice.
    repeat = client.ask("tools/call", {
        "name": "audit", "arguments": {"note": "rev-1"},
        "_meta": {"pipelinek.acp/idempotencyKey": "pkh143-a"}})
    statuses.append(("audit", verdict(repeat)))
    other = client.ask("tools/call", {
        "name": "audit", "arguments": {"note": "rev-2"},
        "_meta": {"pipelinek.acp/idempotencyKey": "pkh143-b"}})
    statuses.append(("audit", verdict(other)))
    # The plan set maxCalls=2 for this tool: the third dispatch-bearing call is refused.
    flood = client.ask("tools/call", {
        "name": "audit", "arguments": {"note": "rev-3"},
        "_meta": {"pipelinek.acp/idempotencyKey": "pkh143-c"}})
    statuses.append(("audit", verdict(flood)))
    ghost = client.ask("tools/call", {"name": "ghost", "arguments": {}})
    statuses.append(("ghost", verdict(ghost)))
    typed = client.ask("tools/call", {
        "name": "audit", "arguments": {"note": 7},
        "_meta": {"pipelinek.acp/idempotencyKey": "pkh143-d"}})
    statuses.append(("audit", verdict(typed)))
    for name, status in statuses:
        witness("call %s %s" % (name, status))
    return names, statuses


def main():
    gateway_url = ""
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
                "protocolVersion": 1,
                "agentCapabilities": {
                    "loadSession": False,
                    "promptCapabilities": {
                        "audio": False, "image": False, "embeddedContext": True},
                    "mcpCapabilities": {"http": True, "sse": False},
                },
                "authMethods": [],
                "agentInfo": {"name": "gateway-fixture-agent", "version": "0.1.0"},
            }})
        elif method == "session/new":
            servers = (msg.get("params") or {}).get("mcpServers") or []
            http_servers = [s for s in servers if s.get("transport") == "http"]
            gateway_url = http_servers[0]["url"] if http_servers else ""
            witness("mcp-http %d" % len(http_servers))
            write({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": SESSION_ID}})
        elif method == "session/prompt":
            update(SESSION_ID, {"sessionUpdate": "agent_thought_chunk",
                                "content": {"type": "text", "text": "dialling the gateway"}})
            if not gateway_url:
                reply = "NOGATEWAY"
            else:
                try:
                    names, statuses = gateway_showcase(gateway_url)
                    reply = "TOOLS=%d|CALLED=%s" % (
                        len(names), ":".join(status for _, status in statuses))
                except Exception as failure:
                    reply = "GATEWAY-DIAL-FAILED %s" % type(failure).__name__
            update(SESSION_ID, {"sessionUpdate": "agent_message_chunk",
                                "content": {"type": "text", "text": reply}})
            write({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
        else:
            write({"jsonrpc": "2.0", "id": mid,
                   "error": {"code": -32601, "message": "fixture has no method " + str(method)}})


main()
