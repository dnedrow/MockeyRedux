#!/usr/bin/env bash
# Checks docs/parity/traceability.md against the sources of legacy Mockey:
#
#   - The table of the servlet mappings has one row for every servlet mapping of the legacy web.xml, and no
#     row for a mapping that the file does not have.
#   - The tables of the documented parameters name every parameter that the legacy API documentation
#     describes. A legacy servlet declares the parameters of its endpoint when it starts.
#   - The table of the startup options has one row for every option of the legacy runner, and no row for an
#     option that the runner does not have.
#   - No row is marked Open, which stands for a row without a place in the new system.
#
# Usage: scripts/check-traceability.sh /path/to/legacy/Mockey
#
# The tests of mockey-parity-tests check what needs no legacy checkout: that the requirements, the parity
# cases, and the deviations that the table names are there.
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

# --- The documented parameters ---------------------------------------------------------------------------

SOURCES="$LEGACY/src/java"

# Prints the text of the constant $2 that the legacy class $1 declares, or nothing when it declares none.
constant_of() {
  local file
  file="$(find "$SOURCES" -name "$1.java" | head -n 1)"
  [ -n "$file" ] || return 0
  sed -n "s/.*String  *$2  *=  *\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

# Prints the name of the parameter that the argument $2 of setFieldName stands for in the servlet file $1:
# a text as it is, a constant of a named class by that class, and any other constant by the servlet and by
# the interfaces that it implements.
parameter_name() {
  local class interfaces owner name
  case "$2" in
    \"*\")
      name="${2#\"}"
      printf '%s\n' "${name%\"}"
      ;;
    *.*)
      constant_of "${2%%.*}" "${2##*.}"
      ;;
    *)
      class="$(basename "$1" .java)"
      interfaces="$(tr -s ' \t\r\n' ' ' <"$1" | sed -n "s/.* class $class [^{]*implements \([^{]*\){.*/\1/p" | tr ',' ' ')"
      for owner in "$class" $interfaces; do
        name="$(constant_of "$owner" "$2")"
        if [ -n "$name" ]; then
          printf '%s\n' "$name"
          return 0
        fi
      done
      ;;
  esac
}

# Prints the first cells of the rows in the part of the table that begins with the heading $1.
first_cells_under() {
  awk -v heading="$1" '
    $0 == heading { inside = 1; next }
    inside && /^##/ { inside = 0 }
    inside && /^\| / { split($0, cells, " \\| "); print substr(cells[1], 3) }
  ' "$TABLE"
}

documented=0
unreadable=""
undocumented=""
for servlet in $(grep -rl 'setServicePath("' "$SOURCES" | sort); do
  path="$(sed -n 's/.*setServicePath("\([^"]*\)").*/\1/p' "$servlet" | head -n 1)"
  cells="$(first_cells_under "### \`$path\`")"
  for argument in $(grep -o 'setFieldName([^)]*)' "$servlet" | sed 's/setFieldName(\(.*\))/\1/' | sort -u); do
    name="$(parameter_name "$servlet" "$argument")"
    if [ -z "$name" ]; then
      # A field without a name describes no parameter. A constant that cannot be read is a problem.
      [ "$argument" = '""' ] || unreadable="$unreadable  $path: $argument"$'\n'
      continue
    fi
    documented=$((documented + 1))
    printf '%s\n' "$cells" | grep -Fq "\`$name\`" || undocumented="$undocumented  $path: $name"$'\n'
  done
done

if [ "$documented" -eq 0 ]; then
  echo "Found no documented parameter in $SOURCES." >&2
  status=1
fi
if [ -n "$unreadable" ]; then
  echo "Documented parameters of legacy whose names the script cannot read:" >&2
  printf '%s' "$unreadable" >&2
  status=1
fi
if [ -n "$undocumented" ]; then
  echo "Documented parameters of legacy without a row in the table of their endpoint:" >&2
  printf '%s' "$undocumented" >&2
  status=1
fi
[ -z "$unreadable$undocumented" ] && [ "$documented" -gt 0 ] &&
  echo "All $documented documented parameters of the legacy endpoints are in docs/parity/traceability.md."

# --- The startup options ---------------------------------------------------------------------------------

RUNNER="$SOURCES/com/mockey/runner/JettyRunner.java"

# The short form of every option that the runner registers: the letter after JSAP.NOT_REQUIRED for an option
# with a value, and the letter after the name for a switch.
options="$(
  tr -s ' \t\r\n' ' ' <"$RUNNER" |
    grep -oE "(JSAP\.NOT_REQUIRED|new Switch\([A-Za-z_.]+), *'[A-Za-z]'" |
    sed "s/.*'\(.\)'/-\1/" |
    sort
)"
option_rows="$(first_cells_under "## Startup options" | grep -oE '`-[A-Za-z]`' | tr -d '`' | sort)"
missing_options="$(comm -23 <(printf '%s\n' "$options" | uniq) <(printf '%s\n' "$option_rows" | uniq))"
unknown_options="$(comm -13 <(printf '%s\n' "$options" | uniq) <(printf '%s\n' "$option_rows" | uniq))"
option_count="$(printf '%s\n' "$options" | grep -c . || true)"

if [ "$option_count" -eq 0 ]; then
  echo "Found no startup option in $RUNNER." >&2
  status=1
fi
if [ -n "$missing_options" ]; then
  echo "Startup options of legacy without a row in the table:" >&2
  printf '%s\n' "$missing_options" | sed 's/^/  /' >&2
  status=1
fi
if [ -n "$unknown_options" ]; then
  echo "Rows of the table for startup options that legacy does not have:" >&2
  printf '%s\n' "$unknown_options" | sed 's/^/  /' >&2
  status=1
fi
[ -z "$missing_options$unknown_options" ] && [ "$option_count" -gt 0 ] &&
  echo "All $option_count startup options of the legacy runner are in docs/parity/traceability.md."

# --- Open rows -------------------------------------------------------------------------------------------

open_rows="$(grep -c '^|.*\*\*Open' "$TABLE" || true)"
if [ "$open_rows" -gt 0 ]; then
  echo "Rows marked Open, which have no place in the new system yet: $open_rows" >&2
  status=1
else
  echo "No row of docs/parity/traceability.md is marked Open."
fi
exit "$status"
