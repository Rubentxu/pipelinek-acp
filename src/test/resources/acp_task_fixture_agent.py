#!/usr/bin/env python3
"""Deterministic ACP agent used by the acpTask contract tests (PKH-133, PKH-134).

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
  permission argv[2]=tool kind (read|edit|execute|other). Asks a permission for that
             kind with options ordered reject_once, allow_always, allow_once and
             replies PERM=granted when the client SELECTED an option — and names which
             optionId — PERM=denied otherwise. The grant must never invent an id and
             must prefer allow-once: the reply carries the selected optionId verbatim.
  fs-probe   writes rev-fs/written.txt, reads it back, then tries reading OUTSIDE the
             root (../escape.txt). Replies FS=<write>:<read>:<escape> where each part
             is ok, the content, or err-<jsonrpc-code>.
  term-probe terminal/create of `echo term-ok`, wait, output, a kill of a FOREIGN id,
             release. Replies TERM=exit<n>:out-<text>:kill-err-<code>:release-ok.
  elic-probe asks a form elicitation (never advertised by the client; asking anyway is
             legal traffic) and replies ELIC=<action> — the honest answer is cancel.
  observe    argv[2]=reply text. Before answering the prompt it sends a rich set of
             session/update traffic — thought, tool call in progress, partial update to
             completion, plan, and an unknown variant — with the fields the redaction
             whitelist must NEVER read (rawInput, _meta, the unknown variant's raw JSON)
             carrying canaries. The PKH-136 row reads the run's events table and fails
             if a canary is found there.

Speaks just enough NDJSON-RPC for initialize -> session/new -> session/prompt over
stdio, plus the reverse methods the probes exercise. TEST scaffolding, not shipped;
same independence guarantee as the PKH-132 fixture: a second implementation of the
wire, no network, no credentials.
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


def upd(session_id, update):
    write({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id, "update": update}})


# The agent's own requests to the client, keyed by id so the RESPONSE block can tell
# what it is answering. A probe mode that issues several kinds of request must not
# confuse a permission answer with a terminal one: each id registers its step name.
pending_steps = {}
next_id = [9000]


def ask(params, method, step):
    """Send one agent->client request and remember what its answer means."""
    next_id[0] += 1
    pending_steps[next_id[0]] = step
    write({"jsonrpc": "2.0", "id": next_id[0], "method": method, "params": params})
    return next_id[0]


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "reply"
    arg2 = sys.argv[2] if len(sys.argv) > 2 else ""
    arg3 = sys.argv[3] if len(sys.argv) > 3 else ""

    prompt_id = None
    prompt_session = None
    session_serial = 0
    probe = {"steps": {}, "text": None}

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

    def finish(text):
        """End the current prompt turn with the probe's answer."""
        nonlocal prompt_id
        chunk(prompt_session, text)
        write({"jsonrpc": "2.0", "id": prompt_id, "result": {"stopReason": "end_turn"}})
        prompt_id = None

    def result_field(msg):
        return msg.get("result") or {}

    def error_code(msg):
        return "err-%d" % msg["error"]["code"] if "error" in msg else None

    def handle_answer(step, msg):
        """One agent->client response arrived; advance the probe."""
        probe["steps"][step] = msg
        if step == "perm":
            outcome = json.dumps(result_field(msg).get("outcome", {}))
            granted = "selected" in outcome or "optionId" in outcome
            if granted:
                option_id = result_field(msg).get("outcome", {}).get("optionId", "?")
                finish("PERM=granted:%s" % option_id)
            else:
                finish("PERM=denied")
        elif step == "fs-write":
            probe["text"] = "write-%s" % (error_code(msg) or "ok")
            ask({"sessionId": prompt_session, "path": "rev-fs/written.txt"},
                "fs/read_text_file", "fs-read")
        elif step == "fs-read":
            if "error" in msg:
                probe["text"] += ":read-%s" % error_code(msg)
            else:
                probe["text"] += ":read-%s" % result_field(msg).get("content", "")
            ask({"sessionId": prompt_session, "path": "../escape.txt"},
                "fs/read_text_file", "fs-escape")
        elif step == "fs-escape":
            probe["text"] += ":%s" % (error_code(msg) or "LEAKED")
            finish("FS=%s" % probe["text"])
        elif step == "term-create":
            if "error" in msg:
                finish("TERM=create-%s" % error_code(msg))
                return
            probe["terminal_id"] = result_field(msg)["terminalId"]
            ask({"sessionId": prompt_session, "terminalId": probe["terminal_id"]},
                "terminal/wait_for_exit", "term-wait")
        elif step == "term-wait":
            probe["text"] = "exit%s" % result_field(msg).get("exitCode")
            ask({"sessionId": prompt_session, "terminalId": probe["terminal_id"]},
                "terminal/output", "term-out")
        elif step == "term-out":
            out = result_field(msg).get("output", "").strip()
            probe["text"] += ":out-%s" % (out or "empty")
            ask({"sessionId": prompt_session, "terminalId": "term-foreign-99"},
                "terminal/kill", "term-kill")
        elif step == "term-kill":
            probe["text"] += ":kill-%s" % (error_code(msg) or "LEAKED")
            ask({"sessionId": prompt_session, "terminalId": probe["terminal_id"]},
                "terminal/release", "term-release")
        elif step == "term-release":
            probe["text"] += ":release-%s" % (error_code(msg) or "ok")
            finish("TERM=%s" % probe["text"])
        elif step == "elic":
            action = result_field(msg).get("action", error_code(msg) or "none")
            finish("ELIC=%s" % action)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        mid = msg.get("id")

        if method is None and mid is not None:
            # A RESPONSE to one of our own requests. The legacy `malicious` mode keeps
            # its original single-request reading; probe modes dispatch by the id they
            # registered, so a permission answer and a terminal answer never merge.
            if mid in pending_steps:
                handle_answer(pending_steps.pop(mid), msg)
                continue
            decision = json.dumps(result_field(msg).get("outcome", {})).lower()
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
            elif mode == "permission":
                ask({"sessionId": prompt_session,
                     "toolCall": {"toolCallId": "tc-perm", "title": "touch a file",
                                  "kind": arg2 or "read"},
                     "options": [
                         {"optionId": "opt-reject", "name": "No", "kind": "reject_once"},
                         {"optionId": "opt-always", "name": "Always", "kind": "allow_always"},
                         {"optionId": "opt-once", "name": "Once", "kind": "allow_once"},
                     ]}, "session/request_permission", "perm")
                continue
            elif mode == "fs-probe":
                os.makedirs(os.path.join(os.getcwd(), "rev-fs"), exist_ok=True)
                ask({"sessionId": prompt_session, "path": "rev-fs/written.txt",
                     "content": "rev-write-ok"}, "fs/write_text_file", "fs-write")
                continue
            elif mode == "term-probe":
                ask({"sessionId": prompt_session, "command": "echo", "args": ["term-ok"]},
                    "terminal/create", "term-create")
                continue
            elif mode == "elic-probe":
                ask({"sessionId": prompt_session, "mode": "form",
                     "message": "give me your token",
                     "requestedSchema": {"type": "object", "properties": {}}},
                    "elicitation/create", "elic")
                continue
            elif mode == "observe":
                # Traffic for the PKH-136 row: every agent-driven update shape the
                # projection must turn into an event, plus the three fields it must
                # NEVER read (rawInput, _meta, an unknown variant's raw JSON), each
                # carrying a canary. A canary in the run's events table is the failure.
                upd(prompt_session, {"sessionUpdate": "agent_thought_chunk",
                                     "content": {"type": "text", "text": "planning the read"}})
                upd(prompt_session, {"sessionUpdate": "tool_call", "toolCallId": "tc-obs-1",
                                     "title": "read config", "kind": "read", "status": "pending",
                                     "rawInput": {"token": "CANARY-RAWINPUT-8842"},
                                     "_meta": {"secret": "CANARY-META-9931"}})
                upd(prompt_session, {"sessionUpdate": "tool_call_update", "toolCallId": "tc-obs-1",
                                     "status": "in_progress",
                                     "rawInput": {"next": "CANARY-RAWINPUT2-7753"}})
                upd(prompt_session, {"sessionUpdate": "tool_call_update", "toolCallId": "tc-obs-1",
                                     "status": "completed",
                                     "rawOutput": {"leak": "CANARY-RAWOUTPUT-6674"}})
                upd(prompt_session, {"sessionUpdate": "plan", "entries": [
                    {"content": "read config", "status": "completed", "priority": "high"},
                    {"content": "report", "status": "in_progress", "priority": "medium"}]})
                upd(prompt_session, {"sessionUpdate": "quantum_progress", "percent": 42,
                                     "blob": "CANARY-RAWJSON-5592"})
                text = arg2 or "fixture-reply"
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
