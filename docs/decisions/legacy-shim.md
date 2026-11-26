# The legacy shim

Decided in tasks 14.1 to 14.3 of the change `kotlin-rewrite-modular`, on 2026-11-26.

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
| A change that can't be stored | The result envelope with `fail`, see "The result envelope". | A server in writable mode stores a change before the change takes effect, so nothing was changed, and that's an outcome that a script can read. |

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

## The result envelope

`/config/service`, `/plan/setup`, and `/home` answer with a JSON object that has the member `result`. That
member has `success` when the request did what it asked for, and `fail` when it didn't, never both
(`legacy-compatibility`: Legacy result envelope). Either one is a text for a person. A `fail` is the only
member of its result, as the recordings state it.

When a change can't be stored, the text says that nothing was changed and that the log says why. The reason
itself goes to the log only: it's about the definitions location, and the mock listener answers everyone.

## `/config/service`

The endpoint changes how one service answers. A request names the service, and each of its other parameters
says one thing about the service. What a request doesn't mention stays as it is.

| Parameter | What it sets |
|---|---|
| `serviceResponseType` | The response mode: `proxy`, `static`, or `dynamic`, or the numbers `0`, `1`, or `2`, in any letter case. |
| `scenarioId`, `scenarioName` | The default scenario. The identifier counts first, and then the name, as for a service. |
| `hangTime` | The delay in milliseconds, as a whole number. |
| `defaultUrlIndex` | The default real URL by its place in the list, counted from 1. |
| `requestInspectorName` | The inspector. The text `null` and a blank name remove it. |
| `requestInspectorJsonRules` | The request rules. An empty text removes them. |
| `requestInspectorJsonRulesEnableFlag` | Whether the request rules are on. They are for `true` in any letter case, and off for every other text. |
| `serviceSchema` | The response schema. An empty text removes it. |
| `serviceSchemaEnableFlag` | Whether the response schema is on, read like the other flag. |
| `transientState` | Nothing. The server has no such state for a service (DEV-22). |

**A value that says nothing usable changes nothing.** That holds for a response type that is none, for a
delay or a place that is no whole number, and for a scenario that the service doesn't have. The result still
says `updated`. Mockey 2.x tried each parameter on its own and went on when one failed, and the recording
`automation-config-unknown-scenario` states `updated` for a scenario name that the service doesn't have. The
result states the service after the change, so a script can see what came of its request.

**A place that the list of real URLs doesn't have gives the first URL.** Mockey 2.x stored such a place and
forwarded to the first URL when it came to it. The core does the same when it stores the service.

**Request rules that aren't a well-formed rule set fail the request.** Mockey 2.x stored every text as rules.
The core takes a well-formed rule set only, for the admin API as well, so the result has `fail` with what is
wrong with the rules. The change to a service is one change, so nothing else of the request is applied
either. An answer is `updated` with everything usable applied, or `fail` with nothing changed.

**The result states ten members.** They are `success`, `serviceId`, `serviceName`, `scenarioId`,
`scenarioName`, `serviceResponseType`, `hangTime`, `requestInspectorName`, `serviceSchemaEnableFlag`, and
`requestInspectorJsonRulesEnableFlag`. The mode, the delay, the inspector, and the scenario are stated as
`/definitions` states them. A service without scenarios has JSON `null` for the scenario, where Mockey 2.x
left `scenarioName` out.

**A request that names no service gets a `fail`.** Mockey 2.x answered it with the status 500 and a stack
trace (DEV-46). Its servlet has a branch for that case which nothing reached. The text of the shim starts as
the text of that branch does, with "Unable to update service configuration.", and then says why.

The second path of the servlet of Mockey 2.x, `/service_scenario`, isn't served (DEV-26).

## `/plan/setup`

The endpoint does one of four things with a plan, and the parameter `action` says which. A request names
the plan by `plan_id` or by `service_plan_name`. The identifier counts first, and then the name, as for a
service. Mockey 2.x found a plan in that order too.

| Action | What it does | The text of `success` |
|---|---|---|
| `save_plan` | Saves the settings that the services of `service_ids[]` have now as the items of the plan, or of a new plan when the request names none that is there. The plan gets the name of `service_plan_name` and the tags of `service_plan_tag`, where the request has them. | `Service plan Evening saved` |
| `set_plan` | Applies the plan. | `Service plan Gold path set` |
| `delete_plan` | Deletes the plan. | `Service plan 'Silver path' deleted` |
| `set_as_default_plan` | Designates the plan as the default plan. With `none` for `plan_id`, it ends the designation. | `Set as Default Service Plan`, or `Removed default plan.` |

The texts are the ones of Mockey 2.x, because the recordings state them for the new server. After an action
on a plan, the result states the identifier of the plan as `planid` and as `planId`, and its name as
`planName` (DEV-48). Mockey 2.x wrote `planId` after `delete_plan` and `planid` after the other actions, so a
script reads the one or the other. `Removed default plan.` is about no plan, and its result states none.

**A request that names no plan that is there gets a `fail`.** Two of the texts are the ones of Mockey 2.x:
`Service plan not deleted. Please check your logs for insight.` and `Unable to set Default Service Plan.
Unknown Service Plan ID.` The first one sends its reader to the log. So the server writes a line to its log
when that happens, at the level of information, which says that the request names no plan that is there.
Mockey 2.x answered `set_plan` for such a plan with its home page (DEV-50). The shim says `Service plan not
set.` and why.

**A request that names none of the four actions gets a `fail` that lists them.** Mockey 2.x answered it with
its home page as well. An action is named as it's written, in lower case.

**`save_plan` for a value of `service_ids[]` that identifies no service saves nothing.** The result has
`fail`, and its text names the value, such as `Service plan not saved. No service has the identifier 3.`
The specification doesn't say what such a request answers, and the three recordings of `save_plan` state
the envelope only: they name their services by the numbers of Mockey 2.x, which name nothing here (DEV-20).
Mockey 2.x has no answer for an unknown number: its servlet fails on it. A plan for the services that are
there would be a plan that the script didn't ask for, so nothing is saved. The core refuses such a save, for
the admin API as well, and the shim passes on what the core says.

**A save keeps what the request doesn't mention.** A plan that exists keeps its name without
`service_plan_name` and its tags without `service_plan_tag`. An empty `service_plan_tag` removes the tags.
The tags are the words of the parameter in lower case, as everywhere in this server. A new plan without a
name gets the name `Plan (auto-generated-name)`, as in Mockey 2.x, and every such save makes a plan of its
own.

The endpoint takes `transient_state` and doesn't use it (DEV-22). It takes `type` as well: Mockey 2.x
answered with a page without `type=json`, and the shim always answers with the result envelope.

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
./gradlew :mockey-server:test --tests '*Shim*Test' --tests '*MockeyServerTest' --tests '*ServerProgramTest'
```

`LegacyShimTest` sends requests to a listener that runs the module of the mock listener, with the shim and
without it. `ShimFixture` defines the services of the parity fixture `automation.xml`, and the test compares
the answers of `/definitions` with the ones that the recordings state. `ShimServicesTest` and
`ShimPlansTest` do the same for `/config/service` and `/plan/setup`: each sends the requests of the
recordings, compares the answers, and compares the definitions after a request with the ones that the
request asks for. Where a recording names a service by a number of Mockey 2.x, a test also sends the request
with the identifier of this server, to see what the request does when it finds its service.
