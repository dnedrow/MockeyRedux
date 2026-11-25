# The Mockey server

`mockey-server` is the program that answers mock traffic. A client sends a request for a real service to the
server, at a path under `/service/`, and the server answers with a scenario of the service that it has
defined for that URL, or it forwards the request to the real service and passes the answer on.

The server has two listeners. The mock listener serves the mock traffic and `/status`. The admin listener is
for the admin API, which changes what the server does.

This document describes the server as it is now. The admin API and the web UI are not part of it yet: the
admin listener answers every request with the status 404, and the definitions of the server are the ones that
it loads and imports when it starts.

## Running the server

The server needs a Java 21 runtime and nothing else. It comes in two forms:

- A distribution, `mockey-server-3.0.0-SNAPSHOT.zip` or `mockey-server-3.0.0-SNAPSHOT.tar.gz`. Unpacked, it has
  the start scripts `bin/mockey-server` for Unix and `bin\mockey-server.bat` for Windows, and the libraries of
  the program in `lib`.
- A runnable jar, `mockey-server-3.0.0-SNAPSHOT-runnable.jar`, which is one file with the program and its
  libraries.

A build of the sources writes the distribution to `mockey-server/build/distributions` and the runnable jar to
`mockey-server/build/libs`. The Gradle task is `:mockey-server:assemble`.

The start script looks for the Java runtime in the directory that the environment variable `JAVA_HOME` names,
and without that variable it runs the `java` of the path. It passes the options in the variables `JAVA_OPTS`
and `MOCKEY_SERVER_OPTS` to the runtime. The script for Unix needs a POSIX shell with `xargs`. Where there is
neither, as in an image that holds a Java runtime alone, the runnable jar is the way.

The commands of this document are written for a shell that has the directory `bin` of the distribution on
its path. This starts the server with its defaults:

```shell
mockey-server
```

The server writes its log to the standard output. It names its settings first, and it is ready when it has
written where its two listeners accept connections. From then on `/status` answers:

```shell
curl http://localhost:8080/status
```

```json
{"status":"running","since":"2026-11-25T06:27:35.314Z"}
```

The runnable jar takes the same arguments as the start script. Every command of this document works with
`java -jar mockey-server-3.0.0-SNAPSHOT-runnable.jar` in the place of `mockey-server`:

```shell
java -jar mockey-server-3.0.0-SNAPSHOT-runnable.jar --version
```

```text
mockey-server version 3.0.0-SNAPSHOT
```

The program ends with the status 0 when the server has stopped in order, see "Stopping", and after it has
printed its help or its version. It ends with the status 1, and says why on the standard error, when a setting
cannot be used and when the server does not start.

## Settings

Every setting has a command-line option and an environment variable. The option takes precedence over the
variable, and the default applies when neither is given.

```shell
mockey-server --help
```

The help prints what this table holds:

| Option | Environment variable | Default | Meaning |
|---|---|---|---|
| `--mock-host` | `MOCKEY_MOCK_HOST` | `0.0.0.0` | The address on which the mock listener accepts connections. |
| `--mock-port` | `MOCKEY_MOCK_PORT` | `8080` | The port of the mock listener. `0` stands for a free port. |
| `--admin-host` | `MOCKEY_ADMIN_HOST` | `127.0.0.1` | The address on which the admin listener accepts connections. |
| `--admin-port` | `MOCKEY_ADMIN_PORT` | `8081` | The port of the admin listener. `0` stands for a free port. |
| `--admin-token` | `MOCKEY_ADMIN_TOKEN` | none | The token that every request to the admin API has to present as a bearer token. |
| `--allow-unprotected-admin` | `MOCKEY_ALLOW_UNPROTECTED_ADMIN` | off | Lets the admin listener accept connections from other hosts without an admin token. |
| `--definitions` | `MOCKEY_DEFINITIONS` | `mockey-definitions` | The directory of the stored definitions. |
| `--read-only`, `--writable` | `MOCKEY_READ_ONLY` | `--read-only` | Whether changes to the definitions stay in memory, or are written to the definitions directory. |
| `--initial-definitions` | `MOCKEY_INITIAL_DEFINITIONS` | none | A file, a directory, or a URL of definitions that take the place of the stored ones at startup. |
| `--tag-filter` | `MOCKEY_TAG_FILTER` | none | Tags, separated by spaces, of which a service or a scenario needs one to take part in routing. |
| `--history-limit` | `MOCKEY_HISTORY_LIMIT` | `1000` | The number of history entries that are kept. |
| `--body-capture-limit` | `MOCKEY_BODY_CAPTURE_LIMIT` | `1048576` (1 MiB) | The number of bytes that a history entry holds of a request body and of a response body. |
| `--upstream-connect-timeout` | `MOCKEY_UPSTREAM_CONNECT_TIMEOUT` | `10s` | The time within which the connection to a real service has to be made. |
| `--upstream-response-timeout` | `MOCKEY_UPSTREAM_RESPONSE_TIMEOUT` | `1m` | The time within which the complete response of a real service has to arrive. |
| `--max-request-body` | `MOCKEY_MAX_REQUEST_BODY` | `52428800` (50 MiB) | The number of bytes that the body of a request may have. |
| `--forward-unknown-targets`, `--no-forward-unknown-targets` | `MOCKEY_FORWARD_UNKNOWN_TARGETS` | `--forward-unknown-targets` | Whether a request for an absolute target that no service knows is forwarded to that target, or answered with the status 404. |
| `--log-level` | `MOCKEY_LOG_LEVEL` | `info` | The level from which on messages are written to the log: `error`, `warn`, `info`, `debug`, or `trace`. |
| `--shutdown-grace-period` | `MOCKEY_SHUTDOWN_GRACE_PERIOD` | `10s` | The time that requests in progress get to finish in when the server is told to stop. |

How a value is written:

- An option takes its value as the next argument or after an equals sign: `--mock-port 9090` and
  `--mock-port=9090` are the same.
- An option without a value switches something on or off. Its variable takes `true` or `false`:
  `MOCKEY_READ_ONLY=false` is `--writable`.
- A time is a number with a unit, such as `500ms`, `10s`, or `2m`. The shutdown grace period can be `0s`.
- A variable that is set counts, also when it is empty. An empty value is a value, and for most settings one
  that cannot be used. Unset a variable to get the default.

These two commands start the same server, the first with options and the second with variables:

```shell
mockey-server --mock-host 127.0.0.1 --log-level debug
```

```shell
export MOCKEY_MOCK_HOST=127.0.0.1
export MOCKEY_LOG_LEVEL=debug
mockey-server
```

## Listeners

The mock listener serves two things: the mock endpoint, which is every path that starts with `/service/`, and
`/status`. It answers every other path with the status 404. By default it accepts connections on port 8080 on
all addresses of the machine, because mock traffic often comes from other machines and from devices. The
address `0.0.0.0` stands for all addresses of IPv4. On a machine that has IPv6 as well, the Java runtime
makes the listener accept connections on the addresses of both.

The admin listener is for the admin API. By default it accepts connections on port 8081 on the loopback
address `127.0.0.1`, so that only programs on the same machine reach it.

The server does not start with an admin listener that other machines can reach and no admin token: whoever
reaches the admin API can change the definitions and make the server send requests. The message names the two
ways on: an admin token, or the setting `--allow-unprotected-admin`. See "Security" before taking the second.

With the port `0` a listener takes a free port, which the log names. When a listener cannot accept
connections on its address and port, because another program has the port or the address is not one of the
machine, the server says which listener, which address, and which port, and ends with the status 1.

Both listeners speak HTTP/1.1 without TLS. A request without a `Host` header, with more than one, or with a
`Host` header that holds what no name of a host holds gets the status 400 from either of them, and so does a
request whose lines do not end as HTTP has it.

## The mock endpoint

What follows `/service/` in the path of a request is the target: the URL of the real service that the client
wants.

- A relative target, as in `/service/orders`, names a service by the mock URL that the service is defined
  with. A relative target that no service has gets the status 404.
- An absolute target, as in `/service/http://api.example.com/v1/orders`, is the URL of a real service. When a
  service is defined for it, that service answers. When none is, the server forwards the request to the target
  and passes the answer on, and it defines a service in proxy mode for the target. That is the setting
  `--forward-unknown-targets`. With `--no-forward-unknown-targets` such a request gets the status 404, and
  the server contacts nobody.

A request whose body is longer than the setting `--max-request-body` allows gets the status 413. It is
neither matched nor forwarded.

The server adds five headers to every response of the mock endpoint, unless the scenario or the real service
sets a header of the same name: `Access-Control-Allow-Origin: *`, three headers that keep the response out of
caches (`Cache-Control`, `Pragma`, and `Expires`), and `Last-Modified` with the time of the response.

[rules.md](rules.md) describes the request rules that pick a scenario, and [inspectors.md](inspectors.md)
the request inspectors.

## Status

`/status` on the mock listener answers a request of every method with the status 200 and a JSON object of two
members: `status` is `running`, and `since` is the time at which the server started, in ISO 8601. It says
nothing else, and so nothing about the machine.

The listeners start when the server has its definitions. A client that gets an answer from `/status` gets it
from a server that answers mock requests by its definitions.

## Definitions

The definitions are the services with their scenarios, the plans, the twist configurations, and a few
settings that belong to them. The server loads them when it starts, from the definitions location: the
directory that the setting `--definitions` names, which is `mockey-definitions` in the working directory by
default. A directory that does not exist holds no definitions, and the server starts without any.

The server is in read-only mode by default: it reads the definitions location when it starts and never writes
to it. What changes while the server runs is gone when it stops. In writable mode, with `--writable`, the
server writes every change to the definitions location, which it creates when it has to. One such change
comes with mock traffic: the service that the server defines for an unknown absolute target.

### Initial definitions

The setting `--initial-definitions` names definitions that take the place of the stored ones when the server
starts. The source is one of these:

- A file with a definitions document of this server, which is JSON, or with an export document of Mockey 2.x,
  which is XML. Nothing but that file is read.
- A definitions directory of Mockey 2.x, which holds the file `mock_service_definitions.xml`. Name the
  directory and not that file: the file alone is read without the service files that it refers to.
- A URL with the scheme `http` or `https` whose server answers with one of the two documents. User
  information in the URL is presented as credentials, and a redirect is followed only when the URL has none,
  so that no other server gets them. The connection has to be made within 10 seconds and the answer has to
  arrive within 60 seconds.

A source that cannot be read keeps the server from starting. Of a source of Mockey 2.x that can be read in
part, the server takes what it can read, and the log names the rest as warnings.

In read-only mode the server keeps the initial definitions in memory and leaves the definitions location as
it is. Here `definitions.json` is a definitions document with a service at the mock URL `orders`:

```shell
mockey-server --initial-definitions definitions.json
```

```shell
curl http://localhost:8080/service/orders
```

```text
silver orders
```

In writable mode the server stores the initial definitions in the definitions location:

```shell
mockey-server --writable --initial-definitions definitions.json
```

It does so only while the definitions location holds no definitions, so that initial definitions never take
the place of stored ones. The same command would fail now, and its message says how to go on. Without the
initial definitions, the server starts with what it has stored:

```shell
mockey-server --writable
```

[migrating-from-2x.md](migrating-from-2x.md) describes the command `import-legacy`, which adds the definitions
of Mockey 2.x to a definitions location without starting a server.

### The tag filter

With a tag filter, only the services and scenarios that carry one of its tags take part in routing. Several
tags are separated by spaces:

```shell
mockey-server --tag-filter "team-a nightly"
```

## Logging

The server writes its log to the standard output, from the level of the setting `--log-level` on. At startup
it logs the settings in effect. The log never holds the admin token or the password of an outbound proxy at
any level: of the token it says only whether there is one, and the URL of initial definitions appears without
its user information and its query.

## Stopping

The server stops in order when the system tells the program to end: on `SIGTERM`, which is what `kill` sends,
on `SIGINT`, which is Ctrl+C in a terminal, and on `SIGHUP`, which is the end of the terminal.

1. It accepts no more connections, and it closes every connection on which no request is in progress.
2. The requests in progress get the shutdown grace period to finish. The answer to the last request of a
   connection carries the header `Connection: close`. The server goes on as soon as the last request is
   answered. A request that is still in progress when the time is over gets no answer, and the log says how
   many connections had one.
3. A save of the definitions that is under way is completed, however long it takes.
4. The program ends with the status 0.

A signal that arrives while the server starts does not cut the start short: the server starts, which completes
what it stores, and stops then. A signal that the program was started to ignore stays ignored, so a server
that `nohup` started outlives its terminal. A Java runtime that was told to leave the signals alone, with its
option `-Xrs`, ends the program on a signal at once and without any of this.

## Security

The server is a tool for development and test networks. It has no user accounts and no TLS, and two of its
defaults suit a trusted network only.

### The mock listener is an open forward proxy

With the default settings, whoever reaches the mock listener can make the server send a request to any URL
with the scheme `http` or `https` that the server can reach, and gets the answer. That is what forwarding of
unknown absolute targets is, and by default the mock listener accepts connections from every machine that
reaches port 8080:

```shell
mockey-server
```

```shell
curl http://localhost:8080/service/http://localhost:8080/status
```

```json
{"status":"running","since":"2026-11-25T06:27:35.314Z"}
```

Here the server sent the request to itself. In the same way it sends requests to hosts that the client cannot
reach, such as services of the internal network, services on the loopback address of the server's machine,
and the metadata service of a cloud provider. What comes with it:

- The request leaves from the server's machine with most headers of the client, so a real service sees the
  address of the server and not that of the client.
- The server keeps the cookies that real services set in one store and sends them with every forwarded
  request that they apply to, whichever client it comes from.
- Every unknown target leaves a service behind, in memory and, in writable mode, in the definitions location.
- A service in proxy mode forwards as well, to the real URL that it is defined with.

### The admin listener through the mock listener

The loopback address protects the admin listener from other machines, but not from the server itself. With
the default settings, a client of the mock listener reaches the admin listener through the mock endpoint:

```shell
curl -i http://localhost:8080/service/http://127.0.0.1:8081/
```

The answer is the one of the admin listener. The request came to it from the loopback address, where it asks
for no token. In this version nothing is behind the admin listener. Once the admin API is, such a request
could change the definitions. The server is to refuse such requests by itself before the admin API becomes
part of it. Until it does, any one of these closes that way:

- Set an admin token. The admin API then takes no request that does not present the token, wherever the
  request comes from:

  ```shell
  export MOCKEY_ADMIN_TOKEN=a-long-text-that-nobody-guesses
  mockey-server
  ```

- Switch the forwarding of unknown targets off. The server then forwards only to the real URLs of the
  services that are defined:

  ```shell
  mockey-server --no-forward-unknown-targets
  ```

- Let the mock listener accept connections from this machine only:

  ```shell
  mockey-server --mock-host 127.0.0.1
  ```

### The admin listener beyond the loopback address

Whoever reaches the admin API can change the definitions and make the server send requests. Leave the admin
listener on the loopback address where the programs that use it run on the same machine. Where they do not,
give the server an admin token:

```shell
export MOCKEY_ADMIN_TOKEN=a-long-text-that-nobody-guesses
mockey-server --admin-host 0.0.0.0
```

- Give the token in the variable and not in the option: the other users of a machine can read the options
  of a running program.
- The listeners have no TLS, so the token crosses the network as plain text. Across a network that is not
  trusted, put the admin listener behind a reverse proxy that has TLS, or reach it through a tunnel.
- `--allow-unprotected-admin` starts an admin listener that other machines reach without a token. Everyone
  who reaches it then has the power of the admin API. The server logs a warning when it starts that way.
- Responses of the admin listener carry no `Access-Control-Allow-Origin` header, so a web page of another
  origin cannot read them. Mock responses carry `Access-Control-Allow-Origin: *`, as a mock has to.
