# Migrating from Mockey 2.x

Mockey Redux 3 takes the definitions of Mockey 2.x as they are: its services, scenarios, plans, twist
configurations, and settings. This document describes how they move over, what the startup options of
Mockey 2.x have become, how a script for Mockey 2.x keeps working, what behaves differently, and what is
gone.

The server answers mock traffic, and its admin API states and changes what the server holds. The web pages
come from a program of its own, `mockey-ui`, which [ui.md](ui.md) describes. The automation endpoints of
Mockey 2.x are answered by a server that is started with the legacy shim, see "The automation endpoints".

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

### Importing into a server that runs

A server that runs takes an export document of Mockey 2.x through its admin API. The document is the body of
a `POST` request for `/api/v1/definitions/import`, which declares it as XML with the header `Content-Type:
application/xml`. Tags go into the query string, as in `?tag=imported`. The import follows the same rules,
and the answer is its report as JSON, with the same four parts. In read-only mode the server keeps what it
imported in memory.

Only a document is imported this way, and not a definitions directory: the server reads no file of its
machine for a request, also not one that a document refers to.

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

## The automation endpoints

A script for Mockey 2.x sets a server up through four endpoints on the port of the mock traffic:
`/definitions`, `/config/service`, `/plan/setup`, and `/home`. The new server has its admin API for that
work. For the scripts that haven't moved to it yet, there's the legacy shim: a server that's started with it
answers the four endpoints as well. The shim takes the parameters of Mockey 2.x, and it answers with the
JSON documents of Mockey 2.x.

The shim is off by default. The option `--legacy-shim` switches it on, and so does the environment variable
`MOCKEY_LEGACY_SHIM` with the value `true`. To follow the examples, stop the server that runs, and start one
with the shim. It loads the definitions that were imported at the beginning of this document:

```shell
mockey-server --legacy-shim
```

The endpoints are on the mock listener, where Mockey 2.x had them, so a script keeps its URLs. The examples
send their requests with `curl` and format the answers with `jq`.

### Reading the definitions

`/definitions` lists the services with their scenarios. With `serviceName` or `serviceId`, it lists that
service alone:

```shell
curl -s "http://localhost:8080/definitions?serviceName=Orders" | jq .
```

```json
{
  "serviceDefinitions": [
    {
      "serviceName": "Orders",
      "serviceId": "945bdc87-1c41-4735-bfed-aadb3bc87f02",
      "defaultScenarioId": "a8408868-75ad-425a-93df-382e34fe8fb5",
      "defaultScenarioName": "no orders",
      "serviceResponseType": "static",
      "hangTime": 50,
      "requestInspectorName": "null",
      "scenarioArray": [
        {
          "scenarioId": "a8408868-75ad-425a-93df-382e34fe8fb5",
          "scenarioName": "no orders"
        },
        {
          "scenarioId": "87981fd4-8796-4477-9479-a1869d54a717",
          "scenarioName": "order list"
        }
      ]
    }
  ]
}
```

As in Mockey 2.x, `requestInspectorName` is the text `null` for a service without an inspector.

### Configuring a service

`/config/service` changes how one service answers. A request names the service, and each of its other
parameters says one thing about it:

| Parameter | What it says |
|---|---|
| `serviceName`, `serviceId` | Which service the request is about. |
| `serviceResponseType` | The response mode: `proxy`, `static`, or `dynamic`, or their numbers `0`, `1`, and `2`. |
| `scenarioName`, `scenarioId` | The default scenario. |
| `hangTime` | The delay in milliseconds. |
| `defaultUrlIndex` | The default real URL by its place in the list, counted from 1. |
| `requestInspectorName` | The inspector. The text `null` removes it. |
| `requestInspectorJsonRules`, `requestInspectorJsonRulesEnableFlag` | The request rules, and whether they're on. |
| `serviceSchema`, `serviceSchemaEnableFlag` | The response schema, and whether it's on. |

This request makes `order list` the default scenario of the service `Orders` and removes its delay:

```shell
curl -s "http://localhost:8080/config/service?serviceName=Orders&scenarioName=order%20list&hangTime=0" | jq .
```

```json
{
  "result": {
    "success": "updated",
    "serviceName": "Orders",
    "serviceId": "945bdc87-1c41-4735-bfed-aadb3bc87f02",
    "scenarioId": "87981fd4-8796-4477-9479-a1869d54a717",
    "scenarioName": "order list",
    "serviceResponseType": "static",
    "hangTime": 0,
    "requestInspectorName": "null",
    "serviceSchemaEnableFlag": false,
    "requestInspectorJsonRulesEnableFlag": false
  }
}
```

The `result` states the service after the change. As in Mockey 2.x, a value that says nothing usable changes
nothing, and the `result` still says `updated`. A scenario name that the service doesn't have is such a
value.

When the request can't be carried out, the `result` has the member `fail` in the place of `success`, with a
text that says why, and the service stays as it was. The answer still has the status 200, so a script reads
the member. A request that names its service by a number of Mockey 2.x gets such an answer, because the
identifiers of the new server are texts:

```shell
curl -s "http://localhost:8080/config/service?serviceId=2&hangTime=5" | jq .
```

```json
{
  "result": {
    "fail": "Unable to update service configuration. No service has the serviceId or the serviceName of the request."
  }
}
```

### Saving and applying a plan

`/plan/setup` does one of four things with a plan, and the parameter `action` says which. A request names
its plan by `service_plan_name` or by `plan_id`.

| `action` | What it does |
|---|---|
| `save_plan` | Saves the settings that the services of `service_ids[]` have now as the items of the plan. The request can give the plan tags with `service_plan_tag`. A name that no plan has makes a new plan. |
| `set_plan` | Applies the plan. |
| `delete_plan` | Deletes the plan. |
| `set_as_default_plan` | Designates the plan as the default plan. With `plan_id=none`, it ends the designation. |

This request applies the plan `Slow day`, which gives the service `Orders` its scenario `no orders` and its
delay of 50 ms again:

```shell
curl -s "http://localhost:8080/plan/setup?action=set_plan&service_plan_name=Slow%20day&type=json" | jq .
```

```json
{
  "result": {
    "success": "Service plan Slow day set",
    "planid": "b2884b2d-b0f9-4ece-be16-e12afd1d2e8d",
    "planId": "b2884b2d-b0f9-4ece-be16-e12afd1d2e8d",
    "planName": "Slow day"
  }
}
```

### Loading a document and starting over

`/home` takes two actions. With `action=init`, the definitions of a document of Mockey 2.x take the place of
everything that the server holds. The parameter `file` names the document, which has to be a file inside the
definitions location of the server. So the export document is copied there first:

```shell
cp export.xml mockey-definitions/suite-a.xml
curl -s "http://localhost:8080/home?action=init&file=suite-a.xml&type=json" | jq .
```

```json
{
  "result": {
    "success": "Loaded definitions from suite-a.xml"
  }
}
```

The server stores its own files in its definitions location and leaves everything else there alone, so a
team can keep its documents next to them. An init takes two more parameters:

- `filterTag` becomes the tag filter of the server.
- `transientState` sets the mode of the server after the init. With `true` that's read-only mode, and with
  any other value it's writable mode. Without the parameter, the server stays in the mode that it's in.

With `action=deleteAllServices`, the server starts over. It has no definitions, no history, and no tag filter
after that:

```shell
curl -s "http://localhost:8080/home?action=deleteAllServices&type=json" | jq .
```

```json
{
  "result": {
    "success": "All is deleted. You have a clean slate. Enjoy."
  }
}
```

The server of these examples is in read-only mode. So the two requests changed what it holds in memory, and
its definitions location still holds what was imported. A server in writable mode stores the outcome: after
an init, its definitions location holds the definitions of the document, and after `deleteAllServices`, it
holds none.

### Who reaches the endpoints

The endpoints ask for no admin token, because a script for Mockey 2.x sends none. So whoever reaches the
mock listener of a server with the shim can read and change its definitions, and delete all of them. The
server says so in its log when it starts. "The legacy shim" in the section "Security" of
[server.md](server.md) says what that means and how to limit it.

### What differs from Mockey 2.x

A script that names its services, scenarios, and plans, and that reads `success` and `fail` from the
answers, runs as it is. These are the differences:

- **An identifier is a text, not a number (DEV-20).** The numbers of Mockey 2.x identify nothing. A script
  reads the identifiers from `/definitions`, or it uses names, which work as before: `serviceName`,
  `scenarioName`, and `service_plan_name`. Only `service_ids[]` of `save_plan` has no name to go with it.
  Where a request has an identifier and a name, the name counts when the identifier identifies nothing. For
  a service and a scenario, Mockey 2.x looked at the identifier alone (DEV-71).
- **No answer is a page or a redirect.** `type=json` isn't needed anymore, and it does no harm. Where
  Mockey 2.x answered with a page or a redirect, the shim answers with a `result` (DEV-50). A request for a
  service that isn't there gets a `result` with `fail`, and no longer the status 500 with a stack trace
  (DEV-46). So does `save_plan` for a service that isn't there, and it saves nothing (DEV-72).
- **An answer has all of its members (DEV-47).** `/definitions` has `serviceDefinitions` when the server
  has no service, and `scenarioArray` and `defaultScenarioName` for a service without scenarios. The `result`
  of `/config/service` has `scenarioName` for such a service. An array is empty then, and a name is JSON
  `null`. `/plan/setup` states the identifier of a plan as `planid` and as `planId`, where Mockey 2.x wrote
  the one or the other (DEV-48).
- **`/config/service` is stricter in two places.** It refuses request rules that are no well-formed rule
  set, and it applies nothing of such a request (DEV-54). [rules.md](rules.md) says what a well-formed rule
  set is. It also ignores a `scenarioId` that the service doesn't have, where Mockey 2.x stored the number
  (DEV-71).
- **`transientState` of a service and `transient_state` of a plan do nothing (DEV-22).** The shim takes
  them, so a script can keep them. Read-only mode holds for the whole server.
- **`/home` is no page anymore.** It answers its two actions, and every other request for it gets the status
  404. Among them is a request with `filterTag` and no action, which set the tag filter in Mockey 2.x.
- **An init reads nothing outside the definitions location (DEV-27).** A relative name starts at that
  location. In Mockey 2.x it started at the working directory, and a request could name any file of the
  machine, or any URL with the parameter `url`. The shim refuses `url`.
- **An init that loads nothing changes nothing.** Mockey 2.x had set the mode and the tag filter by the
  time it looked for its file (DEV-70), and it had deleted its definitions by the time it found that a file
  was no definitions document (DEV-27). An init without `file` gets a `fail`, where Mockey 2.x answered with
  an empty `result` (DEV-73).
- **An init without `transientState` keeps the mode of the server (DEV-70).** In Mockey 2.x it switched the
  server to writable mode.
- **The tag filter of an init stays in effect (DEV-49).** Mockey 2.x lost it when it replaced the
  definitions.
- **In writable mode, a change that can't be stored isn't made (DEV-74).** The `result` has `fail` then.
  Mockey 2.x kept the change in memory and answered as if it were stored. An init that can't store what it
  loaded keeps it in memory, in read-only mode, and its `fail` says so.
- **No answer carries the header `Access-Control-Allow-Origin` (DEV-26).** A web page of another origin
  can't read the answers.

### Moving a script to the admin API

The shim is there for the time of the move. Every request of a script has its counterpart in the admin API,
which [admin-api.md](admin-api.md) describes:

| Request of Mockey 2.x | Admin API, below `/api/v1` |
|---|---|
| `/definitions` | `GET /services` |
| `/config/service` | `PATCH /services/{id}` |
| `/plan/setup?action=save_plan` | `POST /plans`, or `POST /plans/{id}/save` for a plan that is there |
| `/plan/setup?action=set_plan` | `POST /plans/{id}/apply` |
| `/plan/setup?action=delete_plan` | `DELETE /plans/{id}` |
| `/plan/setup?action=set_as_default_plan` | `PUT /default-plan`, and `DELETE /default-plan` to end the designation |
| `/home?action=init` | `POST /system/reset`, and then `POST /definitions/import` with the document as its body |
| `/home?action=deleteAllServices` | `POST /system/reset` |

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

- The admin pages on the port of the mock traffic. Administration has a listener of its own, which only
  programs on the same machine reach by default (DEV-26). The web pages come from the program `mockey-ui`,
  which manages the server through that listener. [ui.md](ui.md) says where the pages of Mockey 2.x are now.
  The automation endpoints `/config/service`, `/plan/setup`, `/definitions`, and `/home` get the status 404
  unless the server is started with the legacy shim, see "The automation endpoints".
- Opening a browser when the server starts, and the option `-H` with it (DEV-25).
- Loading definitions from any file or URL while the server runs, as `/home?action=init` and the upload
  page did. Files and URLs are sources at startup and for the import command. With the legacy shim, an init
  reads a document inside the definitions location of the server, and nothing outside that location (DEV-27).
- The transient flags of a single service and of a single plan. Read-only mode holds for the whole server
  (DEV-22).
- The three buttons that set the response mode of every service at once. The overview of the UI switches the
  mode of one service, and a plan sets the modes of several services at once (DEV-75).
- Request inspectors that are Java classes of Mockey 2.x, which it found by scanning the class path. An
  inspector is ported to the interface of the new engine, see "Porting an inspector of Mockey 2.x" in
  [inspectors.md](inspectors.md).
- The Image Depot, the page for mobile devices (`/mobi`), the viewer of the debug log (`/console`), and the
  loader of JSON Schema samples.
- The WAR file. The server comes as a distribution with start scripts and as a runnable jar.
