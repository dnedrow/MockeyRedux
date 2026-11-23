#!/usr/bin/env bash
# Records parity case groups from a legacy Mockey checkout, or replays their recordings against it.
# Usage: scripts/record-cases.sh /path/to/legacy/Mockey <selection> [--replay]
#
# Selections:
#   routing      routing cases, with the tag filter and twisting
#   responses    static and dynamic response cases
#   proxy        proxy-mode cases against the stub upstream and the stub outbound proxies
#   rules        request-rule cases
#   inspection   service-level inspection cases
#   automation   cases for the legacy automation endpoints
#   all          every group
# A selection can also name a single group, such as proxy-redirects or rules-keys.
#
# Recording keeps the label and the stated new response of a recording whose legacy response did not change,
# and stores every other recording as unreviewed.
#
# With --replay nothing is recorded. The stored recordings of the selection are sent to legacy again, and the
# script fails when a response differs from its recording.
set -euo pipefail

USAGE="usage: record-cases.sh <legacy-checkout> <selection> [--replay]"
LEGACY="$(cd "${1:?$USAGE}" && pwd)"
SELECTION="${2:?$USAGE}"
case "${3:-}" in
  "") MODE=record ;;
  --replay) MODE=replay ;;
  *)
    echo "$USAGE" >&2
    exit 2
    ;;
esac
case "$SELECTION" in
  routing | routing-*) CLASSES=(Routing) ;;
  responses | responses-*) CLASSES=(Response) ;;
  proxy | proxy-*) CLASSES=(Proxy) ;;
  rules | rules-* | inspection) CLASSES=(Rule) ;;
  automation) CLASSES=(Automation) ;;
  all) CLASSES=(Routing Response Proxy Rule Automation) ;;
  *)
    echo "Unknown selection '$SELECTION'. $USAGE" >&2
    exit 2
    ;;
esac

JAVA8_HOME="${JAVA8_HOME:-/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=18080
BASE_URL="http://127.0.0.1:$PORT"

if curl -sf "$BASE_URL/status" >/dev/null; then
  echo "Port $PORT already answers /status. Stop that server first: recording replaces its definitions." >&2
  exit 1
fi

# Legacy writes mockeyDebugFile.log and jsp/ into its working directory, so it runs in a scratch directory.
# The file encoding is pinned because legacy decodes request bodies with the default charset of its JVM.
# The automation cases initialize legacy from suite-a.xml, which legacy looks for in its working directory.
# The directory is under /tmp and not under $TMPDIR, because legacy reports the path of its definitions in
# /status and that answer is recorded: the temporary directory of a user account must not get into a recording.
WORK="$(mktemp -d /tmp/mockey-legacy-cases.XXXXXX)"
cp "$ROOT/mockey-parity-tests/src/test/resources/parity-fixtures/automation-init.xml" "$WORK/suite-a.xml"
(cd "$WORK" && exec "$JAVA8_HOME/bin/java" -Dfile.encoding=UTF-8 -jar "$LEGACY/dist/Mockey.jar" \
  -p "$PORT" -t true -H true -l "$WORK/definitions") >"$WORK/legacy.out" 2>&1 &
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

# A test loads the fixture of a group before each of its cases, so one legacy instance serves them all.
if [ "$MODE" = record ]; then
  METHOD=recordsGroupFromLegacy
  PROPERTY=mockey.parity.recordGroup
else
  METHOD=replaysGroupAgainstLegacy
  PROPERTY=mockey.parity.replayGroup
fi
TESTS=()
for class in "${CLASSES[@]}"; do
  TESTS+=(--tests "*${class}CasesTest.$METHOD")
done
cd "$ROOT"
./gradlew :mockey-parity-tests:test --rerun "${TESTS[@]}" \
  -Dmockey.parity.legacy.baseUrl="$BASE_URL" \
  "-D$PROPERTY=$SELECTION"
