# The Mockey UI

`mockey-ui` is the program that serves the web pages of Mockey. The pages show what a Mockey server holds
and let a person change it from a browser: the services with their scenarios, the plans, the twist
configurations, the tags, the history, and the definitions as a whole.

The UI is a program of its own, and it keeps nothing. Each time a page is loaded, the UI asks the server for
what the page shows. When a form is submitted, the UI makes the change through the admin API of the server.
That has a few consequences:

- The server does its work without a UI. Start the UI when someone needs it, and stop it at any time.
- A UI that's restarted shows the same things as before, because the server holds everything.
- A program can do everything that the UI does, through the same API. [admin-api.md](admin-api.md) describes
  the API.
- One UI manages one server. For a second server, start a second UI on another port.

[server.md](server.md) describes the server.

## Running the UI

The UI needs a Java 21 runtime and nothing else. Like the server, it comes in two forms:

- A distribution, `mockey-ui-3.0.0-SNAPSHOT.zip` or `mockey-ui-3.0.0-SNAPSHOT.tar.gz`. Unpacked, it has the
  start scripts `bin/mockey-ui` for Unix and `bin\mockey-ui.bat` for Windows, and the libraries of the program
  in `lib`.
- A runnable jar, `mockey-ui-3.0.0-SNAPSHOT-runnable.jar`, which is one file with the program and its
  libraries.

A build of the sources writes the distribution to `mockey-ui/build/distributions` and the runnable jar to
`mockey-ui/build/libs`. The Gradle task is `:mockey-ui:assemble`.

The start script looks for the Java runtime in the directory that the environment variable `JAVA_HOME` names.
Without that variable, it runs the `java` command that's on the path. It passes the options in the variables
`JAVA_OPTS` and `MOCKEY_UI_OPTS` to the runtime. The script for Unix needs a POSIX shell with `xargs`. Where
there's neither, as in an image that holds a Java runtime alone, use the runnable jar.

The commands in this document are written for a shell that has the `bin` directory of the distribution on
its path. They expect a server that runs on the same machine with its defaults, which the command
`mockey-server` starts in another terminal. This command starts the UI with its defaults:

```shell
mockey-ui
```

The UI writes its log to the standard output. It lists its settings first, and it's ready when it has logged
the address at which it accepts connections. Then open `http://localhost:8082/` in a browser. The first page
is the overview of the services.

The UI doesn't need the server in order to start. It asks the server when a page is loaded, so the two
programs can start in either order. The following command asks for the overview as a browser does, and prints
the status of the answer:

```shell
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/services
```

```text
200
```

The status is 200 when the UI reaches the server. Otherwise, it's 502, and the page says what's wrong, see
"When something is wrong with the server".

The runnable jar takes the same arguments as the start script. Every command in this document works with
`java -jar mockey-ui-3.0.0-SNAPSHOT-runnable.jar` in place of `mockey-ui`:

```shell
java -jar mockey-ui-3.0.0-SNAPSHOT-runnable.jar --version
```

```text
mockey-ui version 3.0.0-SNAPSHOT
```

The program ends with the status 0 after the UI has shut down cleanly, see "Stopping", and after it has
printed its help or its version. It ends with the status 1, and says why on the standard error, when a
setting can't be used or the UI can't start.

## Settings

Every setting has a command-line option and an environment variable. The option takes precedence over the
variable, and the default applies when neither is given.

```shell
mockey-ui --help
```

The help prints what this table holds:

| Option | Environment variable | Default | Meaning |
|---|---|---|---|
| `--host` | `MOCKEY_UI_HOST` | `127.0.0.1` | The address on which the UI accepts connections. |
| `--port` | `MOCKEY_UI_PORT` | `8082` | The port of the UI. `0` stands for a free port. |
| `--admin-url` | `MOCKEY_UI_ADMIN_URL` | `http://127.0.0.1:8081` | The URL of the admin listener of the server that the UI manages, without the path of the admin API. |
| `--admin-token` | `MOCKEY_ADMIN_TOKEN` | none | The admin token of the server, which the UI presents with every request to the admin API. |
| `--allowed-host` | `MOCKEY_UI_ALLOWED_HOST` | none | A host name that the UI answers to, in addition to IP addresses and `localhost`. |
| `--log-level` | `MOCKEY_UI_LOG_LEVEL` | `info` | The lowest level of messages that the log shows: `error`, `warn`, `info`, `debug`, or `trace`. |

A few notes on how a value is written:

- An option takes its value as the next argument or after an equals sign: `--port 9000` and `--port=9000` are
  the same.
- The option `--allowed-host` is given once for each name. Its variable takes all the names, separated by
  commas.
- A variable that's set counts, even when it's empty. No setting can use an empty value, so unset a variable
  to get the default.

These two commands start the same UI, the first with options and the second with variables:

```shell
mockey-ui --admin-url http://127.0.0.1:9081 --log-level debug
```

```shell
export MOCKEY_UI_ADMIN_URL=http://127.0.0.1:9081
export MOCKEY_UI_LOG_LEVEL=debug
mockey-ui
```

## The server that the UI manages

The setting `--admin-url` says where the admin listener of the server is. That listener has the port 8081 by
default. It's not the listener for the mock traffic, which has the port 8080.

- The URL begins with `http://` or `https://` and names a host. It names a port as well, unless the port is
  the default one of the scheme.
- The URL doesn't include the path `/api/v1`, which the UI adds by itself.
- If a reverse proxy stands in front of the admin listener, the URL includes the path at which the proxy
  serves the listener.

The footer of every page shows this URL, the version of the server, and whether the server is in read-only
mode or in writable mode.

### With an admin token

A server that has an admin token only accepts requests that present the token. Start the UI with the same
token. The UI reads it from the variable that the server reads it from, so a shell that starts both programs
sets the variable once:

```shell
export MOCKEY_ADMIN_TOKEN=a-long-text-that-nobody-guesses
mockey-ui
```

With a server that was started with this token, the overview answers as before:

```shell
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/services
```

```text
200
```

- Give the token in the variable and not in the option. The other users of a machine can read the options of
  a running program.
- The token stays in the UI. No page, no address, no cookie, and no line of the log holds it, so a browser
  never gets it.
- A token consists of printable ASCII characters. It can have spaces inside it, but not at its start or its
  end. The UI doesn't start with any other token, because it couldn't send one in a header.

### A server on another machine

The admin listener of a server only accepts connections from its own machine, unless the server is started
with another address in `--admin-host`. Such a server needs an admin token, see "Security" in
[server.md](server.md). Give the UI the URL of that listener:

```shell
export MOCKEY_ADMIN_TOKEN=a-long-text-that-nobody-guesses
mockey-ui --admin-url http://192.0.2.10:8081
```

The admin listener has no TLS, so the token and everything that the pages show cross the network as plain
text. On a network that isn't trusted, run the UI on the machine of the server. The alternative is a reverse
proxy with TLS in front of the admin listener, with its `https` URL in `--admin-url`. The UI verifies the
certificate of such a proxy against the trust store of its Java runtime.

The UI connects to the server directly. If the network only lets requests out through a proxy, pass the
proxy properties of Java to the runtime of the UI in the variable `MOCKEY_UI_OPTS`, such as
`-Dhttp.proxyHost=proxy.example.com -Dhttp.proxyPort=3128`. For an `https` URL, the properties are
`https.proxyHost` and `https.proxyPort`.

### When something is wrong with the server

The UI keeps running whatever happens to the server, and it asks the server again for every page. So the
pages work again as soon as the server does. Until then, every page says what's wrong and shows the URL that
the UI was started with:

| The page says | What it means | What to do |
|---|---|---|
| The server cannot be reached | Nothing accepts connections at the URL, or the server doesn't answer in time. | Start the server, or give the UI the right URL in `--admin-url`. |
| The server asks for an admin token | The server has an admin token, and the UI was started without one. | Start the UI with the token of the server. |
| The server does not take the admin token of the UI | The token of the UI isn't the token of the server. | Start the UI with the token of the server. |
| The server is not compatible with this UI | What answers at the URL has no admin API of the version `v1`. The mock listener of a server is such a case. | Use a server and a UI of the same version of Mockey, and give the UI the URL of the admin listener. |

Such a page comes with the status 502, and it shows nothing that the server holds. The help needs nothing
from the server, so its pages are shown anyway, with a line that says what's wrong.

If the server fails while it handles a form, the page says whether anything was changed. If the UI can't
tell, the page says that instead. Either way, the other pages show what the server holds now.

## The pages

The navigation at the top of every page leads to these sections:

| Section | What it's for |
|---|---|
| Services | The overview of the services, with a switch for the response mode and for the default scenario of each. It leads to the pages of a service and of its scenarios, the search, the diagnostics, and the form that derives real URLs. |
| History | The requests that the server handled, with filter tokens, replay, and the statistics. |
| Plans | Saving the settings of services as a plan, and applying a plan. |
| Twisting | The twist configurations, and which one is enabled. |
| Tags | The tag filter of the server, and the tags in use. |
| Import/Export | Downloading the definitions, and importing a document. |
| Server | What the server reports about itself, read-only mode, reload, reset, the outbound proxy, and the upstream session. |
| Help | What the terms mean and how the server behaves. |

A few things are true of all pages:

- The address of a page holds the view: the tag filter of the overview, the filter tokens and the page number
  of the history, and the time range of the statistics. A shared link shows the same view in another browser.
- After a form is submitted, the page says what happened, and it shows the report of the server if there is
  one. A form that the server rejects is shown again with the input that was entered and with a message next
  to each field that's wrong.
- The UI asks for confirmation before it deletes something, removes a tag from everything, resets the server,
  reloads the definitions, switches to writable mode, or merges services.
- Everything that the server holds is shown as text. A name, a header, or a body that contains markup is
  shown exactly as it's written, and the browser never runs it.
- A form holds at most 64 MiB, which is the limit for a document to import.

The built-in help is at `/help`. It explains the terms of Mockey in 18 topics, from services and scenarios to
storage and import. The help is part of the UI, so it works without a connection to anything else.

### Where the pages of Mockey 2.x are now

| Mockey 2.x | Mockey UI |
|---|---|
| The page of all services | Services |
| Create a Service | Services, then "New service" |
| The buttons that set every service to one response mode | Gone (DEV-75). The overview under Services switches the mode of one service, and a plan sets the modes of several services at once. |
| Merge | The page of a service, then "Merge services into this one" |
| Real URL injecting | Services, then "Derive real URLs" |
| Proxy Settings and Reset Session | Server, then "Outbound proxy and upstream session" |
| Flush | Server, then "Reset the server" |
| "In Memory Only" and "Writing to file" | Server, which switches between read-only mode and writable mode |
| Twisting | Twisting |
| Import/Export | Import/Export |
| History and History Stats | History, then "Statistics" |
| JSON Schema Validation | The page of a service, then "Check the scenarios against the schema" |
| Search | The search field of the overview |
| Help | Help |
| Configuration API | The admin API, see [admin-api.md](admin-api.md). A server with the legacy shim still answers the endpoints that the page described, see [migrating-from-2x.md](migrating-from-2x.md). |

The Image Depot, the debug console, and the page for mobile devices are gone, see "What is gone" in
[migrating-from-2x.md](migrating-from-2x.md).

### Scripting

Every page works in a browser that runs no scripts, through links, forms, and full page loads. Where scripts
run, the UI avoids a full page load in three places:

- A switch on the overview updates its row in place.
- A form that the server rejects shows its messages in place.
- The history with automatic refresh updates its list in place, every 5 seconds. Without scripts, the page
  reloads itself at that interval.

The script is [htmx](https://htmx.org), which comes with the UI. A page loads its stylesheet, its script, and
its icon from the UI and nothing from anywhere else, so the UI works on a network without access to the
internet. [third-party.md](third-party.md) lists what the UI ships and under which license.

## Who can reach the UI

The UI has no user accounts and no TLS. Everyone who reaches it can do what the admin API lets a client do:
change and delete the definitions, read the history with the headers and the bodies of the recorded requests,
and make the server send requests. So whoever can reach the UI can manage the server.

By default, the UI accepts connections on the loopback address `127.0.0.1`, so only browsers on the same
machine reach it. There are three ways to let someone else in. The first two keep the UI on the loopback
address.

### A tunnel, for one person

A person who can log in to the machine of the UI can forward a port of their own machine to it, for example
with `ssh -L 8082:127.0.0.1:8082` and the name of that machine. The UI is then at `http://localhost:8082/` in
that person's browser. The UI needs no setting for this, and SSH decides who gets in.

### A reverse proxy, for a team

A reverse proxy on the machine of the UI accepts the connections of the team, asks each person to log in,
and passes the requests on to `http://127.0.0.1:8082`. That's the way to share a UI. The proxy needs three
things:

- A login, of whichever kind the proxy offers. The proxy has to ask for it on every path, including the files
  below `/assets`.
- TLS. The UI speaks plain HTTP, so the proxy is where the connection of a browser gets its encryption.
- A host name of its own for the UI, such as `mockey.example.com`. The links of the pages are paths from the
  root, so the UI can't be served below a path of another site.

The UI only answers requests for names that it knows, see "The names of the UI". A proxy that passes on the
name that the browser asked for needs that name in `--allowed-host`:

```shell
mockey-ui --allowed-host mockey.example.com
```

```shell
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: mockey.example.com' http://localhost:8082/services
```

```text
200
```

A proxy that asks the UI for `127.0.0.1:8082` needs no setting.

### Another address, for a trusted network

The setting `--host` makes the UI accept connections on another address of its machine. The address `0.0.0.0`
stands for all of them:

```shell
mockey-ui --host 0.0.0.0
```

Now every machine that reaches port 8082 can manage the server, without a login and without encryption. The
UI logs a warning when it starts that way. Do this only on a network where everyone is allowed to manage the
server.

### The names of the UI

The UI only answers a request whose `Host` header names one of these:

- An IP address.
- `localhost`, or a name that ends with `.localhost`.
- The name in `--host`, if that setting holds a name.
- A name in `--allowed-host`.

Every other request gets the status 403 and a page that names the setting. The UI asks the server for
nothing on behalf of such a request:

```shell
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: other.example.com' http://localhost:8082/services
```

```text
403
```

The check keeps the pages of other sites out. A site can make a name of its own point to the address
`127.0.0.1`. A browser then treats a page of that site and the UI as one site. The name of such a site is
none of the names above, so its page reads nothing and changes nothing.

### What the UI does by itself

- Every form carries a token that the UI gave to that browser. The UI accepts no form without it, so a page
  of another site can't make a browser change something.
- The UI tells the browser to load scripts, stylesheets, and images from the UI alone, to run no script that's
  written into a page, and to show no page in a frame.
- A browser doesn't keep a page in its cache, because a page says what the server holds at that moment.

[decisions/ui-forms.md](decisions/ui-forms.md) and [decisions/ui-stack.md](decisions/ui-stack.md) give the
reasons.

## Logging

The UI writes its log to the standard output, from the level of the setting `--log-level` on. At startup, it
logs the settings in effect. The log never holds the admin token, at any level. It only says whether there is
one.

## Stopping

The UI shuts down cleanly when the system tells the program to end: on `SIGTERM`, which is what `kill` sends,
on `SIGINT`, which is Ctrl+C in a terminal, and on `SIGHUP`, which is the end of the terminal.

1. It accepts no more connections.
2. It waits 5 seconds, in which the requests in progress finish. It waits that long even when no request
   is in progress.
3. The program ends with the status 0.

Stopping the UI loses nothing, because the UI holds nothing. The pages that are open in a browser work again
when the UI runs again, with one exception. A form on a page from before the restart is refused once, with a
page that says so, because the UI that stopped made the token of that form. Load the page of the form again,
and submit the form from there.

A signal that the program was started to ignore stays ignored, so a UI that `nohup` started outlives its
terminal. A Java runtime that was told to leave the signals alone, with its option `-Xrs`, ends the program on
a signal at once.
