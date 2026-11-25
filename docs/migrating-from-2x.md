# Migrating from Mockey 2.x

Mockey Redux 3 takes the definitions of Mockey 2.x as they are: its services, scenarios, plans, twist
configurations, and settings. This document describes how they move over, what the startup options of
Mockey 2.x have become, what behaves differently, and what is gone.

It describes the server as it is now. The server answers mock traffic, and its admin API states what the
server holds and changes its services with their scenarios, its plans, its twist configurations, its tag
filter, its tags, and its outbound proxy. The web UI and the automation endpoints of Mockey 2.x are not part
of it yet, see "What is gone".

## Importing the definitions

The command `import-legacy` of the server program reads the definitions of Mockey 2.x and stores them in the
definitions location of the new server. Its source is one of these:

- The definitions directory of Mockey 2.x, which holds the file `mock_service_definitions.xml` and the
  directory `mockey_def_depot`.
- An export document of Mockey 2.x: the XML document that a running Mockey 2.x gives at its path `/export`,
  as a file.
- The URL of such an export document, which is how the command reads a Mockey 2.x that runs.

The commands of this document run in the directory `/srv/mockey`. It holds the definitions directory of a
Mockey 2.x as `mockey-2x-definitions` and an export document as `export.xml`. The start script of the server
is on the path of the shell, see [server.md](server.md).

```shell
mockey-server import-legacy mockey-2x-definitions
```

```text
Imported mockey-2x-definitions into the definitions location /srv/mockey/mockey-definitions.

Additions: 15
  The service 'Customer Directory' was added with 3 scenarios.
  The scenario 'silver' was added with its service 'Customer Directory'.
  The scenario 'gold' was added with its service 'Customer Directory'.
  The scenario 'Directory down' was added with its service 'Customer Directory'.
  The service 'Orders' was added with 2 scenarios.
  The scenario 'order list' was added with its service 'Orders'.
  The scenario 'no orders' was added with its service 'Orders'.
  The service 'Billing' was added with 0 scenarios.
  The plan 'Gold path' was added.
  The plan 'Slow day' was added.
  The twist configuration 'to qa2' was added.
  The settings of the outbound proxy were taken from the document.
  The scenario 'Directory down' of the service 'Customer Directory' was designated as the universal error scenario.
  The twist configuration 'to qa2' was enabled.
  The plan 'Slow day' was designated as the default plan and applied.
Conflicts: none
Issues of the source: none
Rule sets that are not well formed: none

The definitions location now holds 3 services with 5 scenarios, 2 plans, and 1 twist configuration.
```

The command prints its report and ends. It starts no server, and it changes nothing of the source. The report
has four parts:

- Additions: what the import added and set.
- Conflicts: what it did not add, because the definitions location holds something of that name already.
- Issues of the source: what it could not read or resolve, such as a file that is missing, or a default
  scenario that the service does not have. The import goes on without it.
- Rule sets that are not well formed: request rules that the import took over as they are and that no
  request satisfies. [rules.md](rules.md) says what a well-formed rule set is.

The command ends with the status 0 when the import was carried out, also when the report names conflicts or
issues. It ends with the status 1, and says why on the standard error, when nothing can be read from the
source, and when the definitions location cannot be used.

The server then starts with what was imported:

```shell
mockey-server
```

```shell
curl "http://localhost:8080/service/customer/7?tier=silver"
```

```json
{"id": "1", "tier": "silver"}
```

### The definitions location and tags

The command stores the definitions where the server looks for them: in the directory that the option
`--definitions` or the variable `MOCKEY_DEFINITIONS` names, and in `mockey-definitions` in the working
directory without them. With `--tags` it gives tags to every service, scenario, and plan that it adds, and to
every service that has the name of an imported one:

```shell
mockey-server import-legacy export.xml --definitions staging-definitions --tags "imported 2x"
```

### Importing from a running Mockey 2.x

The source can be the URL of the export of a Mockey 2.x that runs, here one on port 8090:

```shell
mockey-server import-legacy http://localhost:8090/export --definitions from-running-2x
```

Mockey 2.x and the mock listener of the new server both take port 8080 by default. On one machine, stop
Mockey 2.x before the new server starts, or give one of them another port.

### Importing into definitions that are there

The import adds to what the definitions location holds. It removes and replaces nothing:

- A service, a plan, or a twist configuration whose name is new is added.
- A service that has the name of an imported one, whatever the letter case, gets the scenarios that it lacks.
  Everything else of it stays, and the report names what was not taken as a conflict.
- Where the location has no default plan, the default plan of the source becomes the default plan and is
  applied.

Run the command while no server in writable mode uses the definitions location: such a server holds the
definitions in memory and writes its own changes to the location. A server sees what was imported when it
starts.

### Starting from a document without importing it

The server also takes a source of Mockey 2.x as its initial definitions, which it reads at every start. In
read-only mode, which is the default, it keeps them in memory and stores nothing:

```shell
mockey-server --initial-definitions export.xml
```

[server.md](server.md) describes initial definitions and what the log says about a source that can be read in
part only.

## Startup options

| Mockey 2.x | Mockey Redux 3 |
|---|---|
| `-p`, `--port` | `--mock-port` for the mock traffic. The admin listener has a port of its own, `--admin-port`. |
| `-f`, `--file` | `--initial-definitions` with the path of the document. In writable mode the server takes initial definitions only while the definitions location holds none: Mockey 2.x deleted the stored definitions for them (DEV-30). |
| `-u`, `--url` | `--initial-definitions` with the URL of the document. |
| `-t`, `--transientState` | Read-only mode is the default, as it was. `--writable` is what `-t false` was. |
| `-l`, `--location` | `--definitions`. The directory holds the definitions in the format of the new server, so a directory of Mockey 2.x is imported and not named here. |
| `-F`, `--filterTag` | `--tag-filter`. It stays in effect when initial definitions are loaded, which Mockey 2.x lost it over (DEV-49). |
| `-H`, `--headless` | Gone. The server never starts a browser (DEV-25). |
| `-v`, `--version` | `--version` |
| `--help` | `--help`, which prints every setting with its option, its environment variable, and its default. |

Every setting of the new server can also be given in an environment variable, see [server.md](server.md).

## What behaves differently

The rewrite fixes defects of Mockey 2.x. [deviations.md](deviations.md) lists every intended difference in
behavior, and a behavior that is not listed there stays the same. These are the differences that the clients
of a migrated installation meet first:

- A response body is sent exactly as it is stored. Mockey 2.x appended a line break to every scenario response
  (DEV-06).
- A variable in a mock URL, as in `customer/{id}`, matches one path segment. In Mockey 2.x it matched only
  the text `{id}` itself (DEV-01).
- A mock URL belongs to one service, and where several patterns match a request, the most specific one wins.
  Mockey 2.x let several services share a mock URL, and the first of them answered. The import reports the
  second service with a mock URL as a conflict (DEV-02).
- A relative target that no service has gets the status 404. Mockey 2.x took its first path segment for a
  host and forwarded the request there (DEV-03).
- A request for a real URL of a service reaches that service (DEV-31).
- Twisting replaces the text of the origination as it stands. Mockey 2.x replaced it as a regular expression
  (DEV-05). The import enables the twist configuration that a document names as enabled, which Mockey 2.x
  did not do when it loaded the document (DEV-32).
- `/status` says that the server runs and since when, in ISO 8601, and no longer where its definitions are
  (DEV-28).
- The server refuses a few requests that the HTTP server of Mockey 2.x took: a request whose body is longer
  than the setting `--max-request-body` allows, which is 50 MiB by default, and a request with a `Host` header
  that names no host or with more than one (DEV-65, DEV-66). A request for `/service` without a slash after it
  is no mock traffic (DEV-67).
- Told to end, the server lets the requests in progress finish and ends with the status 0. Mockey 2.x ended
  at once (DEV-68).
- A request that the server forwards to a URL with the port of its own admin listener, which is 8081 unless
  it is set otherwise, carries the header `Mockey-Forwarded`. A real service on a port with that number gets
  the header, and the admin listener of the server refuses the request (DEV-69).
- Services, scenarios, plans, and twist configurations have identifiers that are texts and that stay. The
  numbers of Mockey 2.x, which changed with every load, identify nothing (DEV-20).

## What is gone

- The admin pages and the automation endpoints on the port of the mock traffic. Administration has a
  listener of its own, which only programs on the same machine reach by default (DEV-26). In this version the
  admin API changes services, scenarios, plans, twist configurations, the tag filter, tags, and the
  outbound proxy, and nothing else yet. The server has no web UI, and the automation endpoints
  `/config/service`, `/plan/setup`, `/definitions`, and `/home` answer with the status 404.
- Opening a browser when the server starts, and the option `-H` with it (DEV-25).
- Loading definitions from any file or URL of the server's machine while the server runs, as `/home?action=init`
  and the upload page did. Files and URLs are sources at startup and for the import command (DEV-27).
- The transient flags of a single service and of a single plan. Read-only mode holds for the whole server
  (DEV-22).
- Request inspectors that are Java classes of Mockey 2.x, which it found by scanning the class path. An
  inspector is ported to the interface of the new engine, see "Porting an inspector of Mockey 2.x" in
  [inspectors.md](inspectors.md).
- The Image Depot, the page for mobile devices (`/mobi`), the viewer of the debug log (`/console`), and the
  loader of JSON Schema samples.
- The WAR file. The server comes as a distribution with start scripts and as a runnable jar.
