#!/usr/bin/env bash
# Publishes a release on GitHub: the files of a release directory, with its release notes, for a tag.
#
# Usage: scripts/publish-release.sh <tag> <directory>
#        scripts/publish-release.sh --check-tag <tag>
#
# The tag has to be `v` and the version that gradle.properties states, as in v3.0.0-rc.1. With --check-tag,
# the script checks only that, and it publishes nothing.
#
# The directory holds what scripts/assemble-release.sh collects. The release gets the first heading of
# RELEASE-NOTES.md as its title and the rest of that document as its notes. Its files are the files of the
# programs and SHA256SUMS. A version that goes on after a hyphen, such as 3.0.0-rc.1, becomes a pre-release.
#
# The script needs the GitHub CLI, gh, which has to be allowed to write to the repository. The workflow
# .github/workflows/release.yml runs the script when a tag is pushed. The script ends with the status 0 when
# the release is published. Otherwise it says what is wrong, and it ends with the status 1.
set -euo pipefail

usage="usage: publish-release.sh <tag> <directory>, or publish-release.sh --check-tag <tag>"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Says what is wrong and ends the script.
fail() {
  echo "The release is not published: $*" >&2
  exit 1
}

check_only=""
if [ "${1:-}" = "--check-tag" ]; then
  check_only="yes"
  shift
fi
TAG="${1:?$usage}"
VERSION="$(sed -n 's/^version=//p' "$ROOT/gradle.properties")"
[ -n "$VERSION" ] || fail "gradle.properties states no version."
[ "$TAG" = "v$VERSION" ] ||
  fail "The tag is $TAG, and the tag of the version $VERSION, which gradle.properties states, is v$VERSION."
if [ -n "$check_only" ]; then
  echo "The tag $TAG is the tag of the version $VERSION."
  exit 0
fi

DIRECTORY="${2:?$usage}"
NOTES="$DIRECTORY/RELEASE-NOTES.md"
[ -f "$NOTES" ] || fail "$DIRECTORY holds no release notes RELEASE-NOTES.md."
[ -f "$DIRECTORY/SHA256SUMS" ] || fail "$DIRECTORY holds no SHA256SUMS."
command -v gh >/dev/null || fail "The GitHub CLI, gh, is not on the path."

# The title is the first heading of the notes, and the notes of the release are what follows it.
title="$(sed -n '1s/^# //p' "$NOTES")"
[ -n "$title" ] || fail "The first line of $NOTES is no heading."
case "$title" in
  *" $VERSION") ;;
  *) fail "The heading of the release notes, '$title', does not end with the version $VERSION." ;;
esac
BODY="$(mktemp "${TMPDIR:-/tmp}/mockey-release-notes.XXXXXX")"
trap 'rm -f "$BODY"' EXIT
awk 'NR == 1 || (NR == 2 && $0 == "") { next } { print }' "$NOTES" >"$BODY"

# A version that is no release is published as a pre-release.
options=(--verify-tag --title "$title" --notes-file "$BODY")
case "$VERSION" in
  *-*) options+=(--prerelease) ;;
esac
gh release create "$TAG" "${options[@]}" "$DIRECTORY"/mockey-* "$DIRECTORY/SHA256SUMS" ||
  fail "gh did not create the release, see above."
echo "The release '$title' is published for the tag $TAG."
