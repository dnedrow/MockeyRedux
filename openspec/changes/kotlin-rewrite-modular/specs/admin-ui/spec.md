# Spec Delta

## Purpose

Defines the web UI as a separately deployed, server-rendered application that manages a Mockey server through its admin API: what it covers, how it behaves without JavaScript, how optional scripting enhances it, and how it handles errors and untrusted content.

## ADDED Requirements

### Requirement: Separate process that uses only the admin API
The UI SHALL run as its own process and SHALL read and change server state only through the admin API of the server it is configured for. It SHALL hold no definitions or history of its own. The server SHALL operate fully while no UI is running.

#### Scenario: Server without a UI
- **WHEN** the server is running and no UI process is running
- **THEN** the mock endpoint and the admin API work normally

#### Scenario: UI restart loses nothing
- **GIVEN** definitions were edited through the UI
- **WHEN** the UI process is restarted
- **THEN** the UI shows the same definitions, because they are held by the server

### Requirement: Server connection settings
The UI SHALL be configured with the base URL of an admin API and, where the server requires one, the admin token. It SHALL send the token with every admin API request and SHALL NOT disclose the token to the browser.

#### Scenario: Token stays on the UI side
- **GIVEN** the UI is configured with an admin token
- **WHEN** a user loads any page
- **THEN** neither the page, its headers, nor its cookies contain the token

### Requirement: Listener defaults
The UI SHALL listen on a configurable address and port and SHALL bind to the loopback address by default.

#### Scenario: Default bind
- **WHEN** the UI is started without a bind address
- **THEN** it accepts connections on the loopback address only

### Requirement: Usable without JavaScript
Every function of the UI SHALL be usable in a browser with scripting disabled, through links, forms, and full page loads.

#### Scenario: Core flow with scripting disabled
- **GIVEN** a browser with scripting disabled
- **WHEN** a user creates a service, adds a scenario, switches the service to static mode, and applies a plan
- **THEN** every step succeeds and shows its result

### Requirement: Optional scripting enhancement
When scripting is available, the UI SHALL be able to update part of a page in place instead of loading a full page. An enhanced interaction SHALL use the same address as its full-page equivalent and SHALL leave the server in the same state.

#### Scenario: Mode switch updates in place
- **GIVEN** a browser with scripting enabled
- **WHEN** a user switches a service's response mode on the services overview
- **THEN** only that service's entry is redrawn, and the server state is the same as after the full-page form submission

### Requirement: Self-hosted assets
The UI SHALL serve every stylesheet, script, image, and font it uses from its own origin. Its pages SHALL NOT cause the browser to contact any other origin.

#### Scenario: No external requests
- **WHEN** a user loads each page of the UI in a browser that can reach only the UI
- **THEN** every page renders completely, and the browser attempts no request to another origin

### Requirement: Coverage of management operations
The UI SHALL offer every management operation of the admin API for services, scenarios, plans, twist configurations, the tag filter, tags, outbound proxy settings, the shared upstream session, history, statistics, import, export, diagnostics, schema reports, search, read-only mode, reload, reset, and last-used times.

#### Scenario: Import with a report
- **WHEN** a user uploads a Mockey 2.x export document with a tag on the import page
- **THEN** the UI shows the additions and conflicts from the import report

#### Scenario: Replay with edits
- **WHEN** a user opens a history entry, changes the request body, and replays it
- **THEN** the UI shows the new history entry that the replay produced

### Requirement: Services overview
The services overview SHALL list each service with its name, its callable mock URL, its effective response mode, its default scenario, its delay, its tags, its last-used time, and a marker when diagnostics concern it. From the overview a user SHALL be able to switch a service's response mode and default scenario.

#### Scenario: Diagnostics marker
- **GIVEN** diagnostics report duplicate scenarios for the service `Customers`
- **WHEN** a user opens the services overview
- **THEN** the entry for `Customers` shows a marker that leads to the diagnostic

### Requirement: Callable mock URL
For each service the UI SHALL display the complete URL at which a client can call the service at the mock endpoint, built from the mock endpoint base URL that the server reports.

#### Scenario: Relative mock URL pattern
- **GIVEN** the server reports the mock endpoint base URL `http://mockey.test:8080` and a service has the mock URL pattern `customer/{id}`
- **WHEN** a user views the service
- **THEN** the UI displays `http://mockey.test:8080/service/customer/{id}`

### Requirement: View state lives in the address
The UI SHALL keep list filters, history filter tokens, and paging in the page address, so that the same address opened in another browser shows the same view. It SHALL NOT depend on per-user state held in the UI process.

#### Scenario: Shared link
- **GIVEN** a user has filtered history with the tokens `orders` and `!gold`
- **WHEN** the page address is opened in a different browser
- **THEN** the same filtered history is shown

### Requirement: Validation feedback
When the admin API rejects input, the UI SHALL show the form again with the user's input preserved and with each message next to the field it concerns.

#### Scenario: Duplicate mock URL
- **WHEN** a user submits a service whose mock URL pattern is already in use
- **THEN** the form is shown again with all entered values, and the message naming the other service appears at the mock URL field

### Requirement: Result feedback
After a state-changing action the UI SHALL state whether the action succeeded and SHALL show any report the admin API returned. After a successful form submission it SHALL redirect the browser to a page that can be reloaded without repeating the action.

#### Scenario: Reload after saving
- **WHEN** a user saves a scenario and then reloads the resulting page
- **THEN** the scenario is not saved a second time

#### Scenario: Plan apply report
- **WHEN** a user applies a plan of which one item refers to a deleted service
- **THEN** the UI states that the plan was applied and names the skipped item

### Requirement: Confirmation of destructive actions
The UI SHALL ask for confirmation before it deletes a service, scenario, plan, or twist configuration, deletes all history, removes a tag from all definitions, or performs a reset. The confirmation SHALL work with scripting disabled.

#### Scenario: Reset needs confirmation
- **WHEN** a user chooses reset
- **THEN** the UI shows a confirmation step, and nothing is removed unless the user confirms

### Requirement: Live history view
The history page SHALL offer an automatic refresh that the user can switch on and off. With scripting available new entries SHALL appear in place, and with scripting disabled the page SHALL reload itself at an interval.

#### Scenario: New entry appears
- **GIVEN** a user has switched on automatic refresh on the history page
- **WHEN** a request is handled at the mock endpoint
- **THEN** the entry appears on the page without any action by the user

### Requirement: Server unavailable
When the admin API cannot be reached or rejects the UI's token, the UI SHALL show a page that says so and names the configured server address. The UI process SHALL keep running and SHALL work again as soon as the admin API is reachable.

#### Scenario: Server is down
- **GIVEN** the configured server is not running
- **WHEN** a user opens the UI
- **THEN** the UI shows that the server at the configured address cannot be reached

#### Scenario: Server comes back
- **GIVEN** the UI showed that the server cannot be reached
- **WHEN** the server is started and the user reloads the page
- **THEN** the UI shows the server's data

### Requirement: Incompatible server
When the configured server does not offer the admin API version that the UI requires, the UI SHALL say so on every page and SHALL NOT show partial data.

#### Scenario: Wrong API version
- **GIVEN** the UI is configured for a server that lacks the required admin API version
- **WHEN** a user opens any page
- **THEN** the page states that the server is incompatible and which API version the UI requires

### Requirement: Stored content is shown as text
The UI SHALL render names, tags, URLs, headers, bodies, and all other stored or recorded content as text. The browser SHALL never interpret such content as markup or script.

#### Scenario: Markup in a scenario name
- **GIVEN** a scenario named `<script>alert(1)</script>`
- **WHEN** a user opens any page that shows the scenario
- **THEN** the name appears literally as text, and no script runs

### Requirement: Request forgery protection
The UI SHALL reject a state-changing request that does not carry the anti-forgery token that the UI issued to that browser, and SHALL make no admin API call for it.

#### Scenario: Cross-site form post
- **WHEN** a page on another site makes the user's browser post a delete form to the UI without the anti-forgery token
- **THEN** the UI rejects the request, and nothing is deleted

### Requirement: Host names of the UI
The UI SHALL answer only requests whose `Host` header names an IP address, `localhost` or a name under `localhost`, or a host name that the UI was configured with. It SHALL reject every other request, SHALL make no admin API call for it, and SHALL NOT disclose server data or an anti-forgery token in the response.

#### Scenario: Rebound host name
- **GIVEN** a page of another site whose host name has been made to resolve to the address of the UI
- **WHEN** that page requests a UI page under its own host name
- **THEN** the UI rejects the request, and the response contains no server data and no anti-forgery token

#### Scenario: Configured host name
- **GIVEN** the UI is configured with the allowed host name `mockey.example`
- **WHEN** a browser requests a page with the `Host` header `mockey.example`
- **THEN** the UI serves the page

### Requirement: Built-in help
The UI SHALL include help pages that explain services, scenarios, response modes, match criteria, request rules, error scenarios, plans, twisting, tags, storage, and import. The help SHALL be available without network access beyond the UI.

#### Scenario: Help is reachable offline
- **GIVEN** a browser that can reach only the UI
- **WHEN** a user opens the help
- **THEN** the help pages are shown in full
