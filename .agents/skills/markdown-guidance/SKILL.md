---
name: markdown-guidance
description: Use when writing or editing Markdown that needs to follow this repository's custom formatting guidance.
metadata:
  author: "dnedrow"
  tags: "markdown, formatting, tables, documentation"
---

# Markdown guidance

## Purpose

Apply this repository's custom Markdown formatting guidance. It covers two
topics:

- When to pad table columns so they line up, and when to leave a table
  compact.
- The RFC 2119/8174 interpretation note that Markdown files using
  requirement keywords have to carry.

Use it when:

- Adding a table to a Markdown file.
- Editing a table so that a cell, column, or row changes.
- Adding or editing Markdown that uses RFC 2119 keywords in all capitals.
- Reviewing a Markdown change that does any of the above.

## Prerequisites

- No tools, dependencies, credentials, or setup are needed.
- You need the raw Markdown source of the table. Line length is measured on
  the source text, not on the rendered output.

## Instructions

### Tables

- Align table columns with spaces for padding only when every line in the
  formatted table is fewer than 120 characters.
- Count the full length of each table line, including pipes and padding.
- If any line would be 120 characters or longer, do not align columns with
  padding; use compact table formatting instead.

Apply the rule in this order:

1. Work out the width of each column: the length of its longest cell,
   counting the header cell and the delimiter cell.
2. Work out how long each line would be with every cell padded to its column
   width. Include the leading and trailing pipes, the pipes between columns,
   and the space on each side of every cell.
3. Take the longest of those lines. One length decides the whole table.
4. If it is fewer than 120 characters, pad every row so the pipes line up.
5. If it is 120 characters or longer, write every row compact: one space on
   each side of each cell and no extra padding. Shorten delimiter cells to
   three hyphens while preserving any alignment colons (`:---`, `---:`, or
   `:---:`).

The decision applies to the table as a whole. A table is either aligned on
every row or compact on every row, never a mix.

### RFC 2119 keyword note

- Updated and new Markdown files that use RFC 2119 keywords (see RFC 8174)
  MUST include the interpretation note below.
- Do not apply to files under the `openspec` directory. Do not apply to
  files that have not changed in the current branch.
- Apply to other files in the repository, including those under `Sources`,
  `Tests`, and `Docs`.
- The newly inserted RFC guidance lines MUST be no longer than 80 columns,
  with word breaks.
- Inserted lines MUST be inserted at the bottom of the file, two lines after
  the final text in the impacted file.
- The comment block MUST have one blank line between it and the preceding
  text.
- If the required RFC 2119/8174 note is absent, append it after the file's
  final existing content as the last non-whitespace content in the file. It
  MUST NOT be inserted inside a documentation section. The note MUST occur
  exactly once, and only an optional final newline may follow it.

The note, as a Markdown quote, is:

> The keywords "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
> "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in documents
> are to be interpreted as described in RFC 2119 and further clarified in
> RFC 8174 when, and only when, they appear in all capitals, as shown here.

The same requirement for Swift source files is in the repository's root
agent instructions.

## Examples

### Aligned table

Every padded line is 36 characters, so the columns are aligned:

```markdown
| Scheme          | Brand          |
|-----------------|----------------|
| BrambleHarbor   | Bramble Harbor |
| MarlowIronworks | Marlow         |
```

### Compact table

Padding the table below would make every line 131 characters, because the
`Notes` column would be padded to its longest cell. The table is therefore
written compact, even though the header and delimiter lines are short:

```markdown
| Scheme | Notes |
| --- | --- |
| BrambleHarbor | Builds the Bramble Harbor brand of the app for local development, pull request checks, and release pipelines. |
| MarlowIronworks | Builds the Marlow brand. |
```

### Incorrect: mixed formatting

Do not pad the short rows of a table that has to be compact:

```markdown
| Scheme          | Notes                    |
|-----------------|--------------------------|
| BrambleHarbor | Builds the Bramble Harbor brand of the app for local development, pull request checks, and release pipelines. |
| MarlowIronworks | Builds the Marlow brand. |
```

### RFC note placement

The end of a Markdown file that uses the keywords: final text, one blank
line, then the note as the last content in the file.

```markdown
- Unit tests MUST be registered in the BrambleHarbor test plan.

<!--
The keywords "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
"SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in documents
are to be interpreted as described in RFC 2119 and further clarified in
RFC 8174 when, and only when, they appear in all capitals, as shown here.
-->
```

## Validation

Check the RFC note before finishing the edit:

- The file is outside `openspec` and is changed in the current branch.
- The note occurs exactly once and is the last non-whitespace content.
- No line of the note is longer than 80 columns.

To count the notes in a file, which has to print `1`:

```sh
grep -c 'are to be interpreted as described in RFC' "$file"
```

Check a table before finishing the edit:

- An aligned table has lines of equal length, each fewer than 120
  characters.
- A compact table has no run of two or more spaces inside any row.
- A table edit that changes a column's widest cell has had every row of that
  table reformatted, not only the edited row.

To list the lines of a file that are 120 characters or longer:

```sh
awk 'length($0) >= 120 { print FILENAME ":" FNR ": " length($0) }' "$file"
```

Any aligned table row in that output means the table has to be compact.

## Troubleshooting

| Error | Cause | Solution |
| --- | --- | --- |
| Columns no longer line up after an edit | A cell grew past its column width | Recompute the widths and reformat every row |
| Aligned table has a line of 120 or more characters | A cell grew, or a column was added | Rewrite the whole table compact |
| Compact table still has padded rows | Only the long rows were rewritten | Remove the padding from every row |
| Length looks under 120 but the rule says compact | Pipes or padding were not counted | Count the full source line as it would be padded |
| A formatter realigns a compact table | Editor or tool auto-formatting | Restore the compact form |
| RFC note appears twice | A note was appended while one already existed | Keep only the note at the end of the file |
| RFC note is followed by other text | Content was appended after the note | Move the note back to the end of the file |

## Limitations

- This skill covers table column alignment and the RFC 2119/8174 note only.
  It does not define line wrapping, heading style, list style, or link
  style.
- The RFC note is not added to unchanged files in the current branch.
- Length is counted in characters of Markdown source. The rule says nothing
  about how wide a table renders.
- The rule does not ask for a compact table to be split or shortened. Whether
  to restructure a wide table is a separate editorial choice.

<!--
The keywords "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
"SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in documents
are to be interpreted as described in RFC 2119 and further clarified in
RFC 8174 when, and only when, they appear in all capitals, as shown here.
-->
