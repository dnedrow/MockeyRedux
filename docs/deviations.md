# Deviation register

The Kotlin rewrite fixes legacy defects and records intentional behavior
differences here. Evidence is marked `read` until a parity case confirms it.

| ID | Legacy behavior | New behavior | Evidence |
|---|---|---|---|
| DEV-01 | Template variables match literal braces. | Variables match one path segment and the whole target. | run |
| DEV-02 | First matching service in ID order wins. | Most specific pattern wins. | read |
| DEV-03 | Unknown relative targets become proxy services. | Unknown relative targets return 404. | read |
| DEV-04 | Missing tag filter can register a duplicate service. | No duplicate is registered. | read |
| DEV-05 | Twist replacement uses regular expressions. | Twist replacement is literal. | read |
| DEV-06 | Response bodies receive a platform line separator. | Bodies are sent exactly as stored. | read |
| DEV-07 | No scenario can produce an invalid status. | Fallback responses use 404. | read |
| DEV-08 | Encoding depends on any header containing `utf-8`. | Content-Type charset controls encoding; UTF-8 is the default. | read |
| DEV-09 | New scenarios have default Accept headers. | New scenarios have no default headers. | read |
| DEV-10 | Only GET is proxied as GET; other methods become POST. | The original method is forwarded. | read |
| DEV-11 | Proxy bodies are decoded and re-encoded as text. | Proxy bodies are relayed byte for byte. | read |
| DEV-12 | Relative proxy targets are built from the request path. | Relative targets use the default real URL. | read |
| DEV-13 | Upstream errors use only the error body. | Error status, headers, and body are used. | read |
| DEV-14 | Set-Cookie is stripped to name and value. | Set-Cookie is relayed unchanged. | read |
| DEV-15 | Missing rule keys skip required rules. | Required missing keys fail; optional ones pass and count. | read |
| DEV-16 | Several received headers are invisible to rules. | Rules see every received header. | read |
| DEV-17 | Form fields are unavailable to parameter rules. | Query and form parameters are available. | read |
| DEV-18 | Saving a scenario clears universal-error designation. | Only an explicit change alters that designation. | read |
| DEV-19 | Duplicated services reuse name and URL and drop defaults. | Copies get distinct names and URLs and keep designations. | read |
| DEV-20 | Numeric IDs are reassigned and reused. | Opaque persisted identifiers are never reused. | read |
| DEV-21 | Every save rewrites all files with display-name filenames. | Changed files are atomically written with collision-free slugs. | read |
| DEV-22 | Transient flags suppress only save triggers. | Only global read-only mode remains. | read |
| DEV-23 | Draft-03 schemas are accepted. | Draft-04 and later are required. | read |
| DEV-24 | History is unbounded. | History retention is bounded and configurable. | read |
| DEV-25 | Startup opens a browser. | Startup never opens a browser. | read |
| DEV-26 | Admin traffic shares the mock port and is unauthenticated. | Admin traffic has a separate listener and optional token. | read |
| DEV-27 | Runtime import can read arbitrary paths or URLs. | Runtime import accepts document content only. | read |
| DEV-28 | Status exposes a path and locale-specific start time. | Status exposes state and ISO 8601 start time only. | read |
| DEV-29 | Merge copies only equal-named services before deleting sources. | Scenarios, tags, and URLs merge before safe deletion. | read |
| DEV-30 | Writable initialization deletes referenced stored definitions. | Initialization is refused unless the location is empty. | read |
