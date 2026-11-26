# The admin API

The admin API states and changes what a running `mockey-server` holds: the services with their scenarios,
the plans, the twist configurations, the tag filter, the tags, the outbound proxy, the history, and the
definitions as a whole. Its clients are programs, such as a script or a test.

The admin listener of the server serves the API at the path `/api/v1`. With the default settings of the
server that is `http://localhost:8081/api/v1`, and only programs on the machine of the server reach it.
[server.md](server.md) describes the listener and its settings, the admin token, and what to consider before
other machines reach the API.

The examples of this document ask a server that runs with its default settings:

```shell
mockey-server
```

They send their requests with `curl` and read the answers with `jq`.

## Requests and answers

Bodies are JSON in UTF-8. A request with a body says so with the header `Content-Type: application/json`, and
it gets the status 415 otherwise.

A request that creates or updates something states attributes in a JSON object: a creation takes a default
for every attribute that it does not state, and an update changes only the attributes that it states. A
creation is answered with the status 201, with what was created and its identifier, and with its path in the
`Location` header. A save of a plan is a creation when no plan has its name, and it is answered with the
status 200 when it updates a plan. A deletion is answered with the status 204.

An operation with the method `GET` takes the method `HEAD` as well, and answers it without a body. An
operation takes only the parameters in the query string that it knows, so that a filter with a misspelled
name does not give everything: a request with another parameter gets the status 400.

The import takes its document as the body of the request. The request says which document that is: a
definitions document of this server is JSON, and an export document of Mockey 2.x is XML, which a request
declares with the header `Content-Type: application/xml`.

An answer that reports a failure is a problem document: a JSON object that states the status and says what
is wrong. The status is 400 for a body that is no JSON object and for a query string that the operation
does not take, 404 for what is not there, and 422 for a request that is not valid, where the document names
every invalid member with a message. A member that the operation does not know is not valid either. A
request that fails because the definitions location cannot be read or written gets the status 500, and the
document says what the server could not do there.

A change takes effect before its request is answered: the next request to the mock endpoint and the next
read of the API show it. In read-only mode, which is the default, a change is kept in memory, and in
writable mode it is stored in the definitions location. Changes that arrive at the same time are made one
after another, and each is checked against what the ones before it left. Of two requests that create a
service with one mock URL, one succeeds, and the other gets the status 422.

While the server has an admin token, the API takes a request only when it presents the token in the header
`Authorization: Bearer <token>`, and it answers every other request with the status 401. A request that can
change something, which is every request with a method other than `GET` and `HEAD`, gets the status 403 when
it carries an `Origin` header, as the request of a web page does. [server.md](server.md) says more of both
under "Security".

## The description

The API describes itself for the programs that use it. `GET /api/v1/openapi.json` answers with a document in
the OpenAPI format, version 3.1. For every operation the document has the method and the path, the
parameters of the path and of the query string, the body of a request, and the answers with their statuses.
The schemas of the bodies are made from the types that the server reads and writes itself, so they say what
the server takes and gives. A tool that reads OpenAPI makes a client or a reference to browse from the
document.

Every operation has a name in the document, and belongs to an area. These are the operations on plans:

```shell
curl -s http://localhost:8081/api/v1/openapi.json |
  jq -r '.paths[][] | select(.tags[0] == "Plans") | "\(.operationId): \(.summary)"'
```

```text
listPlans: Lists the plans.
savePlan: Saves the settings that services have now as a plan.
getPlan: Reads a plan.
updatePlan: Updates a plan.
deletePlan: Deletes a plan.
saveIntoPlan: Saves the settings that services have now as the items of a plan.
applyPlan: Applies a plan.
getDefaultPlan: States which plan is the default plan.
setDefaultPlan: Designates a plan as the default plan.
clearDefaultPlan: Ends the designation of the default plan.
```

The document is an answer of the API like any other: a request for it presents the admin token when the
server has one.

## Operations

In this version the admin API has these operations:

| Method and path below `/api/v1` | What it does |
|---|---|
| `GET /services` | Lists the services with their scenarios. `?tag=` leaves the services that carry one of the tags, and `?name=` the ones with that name, without regard to letter case. |
| `POST /services` | Creates a service. |
| `GET`, `PATCH`, `DELETE /services/{id}` | Reads, updates, and deletes a service. An update also designates its default scenario and its error scenario. |
| `POST /services/{id}/duplicate` | Creates a copy of a service. |
| `POST /services/{id}/merge` | Merges the services that the request names into the service. |
| `POST /services/real-urls/derive` | Derives real URLs for all services. |
| `GET`, `POST /services/{id}/scenarios` | Lists the scenarios of a service, and creates one. |
| `GET`, `PATCH`, `DELETE /services/{id}/scenarios/{scenarioId}` | Reads, updates, and deletes a scenario. |
| `GET`, `PUT`, `DELETE /universal-error-scenario` | Reads, designates, and clears the universal error scenario. |
| `GET`, `POST /plans` | Lists the plans, and saves the settings that services have now as a plan. A save under the name of a plan updates that plan. |
| `GET`, `PATCH`, `DELETE /plans/{id}` | Reads, updates, and deletes a plan. |
| `POST /plans/{id}/save` | Saves the settings that services have now as the items of the plan. |
| `POST /plans/{id}/apply` | Applies a plan, and answers with the items that it skipped and the scenario names that named no scenario. |
| `GET`, `PUT`, `DELETE /default-plan` | Reads, designates, and clears the default plan. |
| `GET`, `POST /twists` | Lists the twist configurations, and creates one. |
| `GET`, `PATCH`, `DELETE /twists/{id}` | Reads, updates, and deletes a twist configuration. |
| `POST /twists/{id}/enable`, `POST /twists/{id}/disable` | Enables and disables a twist configuration. The status of the server states the one that is enabled. |
| `GET`, `PUT`, `DELETE /tag-filter` | Reads, sets, and clears the tag filter. |
| `GET /tags` | Lists the tags that services, scenarios, and plans carry. |
| `DELETE /tags/{tag}` | Removes a tag from every service, scenario, and plan. |
| `GET`, `PATCH /upstream-proxy` | Reads and sets the outbound proxy: its URL, whether it is enabled, and its credentials. No answer holds the password. |
| `DELETE /system/upstream-session` | Empties the upstream session, which holds the cookies that real services have set. |
| `GET /inspectors` | Lists the installed inspectors, each with its name and whether it is global. |
| `GET /history` | Lists the history entries, the newest one first. `?token=` leaves the entries that satisfy every token, `?serviceId=` the ones of a service, `?after=` the ones that were recorded after an entry, and `?max=` the newest ones up to that number. |
| `DELETE /history` | Deletes all history entries, and with `?serviceId=` the ones of a service. |
| `GET`, `PATCH`, `DELETE /history/{id}` | Reads a history entry, sets and removes its mark, and deletes it. |
| `POST /history/{id}/replay` | Lets the server handle the request of an entry again, with the headers, the parameters, and the body that the request states in the place of those of the entry. The answer is the entry of the replay. |
| `POST /history/{id}/scenario` | Creates a scenario that gives the response of an entry, for the service that handled the request of the entry. |
| `GET /stats` | States for every service how many entries the history keeps of it, and when the earliest of them arrived. `?from=` and `?to=` limit the entries that count to a time range, and `?format=csv` gives the statistics as CSV. |
| `GET /openapi.json` | Gives the description of the API in OpenAPI format, see "The description". |
| `GET`, `PATCH /system` | States the status of the server, and switches the read-only mode on and off. |
| `POST /system/reload` | Puts the definitions of the definitions location in the place of those in memory. |
| `POST /system/reset` | Removes all definitions, the tag filter, the history, and the last-used times. |
| `DELETE /system/last-used-times` | Clears the times at which services, scenarios, and plans were last used. Each of them states that time as `lastUsedTime`. |
| `GET /definitions/export` | Gives all definitions as one document, which a server takes as its initial definitions. |
| `POST /definitions/import` | Imports a definitions document, or an export document of Mockey 2.x. `?tag=` gives tags to what the import adds. The answer is the report of the import. |
| `GET /diagnostics` | States what deserves attention in the definitions and blocks nothing, such as a service that names an inspector which is not installed. |
| `GET /services/{id}/schema-report` | States which scenarios of a service conform to its response schema. |
| `GET /search` | Finds the services, scenarios, and plans that hold the text of `?term=`, without regard to letter case. |

## Examples

The examples follow one another: each builds on what the ones before it defined. Every block of commands
stands by itself all the same, and reads the identifiers that it needs from the server.

### The status of the server

```shell
curl -s http://localhost:8081/api/v1/system | jq .
```

```json
{
  "version": "3.0.0-rc.1",
  "startTime": "2026-11-25T06:27:35.314Z",
  "definitionsLocation": "/srv/mockey/mockey-definitions",
  "readOnly": true,
  "tagFilter": [],
  "enabledTwist": null,
  "mockBaseUrl": "http://localhost:8080",
  "legacyShimEnabled": false
}
```

The mock URL of a service follows `/service/` after the `mockBaseUrl`.

### A service with two scenarios

A creation states the attributes that differ from their defaults. The answer is the service as the server
holds it, with the identifier that the server gave it:

```shell
curl -s -X POST http://localhost:8081/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{"name": "Customers", "mockUrl": "customer/{id}", "responseMode": "static"}' | jq .
```

```json
{
  "id": "e7325e2b-832d-4b79-b3b7-fb69903eb1d8",
  "name": "Customers",
  "mockUrl": "customer/{id}",
  "description": null,
  "realUrls": [],
  "defaultRealUrlIndex": 0,
  "responseMode": "static",
  "effectiveResponseMode": "proxy",
  "delayMillis": 0,
  "tags": [],
  "scenarios": [],
  "defaultScenarioId": null,
  "errorScenarioId": null,
  "requestRules": null,
  "requestRulesEnabled": false,
  "inspectorName": null,
  "responseSchema": null,
  "responseSchemaEnabled": false,
  "lastUsedTime": null
}
```

The service is in static mode and has no scenario to answer with yet, so it forwards requests for now, as
`effectiveResponseMode` says. `lastUsedTime` is the time at which the service last answered a request. It is
`null` here, because the service has answered none since the server started. The server keeps these times in
memory only. The scenarios of a service are resources below it. The first scenario of a service becomes its
default scenario, which answers in static mode:

```shell
api=http://localhost:8081/api/v1
service=$(curl -s "$api/services?name=Customers" | jq -r '.[0].id')

curl -s -X POST "$api/services/$service/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"name": "found", "responseBody": "{\"name\": \"Ada\"}"}' | jq -c '{name, statusCode}'
curl -s -X POST "$api/services/$service/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"name": "missing", "statusCode": 404, "responseBody": "no such customer"}' | jq -c '{name, statusCode}'

curl -s -w '\n%{http_code}\n' http://localhost:8080/service/customer/7
```

```text
{"name":"found","statusCode":200}
{"name":"missing","statusCode":404}
{"name": "Ada"}
200
```

### A request that is not valid

Two services cannot have one mock URL. The problem document names the member that is not valid:

```shell
curl -s -X POST http://localhost:8081/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{"name": "Clients", "mockUrl": "customer/{id}"}' | jq .
```

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "The request is not valid. mockUrl: The service 'Customers' already uses the mock URL pattern 'customer/{id}'.",
  "errors": [
    {
      "field": "mockUrl",
      "message": "The service 'Customers' already uses the mock URL pattern 'customer/{id}'."
    }
  ]
}
```

### Another default scenario

An update states the attributes that change. The next request to the mock endpoint gets the answer of the
new default scenario:

```shell
api=http://localhost:8081/api/v1
service=$(curl -s "$api/services?name=Customers" | jq -r '.[0].id')
missing=$(curl -s "$api/services/$service/scenarios" | jq -r '.[] | select(.name == "missing") | .id')

curl -s -X PATCH "$api/services/$service" \
  -H 'Content-Type: application/json' \
  -d "{\"defaultScenarioId\": \"$missing\"}" | jq -c '{name, defaultScenarioId}'

curl -s -w '\n%{http_code}\n' http://localhost:8080/service/customer/7
```

```text
{"name":"Customers","defaultScenarioId":"028b7642-f4bc-4ba0-9957-5cdaf1ebe1cc"}
no such customer
404
```

### A plan

A plan holds the settings that services have when it is saved: the response mode, the delay, and the default
scenario of each.

```shell
api=http://localhost:8081/api/v1
service=$(curl -s "$api/services?name=Customers" | jq -r '.[0].id')

curl -s -X POST "$api/plans" \
  -H 'Content-Type: application/json' \
  -d "{\"serviceIds\": [\"$service\"], \"name\": \"Customer is missing\"}" | jq .
```

```json
{
  "id": "e411580e-b03e-4a20-9da0-eff6f07fcf35",
  "name": "Customer is missing",
  "description": null,
  "tags": [],
  "items": [
    {
      "serviceName": "Customers",
      "scenarioName": "missing",
      "responseMode": "static",
      "delayMillis": 0
    }
  ],
  "lastUsedTime": null
}
```

Applying the plan gives the services these settings back, whatever they have by then. The answer names the
items of the plan that found no service, and the scenario names that named no scenario:

```shell
api=http://localhost:8081/api/v1
service=$(curl -s "$api/services?name=Customers" | jq -r '.[0].id')
found=$(curl -s "$api/services/$service/scenarios" | jq -r '.[] | select(.name == "found") | .id')
plan=$(curl -s "$api/plans" | jq -r '.[] | select(.name == "Customer is missing") | .id')

curl -s -o /dev/null -X PATCH "$api/services/$service" \
  -H 'Content-Type: application/json' \
  -d "{\"defaultScenarioId\": \"$found\"}"
curl -s -w '\n%{http_code}\n' http://localhost:8080/service/customer/7

curl -s -X POST "$api/plans/$plan/apply" | jq -c '{skippedItems, unresolvedScenarios}'
curl -s -w '\n%{http_code}\n' http://localhost:8080/service/customer/7
```

```text
{"name": "Ada"}
200
{"skippedItems":[],"unresolvedScenarios":[]}
no such customer
404
```

### The history

The history lists the exchanges at the mock endpoint, the newest one first. A token leaves the entries that
hold its text, in the URL of the request or in a body for example, and a token that begins with `!` leaves
the entries that do not hold the rest of it. `max` leaves the newest entries up to that number:

```shell
curl -s 'http://localhost:8081/api/v1/history?token=customer&max=2' |
  jq -c '.[] | {id, target, status: .response.status, scenario: .scenario.name}'
```

```text
{"id":4,"target":"customer/7","status":404,"scenario":"missing"}
{"id":3,"target":"customer/7","status":200,"scenario":"found"}
```

A replay lets the server handle the request of an entry again, with what the request states in the place of
the headers, the parameters, and the body of the entry. The answer is the entry of the replay. An entry also
becomes a scenario of its service, which answers with the response of the entry:

```shell
api=http://localhost:8081/api/v1

curl -s -X POST "$api/history/1/replay" \
  -H 'Content-Type: application/json' \
  -d '{"headers": [{"name": "Accept", "value": "application/json"}]}' |
  jq -c '{id, status: .response.status, scenario: .scenario.name}'
curl -s -X POST "$api/history/1/scenario" \
  -H 'Content-Type: application/json' \
  -d '{"name": "Ada, as recorded"}' | jq -c '{name, statusCode, responseBody}'
```

```text
{"id":5,"status":404,"scenario":"missing"}
{"name":"Ada, as recorded","statusCode":200,"responseBody":"{\"name\": \"Ada\"}"}
```

### Export and import

The export gives all definitions as one document. A server takes that document as its initial definitions,
and the import adds its definitions to those of a running server. Here the server gets back what a reset
removed, and every service, scenario, and plan that the import adds gets a tag:

```shell
api=http://localhost:8081/api/v1

curl -s -o definitions.json "$api/definitions/export"
curl -s -X POST "$api/system/reset"
curl -s "$api/services" | jq -c .

curl -s -X POST "$api/definitions/import?tag=restored" \
  -H 'Content-Type: application/json' \
  --data-binary @definitions.json | jq -c '.additions[] | {kind, name}'
```

```text
[]
{"kind":"service","name":"Customers"}
{"kind":"scenario","name":"found"}
{"kind":"scenario","name":"missing"}
{"kind":"scenario","name":"Ada, as recorded"}
{"kind":"plan","name":"Customer is missing"}
```

### With an admin token

A server with an admin token takes a request only with the token:

```shell
export MOCKEY_ADMIN_TOKEN=a-long-text-that-nobody-guesses
mockey-server
```

```shell
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/v1/services
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'Authorization: Bearer a-long-text-that-nobody-guesses' http://localhost:8081/api/v1/services
```

```text
401
200
```
