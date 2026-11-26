#!/usr/bin/env bash
# Sets the version of the project: in the build, and in the documents that name the files of that version.
#
# Usage: scripts/set-version.sh <version>
#
# A version is a text such as 3.0.0, 3.0.0-rc.1, or 3.1.0-SNAPSHOT. The script puts it in the place of the
# version that gradle.properties has now, in these files:
#
#   gradle.properties                        the version of the build
#   README.md, docs/server.md, docs/ui.md,   the files of the two programs, and what the programs say of
#   docs/admin-api.md                        their version
#   docs/library.md,                         the coordinates of the library
#   samples/embedded/build.gradle.kts
#   docs/release-notes/<release>.md          the release notes of the release that the version leads to
#
# The release of a version is the version without what follows a hyphen: 3.0.0-rc.1 and 3.0.0-SNAPSHOT lead
# to the release 3.0.0. The release notes change only while the new version leads to the same release as the
# one before it, so the notes of an earlier release stay as they are.
#
# The script changes version numbers and no sentence. After it, read what the README and the release notes
# say of the state of the project, and run ./gradlew check, which holds the documents against the version.
set -euo pipefail

usage="usage: set-version.sh <version>"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEW="${1:?$usage}"

# Says what is wrong and ends the script.
fail() {
  echo "The version is not set: $*" >&2
  exit 1
}

# Prints the release that the version $1 leads to.
release_of() {
  printf '%s\n' "${1%%-*}"
}

# Writes the file $1 to the file $2 with the version $NEW wherever $1 has the version $CURRENT, and prints
# how often that is. A longer version that begins or ends with the text of $CURRENT is another version:
# 3.0.0 is no part of 13.0.0, of 3.0.01, or of 3.0.0-rc.1. What follows the version in the name of a
# runnable jar is no part of a version.
with_new_version() {
  awk -v current="$CURRENT" -v new="$NEW" -v out="$2" '
    {
      line = $0
      changed_line = ""
      while ((at = index(line, current)) > 0) {
        before = (at > 1) ? substr(line, at - 1, 1) : ""
        after = substr(line, at + length(current))
        if (before ~ /[0-9.]/ || after ~ /^[0-9]/ || (after ~ /^-[0-9A-Za-z]/ && after !~ /^-runnable/)) {
          changed_line = changed_line substr(line, 1, at + length(current) - 1)
        } else {
          changed_line = changed_line substr(line, 1, at - 1) new
          count++
        }
        line = after
      }
      print changed_line line > out
    }
    END { print count + 0 }
  ' "$1"
}

printf '%s\n' "$NEW" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$' ||
  fail "'$NEW' is no version such as 3.0.0, 3.0.0-rc.1, or 3.1.0-SNAPSHOT."
CURRENT="$(sed -n 's/^version=//p' "$ROOT/gradle.properties")"
[ -n "$CURRENT" ] || fail "gradle.properties states no version."
[ "$CURRENT" != "$NEW" ] || fail "It is $NEW already."

files="gradle.properties README.md docs/server.md docs/ui.md docs/admin-api.md docs/library.md"
files="$files samples/embedded/build.gradle.kts"
notes="docs/release-notes/$(release_of "$NEW").md"
if [ "$(release_of "$CURRENT")" = "$(release_of "$NEW")" ] && [ -f "$ROOT/$notes" ]; then
  files="$files $notes"
fi

# Every file is read before one is written, so that a file which does not name the version changes nothing.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mockey-version.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
number=0
for file in $files; do
  [ -f "$ROOT/$file" ] || fail "$file is no file."
  number=$((number + 1))
  count="$(with_new_version "$ROOT/$file" "$WORK/$number")"
  [ "$count" -gt 0 ] || fail "$file does not name the version $CURRENT."
  echo "$file: $count"
done
number=0
for file in $files; do
  number=$((number + 1))
  cat "$WORK/$number" >"$ROOT/$file"
done
echo "The version is $NEW, and it was $CURRENT. The numbers say how often each file named it."
echo "Read what README.md and the release notes say of the state of the project, and run ./gradlew check."
