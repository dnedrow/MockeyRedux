# The stack of the web UI

Decided in task 13.1 of the change `kotlin-rewrite-modular`, on 2026-11-25.

`mockey-ui` is a Ktor application on Netty, as the server is, and it uses the routing of Ktor, which the
server does not. It writes its pages with kotlinx.html, and it talks to the admin API with the HTTP client
of the JDK, `java.net.http.HttpClient`.

## What was needed

- The UI is a process of its own. It reads and changes what a server holds through the admin API of that
  server, and it holds nothing of it itself (`admin-ui`: Separate process that uses only the admin API).
- It presents the admin token to the server with every request and never to a browser (`admin-ui`: Server
  connection settings). It listens on the loopback address unless it is told otherwise (`admin-ui`: Listener
  defaults).
- Every page works in a browser without scripting, and everything that a page uses comes from the UI itself
  (`admin-ui`: Usable without JavaScript, Self-hosted assets). What the server holds is shown as text
  (`admin-ui`: Stored content is shown as text).
- Design decision 12 names kotlinx.html for the pages, and design decision 19 names Ktor for both
  applications. Design decision 18 gives both applications the same kind of settings, log, and packaging.
- `mockey-ui` depends on `mockey-api` and on no other module of the project. So it shares no code with the
  server program, and what the two programs have alike exists twice: the help of the command line, the start
  and the stop of the program, and the log.

## The routing of Ktor

The admin API has a table of routes of its own, see "The admin API and the routing of Ktor" in
[http-stack.md](http-stack.md). The UI uses the routing of Ktor. What spoke against the routing there does
not hold for the UI, or it takes the UI a few lines:

| What the routing does | Why the admin API does without it | What it means for the UI |
|---|---|---|
| It writes the path of a request to the log, at the level of traces. | The log of the server gets nothing of a request. | A path of the UI holds identifiers and filters, and no secret. The admin token is in no address. |
| It answers a path with a malformed escape with the status 400 and a text that repeats the request target. | Neither an answer nor the log repeats anything of a request. | The UI answers such a request with a page of its own that repeats nothing. It does so in the hook `CallFailed` of Ktor, which also gives every other failure of a request its page. |
| A path that is written in another way reaches the route, such as `//system` or `/%73ystem` for `/system`. | A check that goes by the path as it was sent would be passed. | The UI checks nothing by the path of a request. |
| A `HEAD` request does not reach a route of `GET`. | The API answers it as the operation for `GET`. | A plugin of the UI makes the request one with the method `GET` before the routing sees it, and the engine leaves the body out. |
| A path without a route gets the status 404 with no body. | The API answers with a problem document. | A route for every other path answers with a page in the frame of the UI. |

The UI has many routes, and its forms need what the routing has: the parameters of a path, the parameters of
a query string, and the content of a form.

## The client of the admin API

| Client | What it adds to the class path | Why it was considered |
|---|---|---|
| `java.net.http.HttpClient` | Nothing. | The core forwards to real services with it, see [upstream-client.md](upstream-client.md). It checks certificates against the trust store of the runtime. |
| The Ktor client | Its core and one of its engines. | It is of the stack of the UI. Its engine for Java is the JDK client behind another interface, and each of its other engines is a further HTTP client. |

Only the JDK client was run. `AdminClientTest` runs it against a stand-in for the admin API, on OpenJDK
21.0.13. What the tests show:

| Question | What the JDK client does |
|---|---|
| The headers that it adds | No `Origin` header. So the admin API takes the requests of the UI that change something, see [web-pages-and-the-admin-api.md](web-pages-and-the-admin-api.md). |
| A header with a character beyond ASCII | It takes the header when its characters are below 256, and it sends a question mark in the place of each of them that is beyond ASCII. Its sources say why: `Utils.isValidValue` admits the characters, and `Http1Request` writes the headers in US-ASCII. |
| A redirect | It follows none, as it is told. The token of the UI is for the server and for no other place. |
| A server that does not answer | An `IOException`, also where the time for the connection or for the answer runs out. |

### The decision about the client

The JDK client.

- It adds nothing to the program, and what it does with TLS and with the proxy settings of the runtime is
  what the runtime does.
- The UI gives a connection 5 seconds to be made and an answer 2 minutes to arrive. The second time is
  generous because a replay lasts as long as the real service takes that the server forwards the request to.
- The UI reads an answer leniently: a member that it does not know is left aside, so that a server of a later
  version can state more. The server reads a request strictly, so the UI sends only what the description of
  the API has. The tests hold every request of the UI against that description, see `ApiContract`.

### What follows for the admin token

A token of the UI is made of the characters of ASCII that are printed, with spaces between them. The UI
refuses every other token when it starts, and says why.

- A server takes every text that is not blank as its token, and it compares a presented token as the bytes
  that the client sent. A program that sends the bytes of UTF-8, as `curl` does, can present a token with a
  character beyond ASCII. The UI cannot, because the JDK client writes no such byte.
- So the UI does not manage a server whose token has such a character, until that server gets another token.
  This is less than `admin-ui` asks for where it says that the UI is configured with the admin token that
  the server requires.
- The characters of a bearer token are a part of ASCII by RFC 6750. A token that keeps to it is one that the
  UI presents.
- The test `theHttpClientOfTheRuntimeWritesAQuestionMarkForACharacterBeyondAscii` fails on a runtime whose
  client sends such a character. On such a runtime, the UI can present the token as its bytes in UTF-8, and
  the rule can go.
- Another client would lift the rule now. It was not looked for, because it would be a library for the sake
  of tokens that few servers have.

## The pages

- A page is written with the builders of kotlinx.html, as design decision 12 says. The builders write every
  text and every value of an attribute so that a browser reads it as text. The UI does not use `unsafe`,
  which is the one builder that writes markup as it is given.
- Every page begins with what the server states of itself, which the UI asks for with `GET system` before it
  asks for anything else. That is the one place at which the UI finds that a server cannot be reached, does
  not take the token, or is no server that the UI can manage. It costs one request more for every page, on
  a connection that the client keeps.
- The files that a page refers to are resources of the program: a stylesheet and an icon. The UI serves them
  by their names from a list, so no other resource of the program is served. A file has a tag of its bytes,
  and a browser that has the file asks whether it still holds.

## The settings

| Option | Environment variable | Default |
|---|---|---|
| `--host` | `MOCKEY_UI_HOST` | `127.0.0.1` |
| `--port` | `MOCKEY_UI_PORT` | `8082` |
| `--admin-url` | `MOCKEY_UI_ADMIN_URL` | `http://127.0.0.1:8081` |
| `--admin-token` | `MOCKEY_ADMIN_TOKEN` | none |
| `--log-level` | `MOCKEY_UI_LOG_LEVEL` | `info` |

- The admin URL says where the admin listener is, without the path of the admin API, which the UI knows.
  Behind a reverse proxy it has the path at which the proxy serves the listener. It holds no user name and no
  password, because the pages show it.
- The variable of the admin token is the one of the server program. The token is one secret, and an
  environment that both programs start in holds it once.
- The UI asks the server for nothing before a page is loaded, so it starts whether or not the server runs.
- The UI warns in its log when it listens on an address that other machines reach.

## What every answer carries

- `Content-Security-Policy`: a browser loads stylesheets, scripts, images, and fonts for a page from the UI
  itself and from nowhere else. It runs no script and applies no style that is written into a page, it sends
  the forms of a page to the UI only, and it shows a page in no frame.
- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer`. The address
  of a page holds what the page filters by, which no other site is to get.
- A page comes with `Cache-Control: no-store`, because it says what the server holds now.

The policy is a second line. The first is that no page refers to another place, which `UiPagesTest` checks in
the pages themselves.

## What this does not cover

- The UI knows no users, and it does not speak TLS. Everyone who reaches it can do what the admin API lets
  its clients do. A UI for more than one machine belongs behind a reverse proxy that asks who is there.
- The settings of a proxy for the requests to the admin API. The JDK client goes by the system properties of
  the runtime, such as `http.proxyHost`, and by nothing of the UI.
- A browser was not run. What a browser does with the headers above is what the standards say.

## Repeating the tests

Run `./gradlew :mockey-ui:test`. The tests start the UI on a port that the system picks, with a stand-in for
the admin API beside it, and read the pages over HTTP.
