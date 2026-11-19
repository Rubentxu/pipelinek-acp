#!/usr/bin/env bash
# =============================================================================
# run-gate.sh — THE way this gate is run.
# =============================================================================
#
# Two things the plain `pipelinek run` leaves to the caller, and that the caller
# gets wrong:
#
#   * the host. The plugin declares an api range derived from the SDK it was
#     compiled against, and the host that runs the artifact must be inside that
#     range — so the gate runs under the host of that SDK, not under whichever
#     `pipelinek` happens to be first in the PATH. The rows inside measure that
#     host too; a gate driven by another one would measure a plugin no host
#     admits.
#
#   * the state. A `pipelinek run` with the same base and the same control root
#     REPLAYS the previous run: the new log is written in seconds and looks like
#     a fresh failure. Every run gets a state of its own.
#
# Sourced helpers live in scripts/host-sdk.sh.
set -eu
cd "$(dirname "$0")/.."
. scripts/host-sdk.sh

sdk_version="$(acp_sdk_version)"
acp_use_host "$sdk_version"
host_version="$(acp_host_version)"

state_root="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-gate"
state="$state_root/$(date +%Y%m%d-%H%M%S)-host$host_version"
mkdir -p "$state"

echo "gate: SDK $sdk_version, host $host_version, state $state"
exec pipelinek run \
  --workspace . \
  --db "$state/run.sqlite" \
  --control-root "$state/control" \
  pipeline.kts
