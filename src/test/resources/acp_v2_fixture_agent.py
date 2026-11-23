#!/usr/bin/env python3
"""Deterministic dual-stack (v1/v2) ACP agent for the PKH-142 tests.

Speaks NDJSON-RPC. Like the other fixtures in this directory it is a SECOND,
independent implementation of the wire — the point is that our SDK pairing is
checked against it, not against our own mocks.

Version negotiation (docs/protocol/v2/migration):
  initialize params carry "protocolVersion". The agent selects the highest version
  it supports at or below the request.
  argv[1] = profile:
    dual    implements v1 and v2. Requests >= 2 get protocolVersion 2 (v2 response
            shape: info/capabilities); a request of 1 gets protocolVersion 1 (v1
            response shape: agentInfo/agentCapabilities).
    legacy  a v1-ONLY agent: answers protocolVersion 1 in the v1 shape no matter
            what version the request names (what a legacy agent really does).
    reject  refuses 2 with the JSON-RPC error -32022 (Unsupported protocol
            version) carrying requested/supported data; answers 1 normally.

  The v2 turn machinery:
  session/new    -> {"sessionId": "s<n>"} (echoes configOptions: []).
  session/prompt -> responds {"messageId": "m-<n>"} immediately (ACCEPTANCE only:
                    no stop reason on the response — that is the AC#2 split), and
                    NO idle state update until the client closes: foreground
                    completion and acceptance are then provably separate wire
                    facts. On session/cancel the agent reports the idle
                    state_update with stopReason "cancelled" (confirmation on the
                    update lane, not the request slot). On session/close the
                    agent sends the final state_update (idle) BEFORE answering
                    the close — the ordering the AC#2 close policy consumes.
  session/resume -> with replayFrom {"type":"start"}: replays the retained
                    history as session/update notifications (user_message,
                    agent_message) and answers replay_complete BEFORE the
                    response; without replayFrom: reattaches with no replay and
                    no marker.

argv[2] = witness file (optional): one line per request the agent actually
  received: "INIT <requested-version>", "PROMPT <session-id>", "CANCEL
  <session-id>", "CLOSE <session-id>", "RESUME <session-id>
  replay=<start|none>", "V2READY". Tests assert the negotiation and the cursor
  reached the agent, not just that the client is happy.
"""
import sys
import json

profile = sys.argv[1] if len(sys.argv) > 1 else "dual"
witness = sys.argv[2] if len(sys.argv) > 2 else None

sessions = {}          # sid -> list of retained updates (for replay)
turn_seq = [0]


def say(obj):
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def result(rid, payload):
    say({"jsonrpc": "2.0", "id": rid, "result": payload})


def notify_update(sid, update):
    say({"jsonrpc": "2.0", "method": "session/update",
         "params": {"sessionId": sid, "update": update}})


def witness_line(line):
    if witness:
        with open(witness, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")


def v2_info():
    return {
        "name": "pipelinek-v2-fixture",
        "version": "0",
        "title": "Deterministic v2 dual-stack fixture",
    }


def v2_caps():
    # The stable v2 baseline: the session group is advertised, so resume/close/list
    # are required of the agent; nothing else is announced.
    return {
        "session": {"prompt": {"audio": {}, "image": {}, "embeddedContext": {}}},
    }


def v1_caps():
    return {
        "loadSession": True,
        "agentInfo": {"name": "pipelinek-v2-fixture", "version": "0"},
        "promptCapabilities": {"image": True, "audio": False, "embeddedContext": True},
    }


def handle(req):
    rid = req.get("id")
    method = req.get("method")
    params = req.get("params") or {}

    if method == "initialize":
        requested = params.get("protocolVersion")
        try:
            requested = int(requested)
        except (TypeError, ValueError):
            requested = 1
        witness_line("INIT %s" % requested)
        if profile == "legacy":
            result(rid, {"protocolVersion": 1, "agentCapabilities": v1_caps(),
                         "agentInfo": {"name": "pipelinek-v2-fixture", "version": "0"}})
            return
        if profile == "reject" and requested > 1:
            say({"jsonrpc": "2.0", "id": rid,
                 "error": {"code": -32022, "message": "Unsupported protocol version",
                           "data": {"requested": requested, "supported": [1]}}})
            return
        if requested >= 2:
            witness_line("V2READY")
            result(rid, {"protocolVersion": 2, "info": v2_info(),
                         "capabilities": v2_caps(), "authMethods": []})
        else:
            result(rid, {"protocolVersion": 1, "agentCapabilities": v1_caps(),
                         "agentInfo": {"name": "pipelinek-v2-fixture", "version": "0"}})
        return

    if not isinstance(params.get("sessionId"), str) and method.startswith("session/") \
            and method not in ("session/new",):
        # The v2 session notifications/requests all name a session; unknown ids are
        # refused the way a real agent refuses them.
        pass

    if method == "session/new":
        turn_seq[0] += 1
        sid = "s%d" % turn_seq[0]
        sessions[sid] = []
        result(rid, {"sessionId": sid, "configOptions": []})
        return

    if method == "session/prompt":
        sid = params["sessionId"]
        turn_seq[0] += 1
        mid = "m-%d" % turn_seq[0]
        sessions.setdefault(sid, []).append({
            "sessionUpdate": "user_message", "messageId": mid,
            "content": params.get("prompt") or [],
        })
        witness_line("PROMPT %s" % sid)
        # ACCEPTANCE: the response carries the messageId and NOTHING about completion.
        result(rid, {"messageId": mid})
        return

    if method == "session/cancel":
        sid = params["sessionId"]
        witness_line("CANCEL %s" % sid)
        # Cancellation confirmation on the update lane: idle with stopReason cancelled.
        notify_update(sid, {"sessionUpdate": "state_update", "state": "idle",
                            "stopReason": "cancelled"})
        return

    if method == "session/close":
        sid = params["sessionId"]
        witness_line("CLOSE %s" % sid)
        # The final updates come BEFORE the close response (v2 ordering rule).
        notify_update(sid, {"sessionUpdate": "state_update", "state": "idle",
                            "stopReason": "end_turn"})
        result(rid, {})
        return

    if method == "session/resume":
        sid = params.get("sessionId", "s1")
        cursor = params.get("replayFrom")
        replay = "none"
        if isinstance(cursor, dict) and cursor.get("type") == "start":
            replay = "start"
            for update in sessions.get(sid, []):
                notify_update(sid, update)
            notify_update(sid, {"sessionUpdate": "replay_complete"})
        witness_line("RESUME %s replay=%s" % (sid, replay))
        result(rid, {"configOptions": []})
        return

    if isinstance(rid, (int, str)):
        say({"jsonrpc": "2.0", "id": rid,
             "error": {"code": -32601, "message": "Method not found: %s" % method}})


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(req, list):
            # v2 forbids JSON-RPC batches; a batch line is not ACP traffic here.
            continue
        handle(req)


if __name__ == "__main__":
    main()
