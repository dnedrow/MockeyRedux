# Parity recordings

The `mockey-parity-tests` module stores each recording as a directory containing:

- `label.txt`: `same`, a deviation ID, or `unreviewed` for a recording that has not been reviewed yet.
- `fixture.xml`: the legacy XML fixture used by the case.
- `request.properties`: HTTP method, target, headers, and a base64-encoded body.
- `expected-response.properties`: status, headers, and a base64-encoded response body.
- `timing.properties`, for delay cases only: the bounds on the response time (`atLeastMillis` and
  optionally `lessThanMillis`) and the time observed when recording (`recordedMillis`).

`ParityRecorder` records a case against a supplied base URL. `ParityReplayer` sends
the stored request to another base URL and compares status, stable headers, and body
bytes. Headers whose values are expected to vary between requests (`Date`,
`Last-Modified`, `Set-Cookie`, and `Content-Length`) are excluded from comparison.
For a case with timing bounds the replayer also checks the response time, and the
recorder refuses to store a response that is outside the bounds.

The trivial legacy status recording can be replayed after starting the reference
server from `docs/parity/running-legacy.md`:

```shell
./gradlew :mockey-parity-tests:test \
  -Dmockey.parity.legacy.baseUrl=http://127.0.0.1:18080
```

## Stub upstream and outbound proxy

`StubHttpServer.upstream()` and `StubHttpServer.outboundProxy()` (in `mockey-parity-tests`) are loopback stubs that record every request received (`requests`) and return a fixed response. Point a legacy or new Mockey proxy case at their `port`.

## Routing cases (task 2.4)

Thirteen `routing-*` recordings cover relative and absolute request targets,
URL templates, competing services, nested prefixes, tag filtering (`-F keep`),
and real-URL proxying. Re-record with `scripts/record-routing.sh <legacy-dir>`.

Legacy behaviors captured (verified by probing):

- A request that matches no service auto-creates a PROXY service for the
  request URL, so `routing-url-template-nomatch` and `routing-unknown-target`
  are proxied to the stub upstream.
- A template service matches only when the request path contains a literal
  `{...}` segment (`customer/%7Bx%7D`); `customer/123` does not match it.
  Scenario plain matches run against the raw request including the query
  string, so `?id=123` and `?id=456` select different scenarios.
- `routing-real-url-template` yields the same body whether the template or the
  auto-proxy handled it, so it does not prove template matching.
- Twist (URL rewriting) is not recorded: it must be enabled through the
  twist toggle endpoint, which the harness cannot yet do. Tracked as a gap.

## Response cases (task 2.5)

Sixty-seven `response-*` recordings cover static and dynamic responses. `ResponseCases`
defines them in ten fixture groups, one per `parity-fixtures/responses-*.xml` fixture:

| Fixture group | Cases | Coverage |
|---|---|---|
| `responses-static` | 4 | The default scenario answers whatever the request, the match criteria, or the method restrictions. A default scenario ID that refers to no scenario. |
| `responses-status-headers` | 7 | Status 201, 302, and 503. Several headers, repeated header names, a missing header element, and malformed header text. |
| `responses-body-charset` | 10 | Body bytes: the trailing line break, an empty body, surrounding whitespace, and markup. Encoding with and without a declared charset. |
| `responses-text` | 10 | Text criteria against query parameters and the body, decoding, literal path text, and the value of a template variable. |
| `responses-rules` | 13 | Rule criteria for parameters, headers, the body, and the URL, each satisfied and unsatisfied. The rule score, rule groups, and a malformed rule set. |
| `responses-methods` | 6 | Method restrictions: the same method, another method, `*`, no restriction, and lower case. |
| `responses-ties` | 6 | The higher score wins. Equal scores go to the scenario name that sorts first, and then to the lower scenario ID. |
| `responses-fallback` | 3 | No match: the service error scenario and the built-in response. A scenario without a match criterion. |
| `responses-universal-error` | 2 | No match with a universal error scenario: the service error scenario comes first, the universal one second. |
| `responses-delays` | 6 | The service delay, a scenario delay longer or shorter than the service delay, and delays when no scenario matches. |

Re-record with `scripts/record-responses.sh <legacy-dir>`. The script runs one legacy
instance in a scratch directory. `ResponseCasesTest.recordsGroupFromLegacy` loads each
fixture through the legacy initialization endpoint (`LegacyFixtureLoader`) and then
records its cases. To record one group, run that test with `mockey.parity.recordGroup`
set to the group name.

The recordings are labeled `unreviewed`. Task 2.9 assigns `same` or a deviation ID.

Legacy behaviors captured (each is visible in the named recording):

- Every body ends with an added line break (DEV-06). `response-body-trailing-newline`
  has 12 bytes for an 11-character body, and `response-body-empty` has one byte.
- No match and no error scenario gives the explanation with status 200 (DEV-07):
  `response-fallback-builtin`. Legacy sets the status to -1 and the container keeps 200.
- A body is encoded as ISO-8859-1 unless some header value contains `utf-8` (DEV-08).
  Characters outside ISO-8859-1 become `?`, also under `Content-Type: application/json`
  (`response-charset-json`). A header other than `Content-Type` that mentions UTF-8
  switches the encoding (`response-charset-other-header`).
- A scenario without a `scenario_response_header` element answers with
  `Accept-Language: en-US` and `Accept: text/plain` (DEV-09): `response-headers-default`.
- Header text becomes a map. A repeated name keeps its last value
  (`response-headers-repeated`), and an entry without a colon is dropped
  (`response-headers-malformed`).
- Loading the XML trims the whitespace around a response body (`response-body-whitespace`).
- The evaluation text joins the parameters as `name=value` with `&` in the order of the
  container's parameter map, not in request order (`response-text-params-reordered`).
  The whole text is then form-decoded: a `+` in a body matches a space
  (`response-text-body-plus`), and a `%` that does not start an escape fails the request
  with status 500 (`response-text-body-percent`).
- The value of a template variable is evaluated only when the braces arrive unencoded.
  For `%7B123%7D` the service is found but the value is not evaluated
  (`response-text-template-value`).
- Equal scores go to the lower scenario ID when the names are equal too, whatever the
  order of the scenarios in the document (`response-tie-id-order`).
- When no scenario matches, the delay of the error scenario is ignored and the service
  delay applies (`response-delay-error-scenario`, `response-delay-fallback-service`).
- A default scenario ID that refers to no scenario gives `NO SCENARIO SELECTED` with
  status 200 (`response-static-dangling-default`).

Not recorded:

- Status 204. Legacy answers with `Transfer-Encoding: chunked` and an empty chunked
  body, which the JDK HTTP client of the harness rejects.
- Unencoded braces in a request target, which the JDK HTTP client cannot send. Sent
  over a raw socket, `/service/text-customer/{123}` selects the `customer 123` scenario.
- Absent rule keys, wildcard keys, and form parameters (DEV-15 to DEV-17). They belong
  to the request-rule cases of task 2.7.
- That a delayed request does not hold up another request. A case holds one request.
