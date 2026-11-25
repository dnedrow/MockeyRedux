# Web pages and the admin API

Decided before task 12.3 of the change `kotlin-rewrite-modular`, on 2026-11-25, when the admin API was about
to change things for the first time.

The admin API takes a request that can change something only without an `Origin` header, and it takes a
request body only when the request declares it as `application/json`. So a web page cannot make a browser
change anything through the admin API.

## What was needed

- By default the admin listener takes requests from the loopback address without a token. A browser on the
  machine of the server is a program on that machine, and it sends the requests of the pages that it shows.
  Such a page can come from anywhere.
- A browser lets a page send some requests to another origin without asking the server first: a `GET`, a
  `HEAD`, or a `POST` request whose body has one of the types `application/x-www-form-urlencoded`,
  `multipart/form-data`, and `text/plain`, or that has no body. For every other request it asks the server
  with an `OPTIONS` request, and it sends the request only when the answer permits it. The page does not
  get to read an answer that does not permit that either. The Fetch standard has both rules.
- The admin listener permits a page nothing: no answer carries an `Access-Control-Allow-Origin` header
  (`server-runtime`: Admin responses carry no cross-origin permission). That keeps a page from reading, and
  from every request that the browser asks about first.
- What is left is a `POST` request that the browser does not ask about. Up to task 12.2 the admin API only
  read. From task 12.3 on such a request would create a service from a body that a page sends as plain
  text, and later it would reset the definitions, with no body at all.
- The clients of the admin API are programs: scripts, `curl`, and the web UI, which is a server of its own
  and not a page (design decision 12).

## Ways

**1. Take a body only when the request declares it as JSON.** A request has to say what its body is anyway.
A body of `application/json` is none of the three types above, so the browser asks before it sends one, and
gets no permission.

- It covers every operation that takes a body, and no operation without one.

**2. Refuse a request that can change something and carries an `Origin` header.** By the Fetch standard a
browser sends that header with every request of a page whose method is neither `GET` nor `HEAD`, and where
it is not to tell the origin, it sends `null` in it. A program sends no such header by itself.

- It covers every operation, with a body and without one, and it asks nothing of a program.
- A page that is opened in a browser from before the standard said so sends a form without the header.

**3. Ask every request that can change something for a header that a page cannot send without asking**,
such as a header of the API's own.

- It covers every operation in every browser. Every script and every use of `curl` would have to send the
  header, for the sake of a client that is none.

**4. Tokens that a page cannot know**, as a web application guards its forms. They need a session, and the
admin API has none. An admin token is such a token already, see below.

## Decision

Ways 1 and 2, together.

- Way 2 is the rule: no request of a page changes anything. Way 1 holds on its own for the operations with
  a body, so the two do not depend on the same header.
- Neither asks anything of a program that was not asked before, but that a request says what its body is.
- A request that only reads is taken with an `Origin` header as before. It changes nothing, and the page
  does not get to read the answer.

## What follows

- `AdminApi` refuses a request with an `Origin` header whose method is neither `GET` nor `HEAD`, with the
  status 403 and a problem document. It does so after it has looked for the admin token and before it
  looks for an operation, so the answer is the same for every path. The value of the header does not count.
- `AdminRequest.jsonObject` refuses a body whose request has not exactly one `Content-Type` header that names
  `application/json`, with the status 415. A charset other than UTF-8 is refused as well. The body is not read
  before, so a body that is too long gets the same answer. A request without a body has nothing to declare.
- `curl` declares a body that it is given with `-d` as a form. It takes `-H 'Content-Type: application/json'`,
  or `--json` in the place of `-d`.

## What this does not cover

- A page whose name is made to stand for the loopback address after the browser has loaded it. For the
  browser, the page and the admin listener are then one origin. Such a page reads what the admin API states:
  a `GET` request of a page to its own origin carries no `Origin` header, and the browser gives the page the
  answer. It changes nothing, because its other requests carry the header. An admin token closes that way,
  because the page does not know the token. A check of the `Host` header would close it without a token, and
  it would need to know the names by which the admin listener is reached. It is not decided.
- A browser that sends no `Origin` header with a form, as browsers did before the standard said so. Through
  such a browser a page reaches an operation that takes no body.

## Repeating the tests

Run `./gradlew :mockey-server:test --tests '*AdminApiTest*'`. The tests show what the admin API does with
a request. What a browser sends is what the Fetch standard says, and no test of this project runs a browser.
