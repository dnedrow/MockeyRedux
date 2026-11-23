# Parity recordings

The `mockey-parity-tests` module stores each recording as a directory containing:

- `label.txt`: `same` or a deviation ID.
- `fixture.xml`: the legacy XML fixture used by the case.
- `request.properties`: HTTP method, target, headers, and a base64-encoded body.
- `expected-response.properties`: status, headers, and a base64-encoded response body.

`ParityRecorder` records a case against a supplied base URL. `ParityReplayer` sends
the stored request to another base URL and compares status, stable headers, and body
bytes. Headers whose values are expected to vary between requests (`Date`,
`Last-Modified`, `Set-Cookie`, and `Content-Length`) are excluded from comparison.

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
