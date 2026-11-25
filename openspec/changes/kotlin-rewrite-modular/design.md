# Design: Kotlin rewrite as a library, a server, and a separate web UI

## Context

Motivation is in `proposal.md` (Why). This section records only what shapes the approach.

**Legacy structure.** Mockey is one web application on embedded Jetty. `ResponseServlet`, mapped to `/service/*`, twists the URL, looks up a `Service` in a static `InMemoryMockeyStorage` singleton, runs request inspectors, and calls `Service.execute`. That method proxies the request, returns the default scenario (static mode), or picks the best-matching scenario (dynamic mode). About 40 further servlets plus JSPs form the UI and the scriptable configuration endpoints. Persistence rewrites an XML file tree on every change. Request-inspector plugins are found by scanning for packages annotated with `@MockeyRequestInspector`.

**Decisions already made during exploration.**

- The new code lives in a new repository. The legacy tree is not modified.
- There are three deliverables: a library, a server, and a UI. The UI is a separate process.
- No Spring and no JavaScript application frameworks. htmx is allowed only as an optional enhancement.
- Legacy defects are fixed, and each fix is recorded in a deviation register.
- Legacy automation endpoints are offered through an opt-in shim, off by default, built as the last phase.

**Facts found in the legacy code that affect the plan.**

- URL-template matching is broken at HEAD (`88e31ed`). Commit `f0d83a4` (2019-12-28, for upstream issue 109) changed the variable pattern so that `{id}` matches only literal `{...}` text. Compiling and running the legacy `UriTemplate` class in isolation confirms that `http://customer/{customerId}` no longer matches `http://customer/123`. HEAD is therefore not a trustworthy oracle for routing.
- Proxy mode forwards to the URL written in the request path, never to the service's configured real URLs. `defaultRealUrlIndex` is stored and editable but has no effect on request handling.
- The request path mutates shared service state on every request and blocks a thread for delays.
- The JAX-RS classes under `com.mockey.api` are unfinished stubs. The real scriptable surface is the servlet endpoints.
- Mockey is licensed GPL-2.0-or-later. A port written with the legacy source as its reference is a derivative work and carries that license.

**OpenSpec mechanics.** This change was planned in the legacy repository, `clafonta/Mockey`. OpenSpec scopes implementation edits to the repository that hosts the change, so the change directory was moved into this repository when it was bootstrapped (tasks 1.1 and 1.2). References to legacy files and commits in these documents are to that repository.

## Goals / Non-Goals

**Goals:**

- A core library with no global state and no HTTP server dependency, in which every step of request handling can be unit tested.
- A server whose mock traffic and administrative traffic are separated at the listener level.
- A UI that is a pure client of the admin API and works without JavaScript.
- Behavior parity that is demonstrated by tests, with every intentional difference written down.
- A storage format that is safe to keep in version control and safe against crashes during a write.
- A one-step path for existing users to bring their definitions across.

**Non-Goals:**

- New mocking features, such as new matching languages, response templating, protocols other than HTTP, or TLS termination on Mockey's own listeners.
- Modifying the legacy tree, building on it, or sharing code with it.
- Running legacy Java plugins unmodified.
- Persisting request history.
- User accounts, roles, or multi-tenancy. A single shared admin token is the only access control.
- Clustering or shared state between instances.
- A single-process distribution that embeds the UI in the server.
- Carrying over the Image Depot, the mobile-only page (`/mobi`), the in-browser debug log viewer (`/console`), and the JSON Schema sample loader.
- WAR packaging.

## Decisions

### 1. One new repository with six Gradle modules

``` text
mockey-kt/                       this repository
  build-logic/                   convention plugins
  gradle/libs.versions.toml      version catalog
  mockey-core/                   library: model, request pipeline, ports, default adapters
  mockey-api/                    admin API wire types, shared by server and UI
  mockey-legacy/                 import of Mockey 2.x XML definitions
  mockey-server/                 application: mock endpoint, admin API, legacy shim
  mockey-ui/                     application: server-rendered pages
  mockey-parity-tests/           black-box parity suite
```

Dependency rules, enforced by the build:

``` text
mockey-ui -----> mockey-api <----- mockey-server -----> mockey-core
                                        |                    ^
                                        +---> mockey-legacy -+
```

`mockey-core` depends on nothing else in the repository. `mockey-ui` depends on `mockey-api` only and must not see `mockey-core`.

The repository is named `mockey-kt`. The Maven group is `io.github.dnedrow`, and the base package is `io.github.dnedrow.mockey`. These were agreed in task 1.1.

Runtime view:

``` text
        mock clients                               browser
             |                                        |
             v                                        v
+----------------------------+           +-------------------------+
| mockey-server              |           | mockey-ui               |
|  mock listener   :8080     |           |  server-rendered HTML   |
|    /service/*   /status    |   HTTP    |  optional htmx          |
|  admin listener  :8081     |<----------|  admin API client       |
|    /api/v1/*               |   JSON    |  listener  :8082        |
+-------------+--------------+           +-------------------------+
              |
              v
+----------------------------+
| mockey-core                |
|  model, routing, rules,    |
|  responses, plans, history |
+------+--------------+------+
       |              |
       v              v
definition files   real services (optionally through a corporate proxy)
```

**Why.** Keeping the UI off `mockey-core` guarantees that it stays a pure API client. Separate wire types let the domain model change without breaking the UI or scripts. One repository keeps the wire types, both applications, and the parity suite in step.

**Alternatives considered.**

- *A subdirectory of the legacy repository.* It keeps the OpenSpec flow simple and the old code next to the new. It also mixes two build systems and two generations of code in one tree and makes the library harder to publish.
- *Converting the legacy code in place.* It keeps the coupled structure and the JSP UI that the split is meant to remove.
- *Three repositories.* The wire types and parity tests span all three parts, so most changes would need coordinated releases.

### 2. Toolchain

Kotlin 2.x on a JDK 21 toolchain. Gradle with the Kotlin DSL, a version catalog, and convention plugins. kotlinx.coroutines and kotlinx.serialization throughout. Explicit API mode in `mockey-core` and `mockey-api`. One formatter and linter enforced in CI. Tests use JUnit 5 and kotlin.test. The version line starts at 3.0.0 to signal the breaking changes.

**Alternative considered.** Maven works, but convention plugins and the version catalog make a multi-module Kotlin build simpler to keep consistent in Gradle.

### 3. Engine state is one immutable snapshot with a single writer

All definitions (services, scenarios, plans, twist configurations, settings) form one immutable value. An engine instance holds the current value. Readers take the current snapshot without locking. Every change is a pure function from a snapshot to either a new snapshot or a validation failure, and changes are applied one at a time. Observers such as storage receive the old and the new snapshot.

**Why.** This removes the singletons. It makes "validate, then commit" atomic, so uniqueness rules cannot be bypassed by a race. It makes per-request mutation of shared state impossible. It lets tests and embedders create as many engines as they like.

**Alternatives considered.**

- *Mutable entities guarded by locks.* This is the legacy shape. The invariants span several entities, which makes locking error-prone.
- *An actor per aggregate.* Writes happen at human speed, so the extra machinery is not justified.

### 4. Request handling is a pipeline of small suspending steps

``` text
request
  -> extract target    absolute URL or relative path after /service/
  -> twist             enabled twist configuration, if any
  -> route             pick the service, or register one for an unknown absolute target
  -> inspect           service rules and plug-in inspectors, never blocking the response
  -> respond           proxy | static | dynamic, with error fallback
  -> delay             non-blocking
  -> record            history entry
  -> write response
```

Each step takes plain values and returns plain values. The server converts HTTP to these values at its edge. Every legacy behavior gets one obvious home and one obvious unit test.

### 5. A typed model replaces string conventions

- Response mode is an enumerated type.
- A scenario's match criterion is one of: none, text, or a rule set.
- Headers are an ordered list of name and value pairs.
- Request and response bodies in the pipeline and in history are bytes with a media type. Scenario bodies are text.
- Tags are a normalized set.
- Identifiers are opaque strings, generated once, persisted, and never reused.

The legacy forms are understood only by the importer and the shim. Those forms are `"0"`, `"1"`, and `"2"` for the mode, the literal string `"null"`, pipe-delimited header text, and integer IDs computed as the maximum plus one.

**Why opaque identifiers.** Legacy reassigns service IDs on every load and reuses numbers after a deletion. Integers allocated per instance also collide when two people add definitions on separate branches of a shared definitions repository.

**Alternative considered.** Small integers are familiar, but they are unstable and hostile to merging.

### 6. Routing picks the most specific pattern

Each mock URL and real URL is compiled into a pattern. A variable matches one or more characters that are not `/`. The whole target must match. Literal text compares case-insensitively. Among matching services the winner has the fewest variables, then the most literal characters, then the earliest creation. Requirements are in the `request-routing` spec.

**Why.** This fixes the regression at HEAD and also the over-matching reported in upstream issue 109, which the 2019 change was trying to address.

**Alternative considered.** Restoring the pre-2019 behavior (first match in ID order, greedy variables) would bring issue 109 back.

### 7. Proxy target for relative requests is the default real URL

A request whose target is an absolute URL is forwarded to that URL, as in legacy. A request whose target is a relative path is forwarded to the matched service's default real URL, with the captured variables substituted. If the service has no real URL, the response is a 502 with an explanation.

**Why.** Legacy builds `http://<first path segment>/...` for relative targets, which cannot work, and the stored default real URL has no other use.

### 8. Ports live in the core, with default adapters

`mockey-core` defines interfaces for definition storage, the upstream HTTP client, request inspectors, and the clock. It ships an in-memory store, a file store, and an upstream client built on the JDK HTTP client.

**Why.** The library is complete on its own, and its dependency footprint stays at the kotlinx libraries.

**Alternative considered.** The Ktor client in the core would match the applications' stack, but it is a heavy dependency for embedders and its proxy-authentication support differs by engine. Because the client sits behind a port, this choice is reversible. A spike on authenticated corporate proxies decides it before the adapter is finished.

### 9. Storage is a tree of JSON files, written atomically

``` text
<definitions-root>/
  mockey.json                 format version, plans, twists, enabled twist,
                              upstream proxy URL and flag, universal error
                              scenario, default plan
  services/
    <slug>/
      service.json            service attributes, rule set, response schema
      scenarios/
        <slug>.json           scenario attributes, match criterion, headers
        <slug>.body           response body text in UTF-8, exactly as entered
```

Rules:

- Identity comes from the `id` field inside each file, never from a file or directory name. Slugs exist for readability and receive a suffix on collision.
- Only files whose content changed are written. Each write goes to a temporary file followed by an atomic rename.
- Key order and formatting are deterministic, so unchanged definitions produce identical bytes.
- Not stored: history, usage timestamps, upstream session cookies, proxy credentials, the tag filter, and the read-only flag.

**Why.** Definition sets are shared through version control today (`mockeyDefinitionsRepoHome`), so files must be reviewable and mergeable. Body files keep JSON and XML payloads readable in diffs and exact on disk.

**Alternatives considered.**

- *Legacy XML as the native format.* Its shape follows reflection-based parsing, it is stringly typed, it depends on XInclude, and its file names derive from display names and can collide.
- *An embedded database.* It cannot be reviewed or merged in version control.
- *A single JSON file.* Every edit rewrites everything, and diffs are poor.

### 10. Two listeners: mock traffic and admin traffic

| Listener | Default bind | Serves |
|----------|--------------|--------|
| Mock | `0.0.0.0:8080` | `/service/*`, `/status`, and the legacy shim when enabled |
| Admin | `127.0.0.1:8081` | `/api/v1/*` |

An admin token is optional on loopback. Binding the admin listener to any other address requires a token unless the operator waives that explicitly.

**Why.** Mock traffic often has to be reachable from other hosts and devices. Admin traffic should not be, because the admin API can rewrite definitions and make the server send arbitrary outbound requests.

**Alternatives considered.**

- *One port with a path prefix.* It is the simplest option, but admin exposure then follows mock exposure, which is the legacy situation.
- *Mutual TLS.* It is more than a test tool needs.

### 11. The admin API is resource-oriented JSON with a published description

The API lives under `/api/v1`. Errors use the problem-details format with one entry per invalid field. The server serves an OpenAPI document, and a test checks it against the registered routes. Wire types live in `mockey-api`.

Indicative resource map (the OpenAPI document is the contract):

| Area | Resources |
|------|-----------|
| System | `system` (status, read-only mode, tag filter), `system/reset`, `system/upstream-session`, `diagnostics`, `search` |
| Services | `services`, `services/{id}`, `.../duplicate`, `.../merge`, `.../schema-report`, `services/real-urls/derive` |
| Scenarios | `services/{id}/scenarios`, `services/{id}/scenarios/{scenarioId}` |
| Plans | `plans`, `plans/{id}`, `plans/{id}/apply` |
| Routing | `twists`, `twists/{id}`, `tags`, `tags/{tag}` |
| Proxying | `upstream-proxy` |
| History | `history`, `history/{id}`, `.../replay`, `.../scenario`, `stats` |
| Extensions | `inspectors` |
| Definitions | `definitions/export`, `definitions/import` |

**Alternatives considered.**

- *The legacy query-parameter endpoints as the primary API.* They have no types and no error model, and they change state on GET.
- *GraphQL or an RPC framework.* Neither adds anything this API needs.

### 12. The UI is a stateless server-rendered application

- Pages are rendered with kotlinx.html. A form posts to the UI, the UI calls the admin API, and the browser is redirected to the result.
- Filters and paging live in the query string. There is no server-side session.
- htmx is a vendored static file. When a request carries the htmx marker header, the same handler returns a fragment instead of a full page.
- No asset is loaded from another origin.
- State-changing forms carry an anti-forgery token. User-supplied content is always rendered as text.
- Tests drive the UI over plain HTTP and parse the HTML. The no-JavaScript path needs no browser automation.

**Alternatives considered during exploration.** Wicket, Vaadin with Karibu-DSL, Compose for Web, and template engines such as Pebble or Thymeleaf.

### 13. One rule evaluator for inspection and dynamic matching

The JSON rule format is unchanged, so imported definitions work as they are. Rule sets are parsed and their regular expressions compiled when a definition is saved or loaded, not on every request. The source text is kept verbatim for editing and storage.

### 14. Inspector plug-ins use the JDK service loader

The inspector interface lives in `mockey-core`. Implementations are discovered with `ServiceLoader` and identified by a declared name. Legacy class names are kept as names on import. A service that names an inspector that is not installed produces a diagnostic, not a failure.

**Alternative considered.** Scanning the classpath for annotated packages, as legacy does, is fragile and slow.

### 15. Legacy import is its own module

`mockey-legacy` reads both the legacy file tree and the single-file export. XML parsing has DTDs and external entities disabled. The importer resolves XInclude itself and only for files inside the import root. Its output is domain values plus a report. The source is never modified. The server's command line, the admin API (single documents), and the parity suite (fixtures) all use it.

### 16. Parity suite and deviation register

- The suite in `mockey-parity-tests` is black-box. A case is data: a legacy XML fixture, a request, and an expected response.
- Expected responses are recorded once from the legacy build at a pinned commit and stored in the new repository. Day-to-day runs replay them against the new server, so CI does not need a running legacy instance.
- The same XML fixture configures both systems. Legacy loads it through its initialization endpoint, and the new server loads it through the importer. Parity runs therefore exercise the importer too. Fixtures are single-file documents, and legacy always runs in read-only mode while recording (see DEV-30).
- Every recorded case is reviewed and labeled either `same` or with a deviation ID. A deviation case states both the legacy response and the new response.
- The reference is HEAD (`88e31ed`). Where HEAD is broken, the intent is taken from the code history and upstream issues.
- The legacy test classes are ported as the first unit tests of `mockey-core`. The one exception is `TestPackageInfoPeerClassFinder`, whose subject, classpath scanning, no longer exists.
- A traceability table maps every legacy servlet and documented parameter to a spec requirement, a parity case, or a non-goal.

### 17. The legacy shim is a thin, optional server module

It is off by default and mounted on the mock listener. It answers `/config/service`, `/plan/setup`, `/definitions`, and `/home` (only `action=init` and `action=deleteAllServices`), with legacy parameter names and the legacy `{"result": {...}}` envelope. It calls the same core operations as the admin API.

### 18. Configuration, logging, packaging

Command-line flags with environment-variable fallbacks, parsed with Clikt. Logs go to standard output. Libraries log through the SLF4J API, and the two applications bind Logback. Each application ships as a distribution with start scripts and as a runnable jar.

Initial defaults, all configurable:

| Setting | Default | Legacy |
|---------|---------|--------|
| Read-only mode | on | on |
| Definitions location | `mockey-definitions` under the working directory | the working directory |
| History entries kept | 1,000 | unbounded |
| Body bytes captured per history entry | 1 MiB | unbounded |
| Upstream connect and response timeouts | 10 s and 60 s | none set |
| Maximum request body | 50 MiB | form limit raised to 500 MB |
| Forward and register unknown absolute targets | on | always |
| Legacy shim | off | not applicable |

### 19. HTTP stack: both applications are built on Ktor

`mockey-server` and `mockey-ui` are Ktor 3.x server applications, as the proposal states. `mockey-core` gains no Ktor dependency, and decision 8 is unchanged.

- The mock listener and the admin listener of decision 10 are separate Ktor servers in one process that share one `mockey-core` engine instance. The mock server installs no routing and no plugins. The admin server uses routing and plugins.
- The mock endpoint is one handler that reads the raw request URI and passes it to the core. It does not use Ktor routing, which drops empty path segments and answers 400 to a malformed percent escape before a handler runs.
- Listeners are told apart by socket, never by header. Ktor's `port` route selector matches the `Host` header and must not guard admin routes. Where one server has several connectors, `localPort` is the selector to use.
- The HTTP engine is chosen by the spike in task 11.0. Netty is the default candidate, and CIO is the lighter one.

**Why.** The request pipeline is suspending (decision 4), and Ktor handlers are suspending functions, so no bridge between callbacks and coroutines has to be written. Ktor has first-party modules for choices this design already made: kotlinx.serialization, kotlinx.html, htmx request headers, error mapping for problem documents, bearer tokens, and a request body limit. On Netty and CIO the request target reaches the application as the client sent it. Two servers keep admin plugins away from mock traffic and work with ephemeral ports in tests.

The routing, `port`, and URI behaviors named here were read in the Ktor and Jetty sources. Task 11.0 confirms them over real HTTP.

**Cost.** Ktor brings more dependencies than Jetty alone and has had three major versions since 2018. Both applications are thin edges over `mockey-core` and `mockey-api`, which limits the work of an upgrade.

**Alternatives considered.**

- *Jetty 12.1 without a framework.* It has the smallest dependency footprint and a very stable API. Routing, JSON handling, error mapping, and form handling would be written by hand, and its callback API needs a bridge to coroutines. Its default URI compliance answers 400 to a path that contains `//`, which every absolute target has, until the server is configured to allow it.
- *Ktor on its Jetty engine.* It keeps the Ktor API on Jetty 12.1 and inherits that URI default. It stays available if a Jetty runtime is ever required.
- *One Ktor server with two connectors.* It shares one set of threads. Every admin route then depends on a correct `localPort` guard, application-wide plugins see mock traffic, and the guard needs the port number before an ephemeral port is assigned.

### Deviation register (initial)

Evidence: **run** means verified by executing legacy code. **read** means taken from the source and still to be confirmed by a recorded parity case.

This table is the register as the design started it. Task 2.9 confirmed the entries against legacy, corrected their wording where legacy behaved otherwise, and added DEV-31 to DEV-50 for the differences that the review of the parity recordings found. `docs/deviations.md` holds the register in its current form, with the evidence for each entry.

| ID | Legacy behavior | New behavior | Evidence |
|----|-----------------|--------------|----------|
| DEV-01 | A template variable matches only literal `{...}` text (since `f0d83a4`) | A variable matches one path segment and the whole target must match | run |
| DEV-02 | The first matching service in ID order wins | The most specific pattern wins | read |
| DEV-03 | The target is parsed from the first `http` substring, and otherwise the first path segment becomes a host. An unknown relative target registers a service for that host and proxies to it | A target is absolute only if it starts with `http://` or `https://`. An unknown relative target gets a 404 with an explanation | read |
| DEV-04 | A matching service that lacks the filter tag causes a second service to be registered for the same URL | No second service. The request is handled as unmatched | read |
| DEV-05 | Twisting checks the origination as literal text but replaces it as a regular expression | Literal replacement | read |
| DEV-06 | A platform line separator is appended to every static or dynamic response body | The body is sent exactly as stored | read |
| DEV-07 | With no matching scenario and no error scenario, an explanation is sent and the status is set to the invalid value -1, so the client sees whatever the container makes of it | 404 with an explanation | read |
| DEV-08 | A scenario response is encoded as UTF-8 only if some response header value contains `utf-8` | UTF-8 unless the scenario's `Content-Type` declares another charset | read |
| DEV-09 | New scenarios default to the response headers `Accept-Language: en-US` and `Accept: text/plain` | New scenarios have no default headers | read |
| DEV-10 | Proxy mode sends GET as GET and every other method as POST | The method is forwarded unchanged | read |
| DEV-11 | Proxied bodies are decoded and re-encoded as text, which corrupts binary content and some character sets | Bodies are relayed byte for byte | read |
| DEV-12 | Proxy mode for a relative target forwards to a URL built from the path | Forwards to the service's default real URL | read |
| DEV-13 | On upstream failure only the error scenario's body is used, and the status is left unset | The error scenario's status, headers, and body are used. Without one, 502 with an explanation | read |
| DEV-14 | Upstream `Set-Cookie` headers are relayed as bare `name=value` | Relayed unchanged | read |
| DEV-15 | A header or parameter rule is skipped when its key is absent from the request. A required rule therefore does not fail, and an optional rule is not counted | A required rule fails. An optional rule passes and counts toward the score | read |
| DEV-16 | Header rules cannot see `Host`, `Cookie`, `Content-Length`, or `Accept-Encoding` | Rules see every received header | read |
| DEV-17 | Parameter rules see the query string but apparently not form fields, because the body is consumed first | Rules see query and form parameters | read |
| DEV-18 | Saving any scenario without the universal-error flag clears the universal error scenario | Only an explicit change alters it | read |
| DEV-19 | Duplicating a service copies its name and mock URL and drops the default scenario | The copy gets a distinct name and mock URL and keeps its designations | read |
| DEV-20 | Integer IDs, reused after deletion. Service IDs are reassigned on every load | Opaque, persisted identifiers that are never reused | read |
| DEV-21 | Every save deletes and rewrites the whole definitions directory. File names derived from display names can collide | Atomic per-file writes and collision-free names | read |
| DEV-22 | Per-service and per-plan transient flags suppress only the save trigger | Removed. Only the global read-only mode remains. The shim accepts and ignores the parameter | read |
| DEV-23 | Draft-03 JSON Schemas are accepted | Draft-04 and later are required. A schema in an unsupported dialect is reported as unsupported | read |
| DEV-24 | History grows without limit | Bounded and configurable | read |
| DEV-25 | The server opens a browser at startup unless told not to | It never opens a browser | read |
| DEV-26 | Admin operations are unauthenticated, share the mock port, and send `Access-Control-Allow-Origin: *` | Separate admin listener, loopback by default, optional token. The CORS header appears only on mock responses | read |
| DEV-27 | At runtime, initialization and upload can read any server-local path or fetch any URL | Runtime import accepts document content only. File and URL sources are startup options. The shim limits `file` to the definitions root | read |
| DEV-28 | `/status` reports the definitions path, and the start time as `MM/dd/yyyy \| hh:mm:ss` | `/status` reports the status and an ISO 8601 start time only. The path is available through the admin API | read |
| DEV-29 | Merging services whose names differ copies nothing, because the merge routine acts only on equal names, and then deletes the source services | Scenarios, tags, and real URLs are merged first. Sources are deleted only when no scenario was skipped | read |
| DEV-30 | Starting in writable mode with an initialization document deletes the stored definitions before the document is loaded, including the service files the document refers to | Refused unless the definitions location is empty | read |

**Preserved on purpose.** These look odd but existing definitions and clients depend on them:

- The `/service/` path convention, including full URLs embedded in the path.
- Dynamic-mode scoring. A text criterion scores its trimmed length, a rule criterion scores its number of satisfied rules, the highest score wins, and ties go to the scenario whose name sorts first.
- Rule groups combine with AND inside a group and OR across groups.
- A service with no scenarios behaves as a proxy.
- An unknown absolute target is registered as a proxy service named after its last path segment.
- One shared upstream cookie jar. Client `Cookie` headers are not forwarded.
- `Accept-Encoding` and conditional request headers are not forwarded.
- Read-only mode is the default.
- Mock responses carry `Access-Control-Allow-Origin: *` and cache-suppression headers by default.
- History filtering uses AND, `!` for negation, and case-sensitive containment.
- Plans refer to services and scenarios by name.
- Import merges by service name and reports additions and conflicts.

## Risks / Trade-offs

- [Parity gaps that the suite does not cover, because legacy has few tests] → The traceability table in decision 16 forces every legacy servlet and documented parameter to be accounted for.
- [The legacy build may not compile or run on a current JDK, since it uses Ant, JSP precompilation, and a 2009 Jetty runner] → The first task is a spike. The fallback is a container with an old JDK. Recordings are stored, so legacy is needed only while recording.
- [Recorded responses could enshrine legacy defects as expectations] → Every recorded case is reviewed and labeled before it becomes an expectation.
- [Authenticated corporate proxies with the JDK HTTP client. The JDK disables Basic authentication for HTTPS tunneling by default] → A spike runs before the adapter is finished. The upstream client is behind a port and can be swapped.
- [Paths with an embedded URL, such as `/service/http://host/x`, can be altered by path normalization in an HTTP engine or an intermediary] → The server reads the raw request target, and server tests cover double slashes and encoded characters over real HTTP.
- [Fixed defects can break client test suites that had adapted to them] → The register is published with the release notes, and parity cases show the exact before and after. There are no per-defect compatibility switches, because they would multiply the test matrix.
- [Two processes are harder to start than `java -jar Mockey.jar`] → Start scripts and a documented two-command quick start. This trade-off follows from the decision to separate the UI.
- [Scope: thirteen capabilities] → Phases with exit criteria. The server is usable through the admin API before the UI exists, and the UI and shim are independent final phases.
- [The mock listener is an open forward proxy by design] → The bind address is configurable, handling of unknown targets can be turned off, and the documentation says so plainly.
- [With the default settings, a request that the mock endpoint forwards reaches the server's own admin listener from the loopback address, where no token is asked for] → The server keeps such a request from the admin API (`admin-api`: The admin API is not reachable through the mock endpoint). It marks every request that it forwards to the port of its admin listener with a header, and the admin listener refuses a request with that header. `docs/decisions/forwarding-to-the-admin-listener.md` says why, and what the mark does not cover: a port forward or a reverse proxy in front of the admin listener, and other programs on the server's machine that trust the loopback address.
- [A UI bound to a non-loopback address gives every visitor full admin power, because there are no user accounts] → The UI binds to loopback by default. The documentation tells operators to put an authenticating reverse proxy in front of a shared UI.
- [Opaque identifiers break scripts that hard-code numeric IDs] → The shim and the admin API accept names. Legacy IDs were already unstable across restarts.
- [Import must resolve references that legacy stores as numeric IDs] → The importer resolves them within the imported document, and fixture tests cover each reference type.
- [JSON Schema dialect coverage depends on the validator library] → A spike selects the library. DEV-23 states the supported floor.

## Migration Plan

**Phases and exit criteria.**

| Phase | Content | Exit criterion |
|-------|---------|----------------|
| 0. Baseline | Bootstrap the repository, move this change into it, get legacy running, record parity cases | Recorded cases are reviewed and labeled. The register is seeded |
| 1. Core | Model, routing, rules, responses, proxying, plans, history, storage | Unit tests pass, including the ported legacy tests |
| 2. Import | Legacy XML tree and single-file import | Fixtures, including the legacy `web/sample.xml`, import with a clean report |
| 3. Server | Mock endpoint, then the admin API | The parity suite passes against the new server. The OpenAPI check passes |
| 4. UI | All pages, without JavaScript first, then the htmx enhancements | Every UI flow passes with scripts disabled |
| 5. Shim | Legacy automation endpoints | Shim tests pass with legacy parameter names and envelopes |
| 6. Release | Integration checks and the 3.0.0 release candidate. Documentation and packaging are delivered within the earlier phases | The quick start works from a clean machine |

**Cutover for an existing installation.**

1. Export definitions from the running legacy instance, or point the importer at its definitions directory.
2. Start the new server on a different port with the imported definitions, and read the import report.
3. Run the client test suites against the new server. Compare failures with the deviation register.
4. Point clients at the new server. Enable the shim if automation scripts still use legacy endpoints.

**Rollback.** Point clients back at the legacy instance. Import never modifies legacy files, so the legacy installation stays intact throughout.

## Open Questions

These can be answered later without changing the specs, the approach, or the task breakdown.

- Where the repository is hosted. The group `io.github.dnedrow` anticipates the GitHub account `dnedrow`, but no remote exists yet.
- Whether `mockey-core` is published to an artifact repository, and which one.
- Confirmation of the license by whoever owns open-source policy for the project. GPL-2.0-or-later is the working assumption.
- Whether the Image Depot returns in a follow-up change.
- Whether container images are published alongside the distributions.
