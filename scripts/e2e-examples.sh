#!/usr/bin/env bash
# =============================================================================
# e2e-examples.sh — the published examples run for real (PKH-143 AC#1/#2/#3/#4).
# =============================================================================
#
# Every row under examples/ is a script that ASSERTS on the durable journal — so the gate row
# is simply: run each, in order, and refuse the first failure. The examples consume the
# INSTALLED host (scripts/host-sdk.sh resolves the pair) with the operator jar, the same way
# every other e2e row of this repository does. The socket-observe row additionally starts
# `pipelinek serve` and frees it via its own trap.
#
# AC#2 rides on this row for free: a host that does not ADMIT the artifact cannot run any of
# these scripts — admission is the first thing the run does.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"

rows=(
  "readonly-review:examples/readonly-review/run.sh"
  "goldfish-review:examples/goldfish-review/run.sh"
  "mcp-gateway:examples/mcp-gateway/run.sh"
  "test-repair:examples/test-repair/run.sh"
  "hodei-verify:examples/hodei-verify/verify.sh"
  "socket-observe:examples/socket-observe/observe-and-cancel.sh"
)

for row in "${rows[@]}"; do
  name="${row%%:*}"
  script="${row#*:}"
  echo "== example row: $name =="
  bash "$script"
  echo "== example row PASS: $name =="
done
echo "all published examples passed"
