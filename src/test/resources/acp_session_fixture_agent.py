#!/usr/bin/env python3
"""Deterministic ACP agent for the composed-session tests (PKH-135).

Speaks NDJSON-RPC for: initialize, session/new (with mcpServers echoed into the next
prompt reply), session/prompt (multi-turn: keeps a per-session history and replies
"mcp<n>:turn<n>|seen:<history>|kinds:<types>"), session/load (streams HISTORIC updates
then responds, so the client can prove history is separated from live events),
session/resume, session/list, session/close, session/delete, session/fork, session/set_mode,
session/set_model, session/set_config_option, logout, authenticate, auth/status,
providers/list.

The reply format, which the tests assert on literally:
  mcp<n>       how many MCP descriptors the session creation carried — on the FIRST
               turn only; a later turn of the same session carries no prefix.
  turn<n>      which turn of this session this is.
  seen:<...>   the user texts this session has been given, joined with ";".
  kinds:<...>  the types of the content blocks this turn carried, sorted, joined
               with ";".

argv[1] = caps profile:
  full    loadSession, session.list/resume/delete/close/fork, auth.logout/status,
          providers, prompt image/audio/embeddedContext, mcp http/sse, modes and config
          options ALL announced.
  minimal NONE of them announced (text prompts only).
argv[2] = witness file (optional): records session/delete, session/close and session/fork
  requests the agent actually received, appended as "DELETE"/"CLOSE"/"FORK <source-id>"/
  "MODEL <model-id>" lines. The tests use it to prove normal scope exit never deletes,
  that a declared fork really reached the agent under the id it declared, and that a
  declared model selection arrives at the agent as a real session/set_model wire call.
argv[3] = "slow" (optional): the first prompt turn sleeps 20 s before answering, so a test
  can prove a second exchange on the SAME live connection is refused before launch.
argv[4] = "extensions" (optional): while answering the first prompt, the agent DIALS the
  client with an unknown namespaced request and notification, streams an update carrying
  unknown _meta and an unknown variant, and witnesses the client's answer to the request
  (a code) and the silence to the notification ("NOTIFYDROP silent" / "NOTIFYANSWERED ...")
  (AC#3, PKH-141).
argv[5] = "liar" (optional): the agent announces the full caps but answers session/delete
  with method-not-found, so a test can prove the wire failure of an announced-but-broken
  operation lands as the named session operation, not as the generic rejection.

Test scaffolding, not shipped: a second, independent implementation of the wire.
"""
import sys
import json
import os
import select
import time

CAPS = {
    "full": {
        "loadSession": True,
        "promptCapabilities": {"image": True, "audio": True, "embeddedContext": True},
        "mcpCapabilities": {"http": True, "sse": True},
        "sessionCapabilities": {"list": {}, "resume": {}, "delete": {}, "close": {}, "fork": {}},
        "auth": {"logout": {}, "status": True},
        "providers": {},
    },
    "minimal": {
        "loadSession": False,
        "promptCapabilities": {"image": False, "audio": False, "embeddedContext": False},
    },
}

MODES = [{"id": "default", "name": "Default"}, {"id": "plan", "name": "Plan"}]
CURRENT_MODE = "default"
# SessionModelState's wire fields: currentModelId + availableModels of {modelId, name}.
MODELS = {"currentModelId": "gpt-mini", "availableModels": [
    {"modelId": "gpt-mini", "name": "GPT Mini"},
    {"modelId": "gpt-pro", "name": "GPT Pro"}]}
# The library's SessionConfigSelectOptionsSerializer reads `options` as a JSON ARRAY
# directly (flat is the array form; the grouped form carries an item with a "group"
# key) — an envelope object here fails decoding of the whole session/new result.
CONFIG_OPTIONS = [{
    "id": "verbosity",
    "name": "Verbosity",
    "type": "select",
    "currentValue": "low",
    "options": [
        {"value": "low", "name": "Low"}, {"value": "high", "name": "High"}],
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


def witness_pid():
    """The pid this agent runs under, so a test can prove the scope REAPED it.

    The witness file also carries the close/delete lines; a PID line is none of
    them, so the rows that assert on those still assert on what they mean.
    """
    witness("PID %d" % os.getpid())


def readline_timeout(seconds):
    """One line from stdin, or None when nobody answered within [seconds].

    Used ONLY from inside a request handler (the extensions mode): the loop below reads
    stdin exclusively through readline(), so there is no second iterator buffering lines
    the response could hide in.
    """
    ready, _, _ = select.select([sys.stdin], [], [], seconds)
    if not ready:
        return None
    return sys.stdin.readline()


def dial_client_with_extensions(sid):
    """AC#3 (PKH-141): unknown namespaced and plain methods, unknown _meta, unknown variant.

    The extension asks go out WHILE the client's prompt request is unanswered: the client
    must answer the requests (a protocol error, per direction) and keep the session alive.
    The codes it answers with are witnessed; the notifications are fire-and-forget.
    """
    for tag, method in (("EXTREQ", "_pipelinekTest/ping"), ("EXTREQ2", "plain/invented")):
        write({"jsonrpc": "2.0", "id": tag.lower(), "method": method,
               "params": {"sessionId": sid}})
        answer = readline_timeout(5)
        if answer is None:
            witness(tag + " TIMEOUT")
            continue
        reply = json.loads(answer)
        code = reply.get("error", {}).get("code", "NO_ERROR_FIELD")
        witness("%s %s" % (tag, code))
    write({"jsonrpc": "2.0", "method": "_pipelinekTest/notify", "params": {}})
    # An unknown NOTIFICATION carries no id: JSON-RPC forbids answering it, so anything
    # arriving within the window is a protocol violation witnessed against the client.
    stray = readline_timeout(1)
    witness("NOTIFYDROP silent" if stray is None else "NOTIFYANSWERED " + stray.strip())
    # The schema's own client-side notification with no handler carrier in the pinned SDK:
    # the same silence is what licenses the auth/status_update row's UNSUPPORTED claim.
    write({"jsonrpc": "2.0", "method": "auth/status_update",
           "params": {"sessionId": sid, "authenticated": True}})
    stray = readline_timeout(1)
    witness("STATUSDROP silent" if stray is None else "STATUSANSWERED " + stray.strip())
    # A KNOWN update variant carrying UNKNOWN _meta: compatibility is kept (the update is
    # processed) and no authority is taken (the client never reads the _meta payload).
    update(sid, {"sessionUpdate": "agent_thought_chunk",
                 "content": {"type": "text", "text": "meta-attached"},
                 "_meta": {"mystery": {"keep": 1}}})
    # And an UNKNOWN variant: the client must say unknown_update, not crash, not hang.
    update(sid, {"sessionUpdate": "_pipelinekTest/weird", "payload": {"a": 1}})


def main():
    global witness_path, slow_mode, ext_mode, liar_mode
    flags = set(sys.argv[3:])
    caps_key = sys.argv[1] if len(sys.argv) > 1 else "full"
    witness_path = sys.argv[2] if len(sys.argv) > 2 else ""
    slow_mode = "slow" in flags
    ext_mode = "extensions" in flags
    liar_mode = "liar" in flags
    caps = dict(CAPS[caps_key])
    history = {}          # sessionId -> list of user texts
    mcp_seen = 0          # mcpServers count from the last session/new
    listed = ["agent-listed-7", "agent-listed-8"]
    ext_done = False
    witness_pid()

    while True:
        raw = sys.stdin.readline()
        if not raw:
            break
        line = raw.strip()
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
                result["models"] = MODELS
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
            if ext_mode and not ext_done:
                ext_done = True
                dial_client_with_extensions(sid)
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
                result["models"] = MODELS
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "session/resume":
            sid = msg["params"]["sessionId"]
            history.setdefault(sid, [])
            mcp_seen = len(msg.get("params", {}).get("mcpServers", []))
            result = {}
            if caps_key == "full":
                result["modes"] = {"currentModeId": CURRENT_MODE, "availableModes": MODES}
                result["models"] = MODELS
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "session/list":
            write({"jsonrpc": "2.0", "id": mid, "result": {
                "sessions": [{"sessionId": s, "cwd": "/w", "title": "t-" + s}
                             for s in listed],
                "nextCursor": None}})
        elif method == "session/fork":
            src = msg["params"]["sessionId"]
            witness("FORK " + src)
            sid = "session-%d" % (len(history) + 1)
            history[sid] = []
            mcp_seen = len(msg.get("params", {}).get("mcpServers", []))
            result = {"sessionId": sid}
            if caps_key == "full":
                result["modes"] = {"currentModeId": CURRENT_MODE, "availableModes": MODES}
                result["models"] = MODELS
                result["configOptions"] = CONFIG_OPTIONS
            write({"jsonrpc": "2.0", "id": mid, "result": result})
        elif method == "auth/status":
            write({"jsonrpc": "2.0", "id": mid, "result":
                   {"authenticated": True, "message": "fixture-operator"}})
        elif method == "providers/list":
            # ProviderInfo's serial names (providerId/supported/required) are REQUIRED on the
            # wire; `id` is only the Kotlin-side property.
            write({"jsonrpc": "2.0", "id": mid, "result": {"providers": [
                {"providerId": "openrouter", "supported": ["openai"], "required": False},
                {"providerId": "local-models", "supported": ["anthropic"], "required": False}]}})
        elif method == "session/close":
            witness("CLOSE")
            write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/delete":
            witness("DELETE")
            if liar_mode:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32601, "message": "delete is not really here"}})
                continue
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
        elif method == "session/set_model":
            model_id = msg["params"]["modelId"]
            if model_id not in {m["modelId"] for m in MODELS["availableModels"]}:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32603, "message": "unknown model " + model_id}})
            else:
                witness("MODEL " + model_id)
                write({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_config_option":
            p = msg["params"]
            opt = CONFIG_OPTIONS[0]
            # The SDK writes the option value as the RAW value — a bare JSON string for a
            # select option — and not as an envelope object around it.
            value = p.get("value")
            if p.get("configId") != opt["id"] or value not in {"low", "high"}:
                write({"jsonrpc": "2.0", "id": mid, "error":
                       {"code": -32603, "message": "invalid config"}})
            else:
                refreshed = [dict(opt, currentValue=value)]
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
