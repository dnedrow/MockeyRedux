#!/usr/bin/env bash
# The release check: the files that the release notes list are there, and each program starts from each of
# its files.
#
# Usage: scripts/release-check.sh <directory>
#
# The directory holds the files of a release, as scripts/assemble-release.sh collects them or as they are
# downloaded from a published release, and the release notes as RELEASE-NOTES.md. The script checks this:
#
#   1. The release notes list the files of the release in the table of their section "The files": for the
#      server and for the UI a runnable jar, a zip distribution, and a tar distribution of one version, and
#      the checksums. Each of them is in the directory, and the directory holds no other file.
#   2. The release notes include the deviation register: every entry of docs/deviations.md, as it is there.
#   3. The checksums in SHA256SUMS are those of the files.
#   4. Each distribution unpacks, and it has its start scripts for Unix and for Windows. Each program says
#      from each of its files that it is of the version of the release. It starts, answers a request, and
#      stops with the status 0.
#
# The script needs unzip, tar, curl, sha256sum or shasum, and a Java 21 runtime, which it looks for in
# JAVA_HOME and then on the path, as the start scripts do. The programs take free ports on the loopback
# address. The script ends with the status 0 when every step passed. Otherwise it says which step failed,
# prints the logs of the programs, and ends with the status 1.
set -euo pipefail

usage="usage: release-check.sh <directory>"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIRECTORY="${1:?$usage}"
NOTES="$DIRECTORY/RELEASE-NOTES.md"
REGISTER="$ROOT/docs/deviations.md"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# How long the script waits for a program to start or to end, and for an answer, in seconds.
PATIENCE=60

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-release-check.XXXXXX")"
running=""

# Stops what still runs and removes what the script wrote.
clean_up() {
  local pid
  for pid in $running; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -rf "$WORK"
}
trap clean_up EXIT

# Says what failed, shows the logs of the programs, and ends the script.
fail() {
  local log
  echo "The release check failed: $*" >&2
  for log in "$WORK"/*.log; do
    [ -f "$log" ] || continue
    echo "--- The last lines of $(basename "$log"):" >&2
    tail -n 30 "$log" >&2
  done
  exit 1
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

# Unpacks the distribution $1 of the program $2 into a directory of its own, expects its start scripts
# there, and prints the path of the start script for Unix.
unpack() {
  local into script
  into="$WORK/$(basename "$1")-unpacked"
  mkdir "$into"
  case "$1" in
    *.zip) unzip -q "$1" -d "$into" || fail "$1 cannot be unpacked." ;;
    *) tar -xzf "$1" -C "$into" || fail "$1 cannot be unpacked." ;;
  esac
  script="$(find "$into" -type f -path "*/bin/$2" | head -n 1)"
  [ -n "$script" ] || fail "$(basename "$1") holds no start script bin/$2."
  [ -x "$script" ] || fail "The start script bin/$2 of $(basename "$1") is not executable."
  [ -f "$script.bat" ] || fail "$(basename "$1") holds no start script bin/$2.bat for Windows."
  printf '%s\n' "$script"
}

# Expects the command after $2, which runs the program $1 from what $2 names, to say that the program is of
# the version of the release.
check_version() {
  local program="$1" from="$2" said
  shift 2
  said="$("$@" --version 2>&1)" || fail "$program does not run from $from: $said"
  [ "$said" = "$program version $VERSION" ] ||
    fail "$program says from $from that it is '$said', and not '$program version $VERSION'."
}

# Starts a server with the command after $1, which names what the server is started from. The server runs
# when the function returns: its process is $server_pid, and $MOCK and $ADMIN are the URLs of its listeners.
start_server() {
  local from="$1" log mock admin
  shift
  starts=$((starts + 1))
  log="$WORK/$starts-server.log"
  "$@" --mock-host 127.0.0.1 --mock-port 0 --admin-port 0 >"$log" 2>&1 &
  server_pid=$!
  running="$running $server_pid"
  wait_for_line "$log" 'The admin listener accepts connections on ' "$server_pid" "The server from $from"
  mock="$(sed -n 's/.*The mock listener accepts connections on \([0-9.]*:[0-9]*\)\..*/\1/p' "$log")"
  admin="$(sed -n 's/.*The admin listener accepts connections on \([0-9.]*:[0-9]*\)\..*/\1/p' "$log")"
  [ -n "$mock" ] && [ -n "$admin" ] || fail "The log of the server from $from does not name its listeners."
  MOCK="http://$mock"
  ADMIN="http://$admin"
}

# Checks the server from what $1 names: the command after $1 says its version, and a server that it starts
# answers at both listeners and stops in order.
check_server() {
  local from="$1"
  shift
  check_version mockey-server "$from" "$@"
  start_server "$from" "$@"
  request "$WORK/status.json" 200 "$MOCK/status"
  expect_text "$WORK/status.json" '"status":"running"' "The status of the server from $from"
  request "$WORK/system.json" 200 "$ADMIN/api/v1/system"
  expect_text "$WORK/system.json" "\"version\":\"$VERSION\"" "What the admin API of the server from $from says of the server"
  stop "$server_pid" "The server from $from"
  echo "The server starts from $from: it is of the version $VERSION, answers, and stops in order."
}

# Checks the UI from what $1 names: the command after $1 says its version, and a UI that it starts shows
# the server at $ADMIN and stops in order.
check_ui() {
  local from="$1" log ui_pid ui
  shift
  check_version mockey-ui "$from" "$@"
  starts=$((starts + 1))
  log="$WORK/$starts-ui.log"
  "$@" --port 0 --admin-url "$ADMIN" >"$log" 2>&1 &
  ui_pid=$!
  running="$running $ui_pid"
  wait_for_line "$log" 'The UI accepts connections at ' "$ui_pid" "The UI from $from"
  ui="$(sed -n 's/.*The UI accepts connections at \(http:\/\/[0-9.]*:[0-9]*\)\..*/\1/p' "$log")"
  [ -n "$ui" ] || fail "The log of the UI from $from does not name its address."
  request "$WORK/page.html" 200 -L "$ui/"
  expect_text "$WORK/page.html" "Mockey UI $VERSION" "The page of the UI from $from"
  expect_text "$WORK/page.html" "version $VERSION, in read-only mode" "The page of the UI from $from"
  stop "$ui_pid" "The UI from $from"
  echo "The UI starts from $from: it is of the version $VERSION, shows its server, and stops in order."
}

for tool in unzip tar curl; do
  command -v "$tool" >/dev/null || fail "The check needs $tool, which is not on the path."
done
if command -v sha256sum >/dev/null; then
  checksums="sha256sum"
elif command -v shasum >/dev/null; then
  checksums="shasum -a 256"
else
  fail "The check needs sha256sum or shasum, and neither is on the path."
fi
[ -d "$DIRECTORY" ] || fail "$DIRECTORY is no directory."
[ -f "$NOTES" ] || fail "$DIRECTORY holds no release notes RELEASE-NOTES.md."
[ -f "$REGISTER" ] || fail "$REGISTER is no file."

# No setting of the programs comes from the environment of the caller.
for variable in $(env | sed -n 's/^\(MOCKEY_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$variable"
done

# 1. The files that the release notes list, and the files that are there.
# shellcheck disable=SC2016 # The backticks are those of the table, and nothing is to expand.
listed="$(sed -n '/^## The files$/,/^## /p' "$NOTES" | sed -n 's/^| `\([^`]*\)` |.*/\1/p')"
[ -n "$listed" ] || fail "The release notes list no file in a table of a section \"The files\"."
VERSION="$(printf '%s\n' "$listed" | sed -n 's/^mockey-server-\(.*\)-runnable\.jar$/\1/p' | head -n 1)"
[ -n "$VERSION" ] || fail "The release notes list no runnable jar of the server."
expected="$(
  for program in server ui; do
    printf '%s\n' "mockey-$program-$VERSION-runnable.jar" "mockey-$program-$VERSION.zip" "mockey-$program-$VERSION.tar.gz"
  done
  echo SHA256SUMS
)"
[ "$(printf '%s\n' "$listed" | sort)" = "$(printf '%s\n' "$expected" | sort)" ] ||
  fail "The release notes do not list the files of a release of the version $VERSION, each of them once. They list: $(printf '%s\n' "$listed" | tr '\n' ' ')"
printf '%s\n' "$listed" | while IFS= read -r name; do
  [ -f "$DIRECTORY/$name" ] || fail "The release notes list $name, and the directory does not hold it."
done
for file in "$DIRECTORY"/* "$DIRECTORY"/.[!.]*; do
  [ -e "$file" ] || continue
  name="$(basename "$file")"
  [ "$name" = RELEASE-NOTES.md ] || printf '%s\n' "$listed" | grep -Fxq -- "$name" ||
    fail "The directory holds $name, which the release notes do not list."
done
echo "The release notes list the $(printf '%s\n' "$listed" | grep -c .) files of the version $VERSION, and the directory holds them and no other."

# 2. The deviation register in the release notes.
entries="$(grep -c '^| DEV-' "$REGISTER" || true)"
[ "$entries" -gt 0 ] || fail "docs/deviations.md has no entry."
missing="$(grep '^| DEV-' "$REGISTER" | grep -Fxv -f "$NOTES" | sed 's/^| \(DEV-[0-9]*\) .*/\1/' || true)"
[ -z "$missing" ] ||
  fail "The release notes do not have these entries of the register as docs/deviations.md has them: $(printf '%s\n' "$missing" | tr '\n' ' ')"
[ "$(grep -c '^| DEV-' "$NOTES")" -eq "$entries" ] || fail "The release notes have entries that docs/deviations.md does not have."
echo "The release notes include the $entries entries of the deviation register."

# 3. The checksums.
artifacts="$(printf '%s\n' "$listed" | grep -v '^SHA256SUMS$' | sort)"
[ "$(awk '{ print $2 }' "$DIRECTORY/SHA256SUMS" | sort)" = "$artifacts" ] ||
  fail "SHA256SUMS does not have one checksum for each of the other files."
(cd "$DIRECTORY" && $checksums -c SHA256SUMS >"$WORK/checksums.txt" 2>&1) ||
  fail "SHA256SUMS does not state the checksum of: $(sed -n 's/: FAILED.*//p' "$WORK/checksums.txt" | tr '\n' ' ')"
echo "The checksums of SHA256SUMS are those of the files."

# 4. Each program from each of its files.
"$JAVA" -version >/dev/null 2>&1 || fail "No Java runtime runs as $JAVA."
starts=0
server_jar="$DIRECTORY/mockey-server-$VERSION-runnable.jar"
ui_jar="$DIRECTORY/mockey-ui-$VERSION-runnable.jar"
server_of_zip="$(unpack "$DIRECTORY/mockey-server-$VERSION.zip" mockey-server)"
server_of_tar="$(unpack "$DIRECTORY/mockey-server-$VERSION.tar.gz" mockey-server)"
ui_of_zip="$(unpack "$DIRECTORY/mockey-ui-$VERSION.zip" mockey-ui)"
ui_of_tar="$(unpack "$DIRECTORY/mockey-ui-$VERSION.tar.gz" mockey-ui)"
check_server "its runnable jar" "$JAVA" -jar "$server_jar"
check_server "its zip distribution" "$server_of_zip"
check_server "its tar distribution" "$server_of_tar"
# A UI shows the pages of a server, so one server runs for the three UIs.
start_server "its runnable jar" "$JAVA" -jar "$server_jar"
check_ui "its runnable jar" "$JAVA" -jar "$ui_jar"
check_ui "its zip distribution" "$ui_of_zip"
check_ui "its tar distribution" "$ui_of_tar"
stop "$server_pid" "The server for the UIs"
echo "The release check passed."
