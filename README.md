# Mockey Redux

Mockey Redux is a mock server for HTTP services. An application that's being developed or tested sends its
requests to Mockey in place of the real services. Mockey answers each request with a response that was
defined for it, or it forwards the request to the real service and passes the answer on. It records every
exchange, so the answer of a real service can become a mock response later.

Mockey Redux is a Kotlin rewrite of [Mockey](https://github.com/clafonta/Mockey). The mock engine, the admin
API, the server, and the UI are separate modules, each with its own tests. The project is under active
development, and version 3.0.0 will be its first release. It isn't a drop-in replacement for Mockey 2.x: the
[migration guide](docs/migrating-from-2x.md) describes the move, and the
[deviation register](docs/deviations.md) lists every intended difference in behavior.

## Quick start

Mockey Redux is two programs, and each needs a Java 21 runtime and nothing else:

- The server, `mockey-server`, answers the mock requests. It also has the admin API, which reads and changes
  what the server holds.
- The UI, `mockey-ui`, serves the web pages that manage a server through the admin API.

Each program comes as one file, a runnable jar. Download `mockey-server-3.0.0-SNAPSHOT-runnable.jar` and
`mockey-ui-3.0.0-SNAPSHOT-runnable.jar` from the [releases page][releases] into one directory, or build them
as "Building from the sources" describes and copy them there. Two commands start Mockey. In that directory,
start the server:

```shell
java -jar mockey-server-3.0.0-SNAPSHOT-runnable.jar
```

In a second terminal, start the UI:

```shell
java -jar mockey-ui-3.0.0-SNAPSHOT-runnable.jar
```

Each program writes its log to the terminal, and it's ready when the log says where it accepts connections.

A new server has no definitions, so give it a first mock. In a third terminal, this command imports a
service with one scenario through the admin API. Like the commands of the other documents, it's written for
a Unix shell with `curl`:

```shell
curl -X POST http://localhost:8081/api/v1/definitions/import \
  -H 'Content-Type: application/json' \
  -d '{
    "formatVersion": 1,
    "services": [{
      "id": "greeting",
      "name": "Greeting",
      "mockUrl": "hello",
      "responseMode": "static",
      "scenarios": [{"id": "hello", "name": "Hello", "responseBody": "Hello from Mockey\n"}]
    }]
  }'
```

The body of the request is a definitions document, the same kind of document that a server exports. Each
`id` is a text of your choice that identifies its service or scenario. The answer is the report of the
import, which names the service and the scenario that the server added.

The server answers requests for the mock URL of a service below `/service/`:

```shell
curl http://localhost:8080/service/hello
```

```text
Hello from Mockey
```

Now open <http://localhost:8082/> in a browser. The first page lists the service `Greeting`. History, in the
navigation at the top, shows the request that the service has just answered. From there, the pages of the
UI do the rest: they create and change services, scenarios, and plans, and they import and export
definitions.

To stop a program, press Ctrl+C in its terminal.

Two defaults are worth knowing about:

- The server keeps its definitions in memory, so the service is gone when the server stops. Start the server
  with `--writable` to store every change in the directory `mockey-definitions`.
- The admin API and the UI answer only programs on the same machine. The mock listener accepts connections
  from other machines too, and it forwards a request for an absolute URL that no service knows to that URL.
  Read "Security" in [docs/server.md](docs/server.md) before Mockey runs where others can reach it.

## Documentation

| Document | What it covers |
|---|---|
| [docs/server.md](docs/server.md) | Running the server: its settings, listeners, definitions, and security. |
| [docs/ui.md](docs/ui.md) | Running the UI, and what its pages do. |
| [docs/admin-api.md](docs/admin-api.md) | The admin API, for the scripts and tests that manage a server. |
| [docs/rules.md](docs/rules.md) | Request rules, which say what a request must contain. |
| [docs/inspectors.md](docs/inspectors.md) | Request inspectors, which check requests with code. |
| [docs/library.md](docs/library.md) | Embedding the mock engine in a JVM application or in its tests. |
| [docs/migrating-from-2x.md](docs/migrating-from-2x.md) | Moving from Mockey 2.x: definitions, startup options, and scripts. |
| [docs/deviations.md](docs/deviations.md) | Every intended difference from the behavior of Mockey 2.x. |

The directory `docs/decisions` holds the records of the design decisions. The directory `docs/parity`
describes how the behavior of Mockey 2.x was recorded and how the new system is compared with it.

## Building from the sources

A build needs a JDK 21. The Gradle wrapper in the repository downloads everything else:

```shell
./gradlew assemble
```

The build writes the runnable jars to `mockey-server/build/libs` and `mockey-ui/build/libs`. It also writes a
distribution of each program, with start scripts for Unix and Windows, to `mockey-server/build/distributions`
and `mockey-ui/build/distributions`. To run the tests of every module and the other checks of the project,
use `./gradlew check`.

## License

The project is licensed under GPL-2.0-or-later, see [LICENSE](LICENSE).
[docs/third-party.md](docs/third-party.md) lists the files of others that are in the source tree.

[releases]: https://github.com/dnedrow/MockeyRedux/releases
