#!/usr/bin/env bash
# examples/socket-observe — a socket client against a local PipelineK (PKH-143 AC#3).
#
# Starts the INSTALLED host's service (`pipelinek serve`) on a private Unix socket with two
# operator authorizations — one that may read AND act, one that may only read — then runs the
# raw-socket client (socket_client.py, standard library only: no host classes, no plugin)
# through the whole story: start a real run, observe its state, read the PERMISSION question it
# published, answer it, follow the event street, read the terminal RESULT — and cancel a second
# live run. The read-only token is then refused from cancelling: the gate is a fact, not a
# convention.
#
# The same observation is available through the product's own CLI (`pipelinek inspect <run>
# --state --endpoint <socket> --authorization-file <file>`); this row measures the socket
# directly so nothing but the published wire is trusted.
#
# Everything lives under one temporary state directory, and the trap frees the serve process
# and the directory on any exit.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"

state="$(mktemp -d "${TMPDIR:-/tmp}/pkh143-socket-XXXXXX")"
serve_pid=""
cleanup() {
  if [ -n "$serve_pid" ] && kill -0 "$serve_pid" 2>/dev/null; then
    kill "$serve_pid" 2>/dev/null || true
    wait "$serve_pid" 2>/dev/null || true
  fi
  rm -rf "$state"
}
trap cleanup EXIT

socket="$state/service.sock"
auth="$state/authorizations"
chmod 700 "$state"
# The operator's own file (the trusted-local profile): token, label, scope+rights. The
# second token may read everything and touch nothing — its refusal is part of the evidence.
actor_token="pkh143-actor-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
reader_token="pkh143-reader-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
printf '%s pipelinek-actor all+start+cancel+answer\n%s pipelinek-reader all\n' \
  "$actor_token" "$reader_token" > "$auth"
chmod 600 "$auth"

mkdir -p "$state/workspaces/default"
# The service READS the substrate, it does not create one: prepare the store first,
# or serve refuses to bind at all ("the store is not there").
pipelinek substrate init --db "$state/run.sqlite"
pipelinek serve \
  --db "$state/run.sqlite" \
  --control-root "$state/control" \
  --socket "$socket" \
  --trust-profile trusted-local \
  --authorization-file "$auth" \
  --scripts "$repo/examples/socket-observe/scripts" \
  --workspaces "$state/workspaces" \
  > "$state/serve.log" 2>&1 &
serve_pid=$!

for _ in $(seq 1 60); do
  [ -S "$socket" ] && break
  kill -0 "$serve_pid" 2>/dev/null || { cat "$state/serve.log" >&2; exit 1; }
  sleep 0.5
done
test -S "$socket" || { echo "the service never bound its socket" >&2; cat "$state/serve.log" >&2; exit 1; }

python3 -I examples/socket-observe/socket_client.py \
  --socket "$socket" --token "$actor_token" --read-only-token "$reader_token"
