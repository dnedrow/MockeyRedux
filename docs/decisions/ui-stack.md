# The stack of the web UI

Decided in task 13.1 of the change `kotlin-rewrite-modular`, on 2026-11-25. The section "The script" was
added in task 13.12, and the section "How the UI is delivered" in task 13.13, both on 2026-11-26.

`mockey-ui` is a Ktor application on Netty, as the server is, and it uses the routing of Ktor, which the
server does not. It writes its pages with kotlinx.html, and it talks to the admin API with the HTTP client
of the JDK, `java.net.http.HttpClient`. Its pages load one script, htmx, which nothing depends on.

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
  which is the one builder that writes markup as it is given, and a test reads the sources of the UI for it.
- What a server holds is shown through a few helpers: a tag, a URL, the headers, and a body. A URL that a
  server holds is text and no link, so a URL that begins with `javascript:` runs nothing.
- The path of an address says which kind of page it is, in words of the UI, such as `/service`. What the page
  is about is a parameter of the query string, such as `?id=`, beside what the page filters by. An identifier
  is any text that is not blank, because a server reads it from its definitions:
  - As a segment of a path, an identifier of dots would lead a browser to another page. A browser takes such
    a segment for the directory above, however it is written.
  - A proxy in front of the UI may take a slash that is written as `%2F` for one of the path, or refuse it.
  - A parameter holds every text as it is.
- Every page begins with what the server states of itself, which the UI asks for with `GET system` before it
  asks for anything else. That is the one place at which the UI finds that a server cannot be reached, does
  not take the token, or is no server that the UI can manage. It costs one request more for every page, on
  a connection that the client keeps.
- The files that a page refers to are resources of the program: a stylesheet, an icon, and a script. The UI
  serves them by their names from a list, so no other resource of the program is served. A file has a tag of
  its bytes, and a browser that has the file asks whether it still holds.

## The script

Every page loads one script from the UI itself: htmx 2.0.11, as a file in the source tree (see
[third-party.md](../third-party.md)). Where a page marks an element for it, the script sends the request of
that element and puts the answer into the page, so the browser doesn't load a whole page.

Nothing depends on the script. A browser without scripting gets the same pages with the same forms, and it
uses them with links, forms, and full page loads.

### What was needed

- With scripting, the UI can update a part of a page in place. Such an interaction uses the same address as
  its full-page equivalent, and it leaves the server in the same state (`admin-ui`: Optional scripting
  enhancement, with the scenario "Mode switch updates in place").
- With scripting, new history entries appear in place (`admin-ui`: Live history view).
- A browser gets every script from the UI and from nowhere else (`admin-ui`: Self-hosted assets).
- Design decision 12 says that htmx is a vendored static file, and that a handler returns a fragment instead
  of a full page when a request carries the marker header of htmx.
- Task 13.12 names three uses: the switches of the overview, the refresh of the history, and validation
  feedback.

### What the script does

| Where | Without the script | With the script |
|---|---|---|
| A switch of the overview | The browser is sent to the overview, at the row of the service. | The row of the service is replaced, and the page says what was switched. |
| A form with fields that isn't taken | The page comes back with the form and its messages. | The form with its messages replaces what the page shows below the navigation. |
| A form with fields that is done | The browser is sent to the page of the result. | The script loads the page of the result. |
| The history list with automatic refresh | The browser loads the page again every 5 seconds. | The script asks for the page every 5 seconds and replaces the part that depends on the history. |

Everything else is the same either way: links, forms that are only a button, and the confirmation steps.
[ui-pages.md](ui-pages.md) has the details for the overview and for the history.

### How a page marks an element

- A form has the attribute `hx-post` with the address of the form, which is also its `action`. A form with
  files has `hx-encoding="multipart/form-data"` as well. The script then sends the form the way a browser
  would: to the same address, with the same fields.
- The part of the history page that is refreshed has `hx-get` with the address of the page and
  `hx-trigger="every 5s"`.
- A page has no other attribute for the script, and the tests hold the pages to these four.

### How the UI answers the script

The script adds the header `HX-Request: true` to every request. That header is the only difference to the
request of a browser. With it, the UI answers in one of three ways:

- **A part of a page.** The answer is one element, and its headers say where it goes: `HX-Retarget` names
  the element that it replaces, and `HX-Reswap` says how. The UI has three such parts: the row of a service
  (`closest tr`), the content of a page below the navigation (`main`), and the refreshed part of the history
  page (`#entries`). After a form is shown again, the window shows the start of the page, as it does after
  a page load (`show:window:top`).
- **The address of a page**, in the header `HX-Redirect`, with the status 200 and no content. The script
  loads that page, as a browser does after the status 303. A script that got the status 303 would follow it
  on its own and put the page that it leads to into a part of the page. The address never names a place in
  the page, after a `#`: a browser that is sent to a place in the page that it already shows goes there and
  loads nothing.
- **A whole page**, exactly as for any other request. The script shows it in the place of the page that the
  browser shows, and it takes the title from it. That's how every failure reaches the person: a refusal, a
  server that can't be reached, and a page that something between the browser and the UI sent.

What the page says of a form that is done goes along with a part as a second element. It's marked with
`hx-swap-oob="innerHTML"`, so the script puts its content into the notice element of the page. Every page
has that element, also when it says nothing. So the element stays where it is, and a screen reader that
watches it announces the new content.

There were two ways to say where an answer goes:

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The form names the element, with `hx-target` and `hx-swap`. | It's how htmx is mostly used, and the markup shows where an answer goes. | The page writes the form, and the handler writes the answer. A part that doesn't fit the element that the form names would break the page. A whole page needs a header anyway. |
| 2. The answer names the element, with `HX-Retarget` and `HX-Reswap`. | A part goes where it belongs, whatever the form says. The settings can send every other answer to the place of the whole page. | The markup doesn't show where an answer goes. |

Way 2. An answer without these headers is no part, so it replaces the page.

One case needs care. To make the row of a service, the UI asks the server again, after the server has made
the change. If that fails, the page of a failure would be wrong, because it says that nothing was changed.
So the UI falls back to the address of the overview, as without the script. The overview then says what is
the matter with the server, and it says what was switched.

### The settings of the script

Every page states the settings of the script in a `meta` element with the name `htmx-config`:

| Setting | Value | Why |
|---|---|---|
| `includeIndicatorStyles` | `false` | The script would add a `style` element to the page, which the content security policy forbids. The stylesheet of the UI has the one rule that's needed: a form is dimmed until its answer is there. |
| `allowEval` | `false` | The script evaluates no text as code. The policy forbids that too, and the UI uses nothing that needs it. |
| `allowScriptTags` | `false` | The script runs no script that an answer holds. |
| `historyCacheSize` | `0` | The script keeps no copy of a page in the storage of the browser. It only does that for pages that change the address, which the UI doesn't use, so the setting is a second line. |
| `responseHandling` | See below. | By default, the script drops every answer with a status from 400 on. Someone would press a button and see nothing. |

`responseHandling` has two rules. An answer with the status 204 changes nothing. Every other answer replaces
what the body of the page holds, unless its headers say otherwise.

The script writes one entry into the session storage of the tab: the path and the query string of the page,
under `htmx-current-path-for-history`. It holds nothing of what a page shows.

The content security policy of the UI didn't have to change. It already let a page load a script from the UI
itself, and it lets a script of a page ask the UI and nothing else.

### What the UI relies on

The UI relies on this behavior of htmx. It was read in the source of version 2.0.11 and seen in a browser:

- The script sends a marked form when the form is submitted, by a button or by the Enter key. It sends the
  fields that the form holds and the button that was pressed.
- A field of several lines is sent with line feeds alone, where a browser sends a carriage return before
  every line feed. A form with files is written by the browser, with carriage returns. The UI takes both
  (see [ui-forms.md](ui-forms.md)).
- A file field without a chosen file is left out, where a browser sends a file without a name and without
  content.
- `HX-Retarget` and `HX-Reswap` count for more than the settings. `closest tr` is looked for from the form.
- An answer that is a whole document gives the content of its body and its title. Anything else is read as
  the content of a `template` element, so a table row can stand for itself.
- After the script has put an answer into the page, it gives the focus to the element with the identifier
  of the one that had it before. That's why the controls of a switch and the links and buttons of the
  history list have identifiers.
- A part that is refreshed stops asking when it's no longer in the page, and the part that took its place
  asks on.
- The script only sends requests to the origin of the page.

### A check in a browser

The tests run no browser. So the script was run once in headless Chrome 158, which a small program drove
through the DevTools protocol. The UI and the server were the runnable jars. This is what Chrome did, and
it's the list to go through again after htmx is updated:

1. **A page load.** The console is empty, so nothing violates the content security policy. `htmx.version`
   is `2.0.11`, `htmx.config` has the settings of the page, the page has no `style` element, and the session
   storage has the one entry.
2. **A switch of the overview.** Pressing a mode button sends one request with `HX-Request: true`, and no
   page is loaded. Only the row of that service is a new element. The notice element is the same element as
   before, and it says what was switched. The pressed button has the focus. What the page shows then equals
   the page that is loaded again, apart from the notice. The same holds for the default scenario, for a
   second switch in the row that was replaced, and for a button that is pressed with the space bar.
3. **A form that isn't taken.** A new service with a delay that's no number gets the status 422. No page is
   loaded, the fields keep what was entered, the message is at its field, and the window shows the start of
   the page, as after a page load. The request has line feeds alone in a field of several lines. The Enter
   key sends the form through the script as well. The corrected form leads to the page of the new service
   with its notice.
4. **Every form with fields.** For each of the 15 forms, the script sent exactly the fields that the form
   holds, and Chrome showed the answer: the page of the result, or the form with its messages.
5. **A form with a file.** The import with a chosen file led to the report, and the server held what the
   file held. Without a file, the form came back with its message.
6. **The history.** With automatic refresh, a request to the mock endpoint appeared in the list within 5
   seconds, and no page was loaded. A text that was typed into the filter stayed, and so did the focus on a
   link of the list. After the history was emptied, the list said so and no longer offered to delete
   entries. A list without automatic refresh asked for nothing. With scripting switched off, Chrome loaded
   the page again every 5 seconds.
7. **Failures.** With the server stopped, a switch, a form, and the refresh of the list each showed the page
   "The server cannot be reached" in the place of the page, with its title. The list asked no more.
8. **Where requests go.** Every request of the browser went to the UI.
9. **An address of the page that is shown.** Sent to a place in the page that it showed, Chrome loaded
   nothing. Sent to the same address without a place, it loaded the page again. After a switch in place and
   a visit to another page, the back button showed the overview as the server held it.

### What the script doesn't cover

- **No answer at all.** When the UI is down or the connection breaks, the script changes nothing, and the
  page says nothing. A browser without scripting shows its own error page.
- **A browser that runs scripts and doesn't get the script.** Its forms work as without scripting. The
  history list doesn't refresh: such a browser ignores the `noscript` element, and nothing asks for the
  page.
- **The console of the browser.** A browser notes every answer to a script with a status from 400 on as a
  failed request, also the status 422 of a form that isn't taken.
- **Other browsers.** Only Chrome was run. Safari on macOS doesn't give the focus to a button that is
  clicked, so there's no focus to keep after a click.
- **The names of fields.** In a browser, a field shadows the property of its form that has the same name.
  The fields `name` and `method` do that, and the script doesn't depend on those properties. A field that
  is named like something the script does depend on, such as `attributes`, `matches`, or `closest`, would
  break its form for the script. Check 4 above would show it.
- **The tests.** `Browser.submitWithScript` sends what the script sends, and `Swap` does what the script does
  with an answer. Both follow the behavior that's listed above, and neither is the script.

## The settings

| Option | Environment variable | Default |
|---|---|---|
| `--host` | `MOCKEY_UI_HOST` | `127.0.0.1` |
| `--port` | `MOCKEY_UI_PORT` | `8082` |
| `--admin-url` | `MOCKEY_UI_ADMIN_URL` | `http://127.0.0.1:8081` |
| `--admin-token` | `MOCKEY_ADMIN_TOKEN` | none |
| `--allowed-host` | `MOCKEY_UI_ALLOWED_HOST` | none |
| `--log-level` | `MOCKEY_UI_LOG_LEVEL` | `info` |

- The admin URL says where the admin listener is, without the path of the admin API, which the UI knows.
  Behind a reverse proxy it has the path at which the proxy serves the listener. It holds no user name and no
  password, because the pages show it.
- The variable of the admin token is the one of the server program. The token is one secret, and an
  environment that both programs start in holds it once.
- The allowed hosts are the names under which the UI answers besides IP addresses and `localhost`. Task 13.3
  added them, see [ui-forms.md](ui-forms.md).
- The UI asks the server for nothing before a page is loaded, so it starts whether or not the server runs.
- The UI warns in its log when it listens on an address that other machines reach.

## How the UI is delivered

Task 13.13 packages the UI as the server is packaged, which is what design decision 18 asks for.

- The module applies the convention plugin `mockey.application`. The build delivers a distribution as
  `mockey-ui-<version>.zip` and `mockey-ui-<version>.tar.gz`, with the start scripts `bin/mockey-ui` and
  `bin\mockey-ui.bat`, and a runnable jar, `mockey-ui-<version>-runnable.jar`.
- The distribution holds the jar of the UI, the jar of `mockey-api`, and the libraries of both. It holds
  nothing of the engine, of the importer, or of the server program, because the UI depends on none of them.
- The files of the pages are resources of the jar of the UI: the stylesheet, the icon, the script, and the
  license of the script. [third-party.md](../third-party.md) names the script.
- Neither form holds a native library, so the UI runs on a Java 21 runtime alone, as the server does.
- [ui.md](../ui.md) is the document for whoever runs the UI. It has the settings, the server that the UI
  manages, the pages, and the section "Who can reach the UI", which is the note on exposing the UI that the
  design asks for.

`UiDistributionTest` starts the program from the start script and from the runnable jar, in an environment
that holds nothing but the path. The UI shows the overview and every page of the help, with the files that
the pages refer to, and it ends with the status 0 on `SIGTERM`. `UiDocumentsTest` runs the commands of
`docs/ui.md` as they're written, and it holds the settings table of the document against the help of the
program.

In both tests, the server is a stand-in, because `mockey-ui` must not depend on the server module, not even
for its tests. For the task, the two programs ran together once by hand: the UI from its built distribution
against the server from its built distribution. A script loaded the overview, the first page of the help,
the 18 topics, every page that a topic links to, and the three files. All 79 checks passed. Task 15.1 turns
such a run into a check of the build.

One thing came up that the task doesn't cover. An idle UI takes 5 seconds to stop, because Netty waits for
the grace period even when no request is in progress. `docs/ui.md` says so.

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
- The tests run no browser. What a browser does with the headers above is what the standards say. Where a
  page was looked at in a browser, [ui-pages.md](ui-pages.md) says so, and "A check in a browser" above says
  what the script was seen to do in one.

## Repeating the tests

Run `./gradlew :mockey-ui:test`. The tests start the UI on a port that the system picks, with a stand-in for
the admin API beside it, and read the pages over HTTP. `UiDistributionTest` and `UiDocumentsTest` start the
program as the build delivers it. `UiDocumentsTest` needs the ports 8081 and 8082 free, and it needs `curl`.
