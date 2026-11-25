# The forms of the web UI

Decided in task 13.3 of the change `kotlin-rewrite-modular`, on 2026-11-25. The section "What a form asks the
server for" was added before task 13.7.

A form of the UI holds a token that only this UI makes, for the browser that the page of the form was loaded
in. The UI answers under an IP address, under `localhost`, and under the names that it is started with, and
under no other name. After a form, the UI sends the browser to a page. What the UI says of the form goes with
the browser in a cookie, and a report of the server is kept by the UI under an address of its own.

## What was needed

- The UI rejects a request that changes something and does not carry the anti-forgery token that the UI
  issued to that browser, and it makes no admin API call for it (`admin-ui`: Request forgery protection).
- After a form, the UI says whether the server did what the form asked for and shows the report of the server.
  It sends the browser to a page that can be loaded again without sending the form again (`admin-ui`: Result
  feedback).
- A form that the admin API rejects is shown again with what was entered, and with each message next to the
  field that it concerns (`admin-ui`: Validation feedback).
- The UI asks before it deletes or resets something, without scripting (`admin-ui`: Confirmation of
  destructive actions).
- Design decision 12 says that a form posts to the UI, that the browser is redirected to the result, and that
  there is no server-side session.
- The UI knows no users. By default it listens on the loopback address, where every program of the machine
  reaches it. A browser on that machine is such a program, and it shows pages from anywhere.

## Who can make a browser send a form

1. **A page of another site.** A browser lets such a page send a form to the UI. It does not let the page read
   the answer, or any page of the UI.
2. **A page that another program of the same machine serves**, on another port of `localhost`. For a browser,
   that page and the UI are one site, because it keeps cookies for a host and not for a port. So the forms of
   such a page come with the cookies of the UI, also with those that are marked `SameSite`, and the page can
   give the browser cookies that the UI gets. It still cannot read a page of the UI, which has another port.
3. **A page of another site whose name is made to stand for the address of the UI** after the browser has
   loaded the page. For the browser, the page and the UI are then one origin. The page reads every page of
   the UI, with whatever a form holds, and sends forms as the UI's own pages do.

A token in the forms is what stands against the first two. Nothing in a page stands against the third, which
is why the UI goes by the name that a request is for.

## The token

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The same random value in a cookie and in a field of the form. | The UI keeps nothing, and a UI that is started anew takes the forms of the pages before it. | The page of case 2 gives the browser a cookie of its choice and sends a form with the same value. |
| 2. A random value in a cookie, and in the field the mark that the UI makes of it with a key that only it has. | The UI keeps nothing but the key. Nobody else makes the mark of a value, so the page of case 2 gains nothing by a cookie of its choice. | The key is made anew when the UI starts, so the forms of pages that were loaded before are not taken. |
| 3. A mark of the time in the field, and no cookie. | The UI keeps nothing, and a browser needs no cookies. | The token is for no browser. The specification asks for the token that the UI issued to that browser. |
| 4. The `Origin` or `Sec-Fetch-Site` header of the request, in the place of a token or beside one. | A browser sets them, and no page changes them. | The UI has to know the origin under which a browser knows it, which is another one behind a reverse proxy. A browser from before these headers sends none. |
| 5. A session in the UI that holds the token. | It is how web applications do it. | The UI would hold something for every browser, which design decision 12 rules out. |

### The decision about the token

Way 2.

- The UI gives a browser a value of 18 random bytes in the cookie `mockey-ui-<port>-form`. The token in a
  form is the HMAC-SHA256 of that value with a key of 32 random bytes, which the UI makes when it starts and
  stores nowhere.
- The cookie is marked `HttpOnly`, so no script reads it, and `SameSite=Lax`. With `Lax`, the browser sends it
  when a link of another site leads to the UI, and with no form of another site. With `Strict`, such a link
  would come without the cookie, the UI would give the browser a new value, and the forms of the pages that
  are open in other tabs would no longer be taken.
- The name of the cookie holds the port of the UI. A browser keeps cookies for a host, so two UIs on one
  machine would otherwise take each other's cookie for their own, and each would replace the value of the
  other.
- A request with the method `POST` is taken when it comes with a value and with the token for it. A request
  without a value is refused before its body is read. The check stands before the routes, so no route can
  leave it out, and a request that is refused makes the UI ask the server for nothing.
- A request with the method `GET` changes nothing, so it needs no token. A request with another method than
  `GET`, `HEAD`, and `POST` is refused.
- After the UI was started anew, a form of a page from before is refused once, with a page that says so. The
  browser keeps its value, and a page that is loaded again has a form that the UI takes. What was entered into
  the form is lost, unless the browser still has it when the person goes back.

## The names of the UI

Whether the UI checks the name that a request is for was put to the one who directs this change, because no
requirement asks for it. The answer was to check it, with an option for further names.

- The UI answers a request whose `Host` header names an IP address, `localhost`, a name that ends with
  `.localhost`, or one of the names that the UI is started with, in `--allowed-host` or
  `MOCKEY_UI_ALLOWED_HOST`. The port does not count. The name that the UI is told to listen on, in `--host`,
  is one of its names.
- An IP address is no name that a site can make stand for another address, and no site decides what
  `localhost` stands for. So the page of case 3 has to ask under the name of its site, which is none of the
  UI.
- Every other request gets the status 403 and a page without the frame of the UI: it names no server, has no
  form, and gives no cookie. It repeats the name when that is written as a name is, and says with which
  option the UI is told the name. So the one who runs the UI behind another name reads what to do.
- Behind a reverse proxy, the UI gets the `Host` header that the proxy sends. A proxy that sends the address
  of the UI there needs nothing. A proxy that passes the name of its own on needs that name in
  `--allowed-host`.
- The check stands before everything else, also before the files of the UI.

The admin listener of the server has no such check, see
[web-pages-and-the-admin-api.md](web-pages-and-the-admin-api.md). There the page of case 3 reads, and changes
nothing.

## After a form

- **The browser is sent on** with the status 303 to a path of the UI. A browser asks for that page with the
  method `GET`, so loading the page again sends no form. A handler that names another place than a path of the
  UI fails.
- **A notice** says once that the change was made. It goes with the answer to the form in the cookie
  `mockey-ui-<port>-notice`, with its mark, and the next page shows it and tells the browser to drop it. The
  UI shows only a notice with the mark that it made, and a mark for a notice is none for a form.
  - A notice in the address of the page would stay there when the page is loaded again and when the address
    is given to someone else, and view state lives in the address (`admin-ui`).
  - A notice in the UI would be state for a browser.
- **A report** of the server, such as the one of a plan that was applied, is shown on a page of its own, at
  `/reports/<identifier>`. The UI keeps the report under an identifier of 18 random bytes: the newest 32
  reports, and of them as many as together list no more than 100,000 entries. A report that the UI no longer
  keeps is gone, and so are all reports when the UI stops. What a report was about stays done.
  - A report does not fit a cookie, which holds 4,096 bytes, and a report of an import does not fit an
    address.
  - This is something that the UI holds, where the specification says that the UI holds no definitions and
    no history of its own, and design decision 12 that there is no session. A report is neither: it is what
    the server said once about one form, it is for no browser, and nothing is read from it but its page.
  - The UI shows a report whatever the server says at that moment, and says what is the matter with the
    server where something is.
- **A form that is not taken** is shown again with the status 422. Every field shows what was sent for it. A
  field is for a member of a request to the admin API, and shows the messages of the API for that member and
  for what the member holds, such as `tags[1]` for `tags`. The messages that no field showed stand where the
  form ends, so that none is lost.
  - What the UI itself does not take, such as a delay that is no number, is shown in the same way.
  - The admin API says with the statuses 400, 413, and 422 that it does not take what a request holds. Every
    other refusal gets a page that reports it.
- **Asking first.** A link leads to a page that says what is about to be deleted, with a form. Nothing happens
  before that form is sent: a request for the same path without the field that the form of the page holds
  gets the page that asks.
- **A server that does not do what a form asks for** gets the pages of task 13.2, which say what the UI knows
  of the change: nothing was changed where no connection was made or the API refused, and the UI does not
  know where the request may have arrived and no answer came, or where the server failed.

## What a form holds

- A form is fields that are written as a URL writes its query, or parts, among them files. Every other body is
  refused with the status 415. The texts are UTF-8, as the pages are.
- A form has at most 64 MiB. The server takes a body of 50 MiB unless it is told otherwise, and a form writes
  a text with more bytes than the text has. A request that states a longer body is not read, and any other is
  read as far as the limit goes.
- Ktor takes a form of parts apart. It does so from the bytes that the UI has read with its limit, which the
  hook `ReceiveRequestBytes` of Ktor gives it in the place of the request.
- A browser sends the lines of a field with a carriage return before every line feed, whatever the page gave
  the field. The UI takes the lines of such a field with line feeds alone. Otherwise a body of a scenario
  would change with every form that is sent with it.
- A browser drops a line feed that follows the start of a field of several lines. The UI writes one there, so
  that a text that begins with an empty line keeps it.

## What a form asks the server for

A form that changes something shows what the server holds of it, and a request to the admin API changes only
the attributes that it states. So a form can ask for all that it holds, or for what was changed in it.

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The form states every attribute that it has a field for. | It is the least code, and the server holds what the form showed when it is done. | The form sets back what someone else changed since its page was made: a plan that a test applied, a switch of the overview in another window. A text of several lines is stored with other line ends than it had, because a browser sends every line with a carriage return. The body of a scenario would change when the scenario is renamed. |
| 2. The form states the attributes whose fields hold something else than the server holds when the form is sent. | No attribute is set that is as the server has it. | The UI asks the server once more for every form. What someone else changed in between is still set back, because the field holds what the page showed, which is something else by then. |
| 3. The form states the attributes whose fields hold something else than its page gave them. The page says what it gave each field. | The form changes what the person changed, and nothing else. It needs no request more. | The form has a hidden field for every field. |

Way 3.

- For each field, the form has a hidden field with a mark of the value that the page gave the field: 12 bytes
  of the SHA-256 of the value. A mark is no secret and guards nothing. A copy of the value would say the same
  and make a form with a long body twice as long.
- The mark of a text is the same however its lines end. So a text that the server holds with carriage returns
  is not changed by a browser that sends it back, and it is stored as it was as long as nobody edits it.
- A form that is shown again, because the server did not take it, keeps the marks that it was sent with. They
  still say what the first page gave the fields.
- A form that has no marks states every attribute. The form of something new is such a form.
- What the form checks itself, such as a delay that has to be a number, it checks for the fields that were
  changed. A value that the server holds and that the form would not take does not keep the form from saving
  something else.

## What this does not cover

- A browser that keeps no cookies. Its forms are not taken.
- A program of the machine that asks the UI itself. It reads the pages with their tokens and sends forms.
  Such a program reaches the admin API of a server on that machine as well.
- A UI on an address that other machines reach. Everyone who reaches it uses it, see
  [ui-stack.md](ui-stack.md).
- A page in a frame of another page. No browser shows a page of the UI there, which the headers of every
  answer say.
- A browser was not run. What a browser does with a cookie and with a form of another site is what the
  standards say.

## Repeating the tests

Run `./gradlew :mockey-ui:test --tests '*UiFormsTest*' --tests '*UiHostsTest*'`. The pages of these tests are
those of `SampleArea` in the test sources, which uses what the pages of the UI are made with in each of its
ways.
