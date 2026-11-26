# server-runtime Specification

## Purpose

Defines how the server is configured, started, observed, and stopped: its settings and their precedence, its two listeners, liveness, default headers on mock traffic, limits, logging, shutdown, and the command that migrates legacy definitions.

## Requirements

### Requirement: Settings from options and environment
The server SHALL accept each of its settings as a command-line option and as an environment variable. A command-line option SHALL take precedence over the environment variable for the same setting, and a built-in default SHALL apply when neither is given.

#### Scenario: Option overrides environment
- **GIVEN** the environment sets the mock listener port to 9000
- **WHEN** the server is started with the option that sets the mock listener port to 9100
- **THEN** the mock listener accepts connections on port 9100

#### Scenario: Startup tag filter
- **WHEN** the server is started with the tag filter `regression`
- **THEN** the system status reports `regression` as the active tag filter

### Requirement: Help and version output
With the help option the server SHALL print every setting with its option, its environment variable, and its default, and SHALL exit with status 0 without starting. With the version option it SHALL print its version and exit with status 0.

#### Scenario: Help
- **WHEN** the server is started with the help option
- **THEN** it prints the settings and exits with status 0, and no listener is opened

### Requirement: Two listeners
The server SHALL serve the mock endpoint and `/status` on the mock listener and the admin API on the admin listener. The address and port of each listener SHALL be configurable separately. By default the mock listener SHALL accept connections on all addresses at port 8080, and the admin listener on the loopback address at port 8081.

#### Scenario: Defaults
- **WHEN** the server is started without listener settings
- **THEN** `/service/` and `/status` answer on port 8080 on all addresses, and `/api/v1` answers on port 8081 on the loopback address only

### Requirement: Admin token required beyond loopback
The server SHALL refuse to start when the admin listener is bound to a non-loopback address and no admin token is configured, unless the operator has set the option that explicitly allows an unprotected admin listener. The refusal SHALL explain both ways to proceed.

#### Scenario: Unprotected admin listener is refused
- **WHEN** the server is started with the admin listener on all addresses and without an admin token
- **THEN** the server exits with a non-zero status and explains that a token or the explicit override is needed

#### Scenario: Explicit override
- **WHEN** the server is started with the admin listener on all addresses, without a token, and with the override option
- **THEN** the server starts

### Requirement: Listener cannot be bound
When a listener cannot be bound, the server SHALL exit with a non-zero status and SHALL name the address and port that failed.

#### Scenario: Port already in use
- **GIVEN** another program is listening on port 8080
- **WHEN** the server is started with default settings
- **THEN** the server exits with a non-zero status and names port 8080

### Requirement: Definitions location setting
The definitions location SHALL be configurable and SHALL default to a directory named `mockey-definitions` under the working directory.

#### Scenario: Default location
- **WHEN** the server is started in writable mode without a definitions location and a service is created
- **THEN** the service is stored under `mockey-definitions` in the working directory

### Requirement: Initial definitions at startup
The server SHALL accept a startup setting that names a source of initial definitions, as a file path or a URL. The source can be one of the system's own definitions documents, a Mockey 2.x export document, or, as a path, a Mockey 2.x definitions directory. The server SHALL replace the loaded definitions with the content of that source before it accepts requests.

#### Scenario: Start from a legacy document
- **WHEN** the server is started in read-only mode with a Mockey 2.x export document as initial definitions
- **THEN** the server's definitions are those of the document when the first request is accepted

#### Scenario: Source cannot be read
- **WHEN** the server is started with an initial definitions source that does not exist
- **THEN** the server exits with a non-zero status and names the source

### Requirement: Initial definitions do not overwrite stored definitions
When initial definitions are given and read-only mode is off, the server SHALL refuse to start unless the definitions location is empty.

#### Scenario: Stored definitions are protected
- **GIVEN** the definitions location holds stored definitions
- **WHEN** the server is started in writable mode with an initial definitions source
- **THEN** the server exits with a non-zero status, explains why, and leaves the stored definitions unchanged

### Requirement: Legacy import command
The server program SHALL offer a command that imports a Mockey 2.x definitions directory or export document into the definitions location, prints the import report, and exits without opening a listener.

#### Scenario: Migrate a legacy directory
- **WHEN** an operator runs the import command for a Mockey 2.x definitions directory
- **THEN** the definitions location contains the imported definitions, the import report is printed, and the program exits with status 0

### Requirement: Liveness endpoint
The server SHALL answer `GET /status` on the mock listener with status 200 and a JSON object that has a `status` member with the value `running` and a `since` member with the start time in ISO 8601 format. The response SHALL NOT disclose any file system path.

#### Scenario: Status while running
- **WHEN** a client sends `GET /status` to the mock listener
- **THEN** the response has status 200, `status` is `running`, `since` is an ISO 8601 time, and no other member is present

### Requirement: Ready before serving
The server SHALL NOT answer mock, status, or admin requests before definitions are loaded, initial definitions are applied, and the default plan is applied.

#### Scenario: Status means ready
- **WHEN** a client receives the first successful `/status` response after startup
- **THEN** a request to any defined service is answered according to the loaded definitions

### Requirement: Default headers on mock responses
Every response from the mock endpoint SHALL carry `Access-Control-Allow-Origin: *`, `Cache-Control: no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0`, `Pragma: no-cache`, `Expires: Tue, 03 Jul 2001 06:00:00 GMT`, and `Last-Modified` with the current time, each only when the scenario or the upstream response did not define a header of that name.

#### Scenario: Defaults are added
- **GIVEN** a selected scenario with no response headers
- **WHEN** the response is sent
- **THEN** it carries the five default headers

#### Scenario: A scenario header replaces its default
- **GIVEN** a selected scenario with the header `Cache-Control: max-age=60`
- **WHEN** the response is sent
- **THEN** it carries `Cache-Control: max-age=60` and the other four default headers

### Requirement: Admin responses carry no cross-origin permission
Responses from the admin API SHALL NOT carry an `Access-Control-Allow-Origin` header.

#### Scenario: No cross-origin header on the admin API
- **WHEN** a client sends a request with an `Origin` header to the admin API
- **THEN** the response has no `Access-Control-Allow-Origin` header

### Requirement: Request size limit
The server SHALL reject a request whose body exceeds the configured maximum size with status 413 and SHALL NOT process it further.

#### Scenario: Oversized body
- **GIVEN** a maximum request body size of 1 MiB
- **WHEN** a client posts a body of 2 MiB to the mock endpoint
- **THEN** the response has status 413, and the request is neither matched nor forwarded

### Requirement: No other programs are started
The server SHALL NOT start or control any other program, such as a web browser, when it starts.

#### Scenario: Startup on a desktop machine
- **WHEN** the server is started on a machine with a graphical desktop
- **THEN** no browser window is opened

### Requirement: Logging
The server SHALL write its log to standard output at a configurable level and SHALL log its effective settings at startup. It SHALL NOT write the admin token or the outbound proxy password to the log at any level.

#### Scenario: Secrets are not logged
- **GIVEN** an admin token and an outbound proxy password are configured
- **WHEN** the server runs at the most verbose log level and handles requests
- **THEN** the log contains neither the token nor the password

### Requirement: Graceful shutdown
On a termination signal the server SHALL stop accepting new requests, SHALL let requests in progress finish within the configured grace period, SHALL complete any definition save in progress, and SHALL then exit with status 0.

#### Scenario: Save in progress completes
- **GIVEN** writable mode and a definition change that is being saved
- **WHEN** the server receives a termination signal
- **THEN** the save completes, and the server exits with status 0

### Requirement: Runtime platform
The server and the UI SHALL run on a Java 21 runtime without native libraries or other installed software.

#### Scenario: Plain Java runtime
- **GIVEN** a machine with only a Java 21 runtime installed
- **WHEN** the server and the UI are started from their distributions
- **THEN** both start and serve requests
