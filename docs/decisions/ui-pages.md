# The pages of the web UI

Decided in tasks 13.5 to 13.8 of the change `kotlin-rewrite-modular`, on 2026-11-25.

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
- This filter isn't a setting of the server. The server's tag filter decides which services answer requests,
  and it has a page of its own (see [The page for tags](#the-page-for-tags)).

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
- The overview says when the server's tag filter is on, but it doesn't mark the services that the filter
  leaves out.

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

- The form has a field for every attribute of a service except the error scenario, which is set on the page of
  a scenario. The form of a new service states each of these attributes in its request. The form of a service
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

- The report of the response schema, the merge of services, and the derivation of real URLs are pages of
  task 13.10.

## The pages of a scenario

A scenario has a single page, and that page is the form that edits it. A form of the same kind creates a
scenario. Buttons on the page make the scenario the default scenario or the error scenario of its service, or
the universal error scenario, and a link leads to the confirmation step for a deletion. The page of a service
lists its scenarios in a table that links to these pages.

### Requirements

- The UI offers every management operation of the admin API for scenarios (`admin-ui`: Coverage of management
  operations). Task 13.7 names the editor with the status, the headers, the criterion, the method, the delay,
  the tags, and the error designations.
- A form that the admin API rejects is displayed again with the entered values, and each message appears next
  to the field it's about (`admin-ui`: Validation feedback).
- The UI asks for confirmation before it deletes a scenario (`admin-ui`: Confirmation of destructive actions).
- The server sends the body of a scenario exactly as it's stored (`scenario-responses`: Body fidelity).

### One page instead of two

| Option | Advantages | Disadvantages |
|---|---|---|
| 1. A page that shows the scenario, plus a separate page with the form, like the pages of a service. | It's the same pattern as for a service. | A scenario has little to show beyond its form. The read-only page would repeat the status code, the headers, and the body, and every edit would take one more click. |
| 2. A single page, which is the form. | Opening a scenario from the list of its service leads straight to the editable values. | When a rejected form is displayed again, the designations below it are out of view. |

The UI goes with option 2. Legacy also opens a scenario directly in an editor dialog.

### The addresses

| Address | Page or action |
|---|---|
| `/scenario?service=&id=` | The scenario page. `GET` displays the form, and `POST` saves it. |
| `/scenario/new?service=` | The form for a new scenario. |
| `/scenario/delete?service=&id=` | The confirmation step for a deletion. |
| `/scenario/default?service=&id=` | Makes the scenario the default scenario of its service (`POST`). |
| `/scenario/error?service=&id=` | Makes the scenario the error scenario of its service, or undoes that (`POST`). |
| `/scenario/universal-error?service=&id=` | Makes the scenario the universal error scenario, or undoes that (`POST`). |

Every address names both the service and the scenario. The admin API addresses a scenario below its service,
and two services can have scenarios with the same identifier.

### The form

- The status code is a text field. An HTTP status code has three digits, so the UI accepts a whole number from
  100 to 999. The server doesn't validate the status code at all.
- The headers share one text area, with one header per line in the form `Name: value`. The name ends at the
  first colon, so a value can contain colons. The order of the lines is the order of the headers, and a name
  can appear more than once, which headers such as `Set-Cookie` need. The UI rejects a line without a name and
  says which line it is.
- Separate name and value fields for each header, like the rows for the real URLs of a service, would allow
  each header to be validated on its own. But a form can't add rows without scripting, and a response often
  has more headers than a fixed number of rows could hold.
- The match criterion is a text area with a check box. An empty field means there's no criterion. Otherwise,
  the text is a rule set if the check box is checked, and a text criterion if it isn't. Legacy has the same
  two controls.
- The method restriction is a choice between "Any method" and seven common methods. If the scenario has a
  different restriction, such as `*` or a method in lower case, the form offers it as an extra choice. That
  way, the form never changes the restriction on its own.
- The delay follows the same rule as in the form of a service: a whole number of milliseconds that's 0 or
  greater. An empty field means 0.
- Tags are separated by whitespace, just like in the form of a service.
- The form of an existing scenario only sends the attributes whose fields were changed (see
  [ui-forms.md](ui-forms.md)). The UI only validates the changed fields, so a stored status code that the form
  would reject doesn't stop it from saving a different field. The form for a new scenario sends every
  attribute.

### The line endings of the body

The server sends a body exactly as it's stored, but browsers submit every line break in a text area as a
carriage return plus a line feed. A form that simply stored the submitted text would rewrite the line endings
of every body that someone edits.

- The form has a hidden field that remembers the line endings of the stored body: `crlf` if every line break
  in the body is a carriage return plus a line feed, and `lf` otherwise. When the body field is changed, the
  UI applies that style to the submitted text.
- A body with mixed line endings ends up with plain line feeds once it's edited. A body that isn't edited
  isn't sent at all, so it keeps its mixed line endings.
- A body that's typed into the form for a new scenario gets plain line feeds.
- The match criterion gets the same treatment. A text criterion that spans several lines only matches a
  request body if the line endings are the same.

### The designations

- A section below the form says whether the scenario is the default scenario of its service, the error
  scenario of its service, and the universal error scenario. Each statement also explains in one sentence
  what the designation does.
- Each designation has one button, which either assigns the designation or removes it. The default
  designation has no button to remove it, because a service with scenarios always has a default scenario.
  That designation only goes away when another scenario becomes the default scenario.
- The default scenario and the error scenario are attributes of the service, so their buttons send
  `PATCH services/{id}`. The universal error scenario is a resource of its own, so its button sends
  `PUT universal-error-scenario` or `DELETE universal-error-scenario`.
- A button that removes a designation doesn't change anything if the scenario no longer holds the
  designation. The page could be stale: another scenario might have taken over the designation after the page
  was rendered. Without this check, the button would take the designation away from that other scenario.
- The check and the change are two separate requests, so a change by someone else in between can still be
  overwritten. The admin API has no conditional request that would close this gap.
- Legacy sets the two error designations with check boxes in the scenario editor. Here, the buttons are
  separate forms, so a designation doesn't depend on the other fields, and a rejected form doesn't block a
  designation. Keep in mind that pressing one of these buttons doesn't save unsaved edits in the scenario
  form.

### The list on the page of a service

- The page of a service lists its scenarios in a table, sorted by name without regard to letter case. Each
  row links to the scenario page and shows the status code, match criterion, method restriction, delay, tags,
  and last-used time.
- The table shows what kind of criterion a scenario has. For a text criterion, it also shows the first 80
  characters of the text. It doesn't show a rule set, because rule sets are long.
- A row marks the default scenario, the error scenario, and the universal error scenario. That's why the page
  of a service also asks the server for the universal error scenario.
- A scenario with a delay of 0 shows `service delay`, because the delay of the service applies to it.

### Deleting a scenario

- The link leads to a page that asks for confirmation. That page spells out what the deletion means: the
  oldest remaining scenario becomes the default scenario, the service loses its error scenario, or the server
  loses its universal error scenario. For the only scenario of a service, the page points out that a service
  without a scenario forwards every request.
- After the deletion, the browser goes to the page of the service, which reports the deletion.

### Limits

- A header whose stored value contains a line break shows up as two lines in the form. The form leaves such a
  header alone unless someone edits the headers. In that case, the UI either rejects the second line or reads
  it as a separate header.
- An edited body loses its NUL characters, and an edited name loses its line breaks (see
  [ui-forms.md](ui-forms.md)).
- The scenario page doesn't show the last-used time. The list on the page of the service does.
- Saving a history entry as a scenario belongs to task 13.9.

## The pages for plans

A plan is a snapshot of the response mode, the delay, and the default scenario of several services. The UI
lists the plans, shows a single plan, and offers everything the admin API can do with one: create it from the
current settings of services, edit its name, description, and tags, save the current settings into it again,
apply it, make it the default plan, and delete it.

### Requirements

- The UI offers every management operation of the admin API for plans (`admin-ui`: Coverage of management
  operations).
- After a plan is applied, the UI says so and names the items that were skipped (`admin-ui`: Result feedback,
  with the scenario "Plan apply report").
- The UI asks for confirmation before it deletes a plan (`admin-ui`: Confirmation of destructive actions).

### The addresses

| Address | Page or action |
|---|---|
| `/plans` | The list of all plans. |
| `/plan?id=` | The page of a plan. |
| `/plan/new` | The form that creates a plan. |
| `/plan/edit?id=` | The form that edits the name, the description, and the tags. |
| `/plan/save?id=` | The form that saves the current settings of services into the plan. |
| `/plan/apply?id=` | Applies the plan (`POST`) and leads to the report. |
| `/plan/default?id=` | Makes the plan the default plan, or undoes that (`POST`). |
| `/plan/delete?id=` | The confirmation step for a deletion. |

### Creating a plan

- The admin API creates a plan from the current settings of services, so the form asks which services to
  include. It shows one check box per service, and all of them start checked, so a snapshot of everything
  takes one click. Legacy needs two steps instead: it creates an empty plan first, and the check boxes in its
  service list fill the plan afterward.
- A plan needs at least one service. The UI rejects a form without a checked box, because a plan without
  settings does nothing when it's applied. The admin API accepts such a plan.
- The server updates an existing plan if the new plan uses its name. The hint below the name field says so,
  and the browser ends up on the page of that plan either way.
- A field that's left empty isn't sent. That way, an existing plan with the same name keeps its description
  and its tags.

### Editing a plan

- The edit form covers the name, the description, and the tags. Like every edit form, it only sends the
  attributes whose fields were changed (see [ui-forms.md](ui-forms.md)).
- The form doesn't edit the settings of a single service. To change what a plan holds, set up the services the
  way the plan should have them, and then use "Save current settings". That's also how legacy works. The admin
  API can replace the items of a plan directly, but the UI doesn't offer that.
- "Save current settings" shows the same check boxes as the form for a new plan. The services that the plan
  already names start checked. The UI matches them by name and ignores letter case and surrounding
  whitespace, which is how the server matches them when it applies a plan.

### Applying a plan

- "Apply" is a button on the page of the plan. The server answers with a report, so the browser is sent to a
  report page with an address of its own (see [ui-forms.md](ui-forms.md)). Loading that page again doesn't
  apply the plan again.
- The report says how many services got their settings. It lists the services that the plan names but that
  don't exist, and the scenarios that the server couldn't find. A section without entries is left out.

### The default plan

- The page of a plan says whether it's the default plan, and it has a button that assigns or removes that
  designation. The list marks the default plan.
- As with the designations of a scenario, the button that removes the designation doesn't change anything if
  another plan has become the default plan in the meantime.

### What the plan pages don't have

- The page of a plan doesn't check whether the services and scenarios it names still exist. The report of
  applying the plan does.
- The list has no pages and no filter.

## The pages for twist configurations

A twist configuration rewrites the target of a request before the server looks for a service. The UI lists
the configurations, and each one has a page that doubles as its edit form, like a scenario.

### Requirements

- The UI offers every management operation of the admin API for twist configurations (`admin-ui`: Coverage of
  management operations).
- The UI asks for confirmation before it deletes a twist configuration (`admin-ui`: Confirmation of
  destructive actions).

### The addresses

| Address | Page or action |
|---|---|
| `/twists` | The list of all twist configurations. |
| `/twist?id=` | The page of a twist configuration. `GET` displays the form, and `POST` saves it. |
| `/twist/new` | The form for a new twist configuration. |
| `/twist/enabled?id=` | Enables or disables the configuration (`POST`). |
| `/twist/delete?id=` | The confirmation step for a deletion. |

### The form

- Each replacement is a row with two fields: the text to replace, and the text that takes its place. The form
  shows the stored replacements followed by three empty rows. A form can't add rows without scripting, so a
  configuration that needs more rows has to be saved and opened again.
- The order of the rows matters, because the server only uses the first row whose text occurs in the target.
  The form sends the rows in their order. To move a replacement, retype it in another row.
- A row without a text to replace is left out, which is what the server does as well. A row that has a
  replacement but no text to replace is probably a mistake, so the UI rejects it and names the row.
- Both texts are sent exactly as typed, including spaces, because the server takes them literally.

### Enabling and disabling

- The list has a button for each configuration that enables or disables it, so switching takes one click.
  The page of a configuration has the same button. The browser goes back to wherever the button was pressed.
- The server reports the enabled configuration in its status, which the UI requests for every page anyway. So
  the list doesn't need an extra request to mark the enabled configuration.
- Enabling a configuration disables the one that was enabled before. Disabling a configuration that isn't
  enabled doesn't change anything, so a stale page can't disable another configuration.

## The page for tags

One page covers both things the server does with tags: the tag filter, which decides which services answer
requests, and the list of the tags in use, from which a tag can be removed everywhere.

### Requirements

- The UI offers every management operation of the admin API for the tag filter and for tags (`admin-ui`:
  Coverage of management operations).
- The UI asks for confirmation before it removes a tag from all definitions (`admin-ui`: Confirmation of
  destructive actions).

### The addresses

| Address | Page or action |
|---|---|
| `/tags` | The page with the tag filter and the tags in use. |
| `/tags/filter` | Sets the tag filter (`POST`). |
| `/tags/filter-off` | Turns the tag filter off (`POST`). |
| `/tag/remove?tag=` | The confirmation step for removing a tag from all definitions. |

### The tag filter

- The form has one field, with the tags separated by spaces. An empty field turns the filter off. So does
  the button below the form, which only appears while the filter is on.
- The server reports the tag filter in its status, so the page doesn't need an extra request for it.
- While the filter is on, the services overview says so above its list and links to this page. An active
  filter is easy to forget, and it's a common reason why a service doesn't answer.

### Removing a tag

- Each tag in use has a "Remove" link that leads to a confirmation page. The page says how many services,
  scenarios, and plans carry the tag. The admin API only lists the tags, so the UI requests the services and
  the plans and counts them itself.
- If the tag filter uses the tag, the page warns that removing the tag doesn't change the filter. Afterward,
  no service matches that tag anymore.
- The tag is passed as an address parameter, so it can contain any character.
- For a tag that nothing carries, the page says so and offers nothing to confirm.

## The page for the outbound proxy

The page has the form with the settings of the outbound proxy and the button that clears the upstream
session. Both are about how the server reaches real services, so they share a page in the "Server" section.

### Requirements

- The UI offers every management operation of the admin API for the outbound proxy settings and the shared
  upstream session (`admin-ui`: Coverage of management operations).
- The server never returns the password of the proxy (`upstream-proxying`: Outbound proxy settings and
  credentials).

### The form

- The form covers the proxy URL, the enabled flag, the user name, and the password. Like every edit form, it
  only sends the attributes whose fields were changed (see [ui-forms.md](ui-forms.md)).
- The server only says whether a password is set. So the password field always starts empty, and the hint
  below it says whether the server has a password. Leaving the field empty keeps the stored password.
- A check box removes the stored password. It only appears while a password is set, and it wins over a
  password that's typed in at the same time. An empty user name means there is none.
- When the server rejects the form, the form is displayed again without the typed password. The UI never
  sends a password back to the browser.
- Browsers like to fill in saved logins. The user name field and the password field ask the browser not to do
  that (`autocomplete`), because the form would take a filled-in value for an edit and overwrite the stored
  credentials.
- The server validates the URL and the user name, and its messages appear next to the fields.

### The upstream session

- The button clears the cookies that real services have set. It doesn't ask for confirmation. The
  specification lists the actions that need one, and clearing the session isn't among them. The session only
  holds cookies, and real services set them again.

## A look in a browser

The tests read the pages without a browser. The overview was looked at once in a browser, which is no test and
is not repeated by one:

- Chrome showed the overview and the page of the diagnostics from files, in a window of 1,280 pixels and in
  one of 560 pixels. In the narrow window the list scrolls sideways within the page.
- Chrome showed the page of a service, its form, the form with the messages of a refusal, and the page that
  asks before a service is deleted.
- Chrome displayed the scenario list on the page of a service, the scenario page with its designations at
  widths of 1,280 and 520 pixels, a rejected form with its messages, and the confirmation page for a deletion.
- Chrome displayed the plan list, the page of a plan, the form for a new plan, the report of applying a plan,
  the twist list, the page of a twist configuration, the tags page with an active filter, the confirmation
  page for removing a tag, the proxy page, and the overview with its note about the tag filter.
- In a long list, a script that was added to a copy of the page measured where things are after the browser
  was sent to the row of a service: the line of the page stayed at the top of the window, and the row began
  below it.

## Repeating the tests

Run `./gradlew :mockey-ui:test`. `UiServicesTest` reads the overview and the page of the diagnostics and sends
the forms of the switches. `UiServiceTest` sends the forms of a service and reads what the admin API was
asked for. `UiScenarioTest` submits the scenario forms, presses the designation buttons, and checks
which requests reached the admin API. `UiPlansTest`, `UiTwistsTest`, `UiTagsTest`, and `UiProxyTest` do the
same for plans, twist configurations, tags, and the outbound proxy. `UiCrawlTest` follows every link from the
first page on, also with a server that states content that is written to be read as markup.
