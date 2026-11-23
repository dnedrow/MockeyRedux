#!/usr/bin/env bash
# Records the static and dynamic response parity cases from a legacy Mockey checkout.
# Usage: scripts/record-responses.sh /path/to/legacy/Mockey
set -euo pipefail

LEGACY="$(cd "${1:?usage: record-responses.sh <legacy-checkout>}" && pwd)"
JAVA8_HOME="${JAVA8_HOME:-/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=18080
BASE_URL="http://127.0.0.1:$PORT"

if curl -sf "$BASE_URL/status" >/dev/null; then
  echo "Port $PORT already answers /status. Stop that server first: recording replaces its definitions." >&2
  exit 1
fi

# Legacy writes mockeyDebugFile.log and jsp/ into its working directory, so it runs in a scratch directory.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-legacy-responses.XXXXXX")"
(cd "$WORK" && exec "$JAVA8_HOME/bin/java" -jar "$LEGACY/dist/Mockey.jar" -p "$PORT" -t true -H true -l "$WORK/definitions") \
  >"$WORK/legacy.out" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; rm -rf "$WORK"' EXIT

for _ in $(seq 1 60); do
  curl -sf "$BASE_URL/status" >/dev/null && break
  sleep 1
done
if ! curl -sf "$BASE_URL/status" >/dev/null; then
  echo "Legacy Mockey did not start on port $PORT:" >&2
  cat "$WORK/legacy.out" >&2
  exit 1
fi

# The test loads each fixture group through the legacy initialization endpoint, so one legacy instance serves them all.
cd "$ROOT"
./gradlew :mockey-parity-tests:test --rerun --tests '*ResponseCasesTest.recordsGroupFromLegacy' \
  -Dmockey.parity.legacy.baseUrl="$BASE_URL" \
  -Dmockey.parity.recordGroup=responses
