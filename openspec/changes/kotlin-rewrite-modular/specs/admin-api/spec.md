# Spec Delta

## Purpose

Defines the versioned JSON API through which definitions, plans, twist configurations, outbound proxy settings, history, and system state are managed. It is the only interface the web UI uses and the supported interface for automation.

## ADDED Requirements

### Requirement: Versioned base path and format
The admin API SHALL be served under the path prefix `/api/v1` on the admin listener. Request and response bodies SHALL be JSON encoded as UTF-8, except where a requirement names another format.

#### Scenario: Listing services
- **WHEN** a client sends `GET /api/v1/services` to the admin listener
- **THEN** the response has status 200 and a JSON body encoded as UTF-8

### Requirement: The admin API is absent from the mock listener
The system SHALL NOT serve any admin API operation on the mock listener.

#### Scenario: Admin path on the mock listener
- **WHEN** a client sends `GET /api/v1/services` to the mock listener
- **THEN** the response has status 404, and no admin operation is performed

### Requirement: The admin API is not reachable through the mock endpoint
The system SHALL NOT perform an admin API operation for a request that the server itself sent to the admin listener while it forwarded mock traffic, whether the target of the mock request named the admin listener by an address or by a host name, or a redirect led to it. It SHALL answer a mock request whose target is the admin listener with status 403.

#### Scenario: Mock request for the admin listener
- **GIVEN** the default settings, without an admin token
- **WHEN** a client sends `GET /service/http://127.0.0.1:8081/api/v1/services` to the mock listener
- **THEN** the response has status 403, and no admin operation is performed

#### Scenario: Mock request that names the admin listener by a host name
- **GIVEN** the default settings, without an admin token
- **WHEN** a client sends `GET /service/http://localhost:8081/api/v1/services` to the mock listener
- **THEN** the response has status 403, and no admin operation is performed

#### Scenario: Redirect to the admin listener
- **GIVEN** a real service that answers a `GET` request with a redirect to `http://127.0.0.1:8081/api/v1/services`
- **WHEN** a client sends a mock request that is forwarded to that service
- **THEN** no admin operation is performed

#### Scenario: Mock request that presents the access token
- **GIVEN** an admin token is configured
- **WHEN** a client sends a mock request for the admin listener that presents the token as a bearer token
- **THEN** the response has status 403, and no admin operation is performed

### Requirement: Mark on requests forwarded to the port of the admin listener
The server SHALL add the header `Mockey-Forwarded` to every request that it forwards to a URL with the port of its admin listener, whatever host that URL names, and to no other request. The admin listener SHALL answer every request that carries that header with status 403, whatever its path is, and whether or not it presents the access token.

#### Scenario: Real service on a port with the number of the admin listener
- **GIVEN** the admin listener on port 8081
- **WHEN** a mock request is forwarded to `http://api.example.com:8081/orders`
- **THEN** the real service receives the request with the headers of the client and the header `Mockey-Forwarded`

#### Scenario: Request to another port
- **GIVEN** the admin listener on port 8081
- **WHEN** a mock request is forwarded to `http://api.example.com/orders`
- **THEN** the real service receives the request without a `Mockey-Forwarded` header of the server

#### Scenario: Request that carries the header
- **WHEN** a client sends a request with the header `Mockey-Forwarded` to the admin listener
- **THEN** the response has status 403, and no admin operation is performed

### Requirement: Web pages change nothing
The admin API SHALL NOT perform an operation for a request with a method other than `GET` and `HEAD` that carries an `Origin` header, and SHALL answer such a request with status 403. It SHALL accept a request body only when the request declares the media type `application/json`, and SHALL answer a request with a body of any other declared type, or of none, with status 415.

#### Scenario: Request of a web page that would change something
- **WHEN** a client sends a `POST` request with the header `Origin: https://pages.example` to the admin API
- **THEN** the response has status 403, and no admin operation is performed

#### Scenario: Body that is not declared as JSON
- **WHEN** a client sends a `POST` request whose body is a JSON object and whose `Content-Type` is `text/plain`
- **THEN** the response has status 415, and no admin operation is performed

### Requirement: Access token
When an admin token is configured, the system SHALL reject with status 401 every admin API request that does not present that token as a bearer token in the `Authorization` header, and SHALL NOT perform the requested operation.

#### Scenario: Request without the token
- **GIVEN** an admin token is configured
- **WHEN** a client sends a request to the admin API without an `Authorization` header
- **THEN** the response has status 401, and nothing is read or changed

#### Scenario: Request with the token
- **GIVEN** an admin token is configured
- **WHEN** a client sends a request with the correct bearer token
- **THEN** the request is processed

### Requirement: Safe methods have no side effects
Admin API requests with the methods `GET` and `HEAD` SHALL NOT change definitions, settings, or history.

#### Scenario: Reading does not change state
- **WHEN** a client sends `GET` requests for services, plans, history, and status
- **THEN** definitions, settings, and history are the same as before those requests

### Requirement: Service and scenario management
The admin API SHALL allow services and their scenarios to be listed, read, created, updated, and deleted, services to be duplicated and merged, real URLs to be derived in bulk, and the error scenario of a service and the universal error scenario to be designated and cleared. It SHALL allow the list of services to be filtered by tag and by exact name without regard to letter case.

#### Scenario: Create a service with a scenario and select it
- **WHEN** a client creates a service, creates a scenario for it, and updates the service to static mode
- **THEN** each request succeeds, and a later read of the service shows static mode with that scenario as the default

#### Scenario: Find a service by name
- **GIVEN** a service named `Customers` exists
- **WHEN** a client lists services filtered by the name `customers`
- **THEN** the list contains exactly that service

### Requirement: Partial updates
An update request for a service, scenario, plan, or twist configuration SHALL change only the attributes present in the request and SHALL leave all other attributes as they were.

#### Scenario: Only the delay is changed
- **GIVEN** a service in dynamic mode with a delay of 0
- **WHEN** a client sends an update for that service that contains only a delay of 250
- **THEN** the service has a delay of 250 and is still in dynamic mode with the same default scenario

### Requirement: Results of creation
A successful creation SHALL return status 201 and the created resource including its identifier.

#### Scenario: Created service is returned
- **WHEN** a client creates a service
- **THEN** the response has status 201 and contains the service with its identifier

### Requirement: Plan management
The admin API SHALL allow plans to be listed, read, saved from a set of services, updated, applied, and deleted, and the default plan to be designated and cleared. Applying a plan SHALL return the report of skipped items and unresolved scenario names.

#### Scenario: Apply a plan
- **GIVEN** a plan exists
- **WHEN** a client applies it through the admin API
- **THEN** the response has status 200 and contains the apply report

### Requirement: Routing settings management
The admin API SHALL allow twist configurations to be listed, created, updated, deleted, enabled, and disabled, the tag filter to be read, set, and cleared, tags in use to be listed, and one tag to be removed from all definitions.

#### Scenario: Enable a twist configuration
- **WHEN** a client enables a twist configuration through the admin API
- **THEN** the system status reports it as the enabled twist configuration, and later mock requests are twisted by it

### Requirement: Outbound proxy management
The admin API SHALL allow the outbound proxy URL, enabled flag, and credentials to be read and set, without ever returning the password, and SHALL allow the shared upstream session to be emptied.

#### Scenario: Set and read outbound proxy settings
- **WHEN** a client sets an outbound proxy URL with a user name and password and then reads the settings
- **THEN** the read returns the URL, the enabled flag, and the user name, states that a password is set, and does not contain the password

### Requirement: History access
The admin API SHALL allow history entries to be listed with filter tokens, a service filter, an after-entry position, and a maximum count, and SHALL allow an entry to be read, deleted, flagged, replayed, and saved as a scenario. It SHALL allow all entries, or all entries of one service, to be deleted, and SHALL provide statistics as JSON and as CSV.

#### Scenario: Filtered list with a maximum
- **GIVEN** the history holds 50 entries of which 12 satisfy the token `orders`
- **WHEN** a client lists history with the token `orders` and a maximum of 5
- **THEN** the response contains the 5 newest of those 12 entries

### Requirement: System status
The admin API SHALL report the server's version, its start time, the definitions location, the read-only setting, the active tag filter, the enabled twist configuration, the base URL of the mock endpoint, and whether the legacy shim is enabled.

#### Scenario: Status is read
- **WHEN** a client reads the system status
- **THEN** the response contains all of those items

### Requirement: System operations
The admin API SHALL allow read-only mode to be switched, definitions to be reloaded from the definitions location, a reset to be performed, last-used times to be cleared, and the installed inspectors to be listed.

#### Scenario: Leave read-only mode
- **GIVEN** the server is in read-only mode
- **WHEN** a client switches read-only mode off through the admin API
- **THEN** the response has status 200, and the system status reports that read-only mode is off

### Requirement: Definition exchange and diagnostics
The admin API SHALL allow definitions to be exported, a definitions document or a Mockey 2.x export document to be imported with optional tags, and the definition diagnostics and a service's schema report to be read. An import SHALL return the import report.

#### Scenario: Import a legacy export document
- **WHEN** a client uploads a Mockey 2.x export document to the import operation with the tag `migrated`
- **THEN** the response contains the import report, and the added services carry the tag `migrated`

### Requirement: Search
The admin API SHALL search services, scenarios, and plans for a term without regard to letter case. A service SHALL match on its name, tags, real URLs, or mock URL pattern, a scenario on its name, tags, or response body, and a plan on its name or tags. Each result SHALL state its type, identifier, and name, and for a scenario its service.

#### Scenario: Term found in a scenario body
- **GIVEN** a scenario whose response body contains `PlatinumMember`
- **WHEN** a client searches for `platinummember`
- **THEN** the results include that scenario with its identifier, its name, and its service

### Requirement: Error responses
A failed admin API request SHALL return a JSON problem document with a type, a title, the HTTP status, and a detail. A validation failure SHALL have status 422 and SHALL list each invalid field with a message. An unknown resource SHALL have status 404, and a request body that cannot be parsed SHALL have status 400.

#### Scenario: Validation failure
- **WHEN** a client creates a service with a mock URL pattern that another service already uses
- **THEN** the response has status 422, and its problem document lists the mock URL field with a message naming the other service

#### Scenario: Unknown resource
- **WHEN** a client reads a service with an identifier that does not exist
- **THEN** the response has status 404 and a problem document

#### Scenario: Unparseable body
- **WHEN** a client sends a request body that is not valid JSON
- **THEN** the response has status 400 and a problem document

### Requirement: Changes take effect immediately
When an admin API request that changes definitions or settings has returned success, every later admin API read and every later request at the mock endpoint SHALL reflect the change.

#### Scenario: Scenario switch is visible to the next mock request
- **GIVEN** a static-mode service whose default scenario is `ok`
- **WHEN** a client sets the default scenario to `error` and, after the success response, sends a request to the service's mock URL
- **THEN** the mock response is that of the scenario `error`

### Requirement: Concurrent changes do not corrupt definitions
When several admin API requests change definitions at the same time, the result SHALL be the same as if they had been processed one after another in some order, and no validation rule SHALL be bypassed.

#### Scenario: Two services race for the same mock URL
- **WHEN** two clients simultaneously create services with the same mock URL pattern
- **THEN** exactly one creation succeeds, and the other fails with status 422

### Requirement: API description document
The system SHALL serve a machine-readable description of the admin API in OpenAPI format under the admin API's path prefix. The description SHALL cover every operation that the admin API offers.

#### Scenario: Description matches the API
- **WHEN** a client reads the API description
- **THEN** every operation the admin API accepts is described in it, and every operation described in it is accepted by the admin API
