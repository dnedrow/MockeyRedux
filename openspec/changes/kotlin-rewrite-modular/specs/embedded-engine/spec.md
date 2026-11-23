# Spec Delta

## Purpose

Defines how the core library is used inside another JVM program: creating an engine, defining services in code, evaluating requests without any HTTP server, keeping engines isolated from one another, and replacing the engine's collaborators.

## ADDED Requirements

### Requirement: In-process engine
A JVM program SHALL be able to create an engine through the core library, define services, scenarios, plans, and twist configurations in code, and obtain the response for a request, without any network listener being opened.

#### Scenario: Static scenario evaluated in process
- **GIVEN** a program that has created an engine and defined a static-mode service with the mock URL pattern `customer/{id}` and one scenario
- **WHEN** the program asks the engine to handle a `GET` request for the target `customer/1`
- **THEN** the engine returns that scenario's status, headers, and body, and no network listener has been opened

### Requirement: Same behavior as the server
For the same definitions, settings, and request, an engine SHALL make the same routing decision, return the same response, and record the same history entry as the server's mock endpoint.

#### Scenario: Engine and server agree
- **GIVEN** an engine and a server that hold the same definitions
- **WHEN** the same request is handled by each
- **THEN** both select the same service and scenario and produce the same status, headers, and body

### Requirement: Engines are isolated
Engines created in the same program SHALL NOT share definitions, history, settings, or upstream session cookies.

#### Scenario: Two engines
- **GIVEN** two engines in one program
- **WHEN** a service is defined in the first engine and a request for it is handled by the second
- **THEN** the second engine treats the request as having no matching service, and its history is separate from that of the first

### Requirement: No server dependency
Using the core library SHALL NOT require an HTTP server library, the server application, or the UI application to be present.

#### Scenario: Core library alone
- **GIVEN** a program whose class path holds only the core library and the dependencies the library declares
- **WHEN** the program defines static-mode and dynamic-mode services and handles requests for them
- **THEN** the requests are answered

### Requirement: Replaceable collaborators
When creating an engine, a program SHALL be able to supply its own definition storage, upstream client, inspectors, and clock. For each one it does not supply, the engine SHALL use in-memory storage, the built-in upstream client, the installed inspectors, and the system clock respectively.

#### Scenario: Substitute upstream client
- **GIVEN** an engine created with an upstream client that returns a fixed response
- **WHEN** a request is handled in proxy mode
- **THEN** the engine returns the fixed response, and no network connection is made

#### Scenario: Substitute clock
- **GIVEN** an engine created with a clock fixed at a known time
- **WHEN** a request is handled
- **THEN** the history entry carries that time

### Requirement: Safe concurrent use
An engine SHALL be safe to use from many threads at once. A request handled while definitions are being changed SHALL be answered entirely according to the definitions before the change or entirely according to the definitions after it.

#### Scenario: Request during a change
- **GIVEN** an engine that is handling requests on several threads
- **WHEN** another thread replaces a service's default scenario
- **THEN** every response is that of the old default scenario or that of the new one, and none mixes parts of both

### Requirement: Validation in code
An engine operation that would violate a definition rule SHALL fail with a result that identifies each invalid field and its message, and SHALL leave the engine's definitions unchanged.

#### Scenario: Duplicate mock URL pattern
- **WHEN** a program defines a second service with a mock URL pattern that an existing service uses
- **THEN** the operation fails with a result that names the mock URL field and the existing service, and the engine still has one service

### Requirement: Shared definition format
A program SHALL be able to have an engine load definitions from, and save them to, a definitions location. A location written by an engine SHALL be loadable by the server, and a location written by the server SHALL be loadable by an engine.

#### Scenario: Engine reads what the server wrote
- **GIVEN** a definitions location written by a server in writable mode
- **WHEN** a program creates an engine on that location
- **THEN** the engine has the same services, scenarios, plans, and twist configurations

### Requirement: Closing an engine
A program SHALL be able to close an engine. Closing SHALL complete any definition save in progress and release the engine's upstream connections, after which the engine SHALL refuse further requests.

#### Scenario: Close completes a pending save
- **GIVEN** an engine with file storage and a change that is being saved
- **WHEN** the program closes the engine
- **THEN** the change is in the definitions location when the close returns
