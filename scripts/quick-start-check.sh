#!/usr/bin/env bash
# The quick-start check: the quick start of the README works as it is written, on a machine that has a Java 21
# runtime and nothing of Mockey but the two runnable jars.
#
# The script takes the commands from the section "Quick start" of README.md, and follows them in a container
# of an image that holds a Java 21 runtime and no JDK:
#
#   1. Every block that is marked `shell` runs as it stands, in the directory that holds the two runnable
#      jars. Each block runs in a shell of its own, which stands for a terminal of the reader.
#   2. A block that starts a program leaves the program running. The script goes on when the output of the
#      program says where it accepts connections, which is when the README calls a program ready.
#   3. Every other block has to end with the status 0. Where a block that is marked `text` follows it
#      directly, that is what the commands print.
#   4. The page that the README tells its reader to open in a browser shows what the README says it shows.
#   5. Ctrl+C stops every program, and each ends with the status 0.
#
# The container gets the two jars in the place of the download, and it has no network. So the check shows
# that the quick start needs nothing that it does not name.
#
# Usage: scripts/quick-start-check.sh <server-runnable.jar> <ui-runnable.jar> [image]
#
# The image is docker.io/library/eclipse-temurin:21-jre unless the third argument names another one. The
# image has to hold bash, which runs the blocks, and curl, which the commands of the quick start use.
#
# The script needs a container tool. It takes the tool that the environment variable CONTAINER_TOOL names,
# or else docker, or else podman. It ends with the status 0 when every step passed. Otherwise it says which
# step failed, and it ends with the status 1.
set -euo pipefail

usage="usage: quick-start-check.sh <server-runnable.jar> <ui-runnable.jar> [image]"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_JAR="${1:?$usage}"
UI_JAR="${2:?$usage}"
IMAGE="${3:-docker.io/library/eclipse-temurin:21-jre}"
README="$ROOT/README.md"
FOLLOWER="$ROOT/scripts/quick-start-follower.sh"
SECTION="Quick start"

# What the README says of the UI when the quick start is done: the page that the reader opens lists this
# service, and this link of the page leads to a page with this text, which is the answer to the mock request.
SERVICE_IN_UI="Greeting"
LINK_IN_UI="History"
ANSWER_IN_UI="Scenario served: Hello"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-quick-start.XXXXXX")"
STEPS="$WORK/steps"
TOOL="${CONTAINER_TOOL:-}"
container=""

# Removes the container and what the script wrote.
clean_up() {
  if [ -n "$container" ]; then
    "$TOOL" rm -f "$container" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap clean_up EXIT

# Says what failed and ends the script.
fail() {
  echo "The quick-start check failed: $*" >&2
  exit 1
}

# Writes the section $2 of the Markdown document $1 into the directory $3: the block of commands with the
# number NN as NN.sh, what the block prints as NN.expected, and the pages that the section tells its reader
# to open in a browser as the lines of the file `pages`. Such a page is a URL in angle brackets.
read_section() {
  awk -v section="## $2" -v dir="$3" '
    fence == "" && /^```/ {
      fence = $0
      if (!inside) next
      if ($0 == "```shell") {
        count++
        file = sprintf("%s/%02d.sh", dir, count)
        printf "" > file
      } else if ($0 == "```text" && after_commands && blank_lines == 1) {
        file = sprintf("%s/%02d.expected", dir, count)
        printf "" > file
      } else {
        file = ""
      }
      after_commands = 0
      next
    }
    fence != "" && $0 == "```" {
      if (inside && fence == "```shell") { after_commands = 1; blank_lines = 0 }
      fence = ""
      next
    }
    fence != "" {
      if (inside && file != "") print > file
      next
    }
    /^## / {
      inside = ($0 == section)
      if (inside) found = 1
      after_commands = 0
      next
    }
    !inside { next }
    $0 == "" { blank_lines++; next }
    {
      after_commands = 0
      line = $0
      while (match(line, /<https?:\/\/[^>]+>/)) {
        print substr(line, RSTART + 1, RLENGTH - 2) > (dir "/pages")
        line = substr(line, RSTART + RLENGTH)
      }
    }
    END { if (!found) exit 3 }
  ' "$1"
}

# Prints the last command line of the block $1.
last_command() {
  grep -v '^[[:space:]]*$' "$1" | tail -n 1
}

# Prints the block that starts a program from the jar with the name $1, or nothing when there is none.
program_step() {
  local marker
  for marker in "$STEPS"/*.program; do
    [ -f "$marker" ] || continue
    case "$(last_command "${marker%.program}.sh")" in
      "java -jar $1" | "java -jar $1 "*) printf '%s\n' "${marker%.program}.sh" ;;
    esac
  done | head -n 1
}

for file in "$SERVER_JAR" "$UI_JAR" "$README" "$FOLLOWER"; do
  [ -f "$file" ] || fail "$file is no file."
done

# The quick start: its blocks, and what the task of this check asks of it.
mkdir "$STEPS" "$WORK/home"
read_section "$README" "$SECTION" "$STEPS" || fail "README.md has no section \"$SECTION\"."
[ -f "$STEPS/01.sh" ] || fail "The section \"$SECTION\" of README.md has no block of commands."
for step in "$STEPS"/*.sh; do
  # A block whose last command runs a jar starts a program that keeps running, unless the command only asks
  # the program for its version or its help.
  case "$(last_command "$step")" in
    "java -jar "*" --version"* | "java -jar "*" --help"*) ;;
    "java -jar "*) : >"${step%.sh}.program" ;;
  esac
done
for jar in "$SERVER_JAR" "$UI_JAR"; do
  name="$(basename "$jar")"
  [ -n "$(program_step "$name")" ] ||
    fail "No block of the quick start starts the program with 'java -jar $name', which is the jar of this build."
  cp "$jar" "$WORK/home/$name"
done
mock_request=""
for expected in "$STEPS"/*.expected; do
  [ -f "$expected" ] || continue
  if grep -q '/service/' "${expected%.expected}.sh"; then
    mock_request="${expected%.expected}.sh"
  fi
done
[ -n "$mock_request" ] || fail "The quick start has no request to a mock URL with the answer that it prints."
[ -f "$STEPS/pages" ] || fail "The quick start names no page to open in a browser."
[ "$(grep -c . "$STEPS/pages")" -eq 1 ] || fail "The quick start names more than one page to open in a browser."
mv "$STEPS/pages" "$WORK/pages"
cp "$FOLLOWER" "$WORK/follow.sh"
echo "The quick start of README.md has $(find "$STEPS" -name '*.sh' | grep -c .) blocks of commands."

# The container: an image with a Java runtime, the files of this script, and no network.
if [ -z "$TOOL" ]; then
  for candidate in docker podman; do
    if command -v "$candidate" >/dev/null; then
      TOOL="$candidate"
      break
    fi
  done
fi
[ -n "$TOOL" ] || fail "The check needs docker or podman, and neither is on the path."
command -v "$TOOL" >/dev/null || fail "The container tool $TOOL is not on the path."
"$TOOL" info >/dev/null 2>&1 || fail "$TOOL reaches no container engine. Start one and run the check again."
if ! "$TOOL" image inspect "$IMAGE" >/dev/null 2>&1; then
  "$TOOL" pull "$IMAGE" >&2 || fail "$TOOL cannot get the image $IMAGE."
fi
"$TOOL" run --rm --network none "$IMAGE" bash -c true >/dev/null 2>&1 ||
  fail "The image $IMAGE has no bash, which runs the blocks of the quick start."
container="$(
  "$TOOL" create --network none --workdir /quick-start/home \
    --env "SERVICE_IN_UI=$SERVICE_IN_UI" --env "LINK_IN_UI=$LINK_IN_UI" --env "ANSWER_IN_UI=$ANSWER_IN_UI" \
    "$IMAGE" bash /quick-start/follow.sh
)" || fail "$TOOL cannot create a container of the image $IMAGE."
# The user of the container is not the user of this script, and it has to read what the script wrote.
chmod -R a+rX "$WORK"
"$TOOL" cp "$WORK/." "$container:/quick-start" >/dev/null || fail "$TOOL cannot copy the files into the container."
echo "A container of the image $IMAGE follows the quick start."

status=0
"$TOOL" start --attach "$container" || status=$?
case "$status" in
  0) ;;
  2) fail "The check is not for the image $IMAGE, see above." ;;
  *) fail "The quick start does not work as README.md has it, see above." ;;
esac
echo "The quick-start check passed."
