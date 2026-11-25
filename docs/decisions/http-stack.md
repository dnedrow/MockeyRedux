# HTTP stack

Decided in task 11.0 of the change `kotlin-rewrite-modular`, on 2026-11-24.

`mockey-server` runs its listeners on Ktor with the Netty engine. Design decision 19 chose Ktor and left the
engine to a spike. It also named three things about Ktor that were read in its sources and not yet run. This
record holds what the spike found, the choice of the engine, and the settings that the engine needs.

## What was needed

- The target of a request to the mock endpoint is the request target as the client wrote it, after
  `/service/` (`request-routing`: Mock endpoint path convention). The target can be the whole URL of a real
  service, with `//` after its scheme, and it can hold percent escapes that must reach the core as they are.
  An HTTP stack that tidies a path changes the target, which the design names as a risk.
- Design decision 19 rests on three things: a handler gets the request target unchanged, the routing of
  Ktor does not, and the `port` selector of that routing goes by the `Host` header where `localPort` goes by
  the socket.
- The mock listener and the admin listener are two servers in one process (design decisions 10 and 19), and
  tests need them on ports that the system picks.
- The server lets the requests in progress finish when it is told to end (`server-runtime`: Graceful
  shutdown), and it runs on a Java 21 runtime without native libraries (`server-runtime`: Runtime platform).

## Candidates

`ktor-server-core`, which both engines need, is 18 jars with 10.2 MiB.

| Engine | What it adds to `ktor-server-core` | Why it was considered |
|---|---|---|
| Netty | 21 jars with 4.8 MiB, once the native libraries of QUIC are left out, see below. | The default candidate of design decision 19. |
| CIO | 1 jar with 0.1 MiB. | The lighter candidate of design decision 19. It is written on coroutines and brings no other library. |

The Jetty engine of Ktor was no candidate. Design decision 19 set it aside, because Jetty answers a path that
holds `//` with the status 400 until it is configured otherwise.

## Spike

`HttpStackSpikeTest` in `mockey-server` starts servers on both engines on the loopback address. A server of the
test has one handler and no routing, as the mock endpoint has, and the handler reads the request target from
`call.request.local.uri`. The test talks to the servers over HTTP with the HTTP client of the JDK, as the
parity suite does, and over a socket where that client would not send a request as the test needs it, such as
a target with a malformed escape. The Netty server of the test is the one that the program runs, with the
settings below, unless a row says otherwise.

The tests ran on OpenJDK 21.0.13 with Ktor 3.6.1, which brings Netty 4.2.17. What they show:

### The request target

| Question | Netty | CIO |
|---|---|---|
| The targets of the recorded `routing-*` cases, which were 46 requests of 42 cases | The handler gets every target as it was sent, with its `//`, its escapes, and its query string. | The same. |
| Targets with `%2F`, `%25`, and `%252F`, and with `//` in other places | As sent. | As sent. |
| A malformed percent escape, such as `%zz` or a `%` at the end, unencoded braces, and the characters `\|`, `\`, `^`, `` ` ``, `"`, `<`, and `>` | As sent. | As sent. |
| A target longer than 4,096 characters | Left alone, Netty takes a request line of 4,096 characters and answers a longer one with the status 400. Its setting `maxInitialLineLength` raises the limit. With the setting below, a target of 60,000 characters arrives as sent. | CIO takes a request line of 8,192 characters and answers a longer one with the status 400. It has no setting for the limit. |
| Bytes of a target that are no ASCII characters | Each byte arrives as the character with its number. Nothing is lost, and the bytes can be read in any charset. | The bytes are read as UTF-8. A byte that is no UTF-8 arrives as the replacement character. |

### Routing and listeners

| Question | Netty | CIO |
|---|---|---|
| The routing of Ktor | A request for `/a//b` reaches the route `/a/b`, and a `%2F` in a path parameter arrives as `/`. A request with a malformed escape gets the status 400 before a handler runs. | The same. |
| A route guarded by `port`, on a server with two connectors | A request on the other connector reaches the route when its `Host` header names the guarded port. | The same. |
| A route guarded by `localPort` | Only a request on the connector with that port reaches the route, whatever its `Host` header says. | The same. |
| Two servers in one process, each on port 0 | Both start, and `resolvedConnectors()` tells their ports. | The same. |
| A port that is taken | `start` throws a `BindException`, which names neither the address nor the port. | `start` throws a cancellation whose cause is the `BindException`. |

On Netty the failed start took about 4 seconds, where CIO failed at once. The engine shuts two groups of
threads down before it reports the failure, and Netty waits 2 seconds for each. The time was measured and is
not asserted, because a faster failure would be no fault.

### Requests

| Question | Netty | CIO |
|---|---|---|
| Methods | Every method reaches the handler as it was written, also `PURGE`, `M-SEARCH`, and `get`, with its body. A body in chunks arrives whole. | The same. |
| A request without a `Content-Length` that says `Connection: close`, with a method other than `GET`, `HEAD`, and `OPTIONS` | It is a request without a body, and it is answered. | CIO takes it for a request whose body ends with the connection. A handler that reads the body waits until the client closes its side, so the client gets no answer. |
| Lines that end with a line feed alone | Status 400. | Taken. |
| Headers that are longer than 8,192 characters together | Status 400. The setting `maxHeaderSize` holds the limit. | Not tried. |
| The headers of a request | Their names arrive in their order and as they were written. The headers of one name are together, at the place of the first of them. | The headers arrive in another order. |
| A client that asks to change to HTTP/2 with `Upgrade: h2c`, as the HTTP client of the JDK does unless it is held to HTTP/1.1 | The request is answered in HTTP/1.1. | The same. |

### Responses

| Question | Netty | CIO |
|---|---|---|
| The headers of a response | They are written in the order in which the handler set them, with their names as it wrote them. For a request that says `Connection: close`, the engine adds `Content-Length` and `Connection: close` and nothing else: no `Server` header and no `Date` header. | The same, except that the headers of one name are written together. |
| A status with a reason of its own, such as `299 Odd Status` | Written as the handler gave it. | The same. |
| A `HEAD` request | The response has the `Content-Length` of the body and no body. | The body is sent, which is a fault. |
| A handler that takes longer than the write timeout | The timeout is 10 seconds unless it is set. It starts when the response is written, so a handler that waits before it answers, as for the delay of a scenario, can take longer. | Not tried. |

### Stopping

| Question | Netty | CIO |
|---|---|---|
| `stop` with a grace period, while a request is in progress | Netty ends every connection at once, so the client gets no answer. The grace period is the time for which the threads of the engine go on, and `stop` takes at least that long. | CIO waits for the request, and the client gets its answer. |
| Closing the socket on which the server listens, and stopping the engine later | A new connection is refused, and the request in progress is answered. The engine gives no way to that socket. The bootstrap of Netty does, and `configureBootstrap` lets a server add a handler to it. | Not needed. |

### The class path

| Question | Netty | CIO |
|---|---|---|
| Native libraries | Netty has transports that need a native library, and the engine uses one of them when it is on the class path. The server does not bring them, so the engine uses the transport of the JDK. The Netty engine also brings the native libraries of QUIC for five platforms, 11.1 MiB, which only HTTP/3 loads. | None. |

## Decision

The server runs on the Netty engine.

- The main question has the same answer for both engines: a handler without routing gets the request target as
  it was sent.
- CIO fails at the mock endpoint in ways that the server cannot make up for. A client that sends a `DELETE`
  or a `POST` request without a body and with `Connection: close` gets no answer, because a handler cannot
  tell that no body follows. The limit of 8,192 characters for a request line cannot be raised, and a byte of
  a target that is no UTF-8 is lost. A `HEAD` request gets a body.
- What Netty lacks can be set or built. Its limit for a request line is a setting. Its way of stopping ends
  the requests in progress, so the server lets them finish itself, and the spike shows how.
- Netty costs 20 jars and 4.7 MiB more than CIO.

## What follows for the server

The settings of the engine, which `HttpEngine` holds:

- `maxInitialLineLength` is 65,536 characters. The limit counts the method, the target, and the version of
  HTTP together. A longer request line gets the status 400.
- The server is never in the development mode of Ktor, also when the system property `io.ktor.development`
  says so. In that mode Ktor answers a failure with a page that shows the exception.
- Everything else stays as Ktor sets it: 8,192 characters for the headers of a request, 10 seconds for writing
  a response, no limit on the time for reading a request, and no HTTP/2 without TLS.

The build:

- `mockey-server` leaves out `netty-codec-native-quic`, the native libraries of QUIC. The server offers
  HTTP/1.1 only. Without them the engine brings no native library. `HttpStackSpikeTest` fails when they are
  on the class path again.

The listeners (task 11.2):

- Each listener is a server with one connector. No route needs a guard, and none is ever guarded by `port`.
- A listener that cannot be bound shows as a `BindException` from `start`. The server names the address and
  the port itself, because the exception does not.
- Before it does anything else, the server binds a socket of its own to the address and the port of each
  listener and closes it again. A port that is in use shows at once that way, and not after the 4 seconds of
  the engine, and it shows before the server has stored anything. The failure of `start` is handled as well,
  for a port that another program takes in between.
- A listener is bound to one address. The server looks up a name itself, once, so that the rule about an
  admin listener beyond the loopback address holds for the address that is bound.
- Where a machine has IPv6, the Java runtime binds the address `0.0.0.0` as all addresses of IPv4 and of
  IPv6: a listener on `0.0.0.0` answered a request to `[::1]`, and its socket stated its address as
  `[0:0:0:0:0:0:0:0]`. The log states the address of a listener as it was set. This was seen on macOS and is
  not asserted, because it depends on the machine.

The mock endpoint (tasks 11.3 and 11.4):

- It is one interceptor of the call phase of the application, with no routing and no plugin. It reads the
  target from `call.request.local.uri`, which is the request target of the connection as it arrived.
- A character of that target above 127 is one byte that the client sent.
- The server adds the default headers of mock responses itself, because the engine adds none, and its headers
  are written in the order in which it sets them. It need not leave out the body of the response to a `HEAD`
  request, because the engine does.

Stopping (task 11.7):

- Ktor registers a shutdown hook for every server that it starts. The hook stops the server with a grace
  period of 1 second, which ends the requests in progress on Netty, and it runs at the same time as any other
  shutdown hook. The server therefore switches the hook off with the system property
  `io.ktor.server.engine.ShutdownHook`, which Ktor reads once, when the first server is started. This was read
  in `ShutdownHook.kt` and `ShutdownHookJvm.kt` of Ktor and not run. The test of task 11.7 shows it.
- To stop, the server closes the sockets on which it listens, waits for the requests in progress for as long
  as the grace period lasts, and then stops the engines with a grace period of 0.

## What differs from the HTTP server of legacy

Legacy runs on Jetty 6. The differences below come from the two HTTP servers and not from Mockey. They were
seen by sending the same requests to legacy at commit `88e31ed`, started as `docs/parity/running-legacy.md`
says. No test of this repository holds what legacy answered, because legacy does not run in it.

| Request | Legacy | The new server |
|---|---|---|
| A long request line | A target of 6,009 characters is taken. A target of 7,009 characters gets `413 FULL head`. | A request line of 65,536 characters is taken. A longer one gets the status 400. |
| A long header | A header of 9,000 characters gets `413 FULL head`. | The same request gets the status 400. |
| A malformed percent escape in the path, such as `/service/a%zzb` | Jetty answers `400 Bad Request`, and Mockey does not see the request. | The request reaches the handler, and with it the mock endpoint. |
| Lines that end with a line feed alone | Taken. | Status 400. |

Task 11.3 adds what concerns the mock endpoint to the deviation register.

## Repeating the spike

Run `./gradlew :mockey-server:test --tests '*HttpStackSpikeTest*'`. The test reads the request targets of the
recorded routing cases from `mockey-parity-tests/src/test/resources/parity`.
