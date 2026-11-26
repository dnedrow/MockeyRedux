#!/usr/bin/env bash
# The end-to-end check: the server and the UI run together, as their distributions deliver them.
#
# The script unpacks the two distributions, starts the server and then the UI, and does what a person does
# who moves over from Mockey 2.x:
#
#   1. It imports a definitions document of Mockey 2.x through the import page of the UI.
#   2. It sends a request to a mock URL of the document, and expects the response of the scenario.
#   3. It finds that request in the history that the UI shows, and opens the page of the entry.
#   4. It stops both programs, and expects each to end with the status 0.
#
# Usage: scripts/end-to-end-check.sh <server-distribution.tar.gz> <ui-distribution.tar.gz>
#
# The document is the sample document that Mockey 2.x came with. The tests of the importer have a copy of it.
#
# The script needs tar, curl, and a Java 21 runtime, which the start scripts look for in JAVA_HOME and then
# on the path. Both programs take free ports on the loopback address, so the script runs beside other
# servers. It ends with the status 0 when every step passed. Otherwise it says which step failed, prints the
# logs of the programs, and ends with the status 1.
set -euo pipefail

usage="usage: end-to-end-check.sh <server-distribution.tar.gz> <ui-distribution.tar.gz>"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ARCHIVE="${1:?$usage}"
UI_ARCHIVE="${2:?$usage}"
DOCUMENT="$ROOT/mockey-legacy/src/test/resources/legacy/sample.xml"

# How long the script waits for a program to start or to end, and for an answer, in seconds.
PATIENCE=60

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-end-to-end.XXXXXX")"
JAR="$WORK/cookies"
server_pid=""
ui_pid=""

# Stops what still runs and removes what the script wrote.
clean_up() {
  for pid in "$ui_pid" "$server_pid"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -rf "$WORK"
}
trap clean_up EXIT

# Says what failed, shows the logs of the programs, and ends the script.
fail() {
  echo "The end-to-end check failed: $*" >&2
  for log in "$WORK/server.log" "$WORK/ui.log"; do
    if [ -f "$log" ]; then
      echo "--- The last lines of $(basename "$log"):" >&2
      tail -n 40 "$log" >&2
    fi
  done
  exit 1
}

# Unpacks the distribution $1 into the directory $2 and prints the path of its start script $3.
unpack() {
  mkdir -p "$2"
  tar -xzf "$1" -C "$2" || fail "$1 cannot be unpacked."
  local script
  script="$(find "$2" -type f -path "*/bin/$3" | head -n 1)"
  [ -n "$script" ] || fail "$1 holds no start script bin/$3."
  [ -x "$script" ] || fail "The start script $script of $1 is not executable."
  printf '%s\n' "$script"
}

# Waits until the log $1 holds a line that matches the pattern $2, while the program $3, which is $4, runs.
wait_for_line() {
  local waited=0
  until grep -Eq "$2" "$1" 2>/dev/null; do
    kill -0 "$3" 2>/dev/null || fail "$4 ended before it was ready."
    [ "$waited" -lt $((PATIENCE * 5)) ] || fail "$4 was not ready after $PATIENCE seconds."
    sleep 0.2
    waited=$((waited + 1))
  done
}

# Sends a request with curl and the arguments after $2, writes the body of the answer to the file $1, and
# expects the status $2.
request() {
  local body="$1" expected="$2" status
  shift 2
  status="$(curl -sS --max-time "$PATIENCE" -o "$body" -w '%{http_code}' "$@")" || fail "No answer to: curl $*"
  [ "$status" = "$expected" ] || fail "The status is $status and not $expected for: curl $*"
}

# Expects the file $1 to hold the text $2. $3 says what the file is.
expect_text() {
  grep -Fq -- "$2" "$1" || fail "$3 does not hold: $2"
}

# Tells the program $1, which is $2, to stop, and expects it to end with the status 0.
stop() {
  local status=0 waited=0
  kill -TERM "$1" 2>/dev/null || fail "$2 does not run anymore."
  while kill -0 "$1" 2>/dev/null; do
    [ "$waited" -lt $((PATIENCE * 5)) ] || fail "$2 did not end within $PATIENCE seconds after it was told to stop."
    sleep 0.2
    waited=$((waited + 1))
  done
  wait "$1" || status=$?
  [ "$status" -eq 0 ] || fail "$2 ended with the status $status and not 0."
}

for tool in tar curl; do
  command -v "$tool" >/dev/null || fail "The check needs $tool, which is not on the path."
done
for file in "$SERVER_ARCHIVE" "$UI_ARCHIVE" "$DOCUMENT"; do
  [ -f "$file" ] || fail "$file is no file."
done

# No setting of the programs comes from the environment of the caller.
for variable in $(env | sed -n 's/^\(MOCKEY_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$variable"
done

server_script="$(unpack "$SERVER_ARCHIVE" "$WORK/server" mockey-server)"
ui_script="$(unpack "$UI_ARCHIVE" "$WORK/ui" mockey-ui)"

# The server, in the directory of a person who starts it for the first time.
mkdir "$WORK/home"
cd "$WORK/home"
"$server_script" --mock-host 127.0.0.1 --mock-port 0 --admin-port 0 >"$WORK/server.log" 2>&1 &
server_pid=$!
wait_for_line "$WORK/server.log" 'The admin listener accepts connections on ' "$server_pid" "The server"
mock_address="$(sed -n 's/.*The mock listener accepts connections on \([0-9.]*:[0-9]*\)\..*/\1/p' "$WORK/server.log")"
admin_address="$(sed -n 's/.*The admin listener accepts connections on \([0-9.]*:[0-9]*\)\..*/\1/p' "$WORK/server.log")"
[ -n "$mock_address" ] && [ -n "$admin_address" ] || fail "The log of the server does not name its listeners."
MOCK="http://$mock_address"
echo "The server runs: its mock listener is at $MOCK, and its admin listener at http://$admin_address."

# The UI for that server.
"$ui_script" --port 0 --admin-url "http://$admin_address" >"$WORK/ui.log" 2>&1 &
ui_pid=$!
wait_for_line "$WORK/ui.log" 'The UI accepts connections at ' "$ui_pid" "The UI"
UI="$(sed -n 's/.*The UI accepts connections at \(http:\/\/[0-9.]*:[0-9]*\)\..*/\1/p' "$WORK/ui.log")"
[ -n "$UI" ] || fail "The log of the UI does not name its address."
echo "The UI runs at $UI."

# 1. The import, as a browser does it: the page with the form, and then the form with its token and the file.
request "$WORK/import-page.html" 200 -c "$JAR" -b "$JAR" "$UI/definitions"
token="$(sed -n 's/.*name="_token" value="\([^"]*\)".*/\1/p' "$WORK/import-page.html" | head -n 1)"
[ -n "$token" ] || fail "The import page of the UI has no form with a token."
request "$WORK/report.html" 200 -L -c "$JAR" -b "$JAR" \
  -F "_token=$token" -F "document=@$DOCUMENT" -F "tags=" "$UI/definitions/import"
expect_text "$WORK/report.html" "<h1>Import report</h1>" "The page after the import"
expect_text "$WORK/report.html" "The service 'Customer Directory' was added with 5 scenarios." "The import report"
echo "The UI imported $(basename "$DOCUMENT"): $(grep -o 'The server read[^<]*' "$WORK/report.html" | head -n 1)"

# 2. A mock URL of the document, which the scenario '123 Customer GET' answers.
request "$WORK/mock-response" 200 "$MOCK/service/customer/123"
expect_text "$WORK/mock-response" '"firstName": "Jordan"' "The response of the mock URL"
expect_text "$WORK/mock-response" '"type": "GET"' "The response of the mock URL"
echo "The mock URL $MOCK/service/customer/123 answered with the scenario of the document."

# 3. The request in the history of the UI: the list has one entry, and the page of the entry shows the response.
request "$WORK/history.html" 200 -b "$JAR" "$UI/history"
entries="$(grep -o 'href="/history/entry?id=[0-9]*"' "$WORK/history.html" | sort -u | sed 's/^href="//; s/"$//' || true)"
[ "$(printf '%s\n' "$entries" | grep -c .)" -eq 1 ] || fail "The history of the UI does not list exactly one entry."
expect_text "$WORK/history.html" "customer/123" "The history of the UI"
expect_text "$WORK/history.html" "Scenario served: 123 Customer GET" "The history of the UI"
request "$WORK/entry.html" 200 -b "$JAR" "$UI$entries"
expect_text "$WORK/entry.html" "$MOCK/service/customer/123" "The page of the history entry"
expect_text "$WORK/entry.html" "&quot;firstName&quot;: &quot;Jordan&quot;" "The page of the history entry"
echo "The UI lists the request in its history, and the page of the entry shows the response."

# 4. Both programs stop in order: the UI first, which then no longer needs its server.
stop "$ui_pid" "The UI"
ui_pid=""
stop "$server_pid" "The server"
server_pid=""
echo "The UI and the server stopped in order."
echo "The end-to-end check passed."
