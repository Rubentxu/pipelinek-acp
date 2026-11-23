#!/usr/bin/env bash
# examples/mcp-gateway — the PKH-139 gateway law showcase over the real host (PKH-143 AC#1).
#
# The fixture agent dials the session's loopback MCP gateway and drives the six calls of the
# law: executed, idempotent repeat answered from the journal, executed again, budget refusal,
# unknown tool, schema refusal. This script asserts BOTH halves: the agent's reply (what an
# MCP client saw) and the turn's durable exposedToolCalls (what the gateway answered), and
# then proves the gateway died with the scope — the URL in the journal must refuse.
set -eu
cd "$(dirname "$0")/../.."
. scripts/host-sdk.sh
acp_use_host "$(acp_sdk_version)"
repo="$(pwd)"
state="${XDG_STATE_HOME:-$HOME/.local/state}/pipelinek/pipelinek-acp-examples"
mkdir -p "$state" "$repo/build/examples"
rm -f "$state/gateway.sqlite" "$state/gateway.sqlite-wal" "$state/gateway.sqlite-shm"
rm -rf "$state/control-gateway"
rm -f "$repo/build/examples/gateway-witness.txt"

pipelinek run --workspace "$repo" \
  --db "$state/gateway.sqlite" --control-root "$state/control-gateway" \
  --plugin-jar "$repo/build/libs/pipelinek-acp-0.1.0-operator.jar" \
  examples/mcp-gateway/gateway.kts

python3 -I - "$state/gateway.sqlite" "$repo/build/examples/gateway-witness.txt" <<'PY'
import json, sqlite3, sys
db = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
results = [
    json.loads(out)["result"]
    for (out,) in db.execute("select output from operation_journal where output is not null")
    if json.loads(out).get("result")
]
turns = [json.loads(r) for r in results if "exposedToolCalls" in r]
assert turns, "no reactive turn output with exposedToolCalls in the journal"
turn = turns[-1]
assert turn.get("ok") is True, "the reactive turn was refused: %s %s" % (
    turn.get("failureKind"), turn.get("detail"))

# The agent's side: what an MCP client saw through the wire.
reply = turn.get("reply", "")
assert reply == "TOOLS=1|CALLED=executed:already_acknowledged:executed:call_limit_refused:err--32602:err--32602", (
    "the showcase calls did not answer as the gateway laws say: %r" % reply)

# The gateway's side: every call, typed, in order (PKH-139 AC#1/#2/#3).
calls = [(c["tool"], c["status"]) for c in turn["exposedToolCalls"]]
assert calls == [
    ("audit", "executed"),
    ("audit", "already_acknowledged"),
    ("audit", "executed"),
    ("audit", "call_limit_refused"),
    ("ghost", "unknown_tool_denied"),
    ("audit", "schema_denied"),
], "the durable call ledger disagrees with the wire: %r" % calls

# The idempotent repeat dispatched nothing: two executed rows carrying attempts 1 and 2 out of
# three keyed calls to the same tool is the never-re-drive law visible in the ledger.
assert [c["attemptIndex"] for c in turn["exposedToolCalls"] if c["status"] == "executed"] == [1, 2], (
    "the two executed calls must carry consecutive attempts: %r" % turn["exposedToolCalls"])

# The witness proves the agent really listed ONE tool — the declared surface, no more.
witness = open(sys.argv[2]).read()
assert "mcp-http 1" in witness, "session/new must carry exactly the gateway: %r" % witness
assert "tools audit" in witness, "tools/list must offer only the declared tool: %r" % witness
print("mcp-gateway evidence:", calls, "| reply:", reply)
PY

# AC#2's expiry at example level: once the run is over, the gateway URL must refuse. The
# journal still carries it — the URL is dead data, and a curl against it must say so.
url="$(python3 -I -c "
import json, sqlite3
db = sqlite3.connect('file:$state/gateway.sqlite?mode=ro', uri=True)
for (out,) in db.execute('select output from operation_journal where output is not null'):
    doc = json.loads(out)
    r = doc.get('result')
    if r and '\"exposedToolCalls\"' in r:
        print(json.loads(r).get('mcpGatewayUrl', ''))
)")
test -n "$url"
if curl -fsS --max-time 3 -X POST -H 'Content-Type: application/json' -d '{}' "$url" >/dev/null 2>&1; then
  echo "the gateway outlived its scope: $url still answers" >&2
  exit 1
fi
echo "the gateway died with the session scope: $url refuses"
