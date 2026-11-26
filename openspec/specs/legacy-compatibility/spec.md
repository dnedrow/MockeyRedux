# legacy-compatibility Specification

## Purpose

Defines everything that exists only to ease migration from Mockey 2.x: importing its XML definitions, and an optional shim that answers its documented automation endpoints so that existing scripts keep working.

## Requirements

### Requirement: Import of a legacy definitions directory
The system SHALL import Mockey 2.x definitions from a directory that contains `mock_service_definitions.xml`, following that file's references to service files, and each service file's references to scenario files and to response text files.

#### Scenario: Directory with referenced files
- **GIVEN** a legacy directory whose main file references one service file, which includes two scenario files whose responses are in separate text files
- **WHEN** the directory is imported
- **THEN** one service with two scenarios exists, and each scenario's response body is the content of its text file

### Requirement: Import of a legacy single-file export
The system SHALL import a Mockey 2.x export document in which services and their scenarios are written inline.

#### Scenario: Inline document
- **WHEN** a legacy export document with one inline service and three inline scenarios is imported
- **THEN** one service with three scenarios exists

### Requirement: Legacy service attributes are mapped
Import SHALL carry over every legacy service attribute: name, description, mock URL, tags, delay, response mode (`0` for proxy, `1` for static, `2` for dynamic), default real URL index, inspector name, request rules with their enabled flag, response schema with its enabled flag, and real URLs.

#### Scenario: Response mode and delay
- **WHEN** a legacy service with `service_response_type="2"` and `hang_time="500"` is imported
- **THEN** the service is in dynamic mode with a delay of 500 ms

#### Scenario: Default real URL
- **WHEN** a legacy service with three real URLs and `default_real_url_index="1"` is imported
- **THEN** the service lists the three real URLs in order, and the second is the default

### Requirement: Legacy scenario attributes are mapped
Import SHALL carry over every legacy scenario attribute: name, tags, delay, status code, method restriction, response body, match text, and response header text. Match text SHALL become a rule-set criterion when the legacy rules flag is true, a text criterion when the flag is false and the text is not blank, and no criterion otherwise.

#### Scenario: Rules flag selects the criterion type
- **WHEN** a legacy scenario whose match text is a JSON rule set and whose rules flag is `true` is imported
- **THEN** the scenario has that rule set as its criterion

#### Scenario: Plain match text
- **WHEN** a legacy scenario whose match text is `ABC123` and whose rules flag is `false` is imported
- **THEN** the scenario has the text criterion `ABC123`

### Requirement: Legacy response header text is split
Import SHALL split legacy response header text at each `|` into headers, and each header at its first `:` into a name and a value, trimming both. It SHALL ignore parts that contain no `:`.

#### Scenario: Two headers
- **WHEN** a legacy scenario with the header text `Content-Type: application/json; charset=utf-8 | X-Rate: 10` is imported
- **THEN** the scenario has the headers `Content-Type` with the value `application/json; charset=utf-8` and `X-Rate` with the value `10`, in that order

### Requirement: Legacy references are resolved
Import SHALL resolve the references that legacy documents write as numbers to the items it imports, whatever identifiers those items receive. This covers a service's default scenario and error scenario, the enabled twist configuration, the default plan, and the universal error scenario, whose service number SHALL be read as the position of the service in the document.

#### Scenario: Default scenario
- **WHEN** a legacy service with `default_scenario_id="3"` and a scenario with `id="3"` is imported
- **THEN** that scenario is the service's default scenario

#### Scenario: Universal error scenario
- **WHEN** a legacy document with `universal_error_service_id="2"` and `universal_error_scenario_id="5"` is imported into empty definitions
- **THEN** the universal error scenario is the scenario with legacy `id="5"` of the second service in the document

### Requirement: Legacy placeholders and unresolved references
Import SHALL treat the legacy values `null`, the empty string, and `0` as absent. It SHALL drop any other reference that identifies no imported item and SHALL report it.

#### Scenario: Placeholder inspector name
- **WHEN** a legacy service with `request_inspector_name="null"` is imported
- **THEN** the service has no inspector name, and nothing is reported about it

#### Scenario: Dangling reference
- **WHEN** a legacy service with `error_scenario_id="9"` and no scenario with `id="9"` is imported
- **THEN** the service has no error scenario, and the report names the dangling reference

### Requirement: Unreadable legacy files do not stop the import
A referenced legacy file that is missing or cannot be parsed SHALL NOT stop the import. Import SHALL continue with the remaining content and SHALL report each such file.

#### Scenario: Missing service file
- **GIVEN** a legacy directory whose main file references two service files, one of which does not exist
- **WHEN** the directory is imported
- **THEN** the other service is imported, and the report names the missing file

### Requirement: Legacy rule sets that cannot be read
A legacy scenario or service whose rule set is not well formed SHALL be imported with the rule text preserved, and the report SHALL name it.

#### Scenario: Broken rules are kept and reported
- **WHEN** a legacy scenario whose rules flag is `true` and whose match text is not valid JSON is imported
- **THEN** the scenario exists with that text as its rule-set criterion, and the report names the scenario

### Requirement: Legacy import follows the general import rules
Legacy import SHALL add and merge services, handle plans, twist configurations, and settings, produce its report, and apply optional tags by the same rules as import of the system's own definition documents.

#### Scenario: Same-named service is merged
- **GIVEN** the service `Customer Directory` exists
- **WHEN** a legacy document with a service named `customer directory` is imported
- **THEN** the scenarios that the existing service lacks are added to it, and no second service is created

### Requirement: Legacy sources are never modified
Import SHALL NOT create, change, or delete any file in a legacy definitions directory.

#### Scenario: Source directory is unchanged
- **WHEN** a legacy definitions directory is imported
- **THEN** the directory's files and their contents are the same as before the import

### Requirement: Safe processing of legacy XML
Import SHALL NOT process document type definitions or external entities. It SHALL follow inclusion references only to files inside the directory being imported, and SHALL report any reference that points elsewhere.

#### Scenario: Inclusion outside the directory
- **WHEN** a legacy service file includes a path that resolves outside the directory being imported
- **THEN** that file is not read, and the report names the reference

#### Scenario: External entity
- **WHEN** a legacy document declares an external entity
- **THEN** the entity is not resolved, and the document is reported as not importable

### Requirement: The legacy shim is opt-in
The system SHALL answer the legacy automation endpoints `/config/service`, `/plan/setup`, `/definitions`, and `/home` only when the legacy shim is enabled, and then on the mock listener. When the shim is disabled, requests to those paths SHALL receive status 404.

#### Scenario: Shim disabled
- **GIVEN** the legacy shim is disabled
- **WHEN** a client requests `/definitions` on the mock listener
- **THEN** the response has status 404

#### Scenario: Shim enabled
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/definitions` on the mock listener
- **THEN** the response is a JSON document of service definitions

### Requirement: Legacy result envelope
The endpoints `/config/service`, `/plan/setup`, and `/home` SHALL answer with a JSON object that has a `result` member. That member SHALL have a `success` member when the operation succeeded and a `fail` member when it did not, never both.

#### Scenario: Failure is reported in the envelope
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/config/service` for a service that does not exist
- **THEN** the response is a JSON object whose `result` has a `fail` member and no `success` member

### Requirement: Legacy service configuration endpoint
`/config/service` SHALL find a service by `serviceId` or else by `serviceName` and SHALL apply each of these parameters that is present: `serviceResponseType`, `scenarioId` or `scenarioName` for the default scenario, `hangTime`, `defaultUrlIndex` counted from 1, `requestInspectorName`, `requestInspectorJsonRules`, `requestInspectorJsonRulesEnableFlag`, `serviceSchema`, and `serviceSchemaEnableFlag`. It SHALL accept and ignore `transientState`.

#### Scenario: Switch a service to a named scenario
- **GIVEN** the legacy shim is enabled, and the service `Customers` has a scenario named `gold`
- **WHEN** a client requests `/config/service?serviceName=Customers&serviceResponseType=static&scenarioName=gold&hangTime=250`
- **THEN** `Customers` is in static mode with the default scenario `gold` and a delay of 250 ms

#### Scenario: Numeric response types
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/config/service` with `serviceResponseType=2`
- **THEN** the service is in dynamic mode

### Requirement: Legacy service configuration result
On success, the `result` of `/config/service` SHALL have `success` with the value `updated` and the members `serviceId`, `serviceName`, `scenarioId`, `scenarioName`, `serviceResponseType` (as `proxy`, `static`, or `dynamic`), `hangTime`, `requestInspectorName`, `serviceSchemaEnableFlag`, and `requestInspectorJsonRulesEnableFlag`, describing the service after the change.

#### Scenario: Result describes the updated service
- **GIVEN** the legacy shim is enabled
- **WHEN** a client switches the service `Customers` to static mode through `/config/service`
- **THEN** the `result` has `success` equal to `updated`, `serviceName` equal to `Customers`, and `serviceResponseType` equal to `static`

### Requirement: Legacy plan endpoint
`/plan/setup` SHALL find a plan by `plan_id` or else by `service_plan_name` and SHALL perform the given `action`: `save_plan` saves a plan for the services in `service_ids[]` with the name `service_plan_name` and the tags `service_plan_tag`, `set_plan` applies the plan, `delete_plan` deletes it, and `set_as_default_plan` designates it as default or, with `plan_id=none`, removes the designation. It SHALL accept and ignore `transient_state`.

#### Scenario: Apply a plan by name
- **GIVEN** the legacy shim is enabled, and a plan named `Gold path` exists
- **WHEN** a client requests `/plan/setup?action=set_plan&service_plan_name=Gold%20path`
- **THEN** the plan is applied, and the `result` has a `success` member

#### Scenario: Remove the default plan
- **GIVEN** the legacy shim is enabled, and a default plan is designated
- **WHEN** a client requests `/plan/setup?action=set_as_default_plan&plan_id=none`
- **THEN** there is no default plan

### Requirement: Legacy plan result
On success for an existing or saved plan, the `result` of `/plan/setup` SHALL contain the plan's identifier under both `planid` and `planId`, and its name under `planName`.

#### Scenario: Both identifier spellings are present
- **GIVEN** the legacy shim is enabled
- **WHEN** a client saves a plan through `/plan/setup?action=save_plan`
- **THEN** the `result` has `planid`, `planId`, and `planName` members

### Requirement: Legacy definitions endpoint
`/definitions` SHALL return a JSON object with a `serviceDefinitions` array that has one element for each service, or only for the service identified by `serviceId` or `serviceName`. Each element SHALL have `serviceName`, `serviceId`, `defaultScenarioId`, `defaultScenarioName`, `serviceResponseType`, `hangTime`, `requestInspectorName`, and a `scenarioArray` whose elements have `scenarioId` and `scenarioName`.

#### Scenario: One service by name
- **GIVEN** the legacy shim is enabled, and the service `Customers` has two scenarios
- **WHEN** a client requests `/definitions?serviceName=Customers`
- **THEN** `serviceDefinitions` has one element for `Customers`, and its `scenarioArray` has two elements

### Requirement: Legacy initialization and reset endpoint
`/home` SHALL accept `action=init` with a `file` parameter that names a legacy definitions document inside the definitions location, and SHALL then replace the current definitions with that document and apply the `transientState` and `filterTag` parameters when present. It SHALL accept `action=deleteAllServices` and perform a reset. Any other request to `/home` SHALL receive status 404.

#### Scenario: Initialize from a document in the definitions location
- **GIVEN** the legacy shim is enabled, and the definitions location contains the legacy document `suite-a.xml`
- **WHEN** a client requests `/home?action=init&file=suite-a.xml&type=json`
- **THEN** the definitions are exactly those of `suite-a.xml`, and the `result` has a `success` member

#### Scenario: Reset
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/home?action=deleteAllServices&type=json`
- **THEN** the server has no services, plans, or history entries, and the `result` has a `success` member

### Requirement: Legacy initialization is confined to the definitions location
`/home` SHALL refuse a `file` parameter that resolves outside the definitions location and SHALL refuse the `url` parameter. In both cases the `result` SHALL have a `fail` member and the definitions SHALL be unchanged.

#### Scenario: Path outside the definitions location
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/home?action=init&file=/etc/hosts&type=json`
- **THEN** the `result` has a `fail` member, the file is not read, and the definitions are unchanged

#### Scenario: Remote document
- **GIVEN** the legacy shim is enabled
- **WHEN** a client requests `/home?action=init&url=http://example.com/defs.xml&type=json`
- **THEN** the `result` has a `fail` member, no request is made to that URL, and the definitions are unchanged

### Requirement: Identifiers in legacy endpoints
Where Mockey 2.x used numeric identifiers in parameters and results, the legacy endpoints SHALL accept and return the system's own identifiers.

#### Scenario: Identifier round trip
- **GIVEN** the legacy shim is enabled
- **WHEN** a client reads a `serviceId` from `/definitions` and passes it unchanged to `/config/service`
- **THEN** the service that the identifier came from is the one that is updated
