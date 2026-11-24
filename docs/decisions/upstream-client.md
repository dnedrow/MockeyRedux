# Upstream client

Decided in task 7.1 of the change `kotlin-rewrite-modular`, on 2026-11-24.

`mockey-core` forwards requests to real services with the HTTP client of the JDK, `java.net.http.HttpClient`.
The client sits behind a port, so a program that embeds the core can put another client in its place.

## What was needed

- The capability `upstream-proxying` asks for a client that forwards the method and the body of a request
  unchanged, sends the headers that it is given, and returns the status, the headers, and the body of the
  answer byte for byte.
- When an outbound proxy is enabled, every forwarded request goes through it, for `http` and for `https`
  targets, with the configured credentials when there are any.
- The certificate of an `https` target is checked against the trust store of the runtime, and a connection or
  a response that takes too long is given up.
- Design decision 8 names the JDK client as the default, so that the core depends on nothing but the kotlinx
  libraries. It names one risk: the JDK does not send Basic credentials for a tunnel through a proxy by
  default. This spike settles that risk before the adapter is written.

Legacy forwarded with Apache HttpClient 4.1.1 and gave that client the user name and the password for the
proxy.

## Candidates

| Client | What it adds to the class path | Why it was considered |
|---|---|---|
| `java.net.http.HttpClient` | Nothing. | The default of design decision 8. |
| The Ktor client | Its core and one of its engines. | It is the stack of the two applications. Design decision 8 set it aside for the core, because it is a heavy dependency for programs that embed the core, and because its support for proxy credentials differs by engine. |
| Apache HttpClient 5 | The client and its core library. | It follows the client that legacy used. |
| A client of our own on sockets | Nothing. | Every byte of a request would be ours to decide. |

Only the JDK client was run. It needs no dependency, and the questions below were all about it.

## Spike

`UpstreamClientSpikeTest` in `mockey-core` runs the JDK client against two stubs of the test sources:

- `StubUpstream` is a small HTTP/1.1 server on sockets that records every request and writes a response byte
  for byte. It can speak TLS with a certificate that it signed itself, which the `keytool` of the JDK makes
  for each test run.
- `StubProxy` is a small proxy that records what it gets. It forwards a request with an absolute URL, opens a
  tunnel for a `CONNECT` request, and can answer 407 until a request carries its credentials.

The stubs of `mockey-parity-tests` do the same job for the parity suite. The core cannot use them, because it
depends on no other module, and they speak neither TLS nor `CONNECT`.

The tests ran on OpenJDK 21.0.13. What they show:

| Question | What the JDK client does |
|---|---|
| A request for an `http` URL through a proxy that wants credentials | It arrives. The request line names the whole URL with its port, which is the new behavior of DEV-43. |
| A request for an `https` URL through such a proxy | It arrives through a tunnel when the JVM allows Basic credentials for tunnels, see below. The credentials go to the proxy with the `CONNECT` request and never to the real service. |
| The same request in a JVM that is left alone | The proxy gets a `CONNECT` request without credentials and answers 407. The client returns that answer with the status 407 and no body. |
| Credentials from an `Authenticator` | They are sent after the proxy has asked for them, so every first request is sent twice. A client with an authenticator also drops the `Authorization` header of every request, so the header of the Mockey client would not reach the real service. |
| Credentials in a `Proxy-Authorization` header of the request | They are sent with the first request. A proxy gets the header, and a real service does not. |
| Headers that a request cannot set | `Host`, `Content-Length`, `Connection`, `Upgrade`, and `Expect`. The client refuses a request that sets one of them. |
| Headers that the client adds | `Host`, and `Content-Length` when the request is built with a body publisher, also an empty one. Only a request that is built with `GET()`, `HEAD()`, or `DELETE()` has no `Content-Length`. The client adds `User-Agent: Java-http-client/<version>` to a request without a `User-Agent`. |
| The response | The status as sent. The names of the headers in lower case, the values of one name in their order, and the names in alphabetical order. The body as sent, whether its end is marked by `Content-Length`, by chunks, or by the end of the connection. |
| A response that ends early or does not come | An `IOException`. |
| A certificate that the runtime does not trust | An `SSLHandshakeException` before any request is sent. |
| Redirects | With `Redirect.NEVER` the client returns the redirect response. |
| Cookies | The cookie store of the JDK, `java.net.CookieManager`, writes a cookie that was set with `Max-Age` in the form of RFC 2965: `theme="dark";$Path="/stub/cookie";$Domain="127.0.0.1"`. |
| HTTP/2 | A client that is not held to HTTP/1.1 adds `Upgrade: h2c`, `HTTP2-Settings`, and `Connection: Upgrade, HTTP2-Settings` to a request for an `http` URL. |
| Canceling | `cancel(true)` on the future of a request ends the exchange at once. |
| The URL of a request | `java.net.URI` refuses characters that a request target can hold, such as a pipe sign, braces, a space, and a percent sign that starts no escape. |

The JVM setting is the property `jdk.http.auth.tunneling.disabledSchemes`. The file `conf/net.properties` of
the JDK sets it to `Basic`, and a system property of the same name takes its place. The JDK reads it once,
when its HTTP client is first used. The test `theJdkSendsBasicCredentialsForATunnelOnlyWhenItsPropertyAllowsThem`
therefore starts a JVM for each value: with the property unset or set to `Basic` the proxy answers 407, and
with the property set to nothing the request arrives.

## Decision

The JDK client is the default adapter.

- It does everything that `upstream-proxying` asks for, with no dependency, once the JVM allows Basic
  credentials for tunnels. TLS, the parsing of HTTP, and the pooling of connections stay the work of the
  platform.
- The port keeps the decision reversible. A program that needs another client, such as one for a proxy that
  wants NTLM or Kerberos credentials, supplies its own.

A client on sockets would have none of the limits below, but the core would then own a TLS and HTTP client.
The Ktor client and Apache HttpClient would bring dependencies into every program that embeds the core.

## What follows for the adapter

Credentials for the outbound proxy:

- The adapter sends them itself, as a `Proxy-Authorization` header for the scheme Basic on every request that
  goes through the proxy. It gives the JDK client no authenticator. Basic is the only scheme, and the user
  name and the password are written in UTF-8.
- When it is created, the adapter sets `jdk.http.auth.tunneling.disabledSchemes` to nothing, unless the
  property is set. Whoever starts the JVM with a value for the property keeps that value. The JDK reads the
  property with its first use of any part of its HTTP client, also the building of a request, so the adapter
  cannot wait for its first request.
- The setting holds for the whole JVM. It changes nothing for code that gives no credentials to a proxy.
- In a JVM that has used the JDK HTTP client before the adapter exists, the JDK has read its default already.
  A request for an `https` target through a proxy with credentials then gets the status 407 of the proxy. The
  adapter treats a 407 from an outbound proxy as an upstream failure and says what to do. A program that
  embeds the core can start its JVM with `-Djdk.http.auth.tunneling.disabledSchemes=` to be sure.

The outbound proxy:

- The JDK client speaks plain HTTP to a proxy and nothing else. The URL of an outbound proxy therefore starts
  with `http://` and names a host and, where it is not 80, a port. The settings take no other URL.
- A request names the proxy that it goes through. The adapter keeps one JDK client for direct connections and
  one for the proxy that is in use, because a JDK client has its proxy for good. When another proxy comes
  into use, the client for the proxy before it finishes its requests and ends.

The request:

- The adapter builds the JDK client for HTTP/1.1 only. Left to itself, the client asks an `http` server to
  change to HTTP/2 with headers of its own.
- The adapter does not forward `Expect`, which the JDK client refuses. The server has taken the body of the
  request by then, so nothing waits for an interim answer.
- A request without a body is built with `GET()`, `HEAD()`, or `DELETE()` where its method is one of these, so
  that it carries no `Content-Length`. For any other method it carries `Content-Length: 0`.
- A request without a `User-Agent` reaches the real service with the `User-Agent` of the JDK client, because
  the client offers no way to send none.
- The adapter escapes the characters of a URL that `java.net.URI` refuses, and leaves the rest of the URL as
  the request wrote it.

The response:

- The names of the headers reach the Mockey client in lower case and in alphabetical order. The values of one
  name keep their order, which is all that HTTP gives a meaning to.
- The adapter follows no redirect and keeps no cookie. It builds the JDK client with `Redirect.NEVER` and
  without a cookie handler, and returns every answer as it came.
- The connect timeout is that of the JDK client. It covers the handshake of a secure connection as well:
  a server that takes a connection and then stays silent is given up after that time.
- The adapter ends an exchange when the coroutine that waits for it is canceled. The core uses that for the
  response timeout.

## What the core does above the port

The port stands for one exchange: a request goes out, and its answer comes back. The upstream session and
the handling of redirects are the same for every client, so the core does both itself, around the port. A
client that a program supplies gets them without any work of its own.

- The upstream session. `UpstreamSession` keeps the cookies that real services set, and gives every forwarded
  request the cookies that apply to its URL, as RFC 6265 says. The cookie store of the JDK is not used,
  because of the form in which it writes a cookie that was set with `Max-Age`. The parity recording
  `proxy-session-shared-cookie` states that such a cookie reaches the real service as `theme=dark`.
- Redirects. The core follows the redirects of `GET` and `HEAD` requests, up to 20 in a row, and sends the
  same request to each URL. Every other method gets the redirect response back. Each request of such a chain
  is an exchange of its own, with the cookies of the session that apply to it.
- The response timeout. The core gives the forwarding of a request one time for its complete answer, from the
  start and across redirects, and cancels the exchange that is under way when that time is up. One limit
  therefore covers the connection, the headers, and the body of the response.

## Repeating the spike

Run `./gradlew :mockey-core:test --tests '*UpstreamClientSpikeTest*'`. The test needs a JDK, because it calls
`keytool` and starts JVMs of its own.

`OutboundProxyTest` and `CertificateAndTimeoutTest` show the same for the adapter. They start JVMs of their own
as well, to see what the adapter does about the property in a JVM that sets it and in one that does not.
