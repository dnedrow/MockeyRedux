# JSON Schema validator

Decided in task 3.7 of the change `kotlin-rewrite-modular`, on 2026-11-23.

`mockey-core` checks the response bodies of scenarios against the response schema of their service with
[`dev.harrel:json-schema`](https://github.com/harrel56/json-schema) 1.9.1. It uses the provider of that
library for kotlinx.serialization, and its checks of the `format` keyword, which need
[`com.sanctionco.jmail:jmail`](https://github.com/RohanNagar/jmail) 2.1.0.

## What was needed

- The response schema report of `service-definitions` states for each scenario whether its response body
  conforms to the schema of its service, and lists the violations.
- Schemas in JSON Schema draft-04 or later must be supported. A schema in another dialect is reported as
  unsupported and gets no verdict (DEV-23).
- Design decision 8 keeps the dependencies of `mockey-core` at the kotlinx libraries, so that the library is
  light for programs that embed it. The proposal lists a JSON Schema validator among the dependencies of the
  project.

Legacy checked a response body on its page `/jsonschemavalidate`, with version 1.99.13 of
`com.github.fge:json-schema-validator`. Running that code showed what the page did:

- It read a schema that declares no dialect as draft-04, unless the user ticked "Use draft v3".
- It read a schema that declares a dialect in that dialect.
- It checked the `format` keyword: `"not an email"` failed against `{"format": "email"}`.
- It reported a schema that refers to itself in a loop: "JSON Reference loop detected".

## Candidates

| Library | Version | License | What it adds to the class path | JSON model |
|---|---|---|---|---|
| `dev.harrel:json-schema` | 1.9.1 | MIT | One jar of 211 KB. Every dependency that it declares is optional. | Any, through a provider. A provider for kotlinx.serialization is included. |
| `io.github.optimumcode:json-schema-validator` | 0.5.5 | MIT | Five jars, 956 KB together. | kotlinx.serialization |
| `com.networknt:json-schema-validator` | 3.0.9 | Apache-2.0 | Eight jars, 3,789 KB together: Jackson 3 with its YAML format, SnakeYAML Engine, `itu`, and the SLF4J API. | Jackson |

`com.github.erosb:json-sKema` was not run. Its README says that it implements draft 2020-12 and names another
library for draft-04, draft-06, and draft-07.

## Spike

Each candidate ran the validation tests of the official
[JSON Schema Test Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite) at commit
`e2a68a15cd490750fd6b95e604e4054d7d978d1f`, in a scratch project outside this repository.

- The runner took the 69 files directly under `validation/tests`. It did not take the directories `optional`
  and `proposals`.
- For each dialect it ran the test cases that the suite marks as compatible with that dialect, with the
  candidate set to that dialect as its default. `validation/README.md` of the suite gives the rule.
- It left out the 51 test cases that bring external schemas.
- It left out the 52 test cases that carry a tag. The tags are `format-assertion` and `format-annotation`,
  and they mark the tests that expect one of the two behaviors of the `format` keyword.
- Each candidate ran as it is configured by default.

Tests passed, of the tests run:

| Dialect | Tests run | harrel | OptimumCode | networknt |
|---|---|---|---|---|
| draft-04 | 635 | 635 | 635 | 635 |
| draft-06 | 836 | 836 | 836 | 836 |
| draft-07 | 922 | 922 | 922 | 922 |
| 2019-09 | 1,235 | 1,235 | 1,225 | 1,235 |
| 2020-12 | 1,249 | 1,246 | 1,234 | 1,249 |

What failed:

- harrel, 2020-12: three tests whose pattern names a Unicode property by its long name, `\p{Letter}`. The
  regular expressions of Java know that property as `\p{L}`. harrel does not apply a pattern that Java cannot
  compile, so it accepted the three instances that the pattern rules out.
- OptimumCode, 2019-09: four tests with a `minContains` of 0, and the six tests of an empty `enum`, which it
  refuses to load.
- OptimumCode, 2020-12: the six tests of the empty `enum`, and the nine tests of the three test cases with
  `\p{Letter}`, which it refuses to load.

A second program asked what the report needs to know, with draft-04 as the default dialect:

| Question | harrel | OptimumCode |
|---|---|---|
| Does a missing required property appear by name? | Yes: `Object does not have some of the required properties [id]` | Yes: `missing required properties: [id]` |
| A schema that declares draft-03 or an unknown dialect | Fails with an exception that names the meta-schema | Fails with an exception that names the dialect |
| A schema that declares no dialect and uses the Boolean `exclusiveMinimum` of draft-04 | Read as draft-04 | Read as draft-04 |
| A schema that declares no dialect and uses `const`, which draft-04 does not have | `const` is ignored, as draft-04 says | `const` is applied |
| A schema that declares draft-07 or 2020-12 | Read in that dialect | Read in that dialect |
| A `$ref` to a URL | A violation says that the reference was not resolved | Fails with an exception |
| A schema that its meta-schema rejects, such as `{"type": 5}` | Fails with an exception that lists what is wrong | Fails with an exception |

harrel never fetches a schema. Of `java.net` its class files name only `URI`, `URISyntaxException`, and
`URLDecoder`, and the meta-schemas of its five dialects are resources in its jar.

### harrel with its format checks

harrel checks `format` only when it is told to, and then in every dialect. With the checks on, it ran the
suite again:

| Dialect | Untagged tests passed | `format-assertion` tests passed |
|---|---|---|
| draft-04 | 635 of 635 | 221 of 239 |
| draft-06 | 836 of 836 | 298 of 340 |
| draft-07 | 922 of 922 | 570 of 683 |
| 2019-09 | 1,235 of 1,235 | 638 of 751 |
| 2020-12 | 1,240 of 1,249 | 647 of 763 |

- The untagged result for 2020-12 changes because the meta-schemas give `pattern` the format `regex`. With the
  checks on, harrel refuses a schema whose pattern Java cannot compile. That fails all nine tests of the three
  test cases with `\p{Letter}`, but it is the better behavior for the report: the schema is reported as
  unusable, and no body passes by mistake.
- Of the 116 `format-assertion` tests that fail for 2020-12, 81 are values that the suite calls invalid and
  harrel accepts, such as a time without seconds or a host name of 256 characters.
- The other 35 are values that the suite calls valid and harrel rejects: 28 host names with characters outside
  ASCII under `idn-hostname`, which harrel checks as `hostname`, two date-times with a leap second, a
  date-time and a time with a second fraction of 15 digits, two email addresses whose domain is an IP address,
  and the reference `//`.

## Decision

harrel is the validator.

- It passes every test that was run for the floor, draft-04, and for draft-06, draft-07, and 2019-09.
- With jmail it adds two jars of 255 KB together and nothing else, which fits design decision 8 best. Both
  have the MIT license. Its provider works on the JSON model of kotlinx.serialization, which `mockey-core`
  uses anyway.
- It keeps to the dialect that it is given. A report in draft-04 therefore does not apply keywords of later
  dialects by accident.

OptimumCode would fit the kotlinx libraries as well. It has more gaps in the two newest dialects, has not
reached version 1, and adds five jars. networknt passes every test that was run, but it brings Jackson and a
YAML parser into every program that embeds the core.

## What follows for the report

Dialects:

- The report reads `$schema` itself, before it calls the validator. The value must be the identifier of
  draft-04, draft-06, draft-07, 2019-09, or 2020-12, with or without a `#` at its end. Anything else is
  reported as an unsupported dialect, and no scenario is checked. That includes `https` in the place of `http`
  in an identifier.
- A schema inside the schema that has its own identifier and declares an unsupported dialect is reported in
  the same way.
- A schema that declares no dialect is read as draft-04, as legacy read it. A schema that uses keywords of a
  later dialect has to declare that dialect.

What is checked:

- `format` is checked in every dialect, as legacy checked it. A format name that harrel does not know is not
  checked. That includes the names that only draft-03 has, such as `host-name` and `ip-address`.
- A reference to a schema elsewhere is not followed. A scenario that needs such a reference does not conform,
  and the violation names the reference.
- A schema that is not JSON, or that the meta-schema of its dialect rejects, is reported as unusable with what
  is wrong in it, and no scenario is checked.
- harrel does not apply a keyword whose value it cannot use. The meta-schemas rule most such values out, but
  not all. In draft-04, a name under `patternProperties` that Java cannot compile and a `$ref` that is not a
  URI reference pass without notice.

Limits that the report sets, because harrel has none:

- A schema that refers to itself without taking a part of the body, such as `{"$ref": "#"}`, exhausts the
  stack. The report catches that and reports the schema as unusable.
- harrel computes with numbers at their full size. In a probe, the body `1e10000000` took 4.8 seconds against
  an `enum`, and `1e100000000` ended in an `OutOfMemoryError`. The report therefore checks only numbers within
  the range of a double, which RFC 8259 names as the range that software can rely on. A body with a number
  outside that range does not conform, and a schema with such a number is unusable.
- The response body must be JSON. The report checks that itself, because the parser of kotlinx.serialization
  accepts a bare word as a value. That parser reads JSON that is nested up to 400 levels deep. A body or a
  schema that is nested more deeply is treated as one that is not JSON.
- The report registers the schema under the name `mockey:/response-schema`. harrel would otherwise give it a
  random name, which shows in the text of a reference that cannot be resolved.

Other consequences:

- Only the violations of an enabled schema appear in the definition diagnostics. An enabled schema that is
  unusable or in an unsupported dialect adds nothing there.
- A report for a service with two scenarios took 0.6 milliseconds in a probe, so nothing is cached.
- `mockey-core` now depends on `dev.harrel:json-schema`, on kotlinx-serialization-json, and at run time on
  jmail.

## Repeating the spike

1. Download the test suite at the commit above.
2. Create a Kotlin project with the three libraries and jmail as dependencies.
3. For each dialect and each file under `validation/tests`, load the schema of every compatible test case
   with that dialect as the default, and compare the result for each test with its `valid` member.
4. For the format checks, give the `ValidatorFactory` of harrel a `FormatEvaluatorFactory` and run the test
   cases with the tag `format-assertion` as well.
