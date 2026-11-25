# Forwarding to the admin listener

Decided in task 12.10 of the change `kotlin-rewrite-modular`, on 2026-11-25.

The server marks every request that it forwards to a URL with the port of its own admin listener: the request
gets the header `Mockey-Forwarded`. The admin listener answers every request that carries that header with
the status 403. So no client of the mock endpoint gets an operation of the admin API through the server.

## What was needed

- The mock endpoint forwards a request to any URL of `http` or `https`, and by default the mock listener takes
  requests from other machines. The design names that as a risk that is accepted: the mock listener is an
  open forward proxy.
- By default the admin listener takes requests from the loopback address only, and without a token
  (`server-runtime`: Two listeners, Admin token required beyond loopback). The loopback address keeps other
  machines out. It does not keep the server out, which is on the same machine.
- Task 11.10 found what the two defaults do together, and the admin API of task 12.2 showed it:
  `GET /service/http://127.0.0.1:8081/api/v1/services` on the mock listener was answered with the services
  of the server. The request came to the admin listener from the loopback address. The answer was a mock
  response, which carries `Access-Control-Allow-Origin: *`, so a web page of any origin could read it as well.
- The capability `admin-api` therefore asks (The admin API is not reachable through the mock endpoint): the
  server performs no operation of the admin API for a request that it sent to the admin listener itself
  while it forwarded mock traffic, whether the target named the listener by an address or by a host name,
  or a redirect led to it. A mock request whose target is the admin listener gets the status 403.
- That has to hold with an admin token and without one, and before the admin API changes anything.
- What a real service receives is behavior that the rewrite keeps (`docs/deviations.md`). Legacy copies the
  headers of the client into the request that it forwards, and it adds none that tells of the forwarding. 38
  of the 267 parity recordings state every header that the real service got, such as
  `proxy-headers-application`.

## What leads to the admin listener

`ForwardMarkTest` sends mock requests for the admin listener of a server on `127.0.0.1`, and writes the
address of that listener in different ways. The upstream client is the HTTP client of the JDK
(`docs/decisions/upstream-client.md`), which looks a name up when it connects.

| Host in the URL | Where the request goes |
|---|---|
| `127.0.0.1`, `localhost`, `LOCALHOST` | To the admin listener. |
| `2130706433` | To the admin listener. The JDK reads one number as the four bytes of an address. |
| `[::ffff:127.0.0.1]`, `[::ffff:7f00:1]` | To the admin listener. The JDK takes an address of IPv6 that stands for one of IPv4 as that one. |
| `api.example.com:80@127.0.0.1` | To the admin listener. What stands before the `@` is a user name and a password. |
| `0.0.0.0`, `0` | To the admin listener on Linux and on macOS, where the address of no machine stands for the machine itself. |
| `127.1`, `0x7f.0.0.1` | Nowhere. `java.net.URI` takes neither for a host, and the JDK client sends no such request. |
| `[::1]` | Nowhere, because the listener is bound to the address of IPv4. |
| `127.0.0.1` in an `https` URL | Nowhere. The admin listener speaks no TLS, so the handshake fails before a request is sent. |

A real service leads there as well: the server follows the redirects of `GET` and `HEAD` requests, so an
answer with the status 302 and `Location: http://127.0.0.1:8081/api/v1/services` sends the request on to
the admin listener. So does a name, because whoever owns a name decides which address a name server gives
for it, and that can be `127.0.0.1`.

What all of these have in common is the port. Whatever leads a connection to the admin listener ends at the
port on which that listener accepts connections.

## Ways

Six ways were considered.

**1. Look at where a URL leads before the request is sent.** The server would look up the host of a URL,
and refuse the request when an address of the host is one of this machine and the port is the one of the
admin listener.

- The check and the connection are two lookups. The JDK client looks the name up itself when it connects.
  It cannot be told to connect to an address that the server has looked up, and still to name the host in
  the request: `Host` is one of the headers that it sets itself (`docs/decisions/upstream-client.md`), and it
  checks the certificate of an `https` URL against the host of the URL. Whoever owns a name can answer the
  first lookup with an address elsewhere and the second with `127.0.0.1`. The JDK keeps what it has looked
  up for a while, which makes the time between the two short. It does not make them one.
- Through an outbound proxy, the proxy does the lookup, and the server does not see what it gets.
- The check has to know every address of the machine and every way to write one. The table above holds
  the ways that were found, and nothing says that they are all.

**2. Forward nothing to the port of the admin listener.** No name is looked up, no header is added, and
nothing gets to the admin listener.

- It refuses requests that are not for the admin listener: those for a real service on another machine that
  listens on a port with the same number, which is 8081 unless the server is set otherwise. The capability
  `upstream-proxying` asks that an absolute target is forwarded (Forwarding an absolute target), and its
  exception is the admin listener, not a number.

**3. Mark every forwarded request, and refuse a marked request at the admin listener.** HTTP has a header
for a request that went through an intermediary, `Via`, which also serves to find loops (RFC 9110, section
7.6.3).

- It holds for every way by which a forwarded request comes to the admin listener, also for one that the
  server cannot know, such as a port forward in front of the listener.
- It changes what every real service receives. That is a deviation from legacy for every forwarded request,
  and for the 38 recordings named above.

**4. Mark the requests for the port of the admin listener, and refuse a marked request at the admin
listener.** This is way 3 for the requests that can end at the admin listener, and nothing for the others.

**5. Ask something of every request for the admin API that a forwarded request cannot have**, such as a
header that the mock endpoint never forwards. The metadata services of cloud providers do that.

- Every client of the admin API would have to send that header with every request: a script, `curl` in a
  terminal, the web UI. The cost of the mock endpoint would fall on every use of the admin API.

**6. Let the admin listener answer only to the names that it is known by**, and refuse at the mock endpoint
a URL that names it by one of them. That is what development servers do against a name that is made to
stand for the loopback address.

- Beyond the loopback address, the names of the admin listener are those of the machine, of a reverse proxy,
  and of a published port of a container. They would be a setting, and an admin listener that is reached
  under a name that the setting lacks would answer nobody.

## Decision

Way 4: the mark for the port of the admin listener.

- It holds for every URL that leads to the admin listener by itself, however the URL names the machine,
  and whatever a name stands for at the moment of the connection. The port is part of the URL or follows
  from its scheme, so nothing is looked up. The mark is decided on the URI that the upstream client sends
  the request to, so the two cannot read a URL differently.
- It holds for every request of a redirect chain. The core sends each of them through the upstream client,
  and the mark is given there.
- It refuses no mock request that is for a real service, and it changes nothing for a request to any other
  port. No parity recording changes.
- A real service on another machine that listens on a port with the number of the admin listener is still
  reached. It gets the header, which says what is true: the mock endpoint of a Mockey server forwarded the
  request. Where that matters, the admin listener can have another port.
- The admin listener is the one place that knows for certain that a request has come to it. It refuses a
  marked request before it looks at anything else, so the refusal does not depend on the token.

Way 3 does more, at a price that every user of proxy mode pays. Way 2 is simpler and refuses what it should
not. Ways 1, 5, and 6 are set aside for the reasons given with them.

## What follows

In `mockey-server`:

- `ForwardMark` is the mark. It puts an upstream client of its own around the client of the library, which
  hands every request on, and which adds the header `Mockey-Forwarded: true` to a request for the port of
  the admin listener. `ServerEngine` creates the engine with that client.
- The port of a request is the port of its URI, or else the port of its scheme: 80 for `http`, and 443 for
  `https`. A request whose URL tells no port, because it is no URL or its URI names no host, gets the mark
  as well. The client of the library sends none of those.
- The module of the admin listener asks the mark first. A request with the header gets a problem document
  with the status 403 and `Cache-Control: no-store`, whatever its path is, and whether or not it presents the
  admin token. Its body is not read.
- Only the name of the header counts. A client that sends the header to the admin listener itself is
  refused in the same way.
- An admin listener that is set to port 0 gets its port from the system when it starts, which is after the
  mock listener has started. A request that is to be forwarded before the mark knows the port waits until
  it does. So no request gets to the admin listener without the mark, and none carries it to another port.
- The mock endpoint relays the refusal like any answer of a real service. The history records the request as
  proxied with the status 403, and an unknown target leaves a service behind, as every unknown target does.

In `mockey-core`, so that a program can put a client around the one of the library:

- `UpstreamClient.overHttp` returns the upstream client that the library brings.
- `UpstreamRequest.uri` returns the URL of a request as the URI that this client sends the request to.

`docs/deviations.md` lists the header as DEV-69.

## What the mark does not cover

- A URL that leads to the admin listener through another port: a port forward, the published port of a
  container, or a reverse proxy in front of the listener. The server does not know of them. Each of them
  exists to let other machines in, and an admin listener that other machines reach needs an admin token.
- An outbound proxy that takes the header out of the requests that it passes on, and that reaches the admin
  listener. The mark goes with a request through an outbound proxy like every other header.
- An outbound proxy that is set to the address of the admin listener itself. Only someone with the power of
  the admin API, or over the definitions that the server loads, can set that.
- Other programs on the machine of the server that trust the loopback address, among them the admin
  listener of another Mockey server and, when it exists, the web UI. The mock endpoint forwards to them
  like to any URL. That is the risk of an open forward proxy, which `docs/server.md` describes with what
  closes it: a mock listener on the loopback address, and no forwarding of unknown targets.

## What would change the decision

- A need to keep forwarded requests from more than the admin listener, such as from the web UI or from an
  admin listener behind a port forward. Way 3 does that, at the price of a deviation for every forwarded
  request. A setting that names further ports to mark is a smaller step.
- An upstream client that connects to an address which the server has looked up. Way 1 would then hold for
  a connection that the server makes itself, though not through an outbound proxy.

## Repeating the tests

Run `./gradlew :mockey-server:test --tests '*ForwardMarkTest*'`. `ServerDocumentsTest` sends the request of
`docs/server.md` to the built program with its default settings and compares the refusal.
