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
- Neither Ktor nor Netty asks that a request names a host. HTTP has it that a request of HTTP/1.1 without a
  `Host` header gets the status 400, and Jetty answers so in legacy, whatever the request is for. Every
  listener therefore refuses such a request itself, in the phase of the plugins, before anything else sees
  the request (`RequestHost`, task 11.4). A request of HTTP/1.0 need not name a host.
- Ktor answers a request with more than one `Host` header with the status 400 and no reason
  (`verifyHostHeader` in `BaseApplicationEngine.kt`). The listener comes first and gives the reason.

The mock endpoint (tasks 11.3 and 11.4):

- It is one interceptor of the call phase of the application, with no routing and no plugin. It reads the
  target from `call.request.local.uri`, which is the request target of the connection as it arrived.
- A character of that target above 127 is one byte that the client sent.
- The server adds the default headers of mock responses itself, because the engine adds none, and its headers
  are written in the order in which it sets them. It need not leave out the body of the response to a `HEAD`
  request, because the engine does.

Task 11.3 found more, and the tests of the mock endpoint show it:

- Ktor lists the name of a request header once for each spelling of it, and for each spelling it gives the
  values of all of them. A header that a client sends as `x-id` and as `X-Id` would reach the engine twice.
  The mock endpoint takes each name once, whatever its letter case, with its values under the first spelling.
- The mock endpoint reads the body from `call.request.receiveChannel()`. The channel that
  `call.receiveChannel()` gives is the same one, but on the way to it Ktor writes the request target with its
  query string to the log, at the level of traces. With the channel of the request, the log holds nothing of
  a request at any level.
- A handler can answer without reading the body. On a connection that stays, the engine then reads the rest
  of the body and drops it, so that the client has sent its request when the answer arrives. That is how a
  body beyond the limit gets its status 413: a client received it after sending 1 GiB. The mock endpoint
  looks at `Content-Length` first and reads a body without one, which comes in chunks, as far as the limit and
  one byte more.
- On a connection that ends with the answer, the engine closes the connection as soon as the answer is
  written, also when the client is still sending its body. Such a client loses the answer: not one of the
  clients that sent 8 MiB with `Connection: close` got the status 413, and each of them found the connection
  closed without an answer. The same holds for a client of HTTP/1.0, which does not keep its connection
  unless it asks for that. Before an answer on such a connection, every listener therefore reads the rest of
  the body and drops it (`UnreadBody`, task 11.4). It does so where Ktor sends a response, so that it also
  holds for the status 404 that Ktor gives itself, and for `/status`, which reads no body. On a connection
  that stays, the answer still goes out at once.
- Netty answers a request with `Expect: 100-continue` with `100 Continue` before the handler runs. A client
  that waits for it therefore sends a body beyond the limit before it gets the status 413.
- For a status that allows no body, 204 or 304 or one of the 100 range, the engine sends none. It also takes
  `Content-Length` from a response with the status 204 or one of the 100 range.
- The engine states the length of a body itself, and it fails a response that states another length in a
  header. The mock endpoint therefore leaves the `Content-Length` and the `Transfer-Encoding` of a response
  out, and for a response without a body it gives the engine the length to state.
- A request that nothing answers gets the status 404 with no body from Ktor. That is the answer to every
  request on the mock listener that is neither mock traffic nor a request for `/status`, and to every
  request on the admin listener until the admin API is there.

Stopping (task 11.7):

- The program takes the signals that end a program itself: `SIGTERM`, `SIGINT`, and `SIGHUP`. The Java
  runtime would end the program on them at once, with the status 143 for `SIGTERM`, after the shutdown hooks.
  With a handler for the signals (`sun.misc.Signal`), the thread of the program stops the server in order and
  the process ends with the status 0. A signal that is ignored when the program starts stays ignored, as for
  a program that `nohup` started: the runtime hands no such signal to a handler. Under the option `-Xrs` the
  runtime allows no handler, and the server runs without one: a signal ends it at once then.
- Ktor registers a shutdown hook for every server that it starts, which stops the server with a grace period
  of 1 second and so ends the requests in progress on Netty. The hook does not get to run: no signal starts
  the shutdown of the runtime any more, and Ktor takes the hook back when a server is stopped, which the
  program does before it ends. That Ktor takes it back was read in its `ShutdownHookJvm.kt` and not run. So
  the hook is left as it is, and the system property that switches it off,
  `io.ktor.server.engine.ShutdownHook`, is not set.
- To stop, a listener closes the socket on which it listens and every connection without a request in
  progress. The server waits until no connection is left, for as long as the grace period lasts, and both
  listeners share that time. Then it closes the connections that are left, ends the handlers of their
  requests, stops the engines with a grace period of 0, and closes the engine of Mockey, which completes a
  save that is under way.
- Ktor does not tell when a request is done in the sense that counts here. Its handler is done, and the job
  of its call ends, when the response is given to Netty, and Netty hands the bytes to the system later: a
  large response to a client that reads slowly stays in the buffer of the connection, and closing the
  connection drops it. So a handler of Netty counts for each connection the requests that arrive and the
  answers whose last bytes the system has taken (`Connections`). It stands right before the handler of Ktor
  for HTTP/1.1, which has the name `http1` in the pipeline, and it is put there through `channelPipelineConfig`.
  A request counts until it has arrived whole as well, because a client that still sends when its connection
  is closed can lose the answer.
- The answer to the last request of a connection gets the header `Connection: close` while the listener
  stops, so that the client sends no further request on a connection that is about to be closed.
- A request that is in progress when the grace period is over gets no answer. Its handler has to end before
  the engine is stopped: one that went on would answer when the threads of Netty are gone, and Netty logs an
  error for each such answer. Ktor runs every handler in a coroutine under the job of its application, so
  the listener cancels the children of that job and waits for them, with the connections closed already.
- Netty says that a listening channel is closed before the system has closed the socket. The Java runtime
  closes the socket of a channel that a selector knows when that selector selects again, which the event loop
  of Netty does a moment later, and later still when its thread has to wait for a processor. A client that
  comes in between is connected, is not served, and finds its connection reset. So a client that comes after
  the listening sockets are closed is refused or reset, and it gets no answer either way. The spike test
  shows it by holding the event loop. It was found when the test that expected a refusal failed in the
  continuous integration, where other tasks of the build kept the processors busy.

## What differs from the HTTP server of legacy

Legacy runs on Jetty 7.0.0.RC5, as the `pom.properties` files in its jar say. The differences below come
from the two HTTP servers and not from Mockey. They were seen by sending the same requests to legacy at commit
`88e31ed`, started as `docs/parity/running-legacy.md` says. No test of this repository holds what legacy
answered, because legacy does not run in it. `scripts/probe-deviations.sh` sends the requests to legacy again.

| Request | Legacy | The new server | Deviation |
|---|---|---|---|
| A malformed percent escape in the path, such as `/service/a%zzb` | Jetty answers `400 Bad Request`, and Mockey does not see the request. | The request reaches the handler, and with it the mock endpoint. | DEV-63 |
| A long request line | With the headers that `curl` sends, a target of 6,050 characters is taken, and one of 6,080 characters gets `413 FULL head`: the request line and the headers have to fit into 6,144 bytes together. | A request line of 65,536 characters is taken. A longer one gets the status 400. | DEV-64 |
| Long headers | A header of 7,000 characters gets `413 FULL head`. | Headers of up to 8,192 characters together are taken. More get the status 400. | DEV-64 |
| A long body | A body of 52 MiB is taken. | A body beyond the limit, 50 MiB unless it is set otherwise, gets the status 413. | DEV-65 |
| Lines that end with a line feed alone | Taken. | Status 400. | DEV-66 |
| A `Host` header such as `a/b` or `a?b`, or an empty one | Taken. | Status 400. | DEV-66 |
| More than one `Host` header | Taken. | Status 400. | DEV-66 |
| A request of HTTP/1.1 without a `Host` header, whatever it is for | Status 400. | Status 400. | none |
| A request for `/service` | It is mock traffic with an empty target, as a request for `/service/` is. | Status 404. Only a path that begins with `/service/` is mock traffic. | DEV-67 |
| A request that is in progress when the signal `SIGTERM` arrives | It gets no answer: legacy ends at once, with the status 143. | It is answered when it finishes within the grace period, and the server ends with the status 0. | DEV-68 |

## Repeating the spike

Run `./gradlew :mockey-server:test --tests '*HttpStackSpikeTest*'`. The test reads the request targets of the
recorded routing cases from `mockey-parity-tests/src/test/resources/parity`.
