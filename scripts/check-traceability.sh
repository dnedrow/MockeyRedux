#!/usr/bin/env bash
# Checks that docs/parity/traceability.md has one row for every servlet mapping of the legacy web.xml, and no
# row for a mapping that the file does not have.
# Usage: scripts/check-traceability.sh /path/to/legacy/Mockey
set -euo pipefail

LEGACY="$(cd "${1:?usage: check-traceability.sh <legacy-checkout>}" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB_XML="$LEGACY/web/WEB-INF/web.xml"
TABLE="$ROOT/docs/parity/traceability.md"

# The URL pattern of every servlet mapping that is not commented out. The file spreads a mapping over several
# lines, so it is read as one line, without its comments, and split after each mapping.
mapped="$(
  tr -s ' \t\r\n' ' ' <"$WEB_XML" |
    awk '{
      text = ""
      while ((start = index($0, "<!--")) > 0) {
        text = text substr($0, 1, start - 1)
        rest = substr($0, start + 4)
        end = index(rest, "-->")
        $0 = end > 0 ? substr(rest, end + 3) : ""
      }
      print text $0
    }' |
    sed 's/<\/servlet-mapping>/&\
/g' |
    sed -n 's/.*<servlet-mapping>.*<url-pattern> *\([^ <]*\) *<\/url-pattern>.*/\1/p' |
    sort
)"

# The first column of the table under "Servlet mappings".
rows="$(
  sed -n '/^## Servlet mappings$/,/^## /p' "$TABLE" |
    sed -n 's/^| `\([^`]*\)` |.*/\1/p' |
    sort
)"

missing="$(comm -23 <(printf '%s\n' "$mapped" | uniq) <(printf '%s\n' "$rows" | uniq))"
unknown="$(comm -13 <(printf '%s\n' "$mapped" | uniq) <(printf '%s\n' "$rows" | uniq))"
repeated="$(printf '%s\n' "$rows" | uniq -d)"
count="$(printf '%s\n' "$mapped" | grep -c .)"

status=0
if [ "$count" -eq 0 ]; then
  echo "Found no servlet mapping in $WEB_XML." >&2
  status=1
fi
if [ -n "$missing" ]; then
  echo "Servlet mappings of the legacy web.xml without a row in the table:" >&2
  printf '%s\n' "$missing" | sed 's/^/  /' >&2
  status=1
fi
if [ -n "$unknown" ]; then
  echo "Rows of the table for mappings that the legacy web.xml does not have:" >&2
  printf '%s\n' "$unknown" | sed 's/^/  /' >&2
  status=1
fi
if [ -n "$repeated" ]; then
  echo "Mappings with more than one row in the table:" >&2
  printf '%s\n' "$repeated" | sed 's/^/  /' >&2
  status=1
fi
[ "$status" -eq 0 ] && echo "All $count servlet mappings of the legacy web.xml are in docs/parity/traceability.md."

open_rows="$(grep -c '^|.*\*\*Open' "$TABLE" || true)"
if [ "$open_rows" -gt 0 ]; then
  echo "Rows marked Open, which have no place in the new system yet: $open_rows"
fi
exit "$status"
