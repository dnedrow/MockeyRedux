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
