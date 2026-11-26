#!/usr/bin/env bash
# Collects the files of a release in one directory: the distributions and the runnable jars of the two
# programs, the release notes, and the checksums of the files.
#
# Usage: scripts/assemble-release.sh <directory> <release-notes.md> <file>...
#
#   <directory>         where the release goes. It holds nothing, or the files of an earlier run.
#   <release-notes.md>  the release notes of the release, as the repository has them
#   <file>...           the distributions and the runnable jars
#
# The script copies the files into the directory, and it writes two more files there:
#
#   RELEASE-NOTES.md  The release notes with the deviation register. The notes of the repository have the
#                     line `<!-- deviation-register -->`, and the table of docs/deviations.md takes its place.
#   SHA256SUMS        The SHA-256 checksums of the files, in the form that `sha256sum --check` reads.
#
# The script needs sha256sum or shasum. It ends with the status 0 when the release is there. Otherwise it
# says what is wrong, and it ends with the status 1.
set -euo pipefail

usage="usage: assemble-release.sh <directory> <release-notes.md> <file>..."
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIRECTORY="${1:?$usage}"
NOTES="${2:?$usage}"
shift 2
REGISTER="$ROOT/docs/deviations.md"
MARKER='<!-- deviation-register -->'

# Says what is wrong and ends the script.
fail() {
  echo "The release is not assembled: $*" >&2
  exit 1
}

[ "$#" -gt 0 ] || fail "No file of a program is given, $usage"
for file in "$NOTES" "$REGISTER" "$@"; do
  [ -f "$file" ] || fail "$file is no file."
done
[ "$(grep -cFx -- "$MARKER" "$NOTES")" -eq 1 ] || fail "$NOTES does not have the line '$MARKER' exactly once."
if command -v sha256sum >/dev/null; then
  checksums="sha256sum"
elif command -v shasum >/dev/null; then
  checksums="shasum -a 256"
else
  fail "Neither sha256sum nor shasum is on the path."
fi

# The directory: what an earlier run wrote goes, and nothing else is touched.
mkdir -p "$DIRECTORY"
for old in "$DIRECTORY"/* "$DIRECTORY"/.[!.]*; do
  [ -e "$old" ] || continue
  [ -f "$old" ] || fail "$DIRECTORY holds $(basename "$old"), which is no file."
  case "$(basename "$old")" in
    mockey-* | RELEASE-NOTES.md | SHA256SUMS) rm -f "$old" ;;
    *) fail "$DIRECTORY holds $(basename "$old"), which is no file of a release." ;;
  esac
done

for file in "$@"; do
  case "$(basename "$file")" in
    mockey-*) cp "$file" "$DIRECTORY/" ;;
    *) fail "$file is no file of a program: its name does not begin with mockey-." ;;
  esac
done

# The release notes, with the rows of the register's table in the place of the marker.
awk -v marker="$MARKER" -v register="$REGISTER" '
  $0 == marker {
    while ((getline row < register) > 0) {
      if (row ~ /^\| ID \|/) inside = 1
      else if (row !~ /^\|/) inside = 0
      if (inside) {
        print row
        if (row ~ /^\| DEV-/) entries++
      }
    }
    close(register)
    next
  }
  { print }
  END { if (entries == 0) exit 3 }
' "$NOTES" >"$DIRECTORY/RELEASE-NOTES.md" || fail "docs/deviations.md has no table of deviations."
entries="$(grep -c '^| DEV-' "$DIRECTORY/RELEASE-NOTES.md")"

(cd "$DIRECTORY" && $checksums mockey-*) >"$DIRECTORY/SHA256SUMS"

echo "The release is in $DIRECTORY: $# files of the programs, their checksums, and the release notes."
echo "The release notes have the $entries entries of the deviation register."
