# Parity recordings

The `mockey-parity-tests` module stores each recording as a directory containing:

- `label.txt`: the verdict of the review. It is `same` when the new server answers as legacy does, or the IDs
  of the deviations that make the new server answer differently, such as `DEV-06` or `DEV-06 DEV-08` (in
  ascending order, separated by one space). A recording that nobody has reviewed yet is `unreviewed`.
- `fixture.xml`: the legacy XML fixture used by the case.
- `request.properties`: HTTP method, target, headers, and a base64-encoded body.
- `expected-response.properties`: the response of legacy Mockey, with status, headers, and a base64-encoded
  body. `snapshot=true` marks a response whose body holds times or session IDs.
- `expected-new-response.properties`, for a recording that is labeled with deviations: the response of the new
  server, as far as the specifications define it. See "Stated new responses" below.
- `timing.properties`, for delay cases only: the bounds on the response time (`atLeastMillis` and
  optionally `lessThanMillis`) and the time observed when recording (`recordedMillis`).
- `before-1.request.properties`, `before-2.request.properties`, and so on, for cases that need earlier
  requests: the requests that are sent, in that order, before the recorded one. They bring the server into
  the state that the case needs, such as an upstream cookie. Their responses are not recorded or compared.

`ParityRecorder` records a case against a supplied base URL. `ParityReplayer` sends the stored requests to
another base URL and compares the response. Replayed against legacy (`ParityServer.LEGACY`), the response is
compared with the recorded one. Replayed against the new server (`ParityServer.NEW`), it is compared with the
recorded one when the label is `same`, and with the stated new response when the label names deviations. For a
case with timing bounds the replayer also checks the response time, and the recorder refuses to store a
response that is outside the bounds.

The harness sends every request over HTTP/1.1 with the header `User-Agent: mockey-parity`, unless the case
sets its own, and gives up on a request after 30 seconds. What a server receives therefore does not depend
on the JDK that runs the harness: the default JDK client would add HTTP/2 upgrade headers and its own
version to every request, and a proxying server forwards those upstream.

## How responses are compared

`ResponseComparison` holds the rules. They depend on the endpoint.

A response of the mock endpoint, which is every target under `/service/`, is compared whole:

- The status.
- Every header, without regard to the letter case of its name, and with the values of a repeated header in
  their order. These are left out because they describe the connection or the server software: `Connection`,
  `Content-Length`, `Date`, `Keep-Alive`, `Server`, and `Transfer-Encoding`. Of `Last-Modified`, whose value is
  the time of the response, only the presence counts. A `Content-Type` is compared by its media type and
  parameters, not by its spelling: `text/plain;charset=ISO-8859-1` equals `text/plain; charset=iso-8859-1`.
- The body, byte for byte.

For every other endpoint the specifications define a status and a result but no headers. There only the status
and the body are compared. A body that is a JSON object or array is compared as a document: the order of the
members of an object and the whitespace between tokens do not count. Any other body is compared byte for byte.

Of a snapshot only the status is compared.

Some cases look at what the client addressed: a `url` rule, and a header rule that reads `Host`. Replay them
against a server on `127.0.0.1`, as they were recorded.

## Stated new responses

`expected-new-response.properties` states what the specifications require of the new server for a recording
that deviates. A reviewer writes it by hand, in the format of Java properties:

| Key | Meaning |
|---|---|
| `status` | The status. |
| `header.N.name`, `header.N.value` | The headers, numbered from 0, for a response of the mock endpoint only. They are compared as described above. A value that ends in `<any>` says only how the value starts: `text/plain<any>` leaves the parameters of a content type open, and `<any>` alone asks for no more than the header. |
| `body.text` | The exact body as UTF-8 text. |
| `body` | The exact body, base64-encoded, for a body that is not readable UTF-8 text. |
| `body.json` | A JSON document that the body must equal. The string `"<any>"` stands for any value, which is how an identifier of the new server is written. |
| `body.contains.N` | Texts, numbered from 0, that the body must contain. For built-in explanations, whose wording the specifications leave open. |
| `body.unspecified=true` | The specifications say nothing about the body. |
| `timing.atLeastMillis`, `timing.lessThanMillis` | Bounds on the response time of the new server, where they differ from those of the case. |

A file states the body in exactly one of the five ways.

## Review of the recordings (task 2.9)

Every recording was reviewed against the specifications of the change and labeled. Of the 267 recordings, 36
are `same` and 231 name deviations, 145 of them DEV-06, the line break that legacy appends to every scenario
response. `docs/deviations.md` describes each deviation and names recordings that show it. The 13 recordings
of the group `routing-normalization` were added and reviewed with task 4.1, the 13 recordings of the groups
`rules-malformed` and `rules-values` with tasks 5.1 and 5.2, and the 8 recordings of the groups
`responses-parameters` and `responses-builtin-delay` with tasks 6.2 and 6.3.

`ParityReviewTest.everyRecordingIsReviewed` is the check. It fails when a recording is `unreviewed`, when a
label names an ID that `docs/deviations.md` does not list, when a recording that deviates states no new
response, or when a `same` recording states one.

Recording again keeps a review that still holds. `ParityRecorder` keeps the label and the stated new response
of a recording when its fixture, its requests, and its timing bounds are unchanged and legacy answers as
before, under the comparison rules above. Otherwise it stores the recording as `unreviewed`, and the check
fails until someone reviews it again.

`scripts/record-cases.sh <legacy-dir> all --replay` sends every recording to legacy again and fails on a
difference. It passed after the review, so the recordings are reproducible and the comparison rules leave out
nothing but what changes from run to run.

Where the specifications say nothing about a behavior, the legacy response is the expected one, because a
behavior that is not in the deviation register stays the same. The review relied on that for these points,
which the specifications should settle:

- Import trims the whitespace around a response body, as the legacy loader does (`response-body-whitespace`).
- The evaluation text is form-decoded, so that a `+` becomes a space (`response-text-body-plus`). The
  specification says "percent-decode". A target is not read that way: there a `+` is a `+` (DEV-52).
- Nothing stands between the parameters and the body in the evaluation text (`response-text-param-then-body`),
  and a query string is read without the parameters that are empty (`response-text-param-empty-piece`).
- The built-in response for a request that no scenario matches waits for the delay of the service
  (`response-delay-builtin`).
- Imported scenarios count as created in the order of their legacy IDs, which decides a tie between scenarios
  of the same name (`response-tie-id-order`).
- A rule argument that is a JSON number is read as the text of that number (`rule-number-argument`).
- A request body of nothing but spaces gives a body rule nothing to evaluate (`rule-input-body-blank`).
- A character that the declared charset cannot encode is sent as `?` (`response-charset-latin1`).
- The automation endpoints answer with the legacy texts, such as `Loaded definitions from suite-a.xml`, write
  the string `null` for a missing inspector name, list scenarios in the order of their names, find a service
  name without regard to letter case, list every service for an unknown name or identifier, and report
  `updated` for a scenario name that the service does not have (the `automation-*` recordings labeled `same`
  or DEV-20).

Two deviations rest on a reading of the specifications that should be confirmed: DEV-35, the order of the
parameters in the evaluation text, and DEV-36, a `%` that starts no escape.

A request that uses a legacy number as an identifier finds nothing on the new server (DEV-20). The three
`automation-plan-save-*` recordings pass service numbers in `service_ids[]`, the only way to name the
services of a plan. The specifications do not say what saving a plan for unknown services answers, so these
recordings state only the result envelope.

## Replaying against the new server (task 11.8)

`NewServerParityTest` replays the recordings against the new server with every run of the tests of
`mockey-parity-tests`, so it needs no legacy Mockey. A recording that is labeled `same` has to be answered as
legacy answered it, and one that is labeled with deviations as its stated new response says. The test fails
with the name of every recording that is answered otherwise, and with what differs.

Every case gets a server of its own, so that it starts from the definitions of its fixture and an empty
upstream session, as a case does on legacy. The server runs in the process of the test. `ServerUnderTest`, a
test fixture of `mockey-server`, starts it from the arguments of the program, which it reads as the program
does: the mock listener on `127.0.0.1` with a free port, read-only mode, and the settings of the group. The
requests go to the server over HTTP, as they went to legacy.

The fixture of a group gets into its servers through the importer of legacy definitions, and the test replays
every recording once for each of two ways:

- As initial definitions (`--initial-definitions <fixture>`): every server imports the fixture when it starts
  and keeps the definitions in memory.
- Through the import command: `mockey-server import-legacy <fixture> --definitions <directory>` stores the
  definitions once for the group, and every server loads them from that directory. This is how an
  installation of Mockey 2.x moves over, and it shows that nothing gets lost on the way through the stored
  files.

The settings that a fixture cannot carry are applied as `LegacyGroupRecorder` applies them to legacy. The tag
filter is a setting of the program (`--tag-filter`). The enabled twist configuration of the group
`routing-twist` and the credentials of the group `proxy-outbound-credentials` are set in the running server.
The program has no setting for them, and the server has no admin API yet, so `ServerUnderTest` sets them in
the engine of the server. Once the admin API can set them, the test should use it.

The stub upstream and the two stub outbound proxies run during the replay, on the ports 18091 to 18093 that
the fixtures name. Run the replay alone with
`./gradlew :mockey-parity-tests:test --tests '*NewServerParityTest'`.

The result of the first run: the server answered each of the 223 recordings that it can answer as the label
says, in both ways, so no difference had to be fixed. Of these recordings 30 are `same` and 193 name
deviations.

44 recordings are not replayed yet. Each of them sends a request to one of the legacy automation endpoints
`/config/service`, `/plan/setup`, `/definitions`, and `/home`, which the server answers only through the
legacy shim (`legacy-compatibility`: The legacy shim is opt-in). The shim comes with the tasks of group 14,
and task 14.6 replays these recordings against a server with the shim enabled. They are 41 of the 43
`automation-*` recordings and the three routing recordings that read `/definitions` to see which services a
request left behind: `routing-unknown-target-registered`, `routing-unknown-target-with-escape-registered`,
and `routing-tag-hidden-absolute-registered`. The other two automation recordings are replayed:
`automation-status`, and `automation-config-second-path`, whose path gets the status 404 with and without the
shim (DEV-26). `NewServerParityTest.theRecordingsThatAreNotReplayedAreTheOnesThatNeedTheLegacyShim` names the
44, so that no recording drops out of the replay unseen.

## Stub upstream and outbound proxy

`StubHttpServer.upstream()` and `StubHttpServer.outboundProxy()` (in `mockey-parity-tests`) are loopback stubs
that record every request received (`requests`). Point a legacy or new Mockey proxy case at their `port`.
They run on a small socket server (`RawHttpServer`) that writes header names exactly as given and adds no
`Date` header. The JDK's built-in server rewrites header names, and legacy relays an upstream cookie only
when the header name is spelled `Set-Cookie`.

A request whose path does not start with `/stub/` gets the fixed response `<role>:<target>`, for example
`upstream:/hello`. A request under `/stub/` gets a scripted response (`StubRoutes`):

| Route | Response |
|---|---|
| `echo`, `echo/...`, `cookie/echo` | 200 and a description of the request, see below |
| `status/<code>` | That status, with the body `status <code>` where the status allows a body |
| `headers` | 201 with `X-Upstream: one`, `X-Multi` twice (`a`, `b`), `Cache-Control: max-age=60`, and `Location: /created/9` |
| `cookie/set` | 200 with `Set-Cookie: sid=abc; Path=/stub/cookie; HttpOnly` and `Set-Cookie: theme=dark; Path=/stub/cookie; Max-Age=3600` |
| `binary` | 200 with the 256 bytes 00 to FF as `application/octet-stream` |
| `text/latin1` | `café` in ISO-8859-1, with that charset declared |
| `text/undeclared` | `café` in UTF-8, with no charset declared |
| `redirect/<code>` | That status with a `Location` of the stub's own `echo?after=redirect` |
| `redirect/loop` | 302 with a `Location` of itself |
| `close` | No response: the connection is closed |

The echo describes the request that the stub received, so that a recording shows what a proxying server
forwarded:

```text
method: POST
target: /stub/echo?page=2
header content-type: text/plain
header host: 127.0.0.1:18091
header user-agent: mockey-parity
body-length: 6
body-hex: 706f73746564
```

It lists one `header` line for each value of these request headers, sorted by name: `accept`,
`accept-encoding`, `authorization`, `content-type`, `cookie`, `host`, `if-modified-since`, `if-none-match`,
`keep-alive`, `proxy-authorization`, `te`, `trailer`, `user-agent`, and every header whose name starts with
`x-`. A header that is not listed was not received, or is not reported. Headers that only describe the
connection of the forwarding client, such as `Connection` and `Content-Length`, are left out on purpose:
`body-length` is the number of body bytes that arrived.

The outbound proxy stubs forward nothing. They answer the request themselves, and their echo starts with
the line `via: outbound-proxy` and shows the absolute-form target. `StubHttpServer.outboundProxy(requireProxyCredentials = true)`
answers 407 with a Basic challenge until a request carries a `Proxy-Authorization` header.

## Routing cases (task 2.4)

Forty-two `routing-*` recordings cover how a target is read, normalized, and matched, which service answers,
what the tag filter hides, what becomes of an unknown target, and how twisting rewrites a target.
`RoutingCases` defines them in five fixture groups. Task 4.1 added the group `routing-normalization`:

| Fixture group | Cases | Coverage |
|---|---|---|
| `routing` | 16 | Relative and absolute targets, URL templates, two services with one mock URL, nested paths, matches through real URLs, unknown absolute targets and the service that legacy registers for them, and unknown relative targets. |
| `routing-normalization` | 13 | What counts as the same URL: letter case, slashes at the start and at the end, percent escapes and the `+` in a target, a percent escape in the URL of a service, default ports, and an unknown target with an escape that is requested twice. |
| `routing-tagged` | 6 | The tag filter `keep`: a service that carries the tag, a service whose scenario carries it, services with another tag or none, and a hidden service with an absolute mock URL. |
| `routing-twist` | 6 | An enabled twist configuration: a pair that applies, a regular expression in the origination, the first matching pair only, a later pair, no pair, and a twisted target that is forwarded. |
| `routing-twist-document` | 1 | A fixture that names its enabled twist configuration. |

Re-record with `scripts/record-cases.sh <legacy-dir> routing`. A fixture cannot carry the tag filter, and
legacy ignores the enabled twist configuration that a fixture names. `RoutingCases` therefore gives the
groups `routing-tagged` and `routing-twist` a setting, which `LegacyGroupRecorder` applies after it loads the
fixture: the tag filter through `/taghelp`, and the twist configuration through `/twisting/toggle`.

Legacy behaviors captured (each is visible in the named recording):

- A request that matches no service registers a proxy service for an absolute target and forwards the
  request (`routing-unknown-target`). The service is named after the last path segment, here
  `exist (auto generated)` (`routing-unknown-target-registered`).
- A template service matches only when the request path holds a literal `{...}` segment
  (`customer/%7Bx%7D`); `customer/789` does not match it (DEV-01, `routing-url-template-nomatch`).
  Text criteria run against the query string, so `?id=123` and `?id=456` select different scenarios.
- Two services can have the same mock URL. The first in ID order answers (`routing-competing-services`).
- A real URL leads to its service only when it holds a variable, and then only for a literal `{...}`
  segment (`routing-real-url-with-variable`). For any other real URL legacy registers a new proxy service
  and forwards the request (DEV-31, `routing-real-url-of-relative-service`). `routing-real-url` and
  `routing-real-url-template` reach their services through the mock URL, which equals the target.
- A relative target that matches no service is taken for a host and a path. Legacy registers a proxy service
  for it and forwards the request to that host (DEV-03): `routing-relative-target-as-host` reaches the stub
  upstream this way, and `routing-unknown-relative-target` fails on the unknown host. A target that holds
  `http` anywhere is read from there (`routing-embedded-absolute-target`).
- The letter case of a target does not count, also not that of the scheme (`routing-letter-case`,
  `routing-scheme-letter-case`), and a port that is the default port of the scheme is the same as no port
  (`routing-default-port-omitted`, `routing-default-port-given`).
- A slash at the end must be in the target and in the URL of the service, or in neither, and a slash at the
  start of the target lets no service match (DEV-51, `routing-trailing-slash`,
  `routing-url-with-trailing-slash`, `routing-leading-slash`).
- The target is decoded before it is compared, so that `%2F` is a slash and `%20` a space
  (`routing-encoded-slash`, `routing-encoded-space`). It is decoded as form data: a `+` is a space as well
  (DEV-52, `routing-plus-sign`), and only `%2B` is a plus sign (`routing-encoded-plus-sign`).
- The URL of a service is not decoded. A request for a URL that holds an escape therefore does not find the
  service with that URL (DEV-53, `routing-escape-in-url`), and each request to an unknown target with an
  escape registers one more service (`routing-unknown-target-with-escape-registered`).
- With a tag filter, a service is found when it or one of its scenarios carries the tag
  (`routing-tag-visible`, `routing-tag-scenario`). A service that does not is treated as unknown, so legacy
  registers a second service for its URL (DEV-04). For an absolute URL the request is forwarded
  (`routing-tag-hidden-absolute`, `routing-tag-hidden-absolute-registered`). For a relative URL it goes to
  the first path segment as a host and fails (`routing-tag-hidden`, `routing-tag-untagged`).
- Twisting replaces the first origination that occurs in the request path and applies no later pair
  (`routing-twist-first-pair-only`, `routing-twist-second-pair`). The origination is looked for as literal
  text but replaced as a regular expression, so `a.b` also replaces `axb` (DEV-05,
  `routing-twist-regular-expression`). A twisted absolute target is forwarded to the new host
  (`routing-twist-forwarded`).
- Loading a document enables no twist configuration, also when the document names one (DEV-32,
  `routing-twist-document-enabled`).

Two more legacy behaviors showed while recording. Neither is a case, because the harness has to work around
both:

- The tag filter of the startup option `-F`, like the `filterTag` parameter of `/home?action=init`, is cleared
  when an initialization document is loaded (DEV-49). The first recordings of the group `routing-tagged`
  were made that way, with no filter in effect.
- A reset and an initialization forget the twist configurations but keep the number of the one that was
  enabled. A configuration of the next document that gets that number is enabled at once.
  `LegacyFixtureLoader.load` therefore disables twisting after every load.

Not recorded:

- A request that matches the patterns of two different services. In legacy a variable matches only a literal
  `{...}` segment, so no request reaches two patterns of which one is more specific (DEV-02).
- A pattern with unencoded braces in the request target, which the JDK HTTP client cannot send.

## Response cases (task 2.5)

Seventy-five `response-*` recordings cover static and dynamic responses. `ResponseCases`
defines them in twelve fixture groups, one per `parity-fixtures/responses-*.xml` fixture:

| Fixture group | Cases | Coverage |
|---|---|---|
| `responses-static` | 4 | The default scenario answers whatever the request, the match criteria, or the method restrictions. A default scenario ID that refers to no scenario. |
| `responses-status-headers` | 7 | Status 201, 302, and 503. Several headers, repeated header names, a missing header element, and malformed header text. |
| `responses-body-charset` | 10 | Body bytes: the trailing line break, an empty body, surrounding whitespace, and markup. Encoding with and without a declared charset. |
| `responses-text` | 10 | Text criteria against query parameters and the body, decoding, literal path text, and the value of a template variable. |
| `responses-parameters` | 7 | Text criteria against the query string in detail: an empty value, a repeated parameter, a parameter without a value, an encoded plus sign, and an empty parameter. Parameters followed by a body, and an escape in a body. |
| `responses-rules` | 13 | Rule criteria for parameters, headers, the body, and the URL, each satisfied and unsatisfied. The rule score, rule groups, and a malformed rule set. |
| `responses-methods` | 6 | Method restrictions: the same method, another method, `*`, no restriction, and lower case. |
| `responses-ties` | 6 | The higher score wins. Equal scores go to the scenario name that sorts first, and then to the lower scenario ID. |
| `responses-fallback` | 3 | No match: the service error scenario and the built-in response. A scenario without a match criterion. |
| `responses-universal-error` | 2 | No match with a universal error scenario: the service error scenario comes first, the universal one second. |
| `responses-delays` | 6 | The service delay, a scenario delay longer or shorter than the service delay, and delays when no scenario matches. |
| `responses-builtin-delay` | 1 | The service delay before the built-in response. |

Re-record with `scripts/record-cases.sh <legacy-dir> responses`, or name one group instead
of `responses`. The script runs one legacy instance in a scratch directory.
`ResponseCasesTest.recordsGroupFromLegacy` loads the fixture of a group through the legacy
initialization endpoint (`LegacyFixtureLoader`) before each of its cases.

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
- A parameter whose value is blank is written without its `=`, and the values of a
  repeated parameter follow each other without a separator (DEV-58):
  `response-text-param-empty-value` and `response-text-param-repeated`. A parameter is
  decoded once, so an encoded plus sign stays a plus sign
  (`response-text-param-encoded-plus`). The body follows the parameters without a
  separator (`response-text-param-then-body`), and an escape in a body is decoded
  (`response-text-body-escape`).
- The value of a template variable is evaluated only when the braces arrive unencoded.
  For `%7B123%7D` the service is found but the value is not evaluated
  (`response-text-template-value`).
- Equal scores go to the lower scenario ID when the names are equal too, whatever the
  order of the scenarios in the document (`response-tie-id-order`).
- When no scenario matches, the delay of the error scenario is ignored and the service
  delay applies (`response-delay-error-scenario`, `response-delay-fallback-service`).
  The service delay also comes before the built-in response (`response-delay-builtin`).
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

## Proxy cases (task 2.6)

Fifty-five `proxy-*` recordings cover proxy mode against the stub upstream on port 18091 and the stub outbound
proxies on ports 18092 (open) and 18093 (wants credentials). `ProxyCases` defines them in ten fixture groups,
one per `parity-fixtures/proxy-*.xml` fixture:

| Fixture group | Cases | Coverage |
|---|---|---|
| `proxy-forwarding` | 24 | What the upstream receives: every method, the query string, application, conditional, cookie, and hop-by-hop headers, text, UTF-8, binary, form, and empty bodies. The service delay. Relative targets with one and with two real URLs. |
| `proxy-relay` | 6 | What the client receives: status 404 and 500, a repeated header, an upstream `Cache-Control`, `Location`, and binary, ISO-8859-1, and undeclared UTF-8 bodies. |
| `proxy-session` | 4 | `Set-Cookie` from the upstream, the shared cookie store, the cookie path, and a client's own `Cookie` header. |
| `proxy-redirects` | 9 | 301, 302, 303, and 307 for GET, 302, 303, and 307 for POST, 302 for HEAD, and a redirect loop. |
| `proxy-failures` | 5 | A refused connection with and without a service error scenario, a closed connection, an unknown host, and the service delay on a failure. |
| `proxy-failures-universal` | 2 | A refused connection with a universal error scenario: alone, and next to a service error scenario. |
| `proxy-outbound` | 2 | GET and POST through the open outbound proxy. |
| `proxy-outbound-credentials` | 1 | The outbound proxy that wants credentials. |
| `proxy-outbound-unreachable` | 1 | An outbound proxy on a port where nothing listens. |
| `proxy-outbound-disabled` | 1 | Outbound proxy settings that are present but not enabled. |

Re-record with `scripts/record-cases.sh <legacy-dir> proxy`, or name one group instead of `proxy`. The script
runs one legacy instance in a scratch directory with `-Dfile.encoding=UTF-8`, because legacy decodes request
bodies with the default charset of its JVM. `ProxyCasesTest.recordsGroupFromLegacy` starts the three stubs
and, before each case, loads the fixture of its group (`LegacyFixtureLoader.load`), sets the outbound proxy
credentials where the group has them (`setOutboundProxy`: a fixture cannot carry credentials), and empties
legacy's shared cookie store (`resetUpstreamSession`). No case depends on what an earlier case left behind.
The cases of `proxy-session` that need an upstream cookie carry the request that sets it as a `before`
request.

Port 1 on the loopback address stands for an upstream, or an outbound proxy, that refuses the connection.

Legacy behaviors captured (each is visible in the named recording):

- Every method other than GET reaches the upstream as POST (DEV-10): `proxy-method-put`, `proxy-method-patch`,
  `proxy-method-delete`, `proxy-method-options`, and `proxy-method-trace`. A HEAD request is forwarded as POST too, and the client
  gets the headers of that POST response without a body (`proxy-method-head`).
- A request without a body that is forwarded as POST gains the header
  `Content-Type: text/plain; charset=ISO-8859-1` (`proxy-method-delete`, `proxy-body-empty-post`).
- The query string is rebuilt from the container's parameter map. Parameters are reordered and re-encoded,
  so `%20` arrives as `+` (`proxy-query-order-and-encoding`). An empty value loses its `=`
  (`proxy-query-empty-value`), and the values of a repeated parameter are joined without a separator, as in
  `id=1id=2` (`proxy-query-repeated-parameter`).
- Application headers and `User-Agent` are forwarded (`proxy-headers-application`). `Cookie`,
  `Accept-Encoding`, and `If-None-Match` are dropped, and `If-Modified-Since` is forwarded with the fixed
  value `Fri, 13 May 2006 23:54:18 GMT` (`proxy-headers-client-cookie`,
  `proxy-headers-conditional-and-encoding`). `TE`, `Keep-Alive`, and `Trailer` are forwarded
  (`proxy-headers-hop-by-hop`).
- A request body is decoded with the default charset of legacy's JVM and encoded again as ISO-8859-1
  (DEV-11). With UTF-8 as the default, `é` arrives as the single byte E9 and `€` as `?`, under an unchanged
  `charset=UTF-8` (`proxy-body-utf8`), and every byte from 80 to FF of a binary body arrives as `?`
  (`proxy-body-binary`). An ASCII body and a form body arrive unchanged.
- A relative target is forwarded to a URL built from its path, never to a real URL of the service (DEV-12).
  Both relative cases end in the built-in failure response.
- The upstream's status, `Content-Type`, `Content-Length`, `Location`, and custom headers are relayed, and an
  upstream `Cache-Control` replaces the default one. Of a repeated upstream header only the last value is
  relayed (`proxy-relay-headers`). A proxied response carries Mockey's other default headers.
- A response body is relayed byte for byte when the upstream declares no charset or ISO-8859-1
  (`proxy-relay-binary`, `proxy-relay-text-undeclared`, `proxy-relay-text-latin1`).
- `Set-Cookie` is relayed as the bare `name=value`. The path, `HttpOnly`, and `Max-Age` are lost (DEV-14,
  `proxy-session-set-cookie`).
- Cookies that the upstream sets are kept in one store and sent with later proxied requests inside their
  path (`proxy-session-shared-cookie`), and not outside it (`proxy-session-cookie-path`). A client's own
  `Cookie` header is not forwarded (`proxy-session-client-cookie`).
- A GET follows 301, 302, 303, and 307 and returns the final response. A POST gets a 302 or a 307 back
  unchanged but follows a 303 with a GET (`proxy-redirect-post-303`). A HEAD is forwarded as POST and
  therefore gets the 302 back (`proxy-redirect-head-302`).
- An upstream failure answers with status 200 (DEV-13). The body is that of the service's error scenario, or
  else of the universal error scenario, and the status and headers of that scenario are not used
  (`proxy-failure-refused-error-scenario`, `proxy-failure-universal-error`,
  `proxy-failure-service-error-first`). Without an error scenario the body is a JSON object with `fail` and
  `proxyInfo` (`proxy-failure-refused`). A closed connection, an unknown host, and a redirect loop count as
  failures too. The service delay applies to a failure (`proxy-failure-delay`).
- With the outbound proxy enabled, the request line that the proxy receives has lost the port of the target:
  `http://127.0.0.1/stub/echo` next to `Host: 127.0.0.1:18091` (`proxy-outbound-get`). Legacy answers a 407
  challenge with the configured credentials (`proxy-outbound-credentials`). When the proxy cannot be reached,
  the failure response names the proxy host (`proxy-outbound-unreachable`).

Not recorded:

- An upstream 204 without `Content-Length`. Legacy writes the text `null` as the body and relays status 204
  with `Content-Length: 5`, which the JDK HTTP client of the harness rejects.
- An upstream body in a declared charset other than ISO-8859-1. Legacy encodes the body again as ISO-8859-1
  but relays the upstream's `Content-Length`. For `café €` in UTF-8 the client is promised 9 bytes and gets
  the 6 bytes `63 61 66 E9 20 3F`, and the JDK HTTP client fails.
- An upstream that sends fewer body bytes than its `Content-Length`. Legacy relays the short body under the
  original length, and the JDK HTTP client fails.
- An upstream that accepts a request and never answers. Legacy sets no timeout and waits without end.
- A `Set-Cookie` header that the upstream spells in another letter case, such as `Set-cookie`. Legacy stores
  the cookie but relays no cookie header at all.
- `Connection`, `Upgrade`, and `Proxy-Authorization` request headers from the client. The JDK HTTP client
  refuses to send the first two and drops the third on a direct connection.
- `https` targets, directly or through the outbound proxy. The stubs speak plain HTTP.
- A captured template value in a real URL. Legacy matches no template variable (DEV-01), so no relative
  target with a captured value reaches proxy mode.

## Rule and inspection cases (task 2.7)

Forty-eight `rule-*` recordings and four `inspection-*` recordings cover the request rules and service-level
inspection. `RuleCases` defines them in six fixture groups:

| Fixture group | Cases | Coverage |
|---|---|---|
| `rules-keys` | 11 | Rules with a key: an absent key under a required and under an optional rule, the letter case of a key, the key `*` for parameters and for headers, and a key that contains `*`. |
| `rules-inputs` | 14 | What each group evaluates: form fields, the headers that legacy hides or alters, an absent and a present body, and the URL with and without its query string. |
| `rules-types` | 10 | The rule types: an empty argument, trimming and letter case, an expression that is found and one that is not, a type in upper case, an unknown type, an expression that does not compile, a rule without a key, a rule without an argument, and a rule set without rules. |
| `rules-malformed` | 7 | Rule sets with a fault next to a `url` group that every request satisfies: a rule without a key, an expression that does not compile, a member that is no group, a group that is no array, and an entry that is no rule. A rule set in single quotes, and an argument that is a number. |
| `rules-values` | 6 | What there is to evaluate: an optional rule with the key `*` without and with a parameter, a parameter name in two letter cases, a body in a declared charset and in UTF-8, and a body of spaces. |
| `inspection` | 4 | A service whose own rule set is enabled: its response, the installed inspectors, the latest history entry as JSON, and the history page with every kind of inspection message. |

Every service of the five rule groups is in dynamic mode. Its scenarios carry rule sets and answer with their
own name, and its error scenario `no match` answers with status 404. The response therefore shows whether
legacy took a rule set as satisfied and, where two rule sets compete, which one scored higher.

Re-record with `scripts/record-cases.sh <legacy-dir> rules` and `scripts/record-cases.sh <legacy-dir> inspection`.

Legacy shows inspection results in one place only, the HTML of `/history`. Its JSON endpoints for a history
entry, `/conversation/record` and `/conversation/record/latest`, carry the request and the response but no
inspection message. `inspection-latest-entry` therefore records the JSON form of an inspected exchange, and
`inspection-history-messages` records the history page after five requests. That page holds session IDs and
times, so the recording is marked as a snapshot, of which only the status is compared.

Legacy behaviors captured (each is visible in the named recording):

- A rule whose key is absent from the request is skipped (DEV-15). A required rule then does not fail:
  `rule-key-absent-required` is answered by the scenario although `page_id` is missing. An optional rule is
  not counted: in `rule-key-absent-optional` both rule sets score one, and the tie goes to the scenario name
  that sorts first.
- A key is compared without regard to letter case (`rule-key-letter-case`). The key `*` evaluates the values
  of all parameters or of all headers (`rule-key-wildcard-parameter`, `rule-key-wildcard-header`) and fails
  when there are none (`rule-key-wildcard-without-parameters`). Every key that contains `*` is treated like
  `*` (`rule-key-partial-wildcard`).
- Parameter rules do not see form fields (DEV-17). Under the key `*` a form body leaves nothing to evaluate
  (`rule-input-form-parameter`). Under a named key the rule is skipped, so the rule set counts as satisfied
  whatever the form holds (`rule-input-form-parameter-keyed`).
- Header rules do not see `Host`, `Cookie`, `Content-Length`, and `Accept-Encoding` (DEV-16), and not
  `If-None-Match` either. They see `If-Modified-Since` with the fixed value `Fri, 13 May 2006 23:54:18 GMT`
  in place of the client's value (the six `rule-input-header-*` recordings).
- A body rule has nothing to evaluate when the request has no body. A required rule then fails, and an
  optional rule is satisfied (`rule-input-body-absent-required`, `rule-input-body-absent-optional`).
- A URL rule evaluates the URL that the client addressed, with its scheme, host, port, and `/service/` path
  and without its query string (`rule-input-url-whole`, `rule-input-url-without-query`).
- `string_required` ignores letter case and surrounding whitespace (`rule-type-string-trimmed`), and with an
  empty argument it asks only for a value (`rule-type-string-empty-argument`). The rule type is read without
  regard to letter case (`rule-type-letter-case`).
- A rule of an unknown type never fails, so its rule set counts as satisfied (`rule-type-unknown`). An
  expression that does not compile, a rule without a key, a rule without an argument, and a rule set
  without rules are not satisfied (`rule-type-invalid-regex`, `rule-type-missing-key`,
  `rule-type-missing-argument`, `rule-set-empty`).
- A fault in a rule set spoils only the group that holds it (DEV-54). A rule without a key and a rule whose
  expression does not compile fail their group. A member that is no group, a group that is no array, and an
  entry that is no rule are ignored. In each case the rule set is still satisfied through its `url` group
  (`rule-malformed-missing-key`, `rule-malformed-expression`, `rule-malformed-unknown-member`,
  `rule-malformed-group-not-an-array`, `rule-malformed-rule-not-an-object`). Legacy's parser also reads a rule
  set that is written in single quotes, which is not JSON (`rule-malformed-not-json`).
- An argument that is a JSON number is read as the text of that number (`rule-number-argument`).
- An optional rule with the key `*` fails when the request has no parameter at all (DEV-55), and is satisfied
  by a parameter that matches (`rule-key-wildcard-optional-without-parameters`,
  `rule-key-wildcard-optional-match`).
- A rule is evaluated once for each spelling of its key in the request, and every spelling must satisfy it
  (DEV-56): `Ticker=fb&ticker=goog` does not satisfy a rule that asks for `goog` in `ticker`
  (`rule-key-two-letter-cases`).
- The body is read as text in the default charset of the JVM, whatever the request declares (DEV-57). Legacy
  runs with UTF-8 while recording: a word in ISO-8859-1 is not found although the request declares that
  charset, and the same word in UTF-8 is found (`rule-input-body-declared-charset`, `rule-input-body-utf8`).
- A body of nothing but spaces counts as no body, so an optional body rule is satisfied
  (`rule-input-body-blank`).
- Inspection does not change the response (`inspection-response-unaffected`).
- `/requestinspectors` lists the class names of the installed inspectors. The legacy build holds one,
  `com.mockey.plugin.SampleRequestInspector`, which is global (`inspection-inspectors-listed`).
- The history entry of an inspected request lists the request headers as legacy's inspectors see them,
  without `Host` and `Content-Length` (`inspection-latest-entry`).
- `inspection-history-messages` holds the inspection messages. A failed rule gives one numbered line, such
  as `2) ISSUE: Rule of type 'parameters'.  Belonging to key name of 'ticker'. Requires a value that contains
  'goog'  RULE DESC: The ticker must contain goog.` The first request fails the body rule and both parameter
  rules and gets those three lines, and none for the absent `page_id` header (DEV-15). The request that
  satisfies the rules and the request to the service whose rule set is not enabled get no message. Rules
  that are not valid JSON give `Unable to parse JSON rules from service: inspection unreadable`. The sample
  inspector adds its own message to the request that carries the parameter `foobarKey`.

Not recorded:

- An inspector that a service names, and an inspector that fails. The legacy build holds only the global
  sample inspector.
- Inspection messages as JSON. Legacy has no such endpoint.

## Automation endpoint cases (task 2.8)

Forty-three `automation-*` recordings cover the legacy automation endpoints `/definitions`,
`/config/service`, `/plan/setup`, `/home`, and `/status`. `AutomationCases` defines them in one fixture
group, `automation`. Its fixture holds the services `Customers` (static, three scenarios, two real URLs),
`Orders` (dynamic, a delay of 100 ms), and `Billing` (proxy), and the plans `Gold path` and `Silver path`.
Legacy numbers the services 1 to 3 and the plans 1 and 2 when it loads the fixture. The new server gives
other identifiers (DEV-20), so a case names a service or a plan wherever legacy lets it, and only the cases
that are about identifiers use a number.

| Endpoint | Cases | Coverage |
|---|---|---|
| `/definitions` | 5 | All services, one service by name and by ID, an unknown name, and no services. |
| `/config/service` | 13 | The response type by name and by number, the default scenario by name, a service and a scenario by ID, `hangTime`, `defaultUrlIndex`, the inspector name, the rules and the schema with their flags, `transientState`, an unknown scenario, an unknown service, a form post, the second path `/service_scenario`, and the effect on a mock request. |
| `/plan/setup` | 14 | `set_plan` by name, by ID, for an unknown ID, and with `transient_state`, `save_plan` for a new name, an existing name, and no name, `delete_plan`, `set_as_default_plan` for a plan, with `none`, and for an unknown ID, and the effect of applying a plan on a mock request. |
| `/home` | 10 | `init` from a file, a missing file, a file that is no definitions document, a URL, with `filterTag`, and without `type=json`, each with its effect where it has one, and `deleteAllServices`. |
| `/status` | 1 | The status document. Its start time and its path change with every run, so the recording is a snapshot. |

Re-record with `scripts/record-cases.sh <legacy-dir> automation`. Legacy resolves a relative `file` parameter
against its working directory, so the script copies `parity-fixtures/automation-init.xml` there as
`suite-a.xml`. `AutomationCasesTest.recordsGroupFromLegacy` serves the same document from a stub on port 18091
for the case that initializes from a URL. Every case starts from the freshly loaded fixture, and a case that
shows the effect of a call carries that call as a `before` request.

Legacy behaviors captured (each is visible in the named recording):

- `/definitions` answers with a `serviceDefinitions` array. An element has `serviceName`, `serviceId`,
  `defaultScenarioId`, `serviceResponseType` (`proxy`, `static`, or `dynamic`), `hangTime`, and
  `requestInspectorName` (the text `null` when there is none). When the service has scenarios, it also has
  `defaultScenarioName` and a `scenarioArray` of `scenarioId` and `scenarioName` in the order of the
  scenario names (`automation-definitions-all`). A service name is found without regard to letter case
  (`automation-definitions-by-name`). An unknown name lists every service
  (`automation-definitions-unknown-name`), and without services the answer is `{}`
  (`automation-definitions-empty`).
- `/config/service` answers with `{"result": {...}}`. On success the result has `success` with the value
  `updated` and describes the service after the change (`automation-config-scenario-by-name`). The response
  type is accepted by name and by number (`automation-config-numeric-type`), and a service and a scenario
  are found by their numbers (`automation-config-by-id`). `defaultUrlIndex` and
  `transientState` are accepted and show no effect (`automation-config-default-url-index`,
  `automation-config-transient-state`, DEV-22). A scenario name that the service does not have leaves the
  default scenario as it was and still reports `updated` (`automation-config-unknown-scenario`). The
  parameters are also read from a form post (`automation-config-post-form`), and `/service_scenario` is a
  second path to the same endpoint (`automation-config-second-path`).
- `/config/service` for a service that does not exist answers with status 500 and an HTML error page that
  shows a Java stack trace (`automation-config-unknown-service`).
- `/plan/setup` finds a plan by `plan_id` or by `service_plan_name`, without regard to letter case. The
  result of `set_plan`, `save_plan`, and `set_as_default_plan` names the plan under `planid` and `planName`,
  and the result of `delete_plan` under `planId` and `planName` (`automation-plan-set-by-name`,
  `automation-plan-delete`).
- `save_plan` under an existing name in another letter case updates that plan and takes over the new
  spelling (`automation-plan-save-existing-name`). Without a name the plan is called
  `Plan (auto-generated-name)` (`automation-plan-save-without-name`). A saved plan puts a service back into
  the state it had when the plan was saved (`automation-plan-saved-plan-applies`).
- `delete_plan` and `set_as_default_plan` for an unknown plan answer with `fail`
  (`automation-plan-delete-unknown`, `automation-plan-default-unknown`). `set_plan` for an unknown plan
  answers with the HTML home page (`automation-plan-set-unknown`, a snapshot). `plan_id=none` removes the
  default plan (`automation-plan-default-none`). The three `set_as_default_plan` answers carry no
  `Content-Type`.
- `/home?action=init` loads a document by file name and answers with `success` when `type=json` is given
  (`automation-home-init`), and otherwise with a redirect to `/home` (`automation-home-init-without-type`).
  A missing file answers with `fail` (`automation-home-init-missing-file`).
- `init` reads any path of the machine and any URL (DEV-27). A file that is no definitions document answers
  with `fail`, but by then legacy has deleted every definition (`automation-home-init-not-a-document`,
  `automation-home-init-not-a-document-effect`). A URL is fetched and loaded, and the answer reads
  `Loaded definitions from null` (`automation-home-init-url`, `automation-home-init-url-effect`).
- A `filterTag` given together with `init` is lost: legacy sets the filter and then clears it while it
  replaces the definitions, so a service without the tag still answers (`automation-home-init-filter-tag`).
- `/status` answers with a JSON object of `status`, `since` in the form `11/23/2026 | 11:55:26`, and
  `repoPath`, the path of the definitions directory, under the content type `text/html` (`automation-status`).
- Every answer of these endpoints carries the default headers of mock responses, among them
  `Access-Control-Allow-Origin: *` (DEV-26).

Not recorded:

- `/home` without an action. Legacy answers with the HTML home page of about 33 KB.
- `init` without `transientState=true`. It switches legacy to writable mode, in which legacy rewrites its
  definitions directory (DEV-30). The shim leaves the mode as it is for such a request (DEV-70).
