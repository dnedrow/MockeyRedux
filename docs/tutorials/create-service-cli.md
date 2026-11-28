# Tutorial: Create a service from the command line

This tutorial walks through creating a Mockey service with nothing but `curl` and the admin API. By the end,
you'll have a mock that forwards requests to a live government API, a scenario recorded from a real response,
and a hand-written scenario that the mock serves without calling the real service at all.

The real service is the NHTSA vehicle API (vPIC), a free public API from the U.S. National Highway Traffic
Safety Administration. The tutorial uses one call that lists the models for make 440, which is Aston Martin:

```text
https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeId/440?format=json
```

Mockey has no separate command-line client. The admin API is plain JSON over HTTP, so `curl` is all you need.
If you'd rather click through the same steps, see [Create a service in the UI](create-service-ui.md).

## What you need

- Java 21 or later.
- `curl`.
- `jq` is optional. It's only used to pull values out of JSON responses and to pretty-print them.
- Network access to `https://vpic.nhtsa.dot.gov`.

## Step 1: Start the server

Build or download the runnable server jar, then start it. The [README](../../README.md#quick-start) covers the
download. From a source checkout, build the jar like this:

```shell
./gradlew :mockey-server:assemble
```

Then start the server:

```shell
java -jar mockey-server/build/libs/mockey-server-3.0.0-rc.2-runnable.jar
```

The server listens on three ports:

| Port   | What it serves                       |
|--------|--------------------------------------|
| `8080` | The mocks, under `/service/...`      |
| `8081` | The admin API, under `/api/v1/...`   |
| `8082` | The UI, when `mockey-ui` is running  |

The admin API only answers on the loopback interface, so these steps need to run on the same machine.

Check that the server is up:

```shell
curl http://localhost:8080/status
```

```json
{"status":"running","since":"2026-11-28T00:16:55.483Z"}
```

By default, the server keeps everything in memory, so the service disappears when the server stops. Start it
with `--writable` to save definitions in the `mockey-definitions` folder instead.

### A note about certificates

Java doesn't use the operating system's certificate store. It uses its own `cacerts` file. On a company
network with a TLS-inspecting proxy, Java usually won't trust the certificate that the proxy presents for
`vpic.nhtsa.dot.gov`, and the mock answers with a `502` like this one:

```json
{
  "fail": "The real service at https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeId/440 gave no complete answer: its certificate was not accepted. The Java runtime does not trust the issuer ... PKIX path building failed: ...",
  "proxyInfo": "No outbound proxy is enabled."
}
```

On macOS, the quickest fix is to tell Java to use the keychain, which already trusts the company CA:

```shell
java -Djavax.net.ssl.trustStoreType=KeychainStore -Djavax.net.ssl.trustStore=NONE \
  -jar mockey-server/build/libs/mockey-server-3.0.0-rc.2-runnable.jar
```

For other platforms and other options, see
[The certificate of a real service](../server.md#the-certificate-of-a-real-service).

## Step 2: Create the service

A service ties a mock URL pattern to one or more real URLs. Create one with a `POST` to `/api/v1/services`:

```shell
curl -i -X POST http://localhost:8081/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "NHTSA vPIC models",
        "mockUrl": "vpic/models",
        "responseMode": "proxy",
        "realUrls": ["https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeId/440?format=json"],
        "description": "Models for make 440 (Aston Martin) from the NHTSA vPIC API"
      }'
```

Here's what each field does:

- `name` is the label shown in the UI and in the history.
- `mockUrl` is the pattern that clients call, relative to `http://localhost:8080/service/`.
- `responseMode` decides where the answer comes from. `proxy` forwards each request to a real URL, `static`
  serves a saved scenario, and `dynamic` picks a scenario that matches the request.
- `realUrls` lists the real service URLs. With one URL, it's also the default.
- `description` is optional free text.

The server answers with `201 Created`. The `Location` header and the `id` field both identify the new service:

```text
HTTP/1.1 201 Created
Location: /api/v1/services/27e894a3-e8b8-444c-bdd0-880a10a71288
Content-Type: application/json
```

```json
{
  "id": "27e894a3-e8b8-444c-bdd0-880a10a71288",
  "name": "NHTSA vPIC models",
  "mockUrl": "vpic/models",
  "description": "Models for make 440 (Aston Martin) from the NHTSA vPIC API",
  "realUrls": ["https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeId/440?format=json"],
  "defaultRealUrlIndex": 0,
  "responseMode": "proxy",
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

The rest of this tutorial uses the service id a few times, so save it in a shell variable. Your id will be
different from the one shown here:

```shell
SERVICE_ID=27e894a3-e8b8-444c-bdd0-880a10a71288
```

The mock URL has to be unique. A second service with the same `mockUrl` gets a `422` response.

## Step 3: Call the mock

Call the mock URL the same way a client would:

```shell
curl -i http://localhost:8080/service/vpic/models
```

Because the service is in proxy mode, Mockey forwards the request to the real URL and passes the answer back:

```text
HTTP/1.1 200 OK
Content-Type: application/json
Access-Control-Allow-Origin: *
Content-Length: 1571
```

```json
{
  "Count": 18,
  "Message": "Response returned successfully",
  "SearchCriteria": "MakeId:440",
  "Results": [
    {"Make_ID": 440, "Make_Name": "Aston Martin", "Model_ID": 1684, "Model_Name": "V8 Vantage"},
    {"Make_ID": 440, "Make_Name": "Aston Martin", "Model_ID": 1686, "Model_Name": "DBS"},
    ...
  ]
}
```

The `Results` array is shortened here. The full response lists 18 models, from the V8 Vantage to the Valhalla.

If the request to the mock has a query string, Mockey appends it to the real URL it forwards to. If the
service has no real URL at all, the mock answers with a `502`.

## Step 4: Look at the history

Every call to a mock is recorded in the history. Read it with a `GET`, newest entry first:

```shell
curl -s http://localhost:8081/api/v1/history | jq '.[] | {id, time, outcome, url: .request.url}'
```

```json
{
  "id": 1,
  "time": "2026-11-28T00:17:28.966368Z",
  "outcome": "proxied",
  "url": "http://localhost:8080/service/vpic/models"
}
```

The `outcome` of `proxied` means the answer came from the real service. Each entry also holds the full request
and response. The response body is base64-encoded, and its `size` and `isCut` fields say how big it was and
whether the history kept all of it.

## Step 5: Save the response as a scenario

A scenario is a saved response that the mock can serve without calling the real service. The easiest way to
get a realistic one is to save it from a history entry. Post the scenario name to the entry's `scenario`
endpoint:

```shell
curl -i -X POST http://localhost:8081/api/v1/history/1/scenario \
  -H 'Content-Type: application/json' \
  -d '{"name": "Aston Martin models"}'
```

The server answers with `201 Created`:

```text
HTTP/1.1 201 Created
Location: /api/v1/services/27e894a3-e8b8-444c-bdd0-880a10a71288/scenarios/5a6ba567-5cbc-46d4-80f2-e64aee9c5001
```

The new scenario copies the status, the headers, and the body of the recorded response. The header names come
back in lowercase, the way the real service sent them:

```json
{
  "id": "5a6ba567-5cbc-46d4-80f2-e64aee9c5001",
  "name": "Aston Martin models",
  "statusCode": 200,
  "headers": [
    {"name": "access-control-expose-headers", "value": "Request-Context"},
    {"name": "cache-control", "value": "no-cache"},
    {"name": "content-type", "value": "application/json"},
    {"name": "expires", "value": "-1"},
    {"name": "pragma", "value": "no-cache"},
    {"name": "x-content-type-options", "value": "nosniff"},
    {"name": "x-frame-options", "value": "SAMEORIGIN"},
    ...
  ],
  "responseBody": "{\"Count\":18,\"Message\":\"Response returned successfully\",...}",
  "matchCriterion": {"type": "none"},
  "methodRestriction": null,
  "delayMillis": 0,
  "tags": [],
  "lastUsedTime": null
}
```

The first scenario of a service becomes its default scenario automatically.

## Step 6: Serve the scenario instead of the real service

Switch the service to static mode with a `PATCH`. Only the fields in the body change:

```shell
curl -s -X PATCH http://localhost:8081/api/v1/services/$SERVICE_ID \
  -H 'Content-Type: application/json' \
  -d '{"responseMode": "static"}' | jq '{responseMode, defaultScenarioId}'
```

```json
{
  "responseMode": "static",
  "defaultScenarioId": "5a6ba567-5cbc-46d4-80f2-e64aee9c5001"
}
```

Call the mock again. The answer is the same list of 18 models, but now it comes from the scenario, and the
history records the call with the outcome `scenarioServed`:

```shell
curl -s http://localhost:8080/service/vpic/models | jq '.Count'
```

```text
18
```

A static service with no scenarios at all falls back to proxy mode, so it's safe to switch modes before any
scenario exists.

## Step 7: Write a scenario by hand

Scenarios don't have to come from the history. This one describes an edge case that's hard to get from the
real service: a make with no models. Post it to the service's `scenarios` collection:

```shell
curl -i -X POST http://localhost:8081/api/v1/services/$SERVICE_ID/scenarios \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "No models",
        "statusCode": 200,
        "headers": [{"name": "Content-Type", "value": "application/json"}],
        "responseBody": "{\"Count\":0,\"Message\":\"Response returned successfully\",\"SearchCriteria\":\"MakeId:440\",\"Results\":[]}"
      }'
```

```text
HTTP/1.1 201 Created
Location: /api/v1/services/27e894a3-e8b8-444c-bdd0-880a10a71288/scenarios/8712d498-35a4-4abc-9520-84efe01a6a84
```

Include the `Content-Type` header. A scenario without one sends its body with no content type at all.

## Step 8: Change the default scenario

In static mode, the mock serves the default scenario. Point the service at the new one:

```shell
curl -s -X PATCH http://localhost:8081/api/v1/services/$SERVICE_ID \
  -H 'Content-Type: application/json' \
  -d '{"defaultScenarioId": "8712d498-35a4-4abc-9520-84efe01a6a84"}' > /dev/null
```

Call the mock one last time:

```shell
curl -i http://localhost:8080/service/vpic/models
```

```text
HTTP/1.1 200 OK
Content-Type: application/json
Access-Control-Allow-Origin: *
Cache-Control: no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0
Pragma: no-cache
Expires: Tue, 03 Jul 2001 06:00:00 GMT
Last-Modified: Sat, 28 Nov 2026 00:24:53 GMT
Content-Length: 97

{"Count":0,"Message":"Response returned successfully","SearchCriteria":"MakeId:440","Results":[]}
```

The scenario didn't set any caching headers, so Mockey added its own to keep clients from caching the mock.

## Step 9: Save the definitions

The server started in Step 1 keeps everything in memory, so the service and its scenarios are gone once it
stops. There are two ways to keep them: export them to a file, or let the server save them as you go.

### Export to a file

The export endpoint returns every service and scenario as one JSON document. Save it with `-o`:

```shell
curl -s -o definitions.json http://localhost:8081/api/v1/definitions/export
```

The file starts like this:

```json
{
    "formatVersion": 1,
    "services": [
        {
            "id": "27e894a3-e8b8-444c-bdd0-880a10a71288",
            "name": "NHTSA vPIC models",
            "mockUrl": "vpic/models",
```

The file is plain text, so it can be checked into source control next to the code that uses the mock.

### Import the file

To see an import in action, first clear the server. The reset endpoint removes every service:

```shell
curl -s -X POST http://localhost:8081/api/v1/system/reset
curl -s http://localhost:8081/api/v1/services
```

```json
[]
```

Then post the file back to the import endpoint. Use `--data-binary` rather than `-d`, so `curl` sends the file
exactly as it is:

```shell
curl -s -X POST http://localhost:8081/api/v1/definitions/import \
  -H 'Content-Type: application/json' \
  --data-binary @definitions.json | jq -c '.additions[] | {kind, name}'
```

```text
{"kind":"service","name":"NHTSA vPIC models"}
{"kind":"scenario","name":"Aston Martin models"}
{"kind":"scenario","name":"No models"}
```

An import adds to what the server already has. It doesn't replace it. Add `?tag=<name>` to the import URL to
tag everything that it brings in.

### Load the file at startup

Rather than importing by hand, the server can load the file when it starts. The `--initial-definitions`
option replaces the stored definitions with the file's contents:

```shell
java -jar mockey-server/build/libs/mockey-server-3.0.0-rc.2-runnable.jar \
  --initial-definitions definitions.json
```

The option also takes a folder or a URL.

### Let the server save changes

Start the server with `--writable` and it saves each change as it's made, in the `mockey-definitions` folder.
The next start with `--writable` picks up where the last one left off:

```shell
java -jar mockey-server/build/libs/mockey-server-3.0.0-rc.2-runnable.jar --writable
```

Add `--definitions <folder>` to save somewhere else. The folder holds one file per service and scenario, so
changes show up clearly in a diff.

## Going further: a path variable

The service above always asks for make 440. A mock URL pattern can capture part of the path in a `{name}`
placeholder, and the same placeholder in a real URL is filled in from it. This service works for any make:

```shell
curl -s -X POST http://localhost:8081/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "NHTSA vPIC models by make",
        "mockUrl": "vpic/makes/{makeId}/models",
        "responseMode": "proxy",
        "realUrls": ["https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeId/{makeId}?format=json"]
      }' > /dev/null

curl -s http://localhost:8080/service/vpic/makes/440/models | jq '.SearchCriteria'
```

```text
"MakeId:440"
```

## Next steps

- [Create a service in the UI](create-service-ui.md) walks through the same steps in the browser.
- [The admin API](../admin-api.md) documents every endpoint and field used here, including
  [export and import](../admin-api.md#export-and-import).
- [The server](../server.md) covers the ports, the mock endpoint, saving definitions, and certificates.
