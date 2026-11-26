# The legacy shim

Decided in task 14.1 of the change `kotlin-rewrite-modular`, on 2026-11-26.

The legacy shim answers the automation endpoints of Mockey 2.x, so that the scripts that were written for
them keep working. It's a handler on the mock listener, and a server has it only when it's started with
`--legacy-shim`. This record says where the shim sits, how an endpoint takes a request, and what each
endpoint answers.

## What was needed

- The endpoints `/config/service`, `/plan/setup`, `/definitions`, and `/home` are answered only when the shim
  is enabled, and then on the mock listener. Otherwise they get the status 404 (`legacy-compatibility`: The
  legacy shim is opt-in).
- The shim is off by default. It takes the parameter names of Mockey 2.x, it answers with the JSON documents
  of Mockey 2.x, and it calls the same operations of the core as the admin API (design decision 17).
- Where Mockey 2.x used a number for a service, a scenario, or a plan, the shim takes and states the
  identifiers of the server (`legacy-compatibility`: Identifiers in legacy endpoints, DEV-20).
- The header `Access-Control-Allow-Origin` is on mock responses only (DEV-26).
- Task 2.8 recorded how Mockey 2.x answers the requests of a script, and each recording states what the new
  server answers. 44 recordings send a request to an endpoint of the shim: 41 of the 43 `automation-*`
  recordings, and three routing recordings that read `/definitions`.
  [recordings.md](../parity/recordings.md) describes them.

## Where the shim sits

The module of the mock listener asks three handlers in turn: `/status`, the shim, and the mock endpoint. A
server without the setting has no shim in that module. Then the paths of the endpoints get to the mock
endpoint, which serves the paths under `/service/` only, and Ktor answers them with the status 404. Nothing
checks the setting again for each request.

An endpoint is its exact path. `/definitions/` and `/Definitions` are other paths, and they get the status
404. Mockey 2.x mapped its servlets to exact paths as well.

The shim can't get in the way of mock traffic, because every mock URL is under `/service/`. A service with
the mock URL `definitions` answers at `/service/definitions`. A request to the shim is no request to a
service, so it leaves no history entry.

The admin API states the setting as `legacyShimEnabled` in the system status, and the UI shows it in its
section "Server".

## How an endpoint takes a request

| What | How | Why |
|---|---|---|
| The method | Any method. | The servlets of Mockey 2.x answered every method. |
| The parameters | Those of the query string, and after them the fields of a body that the request declares as a URL-encoded form. A name counts in the letter case in which it's written, and the first value of a name counts. | That's what a servlet gave Mockey 2.x. The core reads the parameters of a mock request by the same rules, so the shim hands the request to that code. |
| The body | As long as `--max-request-body` allows. A longer body gets the status 413. | The limit holds for everything that the mock listener reads. |
| The answer | A JSON document with the status 200, whatever came of the request. | A script reads the document, and Mockey 2.x gave it no other status to read. |
| Caching | `Cache-Control: no-store`. | Most requests to the shim change something, or they read what changes. |
| Other origins | No `Access-Control-Allow-Origin`. | DEV-26. A script needs none, and a page of another origin has no business reading the definitions. |
| A failure | The status 500 with a text that says where to look. The log names the path of the endpoint and the exception. | The log of the server gets nothing else of a request, because a query string can hold a secret. |

## Finding a service

A request names a service by `serviceId` or by `serviceName`. The shim tries the identifier first. When the
request has none, or when the identifier identifies no service, the shim tries the name.

Mockey 2.x looked at the name only when the request had no `serviceId`. The difference shows for a script
that sends both. Its number identifies nothing on this server (DEV-20). With the rule of Mockey 2.x, the
script would find no service. With the rule of the shim, it finds the service that it names. A script that
sends an identifier of this server gets that service either way.

A name is compared as Mockey 2.x compared it: without regard to letter case and to the spaces around it. The
core compares names in the same way when it merges an import. When several services have the name, the one
that was created first counts.

## `/definitions`

The endpoint lists the services with their scenarios, so that a script can name them in its next request.
Task 14.1 delivers it with the shim, because the scenario "Shim enabled" of the specification asks it for a
JSON document. The task list names the endpoint in task 14.3.

| Member | What it states |
|---|---|
| `serviceDefinitions` | Every service, in the order in which the services were created. A request that names a service gets that service alone. |
| `serviceName`, `serviceId` | The name and the identifier of the service. |
| `serviceResponseType` | The mode in which the service answers: `proxy`, `static`, or `dynamic`. A service without scenarios answers as a proxy, whatever mode it's set to, and Mockey 2.x stated the mode in the same way. |
| `hangTime` | The delay of the service in milliseconds, as a number. |
| `requestInspectorName` | The name of the inspector. For a service without one, it's the text `null`, which is what Mockey 2.x wrote and what a script compares with. |
| `defaultScenarioId`, `defaultScenarioName` | The scenario that answers in static mode. A service without scenarios has JSON `null` for both. |
| `scenarioArray` | The scenarios with `scenarioId` and `scenarioName`, in the order of their names without regard to letter case, as Mockey 2.x listed them. |

Two points differ from Mockey 2.x on purpose:

- Every member is always there (DEV-47). Mockey 2.x left out `serviceDefinitions` when there was no service,
  and `scenarioArray` and `defaultScenarioName` for a service without scenarios, so a script had to ask
  whether a member exists before reading it.
- An identifier is a text, not a number, and a number of Mockey 2.x identifies nothing (DEV-20). A request
  with such a number gets every service, like any other request that names a service which isn't there.

The default scenario that the endpoint states is the one that answers, not only the one that the service
designates. The two differ for a service that someone stored by hand with a designation that fits none of
its scenarios. Such a service answers with its first scenario, and that's the scenario a script wants to
know about.

## No admin token

The shim asks for no admin token. Mockey 2.x had none, the scripts that the shim is for send none, and the
specification puts the shim on the mock listener, which has none either. So whoever reaches the mock
listener of a server with the shim can read and change its definitions, as with Mockey 2.x.

That's why the shim is off by default. A server that has the shim says what that means in its log:

```text
The legacy shim is enabled: the mock listener answers the automation endpoints of Mockey 2.x, which ask for
no admin token. Whoever reaches it can read and change the definitions.
```

A web page counts as "whoever" too. A page of any site can make the browser that shows it send a request to
a server that the browser reaches, and the other endpoints of the shim change the definitions on a `GET`
request. The page can't read the answer, because the shim sends no `Access-Control-Allow-Origin`.

The shim could refuse the requests of a browser, which say where they come from in the headers `Origin` and
`Sec-Fetch-Site`. It doesn't, for two reasons. The specification doesn't ask for it. And it would break a
use that Mockey 2.x invited: a link on a team's page that sets a server up with one click. A team that
doesn't want the exposure leaves the shim off and uses the admin API, which is on a listener of its own and
takes a token.

## Repeating the tests

```shell
./gradlew :mockey-server:test --tests '*LegacyShimTest' --tests '*MockeyServerTest' --tests '*ServerProgramTest'
```

`LegacyShimTest` sends requests to a listener that runs the module of the mock listener, with the shim and
without it. `ShimFixture` defines the services of the parity fixture `automation.xml`, and the test compares
the answers of `/definitions` with the ones that the recordings state.
