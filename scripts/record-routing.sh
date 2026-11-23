#!/usr/bin/env bash
# Records the routing parity cases from a legacy Mockey checkout.
# Usage: scripts/record-routing.sh /path/to/legacy/Mockey
set -euo pipefail

LEGACY="${1:?usage: record-routing.sh <legacy-checkout>}"
JAVA8_HOME="${JAVA8_HOME:-/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURES="$ROOT/mockey-parity-tests/src/test/resources/parity-fixtures"
PORT=18080

record_group() {
  local group="$1"
  shift
  "$JAVA8_HOME/bin/java" -jar "$LEGACY/dist/Mockey.jar" -p "$PORT" -t true -H true \
    -l "/tmp/mockey-legacy-$group" -f "$FIXTURES/$group.xml" "$@" >"/tmp/mockey-legacy-$group.out" 2>&1 &
  local pid=$!
  trap 'kill $pid 2>/dev/null || true' RETURN
  for _ in $(seq 1 60); do
    curl -sf "http://127.0.0.1:$PORT/status" >/dev/null && break
    sleep 1
  done
  (cd "$ROOT" && ./gradlew :mockey-parity-tests:test --tests '*RoutingCasesTest.recordsGroupFromLegacy' \
    -Dmockey.parity.legacy.baseUrl="http://127.0.0.1:$PORT" \
    -Dmockey.parity.recordGroup="$group" --rerun-tasks)
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

record_group routing
record_group routing-tagged -F keep
