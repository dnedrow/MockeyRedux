#!/usr/bin/env bash
# Follows the quick start of the README inside a container. scripts/quick-start-check.sh puts this script
# into the container with what it needs, and says what the check is for.
#
# The directory of this script holds:
#
#   steps/NN.sh        the blocks of commands of the quick start, in their order
#   steps/NN.program   a marker: the block NN starts a program that keeps running
#   steps/NN.expected  what the commands of the block NN print
#   pages              the page that the quick start tells its reader to open in a browser
#   home/              the directory of the reader, with the two runnable jars
#
# The environment says what the reader sees in the UI when the quick start is done: the page lists the
# service SERVICE_IN_UI, and its link LINK_IN_UI leads to a page with the text ANSWER_IN_UI.
#
# The script ends with the status 0 when the quick start works as it is written. Otherwise it says which
# step failed, prints what the programs wrote, and ends with the status 1. It ends with the status 2 when
# the image is not one that the check is for.
set -euo pipefail

# Every block is a job of its own, as a command is in a terminal of its own. Ctrl+C in a terminal is the
# interrupt signal for the process group of what runs there, and a job has a process group of its own.
set -m

HERE="$(cd "$(dirname "$0")" && pwd)"
STEPS="$HERE/steps"
: "${SERVICE_IN_UI:?}" "${LINK_IN_UI:?}" "${ANSWER_IN_UI:?}"

# How long the script waits for a program to start or to end, and for an answer, in seconds.
PATIENCE=60

OUT="$(mktemp -d)"
# The programs that run, in the order of their start: the number of the block that started each, and the
# process of that block.
program_steps=()
program_pids=()

# Says what failed, shows what the programs wrote, and ends the script.
fail() {
  local log pid
  echo "The quick start failed: $*" >&2
  for log in "$OUT"/*.log; do
    [ -f "$log" ] || continue
    echo "--- The last lines that the program of the block $(basename "$log" .log) wrote:" >&2
    tail -n 40 "$log" >&2
  done
  for pid in ${program_pids[@]+"${program_pids[@]}"}; do
    kill -KILL -- "-$pid" 2>/dev/null || true
  done
  exit 1
}

# Says why the image is not one that the check is for, and ends the script.
unfit() {
  echo "The image cannot be used: $*" >&2
  exit 2
}

# Prints the first line of the block $1, and says so when the block has more lines.
commands_of() {
  local first
  first="$(head -n 1 "$1" | sed 's/ *\\$//')"
  if [ "$(grep -c '' "$1")" -gt 1 ]; then
    printf '%s ...\n' "$first"
  else
    printf '%s\n' "$first"
  fi
}

# Starts the program of the block $1 with the number $2, and waits until it says where it accepts connections.
start_program() {
  local log="$OUT/$2.log" pid waited=0
  bash "$1" >"$log" 2>&1 &
  pid=$!
  program_steps+=("$2")
  program_pids+=("$pid")
  until grep -q 'accepts connections' "$log" 2>/dev/null; do
    kill -0 "$pid" 2>/dev/null || fail "The program of the block $2 ended before it was ready."
    [ "$waited" -lt $((PATIENCE * 5)) ] || fail "The program of the block $2 was not ready after $PATIENCE seconds."
    sleep 0.2
    waited=$((waited + 1))
  done
  echo "Block $2 started a program, which says where it accepts connections: $(commands_of "$1")"
}

# Runs the commands of the block $1 with the number $2, and compares what they print with the README.
run_commands() {
  local out="$OUT/$2.out" errors="$OUT/$2.err" expected="$STEPS/$2.expected" status=0
  timeout "$PATIENCE" bash "$1" >"$out" 2>"$errors" || status=$?
  if [ "$status" -ne 0 ]; then
    cat "$errors" >&2
    fail "The block $2 ended with the status $status and not 0: $(commands_of "$1")"
  fi
  if [ -f "$expected" ]; then
    if [ "$(cat "$out")" != "$(cat "$expected")" ]; then
      echo "--- The block $2 printed:" >&2
      cat "$out" >&2
      echo "--- The README says that it prints:" >&2
      cat "$expected" >&2
      fail "The block $2 does not print what the README says: $(commands_of "$1")"
    fi
    echo "Block $2 printed what the README says: $(commands_of "$1")"
  else
    echo "Block $2 ran: $(commands_of "$1")"
  fi
}

# Opens the page $1 as a browser does, writes it to the file $2, and expects the status 200.
open_page() {
  local status
  status="$(curl -sS -L --max-time "$PATIENCE" -o "$2" -w '%{http_code}' "$1")" || fail "The page $1 does not answer."
  [ "$status" = 200 ] || fail "The page $1 answers with the status $status and not 200."
}

# Presses Ctrl+C in the terminal of the program with the process $1, which the block $2 started, and expects
# the program to end with the status 0.
interrupt() {
  local status=0 waited=0
  kill -INT -- "-$1" 2>/dev/null || fail "The program of the block $2 does not run anymore."
  while kill -0 "$1" 2>/dev/null; do
    [ "$waited" -lt $((PATIENCE * 5)) ] || fail "The program of the block $2 did not end within $PATIENCE seconds after Ctrl+C."
    sleep 0.2
    waited=$((waited + 1))
  done
  wait "$1" || status=$?
  [ "$status" -eq 0 ] || fail "The program of the block $2 ended with the status $status and not 0 after Ctrl+C."
}

# The machine of the reader: a Java 21 runtime, no JDK, and the tool that the commands send requests with.
java_version="$(java -version 2>&1 | head -n 1)" || unfit "It has no Java runtime on its path."
case "$java_version" in
  *'"21"'* | *'"21.'*) ;;
  *) unfit "Its Java runtime is not of the version 21: $java_version" ;;
esac
if command -v javac >/dev/null; then
  unfit "It has a JDK, and the check is for an image that has a Java runtime alone."
fi
command -v curl >/dev/null || unfit "It has no curl, which the commands of the quick start send requests with."
echo "The container has a Java runtime and no JDK: $java_version"

# The blocks of the quick start, each as it stands, in the directory with the two jars.
cd "$HERE/home"
for step in "$STEPS"/*.sh; do
  number="$(basename "$step" .sh)"
  if [ -f "$STEPS/$number.program" ]; then
    start_program "$step" "$number"
  else
    run_commands "$step" "$number"
  fi
done
[ "${#program_pids[@]}" -gt 0 ] || fail "No block of the quick start started a program."

# The page that the reader opens in a browser, and the page behind the link that the README names.
page="$(head -n 1 "$HERE/pages")"
open_page "$page" "$OUT/page.html"
grep -Fq -- "$SERVICE_IN_UI" "$OUT/page.html" || fail "The page $page does not list the service $SERVICE_IN_UI."
link="$(grep -o "<a href=\"[^\"]*\"[^>]*>$LINK_IN_UI</a>" "$OUT/page.html" | head -n 1 | sed 's/^<a href="\([^"]*\)".*/\1/')" ||
  fail "The page $page has no link with the text $LINK_IN_UI."
origin="$(printf '%s\n' "$page" | sed 's#^\([a-z]*://[^/]*\).*#\1#')"
open_page "$origin$link" "$OUT/linked.html"
grep -Fq -- "$ANSWER_IN_UI" "$OUT/linked.html" || fail "The page $origin$link does not show: $ANSWER_IN_UI"
echo "The page $page lists the service $SERVICE_IN_UI, and its page $LINK_IN_UI shows: $ANSWER_IN_UI"

# Ctrl+C in every terminal, the last program first.
programs="${#program_pids[@]}"
for ((index = programs - 1; index >= 0; index--)); do
  interrupt "${program_pids[index]}" "${program_steps[index]}"
done
program_pids=()
echo "Ctrl+C stopped the $programs programs, and each ended with the status 0."
echo "The quick start works as it is written."
