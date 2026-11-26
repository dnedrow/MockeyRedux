# The pages of the web UI

Decided in tasks 13.5 to 13.13 of the change `kotlin-rewrite-modular`, on 2026-11-25 and 2026-11-26.

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
  the place of the service. Task 13.10 lists the diagnostics among its pages. The page came with the overview
  in task 13.5, because the marker has to lead somewhere, and the scenario "Diagnostics marker" asks that it
  leads to the diagnostic.
- Since task 13.10, a link above the list leads to the page as well. That way, the page can be opened when no
  service has a marker.
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
- The search form stands next to the filter form. It looks for a term in the services, the scenarios, and the
  plans, and it leads to a page of its own (see [The search page](#the-search-page)). The filter narrows the
  list of the overview, and the search doesn't.

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

### A switch in a browser that runs scripts

Task 13.12 added the script of the UI (see [ui-stack.md](ui-stack.md)). With it, a switch updates the page
in place:

- The script sends the form of the switch to the same address. The UI asks the server for the same change,
  and it answers with the row of the service alone, made from the service as the server returns it.
- The script puts the row in the place of the one that the page shows. Nothing else of the overview depends
  on a single service, so the page shows what a full page load would show.
- What the page says of the switch comes with the row and goes into the line at the top of the page. It
  isn't left in a cookie, so the next page doesn't say it again.
- The buttons and the choice of a switch have identifiers, such as `mode-static-<service>`. After the row is
  replaced, the script gives the focus to the control with the identifier of the one that had it. So
  someone who uses the keyboard stays at the switch.
- A switch that isn't taken, or that the server refuses, gets the same page as without the script. The
  script shows it in the place of the overview.
- If the server makes the change and then fails to say what the row needs, the script loads the overview
  again. That page says what is the matter with the server, and it says what was switched.

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

- The report of the response schema and the merge of services have pages of their own, and the page of a
  service links to both (see [The schema report](#the-schema-report) and
  [The pages that merge services](#the-pages-that-merge-services)).
- The derivation of real URLs concerns all services at once, so the overview links to it (see
  [The pages that derive real URLs](#the-pages-that-derive-real-urls)).

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

## The pages for the history

The server records every request that reaches its mock endpoint. The UI lists these history entries, and it
has a page for a single entry. From there, a request can be replayed with edits, and a response can be saved
as a scenario. Entries can also be flagged and deleted.

### Requirements

- The UI offers every management operation of the admin API for the history (`admin-ui`: Coverage of
  management operations, with the scenario "Replay with edits").
- The history filter tokens and the paging are in the page address (`admin-ui`: View state lives in the
  address, with the scenario "Shared link").
- The history page offers an automatic refresh that can be switched on and off. With scripting, new entries
  appear in place, and with scripting disabled, the page reloads itself (`admin-ui`: Live history view).
- The UI asks for confirmation before it deletes all history (`admin-ui`: Confirmation of destructive
  actions).

### The addresses

| Address | Page or action |
|---|---|
| `/history` | The list. The parameters `tokens`, `service`, `page`, and `refresh` say what it shows. |
| `/history/entry?id=` | The page of an entry. |
| `/history/replay?id=` | The form that replays the request of an entry. |
| `/history/scenario?id=` | The form that saves the response of an entry as a scenario. |
| `/history/flag?id=` | Flags an entry, or removes the flag (`POST`). |
| `/history/remove?id=` | Deletes a single entry (`POST`). |
| `/history/delete` | The confirmation step for deleting all entries, or the entries of one service with `service=`. |

### The view is in the address

- The address of the list holds everything that decides what the list shows: the filter tokens, the service,
  the page, and the automatic refresh. So the same address shows the same list in another browser. Legacy
  keeps the filter tokens in the session instead.
- The tokens are entered in one field, with spaces between them, as in legacy. The admin API accepts a token
  with a space in it, but the UI has no way to enter one.
- `service` limits the list to the entries of one service. The page of a service and the statistics link to
  such a list.
- The buttons of a row carry the view in the address of their action. That's how the browser gets back to the
  same list after an entry is flagged or deleted.

### Paging

- A page of the list has 50 entries.
- The admin API returns the newest entries up to a maximum, and it can't skip entries. So for page N, the UI
  asks for N × 50 + 1 entries and shows the last 50 of them. The extra entry says whether there are older
  ones.
- The admin API returns every entry with its bodies, and it has no way to leave them out. A late page of a
  history with large bodies is therefore a large answer. The server bounds it: by default, it keeps 1,000
  entries and 1 MiB of each body.
- Every new request moves the older entries toward later pages, so a page doesn't show the same entries
  forever.

### Automatic refresh

- While the automatic refresh is on, the head of the page has a `meta http-equiv="refresh"` element, and a
  browser without scripting loads the page again every 5 seconds.
- The refresh is switched on and off with links, because it's part of the view: `refresh=on` is in the
  address, like the filter tokens.
- After an entry is flagged, the browser goes back to the row of that entry. A page that reloads itself is
  the exception: with a row in its address, it would jump back to that row on every reload.

Since task 13.12, a browser that runs scripts updates the list in place (see [ui-stack.md](ui-stack.md)):

- The `meta` element is inside a `noscript` element. A browser that runs scripts skips it, so it doesn't
  load the page again.
- Every 5 seconds, the script asks for the address of the page, and the UI answers with one part of the page
  instead of the whole page. The script puts it in the place of the part that the page shows.
- That part holds everything that depends on the history: the line about the service, the links of the
  page, the entries, and the links to the other pages of the list. For example, the link that deletes all
  entries is only there when the history has entries, so it has to appear along with the first entry.
- The filter form isn't in that part. Whatever someone types into it stays, which a page that reloads
  itself can't offer.
- Every link and every button of the part has an identifier, such as `view-entry-7`. After an update, the
  script gives the focus to the one with the identifier of the one that had it. Otherwise, someone who uses
  the keyboard would be thrown out of the list every 5 seconds.
- An update doesn't take the notice of a form with it. The notice waits for the next page that the browser
  loads.
- When the list can't be updated, for example because the server is down, the UI answers with the page of
  the failure. The script shows it in the place of the history page, and nothing asks again. A browser
  without scripting stops reloading at that point too, because the page of a failure doesn't reload itself.

### The list

- A row shows the time, the client, the method with the target, the service with the response mode, and the
  answer: the status code, the outcome, and the scenario.
- The row shows the target and not the URL that the client addressed, because the target is shorter. The page
  of the entry has the URL.
- Marks say that an entry is flagged, that its target was twisted, and how many inspection messages it has.
- A row has a button that flags the entry and one that deletes it. Deleting a single entry doesn't ask for
  confirmation. The specification only requires one when all history is deleted.

### Deleting many entries

- The confirmation page at `/history/delete` covers all entries. With `service=`, it covers the entries of
  one service. The specification only names the deletion of all history, but both are deletions in bulk.
- Before it asks, the page requests the newest entry that would be deleted. Without such an entry, there's
  nothing to delete, and the page says so. The entry also gives the name of the service, even if the service
  itself has been deleted since.

### The page of an entry

- The page shows when and how the request was handled, the inspection messages, the request, and the
  response. The request has its URL with the query string, its headers, its parameters, its cookies, and its
  body. The response has its status code, its headers, and its body.
- The links to the service and to the scenario lead to what the entry recorded. If one of them has been
  deleted since, its page says so.

### Bodies

- The admin API encodes a body in Base64, because a body can hold any bytes. The UI shows a body as text if
  its bytes are text in the charset that the `Content-Type` header declares, or in UTF-8 if the header doesn't
  declare one. A body with a NUL character doesn't count as text.
- For a body that isn't text, the page says so and gives its size.
- The server only keeps the start of a long body. Such a body can end in the middle of a character, and that
  doesn't make it binary. The page says how much of the body the server kept.
- The UI doesn't offer a body as a download.

### Replaying a request

- The form has the headers and the query parameters, each with one item per line, and the body. A page
  without scripting can't add rows to a form, so a field of lines is the simplest way to edit a list of any
  length.
- The form only sends the parts that were changed (see [ui-forms.md](ui-forms.md)). Everything else is
  replayed byte for byte. That matters for a body that isn't text, and for a query string that the server
  would spell differently if it encoded the parameters again.
- The query parameters are shown decoded. Some query strings can't be shown as one parameter per line: a
  name or a value has a line break in it, a name has an equals sign in it, or a percent sign doesn't start an
  escape. For such an entry, the form has no field for the parameters, and the replay keeps the query string.
- The field for a body that isn't text starts empty, and the replay sends the recorded bytes unless something
  is typed. So the form can't replace such a body with an empty one.
- An edited body is encoded in the charset that the `Content-Type` header of the form declares, with the line
  endings of the recorded body. The UI rejects a body with a character that the charset can't encode.
- The server refuses to replay an entry whose body was cut, unless the replay supplies a body. For such an
  entry, the form always sends what the body field holds.
- After the replay, the browser is on the page of the new entry that the replay produced.

### Saving a response as a scenario

- The form only asks for a name. The server takes the status code, the headers, and the body from the entry,
  and the browser ends up on the page of the new scenario, which is the form that edits everything else.
- The server can't make a scenario from an entry without a service, or from one whose response body was cut.
  For such an entry, the page says why and doesn't show the form. The server checks this as well.

### What the history pages don't have

- The list can't be limited to the flagged entries, because the admin API has no such filter.
- A filter token can't contain a space.

## The page for the statistics

The page says how many history entries the server keeps for each service. The same numbers can be
downloaded as CSV and as JSON.

### Requirements

- The UI offers every management operation of the admin API for the statistics (`admin-ui`: Coverage of
  management operations).
- The statistics can be limited to a time range, and they're available as JSON and as CSV
  (`request-history`: Per-service statistics).
- The time range is in the page address (`admin-ui`: View state lives in the address).

### The addresses

| Address | Page or action |
|---|---|
| `/statistics` | The page. The parameters `from` and `to` limit the count to a time range. |
| `/statistics/download?format=` | The numbers as a file in the format `csv` or `json`, for the same time range. |

### The page

- The table lists the services by name. A row has the number of entries, a bar, and the time of the earliest
  entry. The number links to the history of the service.
- The bar is a `meter` element, so it needs no script and no inline style. Legacy draws a bar chart with a
  script.
- The admin API counts per service. A request that no service handled has a history entry, but it isn't
  counted, and the page says so.

### The time range

- The two bounds are `datetime-local` fields, which browsers show with a date and time picker. They're
  labeled as UTC, because the pages show every time in UTC: a page is made without knowing where its reader
  is.
- The address holds a bound the way the browser submits it, for example `2026-01-02T03:04:05`. The UI also
  reads a time the way the pages display one, and an ISO 8601 time with an offset, so that an address that
  someone wrote by hand works as well.
- If a bound can't be read, the page has the status 400 and says so next to the field. It doesn't show any
  numbers, and the server isn't asked for any.

### The downloads

- The UI passes on the CSV and the JSON exactly as the server sends them. The answer is marked as an
  attachment, so a browser saves it and never reads it as a page. That's why a download can hold the server's
  content unchanged, while a page has to show it as text.
- Before it hands out a file, the UI asks for the status of the server, as it does for every page. An
  unreachable or incompatible server gets its failure page, and no file.

## The page for import and export

One page offers the definitions as a download and holds the form that imports a document. After an import,
the browser goes to a page that shows the import report.

### Requirements

- The UI offers the import and the export of the admin API (`admin-ui`: Coverage of management operations).
- When a user uploads a Mockey 2.x export document with a tag, the UI shows the additions and the conflicts
  from the import report (the scenario "Import with a report").
- The UI shows any report that the admin API returns, on a page that can be reloaded without repeating the
  action (`admin-ui`: Result feedback).

### The addresses

| Address | Page or action |
|---|---|
| `/definitions` | The page with the download link and the import form. |
| `/definitions/export` | The download of the definitions document. |
| `/definitions/import` | Imports the uploaded document (`POST`) and leads to the report. |

### The export

- The download is the document exactly as `GET definitions/export` returns it. It's marked as an attachment
  with the name `mockey-definitions.json`, so a browser saves it and never reads it as a page.
- As with the statistics downloads, the UI asks for the status of the server first. An unreachable or
  incompatible server gets its failure page, and no file.
- The page says what the document holds and what it leaves out: the history, the tag filter, and the
  credentials of the outbound proxy. It also says what the document is good for. A server can start from it
  (`--initial-definitions`), and the import form reads it.

### The import form

The form has a file field for the document and a text field for the tags. It's the only form of the UI that
a browser sends as `multipart/form-data`, because a form can't upload a file any other way.

The admin API takes two kinds of documents: a definitions document in JSON, and an export document of
Mockey 2.x in XML. It tells them apart by the `Content-Type` of the request, so the UI has to know which
kind it got.

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. A choice of the kind next to the file field. | Nothing is guessed. | It's one more thing to get wrong. After a wrong choice, the server complains about a format that the document doesn't have. |
| 2. The file name, or the media type that the browser states for the file. | No code reads the document. | A browser derives the media type from the file name, and an export of Mockey 2.x is often saved under a name without `.xml`. |
| 3. The content of the document. | There's nothing to choose, and the file name doesn't matter. | The UI looks at the first bytes of the document. |

Way 3. A document is XML if its first character is `<`, after an optional byte order mark and whitespace. A
JSON document never starts that way. A document that starts with the byte order mark of UTF-16 is XML as
well: the admin API only reads JSON in UTF-8, while an XML document declares its own encoding. Everything
else is sent as JSON. If it isn't a definitions document, the server says what's wrong with it.

- The UI rejects a form without a file, and a form with an empty file, with a message next to the file
  field. Everything else is up to the server. The admin API reports a document that it can't import for the
  field `document`. The file field has that name, so the message appears next to it.
- A page can't put a file back into a file field. A rejected form shows its tags again, but the file has to
  be chosen again.
- Tags have spaces between them, as in every tags field of the UI. The UI sends them the way they were
  entered. The report shows them in lower case and each once, because that's how the server holds them.
- The form has no field for a URL. Legacy could fetch the document from a URL. The new server only reads
  document content at runtime, and it takes files and URLs as startup options (DEV-27 in
  [deviations.md](../deviations.md)).

### The import report

- The page starts with a sentence that names the file and counts the additions and the conflicts. The tags
  of the import, if it had any, come next, and then the additions and the conflicts. Each item has its kind
  in words and the sentence that the server wrote about it. A kind that the UI doesn't know is shown by its
  name in the admin API.
- The sections "Additions" and "Conflicts" are always there. An empty section says that the import added
  nothing, or that it found no conflicts.
- Three more sections appear only when the report has something for them: the default plan that the import
  designated and applied, the parts of a Mockey 2.x document that the server couldn't read, and the rule
  sets of such a document that aren't well formed. The section for the default plan shows what the report
  of applying a plan shows. A rule set is listed with a link to its service, because that's where it can be
  fixed.
- Like every report, the page has an address of its own (see [ui-forms.md](ui-forms.md)). Loading it again
  doesn't import the document again. The UI keeps the report for that address, and it doesn't keep the
  document.

### What the page doesn't have

- There's no preview of an import. The admin API has no dry run, so the only way to get the report is to
  import the document.
- The export is the JSON document of the new server. The admin API doesn't export the XML format of
  Mockey 2.x.

## The search page

The search page finds services, scenarios, and plans. Its form is also on the overview of the services, next
to the tag filter.

### Requirements

- The UI offers the search of the admin API (`admin-ui`: Coverage of management operations).
- The UI keeps list filters in the address of the page, so that the same address shows the same view in
  another browser (`admin-ui`: View state lives in the address). A search term is treated like such a
  filter.

### The page

- The address is `/search?term=`. The form is sent with the method GET, so the term is part of the address,
  and a search can be bookmarked or passed on.
- The server finds nothing for a blank term. The UI doesn't ask it in that case, and the page only shows the
  form.
- The results keep the order of the server: the plans come first, and the scenarios of a service follow
  that service. Sorting the list by name would separate the scenarios from their services.
- Each result has its name as a link to its page, and its kind. A scenario also has a link to its service.
  A kind that the UI doesn't know is shown by its name in the admin API, and it has no link.
- A line above the results says how many there are and repeats the term. A long term is cut there, as every
  text that a sentence of the UI repeats.

### What the search page doesn't have

- The page doesn't say where in a result the term was found. The admin API only names the result.
- There are no pages and no filter by kind.
- The navigation has no search field. The form is on the overview, which is the first page of the UI, and
  on the search page itself.

## The pages that merge services

A merge copies the scenarios of other services into a service. The page of a service links to the form that
chooses those services. A confirmation step follows, and the merge leads to a page with its report.

### Requirements

- The UI offers every management operation of the admin API for services (`admin-ui`: Coverage of management
  operations). Task 13.10 names the merge.
- The UI shows the report that the admin API returns (`admin-ui`: Result feedback).
- The UI asks for confirmation before it deletes a service (`admin-ui`: Confirmation of destructive actions).
  A merge deletes the merged services if every one of their scenarios was copied.

### The addresses

| Address | Page or action |
|---|---|
| `/service/merge?id=` | The form that chooses the services to merge into the service. |
| `/service/merge?id=&step=confirm&source=` | The confirmation step for the services that the address names. Its form merges them (`POST`) and leads to the report. |

### Choosing and confirming

The form lists every other service with a check box, by name as on the overview, and says how many
scenarios each one has. What happens after "Continue" was the question:

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The form is sent with POST, and the server merges at once. | One step. | The merged services can be deleted, and the UI asks before it deletes a service. |
| 2. The form is sent with POST to a page that asks, and that page holds the choice in hidden fields. | The address stays short. | The page that asks is the answer to a POST, so loading it again sends the form again. Every other confirmation step of the UI is a page with an address. |
| 3. The form is sent with GET, so the choice is in the address of the page that asks. | It's the confirmation step that the UI already has. The page can be loaded again, and "Cancel" and the back button lead where one expects. | An address with many services is long. |

Way 3. The form and the confirmation step share one path. A parameter `step=confirm` asks for the
confirmation step, and each chosen service is a parameter `source`. The form of the confirmation step is
sent to the same address, so the merge covers exactly the services that the page asked about.

- The confirmation step names the chosen services and says what a merge does: a scenario is skipped if the
  destination already has one with the same name and response body, the tags are added in any case, and the
  merged services are deleted only if nothing was skipped. The UI can't say in advance which scenarios will
  be skipped, because the admin API has no dry run.
- Without a choice, the page is the form again, with the message "Choose at least one service to merge."
  The server would accept an empty list and do nothing, which helps no one.
- If a chosen service doesn't exist any longer, the page is the form again as well. It says so and keeps the
  rest of the choice. Asking about the remaining services alone would confirm something that wasn't chosen.
- Nothing is merged unless the form of the confirmation step is submitted. A request to the same address
  without the confirmation field gets the page that asks.

### The merge report

- The report says how many scenarios were copied and skipped, and what became of the merged services: they
  were deleted and their tags and real URLs now belong to the destination, or they were kept because a
  scenario was skipped.
- The copied and the skipped scenarios are listed with the names of their services.
- The report has an address of its own, like every report. Loading it again doesn't merge again.

### What the merge pages don't have

- A service can't be chosen if its identifier contains a line break, because a browser rewrites line breaks
  in every form value. The identifiers that the server generates never contain one. The same limit holds
  for the other check boxes and choices of the UI that send an identifier.

## The pages that derive real URLs

The form at `/services/real-urls` adds real URLs to all services at once. For each real URL of a service, the
server replaces what a regular expression finds, and it adds the result to the service as another real URL.
That's how the services of an environment `qa1` get the URLs of an environment `qa2`. The overview links to
the form.

### Requirements

- The UI offers every management operation of the admin API for services (`admin-ui`: Coverage of management
  operations). Task 13.10 names the bulk derivation.
- A form that the admin API rejects is shown again with what was entered, and with each message next to the
  field that it concerns (`admin-ui`: Validation feedback).
- The UI shows the report that the admin API returns (`admin-ui`: Result feedback).

### The form

- The form has a field for the regular expression and a text area for the replacements, one per line. Each
  replacement gives one new real URL per existing real URL. Legacy has a single replacement field. The admin
  API takes a list, so the form takes one as well.
- Blank lines are left out. Every other line is sent exactly as it was typed.
- The fields have the names of the members of the request, `expression` and `replacements`. The UI doesn't
  check either of them. The server compiles the expression, and what it rejects appears next to the field.
- The server replaces with the regular expression functions of Kotlin, so `$1` in a replacement refers to a
  group of the expression. The hint below the field says so.
- The form doesn't ask for confirmation. A derivation only adds real URLs, and the report lists every one of
  them.

### The report

- The report says how many real URLs were added, and it lists each one with a link to its service.
- If nothing was added, the report gives the two possible reasons: the expression found nothing in the real
  URLs, or the services already list every derived URL.
- The report has an address of its own, like every report.

## The schema report

The page at `/service/schema-report?id=` shows what the response schema of a service says about the response
body of each scenario. The page of a service links to it, in the row of the response schema. A service
without a schema has no such link.

### Requirements

- The UI offers the schema reports of the admin API (`admin-ui`: Coverage of management operations).

### The page

- The report changes nothing on the server, so it's a plain page. It can be loaded again and passed on.
- If the server checked the scenarios, the page says how many don't conform. A table lists each scenario with
  a link to its page, the result, and the violations.
- If the schema declares a dialect that the server doesn't support, the page says so and shows the dialect.
  If the server can't apply the schema for another reason, the page lists the problems that the server
  reports.
- The page says that the report has no effect on its own: a scenario that doesn't conform is stored and
  served as before.
- The server answers with 404 for a service without a response schema. The UI shows its message on the page
  that it has for everything that isn't there.

### What the page doesn't have

- Legacy has a tool that checks any JSON text against any schema. The admin API reports on the scenarios of
  a service, so the UI does the same.

## The page of the server

The page at `/system` shows what the server reports about itself. From there, a user switches between
read-only mode and writable mode, reloads the definitions, resets the server, and clears the last-used
times.

### Requirements

- The UI offers the read-only mode, the reload, the reset, and the clearing of the last-used times
  (`admin-ui`: Coverage of management operations). Task 13.10 also names the status.
- The UI asks for confirmation before a reset, and nothing is removed unless the user confirms (`admin-ui`:
  Confirmation of destructive actions, with the scenario "Reset needs confirmation").
- After an action, the UI says whether it succeeded (`admin-ui`: Result feedback).

### The addresses

| Address | Page or action |
|---|---|
| `/system` | The status of the server, with the links and buttons for everything below. |
| `/system/read-only` | Switches the server to read-only mode (`POST`). |
| `/system/writable` | The confirmation step for the switch to writable mode. |
| `/system/reload` | The confirmation step for a reload of the definitions. |
| `/system/reset` | The confirmation step for a reset. |
| `/system/clear-last-used` | Clears the last-used times (`POST`). |

### The status

- The page shows the version, the start time, the definitions location, the mode, the address of the mock
  endpoint, the tag filter, the enabled twist configuration, and whether the legacy shim is enabled. A link
  leads to the page for the outbound proxy.
- All of that comes from the status of the server, which the UI asks for on every page anyway. The page asks
  for nothing else.

### Read-only mode

- The switch to read-only mode is a button that acts at once. The switch stores nothing and discards
  nothing, and it's easy to undo.
- The switch to writable mode has a confirmation step. The specification doesn't ask for one. The step is
  there because the switch stores the definitions that the server holds in memory, and they take the place
  of the stored ones. The page that asks names the definitions location.
- If the server is in writable mode already, that page says so and asks nothing.

### Reload and reset

- A reset has a confirmation step, as the specification requires. A reload has one as well: in read-only
  mode, it discards every change that the server hasn't stored.
- Each page that asks says what happens in the mode that the server is in. In writable mode, the server has
  already stored every change, so a reload loses none. A reset in writable mode also removes the stored
  definitions.
- Both pages offer the download of the definitions before anything is discarded.
- After the action, the browser goes back to the page of the server, which says what was done.

### The last-used times

- The button that clears the last-used times is in a section of its own on the page of the server. The hint
  below the list of the overview, which says how long the server keeps these times, links to that section.
- The button acts at once. The times say when something was last used, and no definition depends on them.

### What the page doesn't have

- Legacy can set the response mode of every service at once. As noted for the overview, the admin API has no
  operation for that.
- The page doesn't list the inspectors that are installed. The form of a service offers them where one is
  chosen.

## The coverage of the admin API

Task 13.11 checks two requirements for the UI as a whole: everything works without scripting, and the UI
offers every management operation of the admin API. One test class does both, `UiCoverageTest`.

### Requirements

- Every function of the UI is usable in a browser with scripting disabled, through links, forms, and full
  page loads (`admin-ui`: Usable without JavaScript, with the scenario "Core flow with scripting disabled").
- The UI offers every management operation of the admin API (`admin-ui`: Coverage of management
  operations).

### The walk

- The test walks through the UI the way someone with a browser without scripting does. The walk starts at
  the first page. From there on, it only opens an address that one of its pages refers to, and it only
  submits a form that one of its pages shows. Anything else fails the test, and so does an answer that isn't
  a page or a file.
- The walk leaves nothing out. The test asks Ktor for the routes of the UI and compares them with the walk:
  it opened each of the 61 addresses for pages and files, and it submitted a form to each of the 40
  addresses that take one. It also submitted every form that one of its pages showed. Of the 61 addresses,
  19 are the pages of the help, which task 13.13 added.
- A stand-in for the server records which operations of the admin API each step of the walk reaches. It
  also checks every request against the description of the admin API, as in all tests of the UI.
- A separate test goes through the scenario "Core flow with scripting disabled" and checks the result that
  each step shows.
- The rules of the walk have tests of their own, in `JourneyTest`: an address that no page refers to, a form
  that no page shows, and an answer that reports a failure each count against a walk.

### The coverage list

The coverage list is `COVERAGE` in `UiCoverageTest`. For each operation, it names the step of the walk that
reaches it, such as "the merge of services" for `mergeServices`. The test fails in three cases: the walk
doesn't reach an operation at its step, the list lacks an operation that the description of the admin API
names, or the UI asks for an operation that the list doesn't have.

The description names 61 operations, and the UI asks the server for 58 of them. The other three:

- `listScenarios` returns the scenarios of a service. The page of a service lists them, but they're part
  of the service, which the page gets with `getService`.
- `getTagFilter` returns the tag filter. The page for tags shows it, but it's part of the status of the
  server, which every page gets with `getSystem`.
- `getDescription` returns the description of the admin API itself. It manages nothing, so the UI doesn't
  offer it.

For the first two, there was a choice:

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. The pages ask for the scenarios and for the tag filter with their own operations as well. | Every entry of the coverage list is of one kind. | Each page load sends a request for something that the UI already has. Two answers about the same thing can also disagree. |
| 2. The two operations count as covered, and a test checks that the pages show what they return. | The UI sends no request that it doesn't need. | The coverage list has a second kind of entry. |

Way 2. The test asks the stand-in server for the scenarios and for the tag filter with the two operations,
and it compares the answers with what the page of a service and the page for tags show.

### What the test doesn't cover

- The server of the test is a stand-in. The smoke runs of each task used the real server, and the
  end-to-end check of task 15.1 runs the two programs together, see [release-checks.md](release-checks.md).
- The walk submits each form once, with input that the server accepts. Rejected forms are covered by the
  tests of each page.

## The help

Task 13.13 adds the built-in help. The page at `/help` says what Mockey is for and lists the topics, and
each of the 18 topics has a page of its own at `/help/<name>`. The navigation of every page leads to the
help.

### Requirements

- The help explains services, scenarios, response modes, match criteria, request rules, error scenarios,
  plans, twisting, tags, storage, and import (`admin-ui`: Built-in help).
- The help is available without network access beyond the UI (the scenario "Help is reachable offline").
- Task 13.13 asks for help pages that are written from the help topics of Mockey 2.x and updated for the
  new behavior.

### One page for each topic

| Way | What speaks for it | What speaks against it |
|---|---|---|
| 1. One page with every topic, as in Mockey 2.x. | The browser searches the whole help at once. | The help has about 5,300 words, which makes a long page. A link to a topic needs a place in the page. |
| 2. A page that lists the topics, and a page for each topic. | A topic has an address, and its page is short. Each page links to the pages of the UI that its topic is about, and to the topics that continue from it. | The browser searches one topic at a time. |
| 3. The documents of `docs/`, turned into pages. | The help and the documents have one source. | The UI needs a library that reads Markdown, or the build needs a step that does. The documents are written for someone who runs the programs, and the help is for someone who uses the pages. |

Way 2. The list on the first page has a sentence about each topic, so that a reader finds the right one
without opening them all.

### The topics

Each topic of Mockey 2.x has a page of this help, and each page of this help covers a topic or a part of a
topic of Mockey 2.x. `UiHelpTest` holds this table.

| Topic of Mockey 2.x | Page of this help |
|---|---|
| Big Picture | The first page, `/help` |
| Storage | `storage` |
| Mock Service | `services` |
| Service Plan | `plans` |
| Mock Service Scenario | `scenarios`. Its parts have pages of their own: "Match Argument" became `response-modes` and `match-criteria`, "Tags" became `tags`, and "Error Handling" became `error-scenarios`. |
| Twisting | `twisting` |
| Get Started - Record Stuff | `recording` |
| Flush | `storage`, where it's the reset |
| Export/Upload | `import` |
| URL Injection | `real-urls` |
| Merge Services | `merging` |
| Initialization | `automation` |
| Good Things to Test | `ideas` |
| URL Config Recommendations | `clients` |
| Robots | `automation` |
| Service Request Validation Rules | `request-rules` |
| Getting the Right Scenario | `match-criteria` |
| Response JSON Schema | `schema` |
| Status | `automation` |
| Start Up Configurations | `automation` |

The help says how this server behaves, which differs from Mockey 2.x in places:

- Storage. The transient flag is read-only mode, which is the default and holds for the whole server
  (DEV-22). The help says what a reload and a reset do, and what is never stored.
- Match criteria. Where two scenarios match with the same score, the name decides. The help of Mockey 2.x
  says that there's no guarantee.
- Request rules. The help describes the evaluation of this server, in which a required rule fails when its
  parameter or header is missing (DEV-15).
- Initialization. A running server reads a document only from an upload. A file or a URL is a source at
  startup and for the import command (DEV-27).
- Merging. The other services are deleted only when no scenario was skipped (DEV-29).
- Status. The address `/status` says that the server runs and since when, and no longer where the
  definitions are (DEV-28).
- Robots. The help names the admin API. Since task 14.5, it also says that a server answers the automation
  endpoints of Mockey 2.x only with the legacy shim, and that those endpoints ask for no admin token.
- The help of Mockey 2.x has no topic for the Image Depot, the debug console, or the page for mobile
  devices, and this version has none of the three. So this help doesn't mention them.

### The texts

- The texts are in `HelpTopics.kt`. A topic has a name for its address, a title, a summary of one sentence,
  the pages of the UI that it's about, the topics that continue from it, and its content.
- The content is written with three helpers. `prose` takes a text of paragraphs and lists, in which
  backticks mark code. `example` shows a text as it is, such as a rule set. `rows` writes a table.
- The helpers write through kotlinx.html like every other page, so a text can't hold markup. The pages of
  the help use only elements that the crawl allows for any page.
- An example keeps its lines. No line of an example is longer than 72 characters, which fits the width of the
  help. A narrower window scrolls the example sideways, and not the page.
- The help uses the words of the pages: "mock URL pattern", "real URL", "response mode", and so on. The
  texts are for someone who uses the pages. The documents in `docs/` go further, and the first page of the
  help names four of them.

### When the server is in trouble

- The help needs nothing from the server. Its pages are shown whatever the server says, with a line that
  says what's wrong, as the page of a report is.
- The UI still asks the server for its status, because the footer of every page names the server. So a page
  of the help takes as long as any other page when the server doesn't answer.

### What the help doesn't have

- The other pages don't link to their topics. The navigation leads to the help from every page, and a topic
  leads to the pages that it's about.
- The help has no search of its own. The first page lists every topic with a sentence about it.
- The help comes in English only, like the rest of the UI.

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
- Chrome displayed the history list at widths of 1,280 and 560 pixels, the page of an entry with a text body
  and with a body that isn't text, the replay form, and the statistics page with and without a time range.
- Chrome displayed the overview with the tag filter and the search form side by side at 1,280 pixels and one
  below the other at 560 pixels, the page for import and export at both widths, an import report, and the
  search page.
- Chrome displayed the merge form at 560 pixels, its message for an empty choice, its confirmation step, the
  derivation form with a rejected expression, the derivation report, a schema report, and the page of a
  service with its links to the merge and to the schema report.
- Chrome displayed the page of the server in both modes, and the confirmation pages for the switch to
  writable mode, the reload, and the reset.
- With the automatic refresh on, Chrome loaded a copy of the history list three times in 13 seconds. The copy
  was served with the content security policy of the UI, so the policy doesn't get in the way of the reload.
- For task 13.12, Chrome ran the script of the UI against the real pages. [ui-stack.md](ui-stack.md) lists
  what was checked, under "A check in a browser".
- In a long list, a script that was added to a copy of the page measured where things are after the browser
  was sent to the row of a service: the line of the page stayed at the top of the window, and the row began
  below it.
- For task 13.13, Chrome displayed the first page of the help and every topic at widths of 1,280 and 420
  pixels. The UI ran from its built distribution, with the server from its built distribution beside it. At
  the narrow width, no page scrolls sideways, and four examples scroll within their boxes. For the 19
  pages, Chrome asked the UI for the pages, the stylesheet, and the script, and it asked no other place for
  anything.

## Repeating the tests

Run `./gradlew :mockey-ui:test`. `UiServicesTest` reads the overview and the page of the diagnostics and sends
the forms of the switches. `UiServiceTest` sends the forms of a service and reads what the admin API was
asked for. `UiScenarioTest` submits the scenario forms, presses the designation buttons, and checks
which requests reached the admin API. `UiPlansTest`, `UiTwistsTest`, `UiTagsTest`, and `UiProxyTest` do the
same for plans, twist configurations, tags, and the outbound proxy. `UiHistoryTest`, `UiHistoryEntryTest`,
and `UiStatisticsTest` cover the history and the statistics. `UiDefinitionsTest`, `UiSearchTest`,
`UiMergeTest`, `UiRealUrlsTest`, `UiSchemaReportTest`, and `UiSystemTest` cover the pages of task 13.10, and
`ImportFormTest` checks how the import form tells XML from JSON. `UiCrawlTest` follows every link from the first
page on, also with a server that states content that is written to be read as markup. `UiCoverageTest` walks
through the whole UI without scripting and holds the coverage list, and `JourneyTest` checks the rules of
such a walk. `UiEnhancementTest` sends the requests of the script, puts the answers into the pages, and takes
the same walk a second time with the script. `UiHelpTest` reads the pages of the help, with the server and
without it.
