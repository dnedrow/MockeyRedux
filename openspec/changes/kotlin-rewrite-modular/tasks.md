# Tasks

Tasks 1.1 and 1.2 create the new repository and move this change into it. Every later task is carried out in the new repository. Deviation IDs (DEV-nn) refer to the register in design.md.

## 1. Repository bootstrap

- [x] 1.1 Agree the repository name, location, Maven group, and base package with the project owner, then create the repository with the Gradle wrapper, `settings.gradle.kts`, a version catalog, and `build-logic` convention plugins for Kotlin 2.x on a JDK 21 toolchain. Verify that `./gradlew help` succeeds on a clean checkout.
- [x] 1.2 Initialize OpenSpec in the new repository and move the `kotlin-rewrite-modular` change directory into it. Verify that `openspec validate kotlin-rewrite-modular --strict` passes there and that `openspec status` shows proposal, design, specs, and tasks as done.
- [x] 1.3 Add the modules `mockey-core`, `mockey-api`, `mockey-legacy`, `mockey-server`, `mockey-ui`, and `mockey-parity-tests` with the dependency rules of design decision 1, and a build check that enforces them. Verify that `./gradlew build` passes and that the check fails when `mockey-ui` is given a dependency on `mockey-core`.
- [x] 1.4 Enable explicit API mode in `mockey-core` and `mockey-api`, and add the formatter and linter plus the JUnit 5 and kotlin.test conventions. Verify that `./gradlew check` runs the linter and one placeholder test in every module.
- [x] 1.5 Add a CI workflow that runs `./gradlew check` on every push. Verify with one green pipeline run.
- [x] 1.6 Add the GPL-2.0-or-later license file, a README that states the project's origin and status, and `docs/deviations.md` seeded with DEV-01 to DEV-30 from design.md. Verify that the three files exist and that the register lists all thirty entries.

## 2. Legacy baseline and parity recordings

- [x] 2.1 Spike: build legacy Mockey at commit `88e31ed` and start it headless in read-only mode, first on a current JDK and, if that fails, in a container with an older JDK. Deliver the working commands in `docs/parity/running-legacy.md`. Verify that `GET /status` on the legacy instance returns `running`.
- [x] 2.2 Build the parity harness in `mockey-parity-tests`: a case format (legacy XML fixture, request, expected response, label), a recorder that runs cases against a base URL and stores the responses, and a replayer that compares. Verify by recording one trivial case from legacy and replaying it against legacy with no difference.
- [x] 2.3 Add a stub upstream and a stub outbound proxy to the harness for proxy cases. Verify with a harness self-test that both stubs record the requests they receive.
- [x] 2.4 Write fixtures and cases for routing: absolute and relative targets, URL templates, matches through real URLs, competing services, the tag filter, unknown targets, and twisting. Record them from legacy. Verify that a recording is stored for every case.
- [x] 2.5 Write and record cases for static and dynamic responses: text criteria, rule criteria, method restrictions, score ties, error fallbacks, status, headers, body bytes, charset, and delays. Verify that a recording is stored for every case.
- [x] 2.6 Write and record cases for proxy mode against the stubs: every HTTP method, header forwarding, text and binary bodies, cookies and the sticky session, redirects, the outbound proxy, and upstream failures. Verify that a recording is stored for every case.
- [x] 2.7 Write and record cases for request rules and inspection, reading inspection results where legacy exposes them as JSON, including absent keys, wildcard keys, and form parameters. Verify that a recording is stored for every case.
- [x] 2.8 Write and record cases for the legacy automation endpoints `/config/service`, `/plan/setup`, `/definitions`, `/home`, and `/status`, covering parameters and result shapes. Verify that a recording is stored for every case.
- [x] 2.9 Review every recorded case and label it `same` or with a deviation ID, adding the expected new response to each deviation case. Update `docs/deviations.md`: confirm or correct each entry marked "read", and add entries for differences found. Verify that the harness check that rejects unlabeled cases passes.
- [x] 2.10 Create `docs/parity/traceability.md`, which maps every legacy servlet mapping and documented parameter to a spec requirement, a parity case, or a non-goal. Verify with a script that every servlet mapping in the legacy `web.xml` appears in the table.

## 3. Core: definitions (spec: service-definitions)

- [x] 3.1 Implement the model types for services, scenarios, plans, twist configurations, settings, identifiers, tags, headers, and match criteria, with serialization. Verify with unit tests for defaults, tag normalization, and identifier stability.
- [x] 3.2 Implement the engine state holder of design decision 3: an immutable snapshot, single-writer changes that return a new snapshot or a validation failure, and observers. Verify with tests that concurrent changes never break an invariant and that readers never see a partial change.
- [x] 3.3 Implement service create, update, and delete with name validation, the derived name, mock URL uniqueness, real URL ownership, and clearing of the universal error designation on delete. Verify with unit tests for each scenario of those requirements.
- [x] 3.4 Implement scenario create, update, and delete with the generated name, name length validation, default scenario integrity, and error and universal error designation (DEV-18). Verify with unit tests for each scenario of those requirements, including the ported legacy `ScenarioTest` cases.
- [x] 3.5 Implement service duplication (DEV-19), service merge (DEV-29), and bulk derivation of real URLs. Verify with unit tests for each scenario of those requirements.
- [x] 3.6 Implement tag listing and removal across definitions, definition diagnostics, and search over services, scenarios, and plans. Verify with unit tests, including the ported legacy `ConflictInfoTest` and `SearchResultBuilderTest` cases.
- [x] 3.7 Spike a JSON Schema validator against the draft-04 floor of DEV-23, record the choice in `docs/decisions/json-schema.md`, and implement the response schema report. Verify with unit tests for a conforming body, a non-conforming body, and an unsupported dialect.
- [x] 3.8 Port the remaining legacy model tests (`ServiceTest`, `TestInMemoryMockeyStorage`) to the new model. Verify that each passes or is linked to a deviation ID in the traceability table.

## 4. Core: routing (spec: request-routing)

- [x] 4.1 Implement target extraction and normalization: absolute and relative targets, query separation, percent-decoding, slashes, letter case, and default ports. Verify with unit tests, including the ported legacy `UrlTest` and `MockServiceBeanTest` cases, which both test the legacy `Url` class. The checks of `MockServiceBeanTest` that read a text without a scheme as a host are linked to DEV-03.
- [x] 4.2 Implement the URL pattern compiler and matcher with single-segment variables, whole-target matching, and captured values in request case (DEV-01). Verify with unit tests, including the ported legacy `UriTemplateTest` cases.
- [x] 4.3 Implement candidate lookup through mock URL patterns and real URLs, and selection of the most specific service (DEV-02). Verify with unit tests, including the services from upstream issue 109.
- [x] 4.4 Implement the tag filter and the handling of unknown targets: forwarding and registration for absolute targets, refusal for relative targets, and the setting that disables handling (DEV-03, DEV-04). Verify with unit tests for each scenario of those requirements.
- [x] 4.5 Implement twist configurations with at most one enabled, and literal twisting before lookup (DEV-05). Verify with unit tests for each scenario of those requirements.

## 5. Core: request rules and inspection (spec: request-inspection)

- [x] 5.1 Implement the rule-set parser and the well-formedness checks for groups, rule types, keys, and regular expressions, keeping the source text. Apply the checks when a service or a scenario is saved: reject one whose rule set is not well formed, with an error on the rule set or the match criterion that says what is wrong (`service-definitions`: Rule sets are validated on save). Give the checks to the definition diagnostics as well. Verify with unit tests for well-formed and malformed rule sets, for a service and a scenario that are saved with a malformed rule set and stay unchanged, and for a diagnostic about a stored rule set that is malformed.
- [x] 5.2 Implement rule evaluation: group inputs, keyed and wildcard rules, the three rule types, group and rule-set satisfaction, and the satisfied-rule count (DEV-15, DEV-16, DEV-17). Verify with unit tests for each scenario of those requirements.
- [x] 5.3 Implement service-level inspection messages and the handling of stored rule sets that cannot be read. Verify with unit tests that messages name the group, key, requirement, and description, and that the response is unaffected.
- [x] 5.4 Implement the inspector interface, discovery at startup, global and named inspectors, failure isolation, and listing. Give the names of the installed inspectors to the definition diagnostics. Verify with tests that use a sample inspector in the test sources, including the intent of the legacy `TestPluginStore`, and record the legacy `TestPackageInfoPeerClassFinder` as obsolete in the traceability table.
- [x] 5.5 Write `docs/rules.md` and `docs/inspectors.md`, including how to port a legacy `IRequestInspector`. Verify that the documented sample inspector compiles and is discovered in a test.

## 6. Core: scenario responses (spec: scenario-responses)

- [x] 6.1 Implement static mode and response assembly: status, ordered headers, exact body (DEV-06), and encoding (DEV-08). Verify with unit tests that compare response bytes.
- [x] 6.2 Implement dynamic mode: method eligibility, the evaluation text, text and rule scores, best-match selection, and tie-breaking. Verify with unit tests for each scenario of those requirements.
- [x] 6.3 Implement the fallback chain: service error scenario, universal error scenario, and the built-in 404 (DEV-07). Verify with unit tests for each step of the chain.
- [x] 6.4 Implement the non-blocking response delay with the scenario override. Verify with virtual-time tests, including one that shows a delayed request does not hold up another.

## 7. Core: upstream proxying (spec: upstream-proxying)

- [x] 7.1 Spike: forward `http` and `https` requests through an authenticated outbound proxy with the JDK HTTP client, and decide the default adapter (design decision 8). Record the result in `docs/decisions/upstream-client.md`. Verify with a runnable spike test against the stub proxy.
- [x] 7.2 Implement the upstream client port and the default adapter: unchanged method and body (DEV-10, DEV-11), request header filtering, and relay of status, headers, and body including `Set-Cookie` (DEV-14). Verify with tests against the stub upstream that compare bytes.
- [x] 7.3 Implement forward-target resolution: absolute targets, relative targets through the default real URL with substitution, and the 502 cases (DEV-12). Verify with unit tests for each scenario of those requirements.
- [x] 7.4 Implement the shared upstream cookie store with its reset operation, and redirect handling. Verify with tests against the stub upstream, including two clients that share a cookie.
- [x] 7.5 Implement outbound proxy settings with in-memory credentials, certificate verification, and connect and response timeouts. Verify with tests against the stub proxy and a stub upstream with an untrusted certificate, including the ported legacy `ProxyServerModelTest` cases.
- [x] 7.6 Implement upstream failure handling: error scenario fallback, the built-in 502 JSON response (DEV-13), and the service delay on proxied responses. Verify with unit tests for each scenario of those requirements.

## 8. Core: plans and history (specs: service-plans, request-history)

- [x] 8.1 Implement plans: save from services, update by identifier or name, delete, and the default designation. Verify with unit tests for each scenario of those requirements.
- [x] 8.2 Implement plan application with its report, propagation of service and scenario renames, and application of the default plan after loading. Verify with unit tests for each scenario of those requirements.
- [x] 8.3 Implement the history store: the entry model with its outcome, bounded retention (DEV-24), the body capture limit, and the target before twisting. Verify with unit tests for each scenario of those requirements.
- [x] 8.4 Implement history listing, token filtering, deletion, and flagging. Verify with unit tests, including negated tokens and case sensitivity.
- [ ] 8.5 Implement replay and saving an entry as a scenario. Verify with unit tests, including replay under changed definitions and refusal of a cut body.
- [ ] 8.6 Implement per-service statistics as JSON and CSV, and last-used times with their clear operation. Verify with unit tests, including the ported legacy `ServiceStatHelperTest` cases.

## 9. Core: pipeline, storage, and library surface (specs: definition-storage, embedded-engine)

- [ ] 9.1 Assemble the request pipeline of design decision 4 into the engine's request-handling operation. Hold the tag filter and the setting for unknown targets in the engine, keep one routing table for each value of the definitions, and apply the registration of an unknown absolute target (task 4.4) before the request is forwarded. Verify with in-process tests that run a request through twisting, routing, inspection, response, delay, and history, including a request under a tag filter and two requests for one unknown absolute target.
- [ ] 9.2 Implement the storage port, the in-memory store, read-only mode with runtime switching, and saving before acknowledging with rollback on failure. Verify with unit tests, including a store that fails on write.
- [ ] 9.3 Implement the file store: layout, deterministic serialization, atomic writes, rewriting only changed files, collision-free names, and identity from content (DEV-21). Verify with tests for a byte-identical load and save, an interrupted write, similar names, and a renamed directory.
- [ ] 9.4 Implement loading at startup, the format version check, reload, and reset. Verify with tests for an empty location, a corrupt file, a newer format version, and a failed reload that leaves memory unchanged.
- [ ] 9.5 Implement export and import of definition documents with the merge rules, the report, and tagging. Verify with unit tests, including an export and import round trip into an empty engine.
- [ ] 9.6 Implement engine creation with replaceable storage, upstream client, inspectors, and clock, plus engine isolation and closing. Verify with tests for two engines in one process, a substitute upstream client, a fixed clock, and a build check that `mockey-core` has no HTTP server dependency.
- [ ] 9.7 Write `docs/library.md` and a sample project under `samples/embedded` that uses `mockey-core` from the local Maven repository. Verify that the sample builds and its test passes in CI.

## 10. Legacy import (spec: legacy-compatibility)

- [ ] 10.1 Implement hardened XML reading and an inclusion resolver confined to the import root. Verify with tests for an external entity and for an inclusion that points outside the root.
- [ ] 10.2 Implement mapping of single-file export documents: service and scenario attributes, header text splitting, criterion types, and placeholders. Verify with tests that import a copy of the legacy `web/sample.xml`, including the ported legacy `MockeyXmlFileManagerTest` cases.
- [ ] 10.3 Implement import of the legacy directory layout: main file, service references, scenario inclusions, and response text files. Verify with tests that import a directory written by the legacy instance from task 2.1.
- [ ] 10.4 Implement resolution of legacy numeric references and reporting of dangling ones. Verify with unit tests for the default scenario, error scenario, universal error scenario, enabled twist configuration, and default plan.
- [ ] 10.5 Implement the import report, continuation past unreadable files, preservation of unreadable rule sets, and merging by the general import rules. Verify with tests, including one that checks the source directory is byte-identical after import.

## 11. Server: runtime and mock endpoint (spec: server-runtime)

- [ ] 11.0 Spike: choose the Ktor HTTP engine and confirm the assumptions of design decision 19. On Netty and on CIO, send to a single handler over real HTTP the targets of the recorded `routing-*` cases, targets with `%2F` and `%25`, a target longer than 4,096 characters, and, over a raw socket, a malformed percent escape and unencoded braces. Also check that a forged `Host` header reaches a route guarded by `port` but not one guarded by `localPort`, and that two servers in one process start on ephemeral ports. Record the choice and any engine settings it needs in `docs/decisions/http-stack.md`. Verify with a runnable spike test that asserts, for the chosen engine, that each target the handler received equals the target that was sent.
- [ ] 11.1 Implement the server's settings from options and environment variables, help and version output, and logging without secrets. Verify with tests for precedence, exit status, and a log scan for the token and proxy password.
- [ ] 11.2 Implement the two listeners with their defaults, the rule that requires an admin token beyond loopback, and the exit on a bind failure. Verify with tests for each scenario of those requirements.
- [ ] 11.3 Implement the mock endpoint: conversion of HTTP requests to engine requests from the raw request target, all methods, and the request size limit. Verify with tests over real HTTP for double slashes, encoded characters, and a 413 response.
- [ ] 11.4 Implement response writing with the default headers, `/status`, readiness ordering, and the absence of cross-origin headers on the admin listener (DEV-26, DEV-28). Verify with tests over real HTTP for each scenario of those requirements.
- [ ] 11.5 Implement the startup flow: load definitions, apply initial definitions from a file or URL, protect stored definitions in writable mode (DEV-30), apply the default plan, and set the tag filter. Verify with tests for each source type and for the refusal.
- [ ] 11.6 Implement the legacy import command. Verify with a test that runs it on a legacy directory and checks the definitions location, the printed report, and exit status 0.
- [ ] 11.7 Implement graceful shutdown. Verify with a test that sends a termination signal during a save and finds the save complete.
- [ ] 11.8 Run the parity suite against the new server, loading fixtures through the importer, and fix differences. Verify that every case labeled `same` passes and every deviation case returns its stated new response.
- [ ] 11.9 Package the server as a distribution with start scripts and as a runnable jar. Verify by starting it from the built distribution on a plain JDK 21 and receiving `running` from `/status`.
- [ ] 11.10 Write `docs/server.md` (settings, listeners, and the security notes on the open forward proxy and admin exposure) and `docs/migrating-from-2x.md` (import, deviations, removed features). Verify that every command in both documents runs as written against fixture data.

## 12. Server: admin API (spec: admin-api)

- [ ] 12.1 Define the wire types in `mockey-api` and their mapping to and from the model. Verify with serialization tests that pin the JSON shape of every type.
- [ ] 12.2 Implement the API foundation: the `/api/v1` prefix on the admin listener only, the token check, problem documents for 400, 404, and 422, and side-effect-free reads. Verify with tests for each scenario of those requirements.
- [ ] 12.3 Implement the service and scenario operations: list with filters, read, create, partial update, delete, duplicate, merge, bulk derivation, and the error designations. Verify with API tests for each operation.
- [ ] 12.4 Implement the plan operations and the routing settings operations for twist configurations, the tag filter, and tags. Verify with API tests for each operation.
- [ ] 12.5 Implement the outbound proxy and upstream session operations and the inspector list. Verify with API tests, including one that shows the password is never returned.
- [ ] 12.6 Implement the history operations: list with tokens, service, position, and maximum, read, delete, flag, replay, save as scenario, and statistics as JSON and CSV. Verify with API tests for each operation.
- [ ] 12.7 Implement the system and definition operations: status, read-only switch, reload, reset, clearing last-used times, export, import of both document types with tags, diagnostics, schema report, and search. Verify with API tests for each operation.
- [ ] 12.8 Add tests for immediate effect and concurrency: a change is visible to the next mock request, and two simultaneous conflicting creations yield exactly one success. Verify that both tests pass repeatedly in CI.
- [ ] 12.9 Serve the OpenAPI document and add a test that compares it with the registered routes in both directions. Write `docs/admin-api.md` with request examples. Verify that the comparison test passes and that the documented examples run in a test script.

## 13. Web UI (spec: admin-ui)

- [ ] 13.1 Implement the UI application skeleton: settings for the admin API address, token, and bind address, the typed admin API client, the page layout, and self-hosted assets. Verify with tests against a fake admin API that the token never reaches the browser and that the default bind is loopback.
- [ ] 13.2 Implement the pages for an unreachable server, a rejected token, and an incompatible server. Verify with tests for each case and for recovery when the server returns.
- [ ] 13.3 Implement the form infrastructure: the anti-forgery token, redirect after success, result and report display, validation feedback with preserved input, and confirmation pages. Verify with tests, including a forged post and a reload after saving.
- [ ] 13.4 Implement text-only rendering of stored content across the page helpers. Verify with tests that render names, headers, and bodies containing markup and find them escaped.
- [ ] 13.5 Implement the services overview with the tag filter in the address, diagnostics markers, the callable mock URL, and the mode and default-scenario switches. Verify with HTML tests for each element and action.
- [ ] 13.6 Implement the service editor, including real URLs, rule set, inspector, schema, tags, duplicate, and delete. Verify with tests that submit each form and check the admin API calls.
- [ ] 13.7 Implement the scenario editor, including status, headers, criterion, method, delay, tags, and the error designations. Verify with tests that submit each form and check the admin API calls.
- [ ] 13.8 Implement the pages for plans, twist configurations, the tag filter, tags, and outbound proxy settings with the session reset. Verify with tests that submit each form and check the admin API calls.
- [ ] 13.9 Implement the history pages: tokens in the address, entry detail, delete, flag, replay with edits, save as scenario, and automatic refresh without scripting. Implement the statistics page with its downloads. Verify with tests for each action and for a shared address.
- [ ] 13.10 Implement the definitions and system pages: import with tags and report, export, merge, bulk derivation, schema report, diagnostics, search, status, read-only switch, reload, reset, and last-used times. Verify with tests that submit each form and check the admin API calls.
- [ ] 13.11 Add a no-script coverage test that drives every form over plain HTTP and checks that each admin API management operation is reachable from the UI. Verify that the coverage list has no gaps.
- [ ] 13.12 Add the optional scripting enhancement: vendor the script, and return fragments for in-place updates of overview switches, history refresh, and validation feedback. Verify with tests that enhanced requests receive fragments and leave the same server state, and that no page refers to another origin.
- [ ] 13.13 Write the built-in help pages from the legacy help topics, updated for the new behavior, and `docs/ui.md` with the note on exposing the UI. Package the UI as a distribution and a runnable jar. Verify by starting the UI from the built distribution against a running server and loading the overview and help pages.

## 14. Legacy shim (spec: legacy-compatibility)

- [ ] 14.1 Implement the shim as an optional server module on the mock listener that is off by default. Verify with tests for 404 responses when disabled and JSON responses when enabled.
- [ ] 14.2 Implement `/config/service` with its parameters, the ignored `transientState` (DEV-22), and its result. Verify with tests against the result shapes recorded in task 2.8.
- [ ] 14.3 Implement `/plan/setup` with its four actions and result, and `/definitions`. Verify with tests against the result shapes recorded in task 2.8.
- [ ] 14.4 Implement `/home` for `init` and `deleteAllServices`, confined to the definitions location and refusing `url` (DEV-27), and the identifier round trip (DEV-20). Verify with tests for each scenario of those requirements.
- [ ] 14.5 Add the shim to `docs/migrating-from-2x.md`: how to enable it and how it differs from Mockey 2.x. Verify that the documented requests run in a test.

## 15. Integration and release checks

- [ ] 15.1 Run an end-to-end check from the built distributions: start the server and the UI, import the legacy sample document through the UI, call a mock URL, and find the history entry in the UI. Verify that the scripted check passes.
- [ ] 15.2 Run all module tests, the parity suite, and the OpenAPI comparison in one CI job on a clean checkout. Verify that the job is green.
- [ ] 15.3 Check that the traceability table has no unmapped legacy servlet or parameter and that the deviation register matches the labels used by the parity cases. Verify that the automated check passes.
- [ ] 15.4 Follow the README quick start verbatim in a container that has only a Java 21 runtime. Verify that the server and UI start and that a mock request is answered.
- [ ] 15.5 Build the 3.0.0 release candidate with release notes that include the deviation register. Verify that the artifacts listed in the release notes exist and start.

## Workflow follow-up

- Archive this change in the new repository once review is complete, so that the thirteen capability specs become that repository's main specs.
- Decide whether the `openspec/` directory in the legacy repository is removed or kept as a pointer to the new repository.
