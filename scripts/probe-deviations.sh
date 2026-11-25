#!/usr/bin/env bash
# Confirms, by running legacy Mockey, the deviations that no parity case can show: they concern operations of the
# legacy user interface and what legacy writes to disk. docs/deviations.md cites this script as their evidence.
# Usage: scripts/probe-deviations.sh /path/to/legacy/Mockey
#
# Prints one line for each deviation and fails when legacy does not behave as the register says.
# Everything runs in a scratch directory. The writable instances store their definitions there too.
set -euo pipefail

LEGACY="$(cd "${1:?usage: probe-deviations.sh <legacy-checkout>}" && pwd)"
JAVA8_HOME="${JAVA8_HOME:-/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURES="$ROOT/mockey-parity-tests/src/test/resources/parity-fixtures"
PORT=18080
BASE_URL="http://127.0.0.1:$PORT"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-legacy-probe.XXXXXX")"
DEFINITIONS="$WORK/definitions"
DEPOT="$DEFINITIONS/mockey_def_depot"
pid=""
failures=0

stop_legacy() {
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    pid=""
  fi
}
trap 'stop_legacy; rm -rf "$WORK"' EXIT

# start_legacy <read-only: true|false> [more legacy arguments]: starts legacy without a browser.
start_legacy() {
  local read_only="$1"
  shift
  refuse_running_server
  (cd "$WORK" && exec "$JAVA8_HOME/bin/java" -Dfile.encoding=UTF-8 -jar "$LEGACY/dist/Mockey.jar" \
    -p "$PORT" -t "$read_only" -H true -l "$DEFINITIONS" "$@") >>"$WORK/legacy.out" 2>&1 &
  pid=$!
  await_legacy
}

refuse_running_server() {
  if curl -sf "$BASE_URL/status" >/dev/null; then
    echo "Port $PORT already answers /status. Stop that server first." >&2
    exit 1
  fi
}

await_legacy() {
  for _ in $(seq 1 60); do
    curl -sf "$BASE_URL/status" >/dev/null && return
    sleep 1
  done
  echo "Legacy Mockey did not start on port $PORT:" >&2
  cat "$WORK/legacy.out" >&2
  exit 1
}

get() { curl -s "$BASE_URL$1"; }
status_of() { curl -s -o /dev/null -w '%{http_code}' "$BASE_URL$1"; }
load() { get "/home?action=init&transientState=${2:-true}&type=json&file=$FIXTURES/$1.xml" >/dev/null; }
inode() { ls -i "$1" | awk '{print $1}'; }
count() { grep -o "$1" | wc -l | tr -d ' '; }

# confirm <deviation> <what was observed> <condition...>: the condition is a command that succeeds when legacy
# behaves as the register says.
confirm() {
  local deviation="$1" observed="$2"
  shift 2
  if "$@"; then
    echo "$deviation confirmed: $observed"
  else
    echo "$deviation NOT confirmed: expected that $observed" >&2
    failures=$((failures + 1))
  fi
}
contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }
lacks() { ! contains "$1" "$2"; }
same() { [ "$1" = "$2" ]; }
differ() { [ "$1" != "$2" ]; }

# ---- read-only instance ------------------------------------------------------------------------------------
start_legacy true

# DEV-18: saving a scenario without the universal-error flag clears the universal error scenario.
load responses-universal-error
before="$(status_of '/service/fallback/universal-second?q=other')"
curl -s -o /dev/null -X POST "$BASE_URL/scenario" \
  --data 'serviceId=2&scenarioId=1&scenarioName=known&responseMessage=matched&matchStringArg=known&httpResponseStatusCode=200'
after="$(get '/service/fallback/universal-second?q=other')"
confirm DEV-18 "the universal error scenario answered with 503 before another scenario was saved" same "$before" 503
confirm DEV-18 "after the save, legacy knows no universal error scenario" contains "$after" "nor is there a universal-scenario-error defined"

# DEV-19: a duplicated service has the name and the mock URL of the original and no default scenario.
load automation
curl -s -o /dev/null "$BASE_URL/setup?serviceId=1&duplicateService=1"
exported="$(get /export/definitions.xml | grep '<service ' | grep 'name="Customers"')"
confirm DEV-19 "two services are named Customers and have the mock URL customers" \
  same "$(printf '%s' "$exported" | count 'name="Customers" [^>]*url="customers"')" 2
confirm DEV-19 "the copy has no default scenario" contains "$exported" 'default_scenario_id="null"'

# DEV-20: identifiers are integers, and the number of a deleted service is given to the next new service.
load automation
curl -s -o /dev/null "$BASE_URL/setup?serviceId=3&deleteService=1"
curl -s -o /dev/null "$BASE_URL/service/http://127.0.0.1:1/new-service"
confirm DEV-20 "the service created after service 3 was deleted is service 3 again" \
  contains "$(get /definitions)" '"serviceName":"new-service (auto generated)","serviceId":3'

# DEV-23: a draft-03 schema is accepted and applied.
schema='{"type":"object","properties":{"id":{"type":"string","required":true}}}'
validate() {
  curl -s -X POST "$BASE_URL/jsonschemavalidate" --data-urlencode "schema=$schema" --data-urlencode "data=$1" \
    --data "useV3=$2&useId=false"
}
confirm DEV-23 "the draft-03 keyword \"required\": true rejects an object without the property" \
  contains "$(validate '{}' true)" 'missing required property'
confirm DEV-23 "the same schema accepts an object with the property" contains "$(validate '{"id":"a"}' true)" '"valid":true'

# DEV-24: the history is not bounded.
load responses-static
curl -s -o /dev/null "$BASE_URL/service/static/plan?n=[1-3000]"
confirm DEV-24 "the first of 3000 history entries is still there" \
  contains "$(get '/conversation/record?conversationRecordId=1')" '"conversationRecordId":"1"'
confirm DEV-24 "so is the last" contains "$(get '/conversation/record?conversationRecordId=3000')" '"conversationRecordId":"3000"'

# DEV-27: an upload reads any URL, also one of a file on the server. The parity cases show the same for /home.
get '/home?action=deleteAllServices&type=json' >/dev/null
curl -s -o /dev/null -X POST "$BASE_URL/upload" --data-urlencode 'viaUrl=1' --data-urlencode "url=file://$FIXTURES/automation-init.xml"
confirm DEV-27 "an upload by URL loaded a file of the server" contains "$(get /definitions)" '"serviceName":"Suite tagged"'

# DEV-29: merging services whose names differ copies nothing and deletes the source.
load automation
merged="$(curl -s -X POST "$BASE_URL/merge" --data 'serviceIdMergeSource[]=2&serviceIdMergeDestination=1')"
definitions="$(get /definitions)"
confirm DEV-29 "the merge reports no additions and no conflicts" contains "$merged" '"additions":"","conflicts":""'
confirm DEV-29 "the source service Orders is deleted" lacks "$definitions" '"serviceName":"Orders"'
confirm DEV-29 "none of its scenarios reached the destination service" lacks "$definitions" '"scenarioName":"open"'

# DEV-63 to DEV-67: what Jetty, the HTTP server of legacy, does with a request before Mockey sees it.
load responses-static
plan='/service/static/plan'
letters() { head -c "$1" /dev/zero | tr '\0' a; }
# raw <request>: writes the request to a connection as it is and prints the status line of the answer.
raw() {
  exec 3<>"/dev/tcp/127.0.0.1/$PORT"
  printf '%b' "$1" >&3
  head -n 1 <&3 | tr -d '\r'
  exec 3>&- 3<&-
}
confirm DEV-63 "the static service answers a request for its URL" same "$(status_of "$plan")" 200
confirm DEV-63 "the same request with a % that starts no escape gets the status 400" \
  same "$(curl -s -o /dev/null -w '%{http_code}' --path-as-is "$BASE_URL/service/static/pl%zzan")" 400

confirm DEV-64 "a request with a query string of 6,000 characters is answered" same "$(status_of "$plan?q=$(letters 6000)")" 200
confirm DEV-64 "a request with a query string of 7,000 characters gets the status 413" \
  same "$(status_of "$plan?q=$(letters 7000)")" 413
confirm DEV-64 "a header of 7,000 characters gets the status 413" \
  same "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Long: $(letters 7000)" "$BASE_URL$plan")" 413

letters 54525952 >"$WORK/body-of-52-mib"
confirm DEV-65 "a body of 52 MiB is taken" \
  same "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/body-of-52-mib" "$BASE_URL$plan")" 200
rm -f "$WORK/body-of-52-mib"

confirm DEV-66 "a request whose lines end with a line feed alone is answered" \
  contains "$(raw "GET $plan HTTP/1.1\nHost: 127.0.0.1\nConnection: close\n\n")" ' 200 '
confirm DEV-66 "a request with the Host header a/b is answered" \
  same "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: a/b' "$BASE_URL$plan")" 200
confirm DEV-66 "a request with the Host header a?b is answered" \
  same "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: a?b' "$BASE_URL$plan")" 200
confirm "No deviation" "a request of HTTP/1.1 without a Host header gets the status 400, as in the new server" \
  contains "$(raw "GET $plan HTTP/1.1\r\nConnection: close\r\n\r\n")" ' 400 '

confirm DEV-67 "a request for /service is answered by Mockey, as a request for /service/ is" \
  same "$(get /service)" "$(get /service/)"
confirm DEV-67 "and that answer is the one for a target that no service knows" contains "$(get /service)" '"fail"'

stop_legacy

# ---- writable instance -------------------------------------------------------------------------------------
start_legacy false
load automation false

# DEV-21: a save rewrites the files of every service, and services with the same name share one file.
customers="$DEPOT/customers/customers.xml"
before="$(inode "$customers")"
get '/config/service?serviceName=Orders&hangTime=7' >/dev/null
confirm DEV-21 "a change to Orders replaced the file of Customers" differ "$before" "$(inode "$customers")"
curl -s -o /dev/null "$BASE_URL/service/http://127.0.0.1:1/a/twin"
curl -s -o /dev/null "$BASE_URL/service/http://127.0.0.1:1/b/twin"
confirm DEV-21 "two services named twin (auto generated) are stored in one file" \
  same "$(grep -o 'file="mockey_def_depot/twinautogenerated/twinautogenerated.xml"' "$DEFINITIONS/mock_service_definitions.xml" | wc -l | tr -d ' ')" 2

# DEV-22: a transient change is not saved by itself, but the next save of anything writes it.
hang_time() { grep -o 'hang_time="[0-9]*"' "$customers" | head -1; }
get '/config/service?serviceName=Customers&hangTime=55&transientState=true' >/dev/null
confirm DEV-22 "the transient change of Customers is not in its file" same "$(hang_time)" 'hang_time="0"'
get '/config/service?serviceName=Orders&hangTime=8' >/dev/null
confirm DEV-22 "a later change of Orders wrote the transient change of Customers" same "$(hang_time)" 'hang_time="55"'

stop_legacy

# DEV-30: starting in writable mode with an initialization document deletes the stored definitions first.
stored="$(find "$DEPOT" -type f | wc -l | tr -d ' ')"
start_legacy false -f "$DEFINITIONS/mock_service_definitions.xml"
sleep 2
confirm DEV-30 "definition files were stored before the restart" differ "$stored" 0
confirm DEV-30 "the restart with the stored document as initialization document left no definition file" \
  same "$(find "$DEPOT" -type f 2>/dev/null | wc -l | tr -d ' ')" 0
confirm DEV-30 "and no service" lacks "$(get /definitions)" 'serviceName'

stop_legacy

# DEV-25: without the option -H true, legacy launches a browser at startup. So that no browser opens here,
# legacy finds stand-ins that only note the URL they are asked to open: on macOS a class in place of the one
# that legacy calls, and elsewhere a "firefox" on the PATH, which is the first browser that legacy looks for.
STAND_IN="$WORK/stand-in"
mkdir -p "$STAND_IN/com/apple/eio"
cat >"$STAND_IN/com/apple/eio/FileManager.java" <<'JAVA'
package com.apple.eio;

public class FileManager {
    public static void openURL(String url) throws java.io.IOException {
        java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("mockey.probe.opened")), url.getBytes());
    }
}
JAVA
"$JAVA8_HOME/bin/javac" -d "$STAND_IN" "$STAND_IN/com/apple/eio/FileManager.java"
printf '#!/bin/sh\nexit 0\n' >"$STAND_IN/which"
printf '#!/bin/sh\necho "$1" >"%s/browser-opened"\n' "$WORK" >"$STAND_IN/firefox"
chmod +x "$STAND_IN/which" "$STAND_IN/firefox"
refuse_running_server
(cd "$WORK" && PATH="$STAND_IN:$PATH" exec "$JAVA8_HOME/bin/java" "-Xbootclasspath/p:$STAND_IN" \
  "-Dmockey.probe.opened=$WORK/browser-opened" -Djava.awt.headless=true \
  -jar "$LEGACY/dist/Mockey.jar" -p "$PORT" -t true -l "$WORK/definitions-browser") >>"$WORK/legacy.out" 2>&1 &
pid=$!
await_legacy
for _ in $(seq 1 20); do
  [ -f "$WORK/browser-opened" ] && break
  sleep 1
done
confirm DEV-25 "legacy asked a browser to open its initialization URL" \
  contains "$(cat "$WORK/browser-opened" 2>/dev/null)" "http://127.0.0.1:$PORT/home?action=init"

if [ "$failures" -ne 0 ]; then
  echo "$failures observations differ from the deviation register." >&2
  exit 1
fi
