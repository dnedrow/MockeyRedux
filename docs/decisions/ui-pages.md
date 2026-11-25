# The pages of the web UI

Decided in tasks 13.5 and 13.6 of the change `kotlin-rewrite-modular`, on 2026-11-25.

This record says how the pages of the UI show what a server holds, page by page. What all pages have in common
is in [ui-stack.md](ui-stack.md), and what their forms are made of is in [ui-forms.md](ui-forms.md).

## The overview of the services

The overview is the first page of the UI. It lists the services by their names, with a switch for the response
mode and a switch for the default scenario of each. A marker at a service leads to the page of the
diagnostics, at what that page says of the service.

### What was needed

- The overview lists each service with its name, its callable mock URL, its effective response mode, its
  default scenario, its delay, its tags, its last-used time, and a marker when diagnostics concern it. From the
  overview a user switches the response mode and the default scenario of a service (`admin-ui`: Services
  overview).
- The UI shows the complete URL at which a client calls a service at the mock endpoint, built from the base
  URL that the server reports (`admin-ui`: Callable mock URL).
- A list filter is in the address of the page, so that the same address shows the same view in another
  browser (`admin-ui`: View state lives in the address).
- Everything works in a browser without scripting (`admin-ui`: Usable without JavaScript).

### What the overview lists

- The services are listed by their names, without regard to letter case, and two services with one name by
  their identifiers. The scenarios of a switch are listed in the same way. Legacy lists both by name. The
  admin API states services in the order in which the server holds them, which says little to someone who
  looks for a service.
- The callable URL is the base URL of the mock endpoint, `/service/`, and the mock URL pattern of the service.
  A server takes a pattern without the slash at its start, and so does the URL. A pattern that is the URL of
  a real service follows `/service/` whole, which is how a client calls such a service at the mock endpoint.
  The URL is text and no link, as every URL that a server holds: it has the variables of the pattern in it,
  so it is no address that a browser could open.
- A time is shown to the second and in UTC, such as `2026-11-25 19:43:33 UTC`. The UI makes a page without
  knowing where its reader is, and a page without scripting cannot find it out. The element of the time holds
  the time as the server stated it.
- A service that was not used since the server started says `never`. A line below the list says that the
  server keeps these times while it runs.

### The response mode

A service has the mode that it is set to, and the mode in which it answers. They differ for a service without
a scenario, which forwards every request whatever its mode is. The requirement asks for the effective mode,
and the switch changes the mode that the service is set to.

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The effective mode as text, and a button for each other mode. | The text is what the requirement names. | Where the two modes differ, the text names a mode that a button beside it offers to switch to, although the service is set to another one. |
| 2. A button for each mode, of which the one of the mode that the service is set to says that it is pressed. A line says in which mode the service answers where that is another one. | It is the switch that legacy has. One look shows the mode, and one click changes it. Where the modes differ, the page says both. | The effective mode is no text of its own where it is the mode of the pressed button. |
| 3. A choice of the modes and a button that sets the chosen one. | It is the plainest form. | Two clicks for what is done most often on this page. |

Way 2. The three buttons are a group that names the service, and each button states whether it is pressed, so
a screen reader says what a look at the page shows. The line for a service that answers otherwise names the
effective mode, and says that the service has no scenario to answer with where that is the reason.

### The default scenario

- A service with more than one scenario has a choice of its scenarios and a button that sets the chosen one.
  Without scripting, a choice does nothing by itself.
- A service with one scenario shows the name of that scenario, and a service without a scenario says `none`.
  There is nothing to switch to.
- A service always has one of its scenarios as its default one, by a rule of the server. Where a server states
  another one, the choice has an entry `none` that is chosen and cannot be sent. A browser would otherwise
  show the first scenario as the chosen one, which it is not.

### The marker and the page of the diagnostics

- The marker says how many diagnostics concern the service, and it leads to the page of the diagnostics, at
  the place of the service. Task 13.10 lists the diagnostics among its pages. The page is here already,
  because the marker has to lead somewhere, and the scenario "Diagnostics marker" asks that it leads to the
  diagnostic.
- The page shows the diagnostics service by service, in the order of the overview, each with its kind in
  words and with what the server says of it. A kind that the UI does not know is shown by the name that the
  admin API has for it, so a server of a later version loses nothing.
- A diagnostic concerns the service that the admin API states it for. Two services that share a URL each
  have a diagnostic of their own.

### The filter

- The address of the overview names the tags that it filters by, each as a parameter `tag`. The form of the
  filter has one field, in which the tags have spaces between them, and a browser sends it with the method
  GET. A tag of a service is a link to the overview of the services that carry it.
- The UI asks the admin API for the services that carry one of the tags. So the filter is the one of the API,
  which looks at the tags of a service and not at those of its scenarios.
- The UI takes the tags in lower case and each once, as a server holds tags, so that two addresses that mean
  the same list show the same page.
- This filter is no setting of the server. The tag filter of a server decides which services answer requests,
  and task 13.8 gives it a page.

### After a switch

- A switch is a form of the overview with the method POST. Its address names the service and the tags that
  the overview filters by. The UI asks the server to change the one attribute that the switch is about, with
  `PATCH services/{id}`.
- The browser is then sent to the overview as it was, at the place of the service: the address has the tags
  and, after a `#`, the row of the service. In a long list, the page would otherwise begin at its top after
  every switch.
- The page says once what was switched, as after every form. The stylesheet keeps that line at the top of the
  window, and it keeps the row that the address names below it, so both are in view.
- What the page says is made of the service as the server returns it. So it also says when a service that
  was set to static mode still forwards requests because it has no scenario.
- A name that the page repeats in that line is cut after 80 characters. A browser takes no cookie beyond
  4,096 bytes, and the line travels in one.
- A form that names no mode or no scenario gets a page that says so, with the status 422, and the server is
  not asked. A switch that the server refuses gets the page of the refusal, see [ui-forms.md](ui-forms.md).

### What the overview does not have

- Legacy can set the response mode of every service at once. The specification does not ask for it, and the
  admin API has no operation for it.
- The list has no pages and no other order. The specification asks for paging of the history only.
- The overview does not say which services the tag filter of the server leaves out.

## The pages of a service

A service has a page that shows what it is set to, and a page with the form that changes it. A form of the
same kind creates a service. A button of the page duplicates the service, and a link leads to the step that
asks before the service is deleted.

### What was needed

- The UI offers every management operation of the admin API for services (`admin-ui`: Coverage of management
  operations). Task 13.6 names the editor with the real URLs, the rule set, the inspector, the schema, and the
  tags, and the duplicate and the delete.
- A form that the admin API rejects is shown again with what was entered, and with each message next to the
  field that it concerns (`admin-ui`: Validation feedback, with the scenario "Duplicate mock URL").
- The UI asks before it deletes a service (`admin-ui`: Confirmation of destructive actions).

### Two pages

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. One page: the scenarios of the service, and below them the form. | One address for a service. | A form that is not taken is shown again below the scenarios, where what the page says of it is out of view. |
| 2. The page of the service at `/service`, and the form at `/service/edit`. | The page of the service is short and leads on to its scenarios. The form is a page of its own, so what it is refused for is the first thing on it. | Two pages show the attributes of a service. |

Way 2. The page of the service says in words what the form holds as fields: whether the server evaluates the
rule set, and which real URL is the default one. It shows neither the rule set nor the schema, which are
long.

### The form

- The form has a field for every attribute of a service but the error scenario, which task 13.7 adds with the
  scenarios. The form of a new service states each of these attributes in its request. The form of a service
  that is there states the ones that were changed in it, see [ui-forms.md](ui-forms.md), so a form that is
  sent as it is shown asks for nothing.
- A real URL has a row of its own, with a button that makes it the default one. The form has two rows more
  than the service has real URLs, for the URLs that are entered, and a row without a URL is left out. Without
  scripting, a form cannot grow, so a service that needs more rows is saved and edited again. The default
  real URL is the row that is marked, by its place among the rows that have a URL.
- A text field of several lines would take less code and would take a list that is pasted. It has no place
  for the choice of the default one, which is what changes most often.
- The delay is checked by the UI where it was changed: a whole number of milliseconds, 0 or more. The server
  takes a negative delay and waits as for 0.
- The default scenario and the inspector are choices. What the service names that is not among them stays a
  choice: an inspector that is not installed is offered with a note, and a default scenario that is none of
  the scenarios leaves the choice at `none`, which is never stated in a request.
- The form of a new service says that a service with a real URL can do without a name. That holds for a new
  service only: the server derives a name when it creates a service, and not when it changes one.

### Duplicating and deleting

- The button that duplicates a service sends a form with the method POST. The browser is sent to the page of
  the copy, because the copy is what is edited next: it has a name and a mock URL that the server made up.
- The link that deletes a service leads to a page that asks and says how many scenarios go with the
  service. Nothing is deleted before the form of that page is sent. The overview says then that the service
  was deleted.

### What the pages of a service do not have

- The scenarios of the service are listed by their names, with the default one and the error scenario
  marked. Task 13.7 adds their pages and the forms that change them.
- The report of the response schema, the merge of services, and the derivation of real URLs are pages of
  task 13.10.

## A look in a browser

The tests read the pages without a browser. The overview was looked at once in a browser, which is no test and
is not repeated by one:

- Chrome showed the overview and the page of the diagnostics from files, in a window of 1,280 pixels and in
  one of 560 pixels. In the narrow window the list scrolls sideways within the page.
- Chrome showed the page of a service, its form, the form with the messages of a refusal, and the page that
  asks before a service is deleted.
- In a long list, a script that was added to a copy of the page measured where things are after the browser
  was sent to the row of a service: the line of the page stayed at the top of the window, and the row began
  below it.

## Repeating the tests

Run `./gradlew :mockey-ui:test`. `UiServicesTest` reads the overview and the page of the diagnostics and sends
the forms of the switches. `UiServiceTest` sends the forms of a service and reads what the admin API was
asked for. `UiCrawlTest` follows every link from the first page on, also with a server that states content
that is written to be read as markup.
