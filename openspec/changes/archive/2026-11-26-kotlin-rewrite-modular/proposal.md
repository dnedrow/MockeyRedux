# Proposal: Rewrite Mockey in Kotlin as a library, a server, and a separate web UI

## Why

Mockey is one Java web application (about 20,800 lines of first-party Java in 139 files, an Ant build, 65 vendored jars, 44 servlets and 28 JSPs on embedded Jetty) in which the mock engine, HTTP serving, persistence, and UI are coupled through static singletons. The engine cannot be embedded or tested in isolation, the UI cannot be deployed on its own, most dependencies are years past end of life, and only 13 test classes guard the behavior. The most recent functional change upstream (2019) also left URL-template matching broken at HEAD, so the tool no longer does one of the things it is known for.

A rewrite in modern, idiomatic Kotlin, split along the seams the product already has conceptually, makes it maintainable, testable, and reusable.

## What Changes

- Create a new Kotlin codebase in a **new repository**, built with Gradle (Kotlin DSL). The existing Java/Ant code is not modified and stays available as the behavioral reference.
- Split the product into three deliverables:
  - **`mockey-core`**: a library containing the domain model, request routing, scenario selection, request rules, service plans, request history, and storage abstractions. It has no HTTP server dependency and can be embedded in other JVM programs.
  - **`mockey-server`**: a Ktor application that exposes the mock endpoint (`/service/*`) and a versioned JSON admin API.
  - **`mockey-ui`**: a separate process built with Ktor and kotlinx.html (server-rendered HTML, with htmx as an optional enhancement) that talks to the server only through the admin API.
- Capture current behavior in a black-box characterization suite before porting, and keep an explicit register of intentional deviations, so that defects are fixed deliberately instead of being reproduced or changed by accident.
- Introduce a new on-disk definition format, plus an importer for existing Mockey XML definitions.
- Provide optional shims for the legacy automation endpoints so that existing scripts keep working during migration.
- **BREAKING** (relative to Mockey 2.x):
  - The UI is no longer served by the mock server. All JSP page URLs go away.
  - Administrative operations move to the versioned admin API. Legacy servlet URLs exist only through the optional shim.
  - The native storage format changes. Existing XML definitions must be imported.
  - Request-inspector plugins found by package-annotation scanning must be ported to the new extension interface.
  - WAR/Tomcat deployment and the all-in-one executable jar are dropped. The server and the UI are separate runnable distributions.
  - JDK 21 is required.
  - A defined set of legacy defects is corrected, which changes observable behavior in those cases. The design lists each one.

## Capabilities

### New Capabilities

- `service-definitions`: Services and their scenarios as managed definitions, including attributes, validation, uniqueness, tags, error-scenario designation, duplication, merging, conflict diagnostics, and response-schema checks.
- `request-routing`: How a request to the mock endpoint is mapped to a service, covering the `/service/` path convention, URL twisting, URL-template matching and precedence, the tag filter, and handling of unknown targets.
- `scenario-responses`: How a service in static or dynamic mode produces a response, covering scenario selection, match scoring, error fallback, status and headers, body fidelity, and delays.
- `upstream-proxying`: Forwarding requests to real services in proxy mode, including target selection, header and body handling, the outbound corporate proxy, sticky upstream sessions, timeouts, and failure fallback.
- `request-inspection`: The JSON request-rule format and its evaluation, service-level request inspection, and pluggable inspectors.
- `request-history`: Recording of handled requests, bounded retention, filtering, deletion, flagging, replay, saving an exchange as a scenario, and per-service statistics.
- `service-plans`: Named snapshots of service settings that can be saved, applied, set as the startup default, and kept consistent when services or scenarios are renamed.
- `definition-storage`: Persistence of definitions, covering read-only mode, durable and diff-friendly files, startup loading, export, import with merge reporting, and reset.
- `legacy-compatibility`: Everything that exists only to ease migration from Mockey 2.x, namely import of legacy XML definitions and the optional legacy automation endpoints.
- `admin-api`: The versioned JSON API through which definitions, plans, twists, proxy settings, history, and system state are managed, including its error model, description document, and access control.
- `admin-ui`: The separately deployed, server-rendered web UI, including operation without JavaScript, optional htmx enhancement, self-hosted assets, and its behavior when the server is unavailable.
- `server-runtime`: How the server is started and operated, covering configuration, listeners, liveness, default response headers on mock traffic, limits, logging, and shutdown.
- `embedded-engine`: Use of the core library in-process, covering programmatic definitions, request evaluation without an HTTP server, instance isolation, and replaceable collaborators.

### Modified Capabilities

None. The project has no existing specs.

## Impact

- **Code**: All new code lives in this repository, `mockey-kt`. The legacy Mockey repository is not changed.
- **Mock clients**: Applications that call `/service/...` keep the same URL convention. Their observable behavior changes only where the deviation register says so.
- **Automation scripts**: Scripts that call `/config/service`, `/plan/setup`, `/definitions`, or `/home?action=...` need the optional shim or must move to the admin API. `/status` stays available without the shim.
- **Operators**: Two processes instead of one, new startup options, a new storage format, and a one-time import of existing definitions.
- **Plugin authors**: Existing `IRequestInspector` implementations need a small port.
- **Dependencies**: Kotlin 2.x, Ktor (server and client), kotlinx.serialization, kotlinx.coroutines, kotlinx.html, a JSON Schema validator, htmx as a vendored static file, and SLF4J with Logback. All 65 vendored legacy jars are left behind.
- **Licensing**: Mockey is licensed GPL-2.0-or-later and a port is a derivative work, so the license of the new repository has to be decided with that in mind.
- **Risk**: Behavior parity is the main risk because the legacy test suite is thin. The characterization suite is the mitigation and comes first.
