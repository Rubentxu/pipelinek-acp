#!/usr/bin/env python3
"""Socket client of the PipelineK local service (PKH-143 AC#3).

Speaks the published wire contract `pipelinek.service/2` over the Unix socket served by
`pipelinek serve`: one JSON-RPC-ish question per connection (the wire's own rule), the answer
on one line. NO pipeline, NO plugin and NO workspace class is imported here — this file is the
evidence that a third-party consumer (Fabric, Hodei, an operator's script) can observe a REAL
run and act on it through the published surface alone:

    version / health                     — the service and its protocol
    run.start  {script}                  — start a supervised run BY NAME
    run.state  {runId}                   — the live/terminal observation
    inputs.list {runId}                  — the questions the run is asking a PERSON
    input.answer {runId, opId, decision} — the person's own answer (never an authorization)
    events.follow {runId}                — the durable event street (records, then end)
    run.cancel {runId, reason}           — close a supervised run; the terminal is the answer

A second token, scoped WITHOUT any right (`all` alone), may read and is refused from acting:
the refusal is part of the example, not an error path.

Usage:
    python3 socket_client.py --socket PATH --token TOKEN [--read-only-token TOKEN2]
    python3 socket_client.py --socket PATH --token TOKEN --start-only SLOW.kts   # start, exit

Exit 0 only if every law the scenario asserts held. TEST-scaffolding quality: example tooling,
no dependencies beyond the standard library.
"""
import argparse
import json
import socket
import sys
import time

SCHEMA = "pipelinek.service/2"


class Service:
    """One question per connection, exactly as the published client does it."""

    def __init__(self, path, token, timeout=20.0):
        self.path = path
        self.token = token
        self.timeout = timeout

    def _connect(self):
        channel = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        channel.settimeout(self.timeout)
        channel.connect(self.path)
        return channel

    def ask(self, op, **fields):
        request = {"schema": SCHEMA, "auth": self.token, "op": op}
        request.update(fields)
        channel = self._connect()
        try:
            channel.sendall((json.dumps(request) + "\n").encode())
            line = _read_line(channel, self.timeout)
            if line is None:
                raise RuntimeError("%s: the service closed without answering" % op)
            return json.loads(line)
        finally:
            channel.close()

    def street(self, op, on_record, **fields):
        """Follow a subscription until its end line (or the read deadline, reported)."""
        request = {"schema": SCHEMA, "auth": self.token, "op": op}
        request.update(fields)
        channel = self._connect()
        records = []
        try:
            channel.sendall((json.dumps(request) + "\n").encode())
            while True:
                line = _read_line(channel, self.timeout)
                if line is None:
                    return records, "deadline"
                doc = json.loads(line)
                kind = doc.get("kind")
                if kind == "refused":
                    raise RuntimeError("%s street refused: %r" % (op, doc.get("refusal")))
                if kind == "record":
                    records.append(doc["record"])
                    on_record(doc["record"])
                elif kind == "end":
                    return records, doc.get("reason", "end")
                else:
                    raise RuntimeError("%s street spoke an unknown line kind %r" % (op, kind))
        finally:
            channel.close()


def _read_line(channel, timeout):
    chunks = bytearray()
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        channel.settimeout(remaining)
        try:
            byte = channel.recv(1)
        except socket.timeout:
            return None
        if not byte:
            return chunks.decode() if chunks else None
        if byte == b"\n":
            return chunks.decode()
        chunks += byte


def wait_state(service, run_id, wanted, attempts=60, pause=0.5):
    last = None
    for _ in range(attempts):
        answer = service.ask("run.state", runId=run_id)
        last = answer
        if answer.get("kind") == "answer" and answer.get("result", {}).get("state") in wanted:
            return answer["result"]
        time.sleep(pause)
    raise AssertionError("run %s never reached %s, last answer: %r" % (run_id, wanted, last))


def pending_question(service, run_id, attempts=60, pause=0.5):
    for _ in range(attempts):
        answer = service.ask("inputs.list", runId=run_id)
        assert answer.get("kind") == "answer", "inputs.list was refused: %r" % answer
        inputs = answer["result"].get("inputs") or []
        if inputs:
            return inputs[0]
        time.sleep(pause)
    raise AssertionError("run %s published no question within the deadline" % run_id)


def scenario(args):
    service = Service(args.socket, args.token)
    evidence = {}

    version = service.ask("version")
    health = service.ask("health")
    assert version.get("kind") == "answer" and health.get("kind") == "answer", (
        "the service must answer version and health: %r %r" % (version, health))
    evidence["service"] = version["result"]

    if args.start_only:
        started = service.ask("run.start", script=args.start_only)
        assert started.get("kind") == "answer", "run.start refused: %r" % started
        print("STARTED %s" % started["result"]["runId"])
        return

    # -- the observed run: state, PERMISSION question, answer, events, result --------------
    started = service.ask("run.start", script=args.gate_script,
                          idempotencyKey="pkh143-gate-%d" % int(time.time()))
    assert started.get("kind") == "answer", "run.start refused: %r" % started
    run_id = started["result"]["runId"]
    evidence["started"] = {"runId": run_id}

    running = wait_state(service, run_id, {"running", "empty"})
    evidence["state_while_live"] = running["state"]

    question = pending_question(service, run_id)
    evidence["permission"] = {"opId": question["opId"], "message": question["message"]}
    answered = service.ask("input.answer", runId=run_id, opId=question["opId"],
                           decision="proceed", message="observed by the PKH-143 socket client")
    assert answered.get("kind") == "answer", "input.answer refused: %r" % answered
    evidence["permission_answer"] = answered["result"].get("outcome")

    kinds = []
    records, street_end = service.street(
        "events.follow", lambda record: kinds.append(record.get("kind", "")), runId=run_id)
    evidence["events"] = {"records": len(records), "end": street_end,
                          "kinds": sorted(set(k for k in kinds if k))[:8]}
    assert len(records) >= 1, "the event street carried no record for a real run"

    final = wait_state(service, run_id, {"terminal"}, attempts=120)
    evidence["result"] = {"state": final["state"], "outcome": final.get("outcome")}

    # -- the cancelled run: a supervised run the socket client really ends ------------------
    slow_started = service.ask("run.start", script=args.slow_script,
                               idempotencyKey="pkh143-slow-%d" % int(time.time()))
    assert slow_started.get("kind") == "answer", "run.start (slow) refused: %r" % slow_started
    slow_id = slow_started["result"]["runId"]
    cancelled = service.ask("run.cancel", runId=slow_id, reason="PKH-143 example: cancel is a fact")
    assert cancelled.get("kind") == "answer", "run.cancel refused: %r" % cancelled
    evidence["cancelled"] = cancelled["result"]
    slow_final = wait_state(service, slow_id, {"terminal"}, attempts=120)
    assert "cancel" in str(slow_final.get("outcome", "")).lower(), (
        "the cancelled run must say so in its terminal: %r" % slow_final)
    evidence["cancelled_terminal"] = slow_final.get("outcome")

    # -- the read-only token: may watch, may not touch -------------------------------------
    if args.read_only_token:
        watcher = Service(args.socket, args.read_only_token)
        readable = watcher.ask("run.state", runId=run_id)
        assert readable.get("kind") == "answer", "a reader may read: %r" % readable
        refused = watcher.ask("run.cancel", runId=run_id, reason="should not be allowed")
        assert refused.get("kind") == "refused", (
            "a token without the cancel right must be REFUSED, not obeyed: %r" % refused)
        evidence["read_only_refusal"] = refused.get("refusal", {}).get("kind")

    print("PKH143-SOCKET-EVIDENCE " + json.dumps(evidence, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--read-only-token", default=None)
    parser.add_argument("--gate-script", default="input-gate.kts")
    parser.add_argument("--slow-script", default="slow.kts")
    parser.add_argument("--start-only", default=None)
    sys.exit(0 if _run(parser) else 1)


def _run(parser):
    args = parser.parse_args()
    try:
        scenario(args)
        return True
    except (AssertionError, RuntimeError) as failure:
        print("SOCKET-CLIENT FAILED: %s" % failure, file=sys.stderr)
        return False


if __name__ == "__main__":
    main()
