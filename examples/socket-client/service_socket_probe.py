#!/usr/bin/env python3
"""Cliente del socket de servicio de PipelineK (PKH-143 AC#3).

Habla el alambre publicado `pipelinek.service/2` (una linea JSON por pregunta,
la misma que codifica el ServiceClient del SDK publicado) sobre un socket Unix:
version/health, run.state (ESTADO), inputs.list (PERMISO), events.follow
(EVENTOS), output.follow (RESULTADO) y run.cancel (CANCELACION). No toca la
memoria ni el scheduler del host: solo el contrato publicado del servicio.

Uso:
  service_socket_probe.py --socket PATH [--token TOKEN] MODE [args...]

Modos:
  version                     version + health
  observe RUN_ID              run.state, inputs.list, events.follow, output.follow
  cancel RUN_ID [MOTIVO]      run.cancel
  start NOMBRE_SCRIPT [WS]    run.start (el servicio supervisa el hijo)
  wait-terminal RUN_ID        run.state en bucle hasta un estado terminal (max ~25s)

Cada respuesta del servicio se imprime como una linea JSON con la etiqueta del
op, para que el recibo sea legible tal cual.
"""
import argparse
import json
import socket
import sys
import time

SCHEMA = "pipelinek.service/2"


def connect(path, timeout=10.0):
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(timeout)
    client.connect(path)
    return client


class Wire:
    def __init__(self, path, token):
        self.path = path
        self.token = token

    def ask(self, request, max_lines=200, timeout=30.0):
        """Una pregunta por conexion (la regla del alambre) y sus lineas.

        Las calles follow viven hasta que el servicio dice "end" o el run acaba;
        un silencio mas largo que el presupuesto del cliente es el final honesto
        de la lectura, no un error: se devuelve lo que ya hay.
        """
        lines = []
        client = connect(self.path, timeout=timeout)
        try:
            doc = {"schema": SCHEMA, **request}
            if self.token:
                doc["auth"] = self.token
            client.sendall((json.dumps(doc) + "\n").encode())
            buffer = b""
            while len(lines) < max_lines:
                try:
                    chunk = client.recv(65536)
                except TimeoutError:
                    break
                if not chunk:
                    break
                buffer += chunk
                while b"\n" in buffer:
                    raw, buffer = buffer.split(b"\n", 1)
                    if raw.strip():
                        lines.append(json.loads(raw.decode()))
                if lines and lines[-1].get("kind") in ("answer", "refused", "end"):
                    break
            return lines
        finally:
            client.close()


def emit(tag, payload):
    print(json.dumps({"lane": tag, "reply": payload}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", required=True)
    parser.add_argument("--token", default="")
    parser.add_argument("mode")
    parser.add_argument("args", nargs="*")
    opts = parser.parse_args()
    wire = Wire(opts.socket, opts.token)

    if opts.mode == "version":
        emit("version", wire.ask({"op": "version"}))
        emit("health", wire.ask({"op": "health"}))
    elif opts.mode == "peek":
        # Solo las calles decisivas mientras el run vive: ESTADO y PERMISO.
        run_id = opts.args[0]
        emit("state", wire.ask({"op": "run.state", "runId": run_id}))
        emit("inputs", wire.ask({"op": "inputs.list", "runId": run_id}))
    elif opts.mode == "observe":
        run_id = opts.args[0]
        emit("state", wire.ask({"op": "run.state", "runId": run_id}))
        emit("inputs", wire.ask({"op": "inputs.list", "runId": run_id}))
        emit("events", wire.ask({"op": "events.follow", "runId": run_id}, max_lines=80))
        emit("output", wire.ask({"op": "output.follow", "runId": run_id}, max_lines=80))
    elif opts.mode == "cancel":
        run_id = opts.args[0]
        reason = opts.args[1] if len(opts.args) > 1 else "pkh-143 socket client"
        emit("cancel", wire.ask({"op": "run.cancel", "runId": run_id, "reason": reason}))
    elif opts.mode == "start":
        request = {"op": "run.start", "script": opts.args[0],
                   "idempotencyKey": "pkh143-%d" % int(time.time())}
        if len(opts.args) > 1:
            request["workspace"] = opts.args[1]
        emit("start", wire.ask(request))
    elif opts.mode == "wait-terminal":
        run_id = opts.args[0]
        deadline = time.time() + 25
        last = None
        while time.time() < deadline:
            last = wire.ask({"op": "run.state", "runId": run_id})
            text = json.dumps(last)
            if any(word in text for word in ("SUCCEEDED", "FAILED", "CANCELLED",
                                             "COMPLETED", "\"terminal\":true")):
                break
            time.sleep(2)
        emit("state-terminal", last)
    else:
        print("modo desconocido: %s" % opts.mode, file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
