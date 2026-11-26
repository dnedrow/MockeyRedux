# service-definitions Specification

## Purpose

Defines services and their scenarios as managed definitions: which attributes they carry, which rules keep them valid and unambiguous, and which maintenance operations exist for them.

## Requirements

### Requirement: Service attributes
A service SHALL have a stable identifier, a name, an optional description, a mock URL pattern, an ordered list of real URLs of which one is the default, a response mode (proxy, static, or dynamic), a delay in milliseconds, a set of tags, its scenarios, an optional error scenario, an optional request rule set with an enabled flag, an optional inspector name, and an optional response schema with an enabled flag.

#### Scenario: Service created with minimal input
- **WHEN** a service is created with only a name and a mock URL pattern
- **THEN** it is stored with proxy mode, a delay of 0, no tags, no real URLs, no scenarios, and both enabled flags off

#### Scenario: Identifier is stable
- **WHEN** a service is renamed, saved, and loaded again after a restart
- **THEN** its identifier is unchanged

#### Scenario: Identifier is not reused
- **WHEN** a service is deleted and another service is created afterwards
- **THEN** the new service has an identifier that was never used before

### Requirement: Service name validation
The system SHALL reject a service whose name, after trimming, is empty or longer than 250 characters. The rejection SHALL identify the name as the invalid field and SHALL leave the stored definitions unchanged.

#### Scenario: Blank name rejected
- **WHEN** a service with no real URLs is saved with a name that consists only of spaces
- **THEN** the save is rejected with an error on the name field, and no service is created or changed

### Requirement: Derived service name
When a service is created without a name and with at least one real URL, the system SHALL name it after the last path segment of its first real URL, ignoring any query string, followed by the text ` (auto generated)`.

#### Scenario: Name derived from the real URL
- **WHEN** a service is created with no name and the real URL `http://api.example.com/v1/customers?active=true`
- **THEN** the service is named `customers (auto generated)`

### Requirement: Unique mock URL pattern
The system SHALL reject a service whose mock URL pattern equals that of another service, comparing after trimming and without regard to letter case. The rejection SHALL name the service that already uses the pattern.

#### Scenario: Duplicate mock URL rejected
- **GIVEN** a service named `Customers` with the mock URL pattern `customer/{id}`
- **WHEN** another service is saved with the mock URL pattern `Customer/{ID}`
- **THEN** the save is rejected, and the error names `Customers`

### Requirement: A real URL belongs to one service
The system SHALL reject a service that lists a real URL already listed by another service. The rejection SHALL name the other service and the URL.

#### Scenario: Real URL already managed elsewhere
- **GIVEN** the service `Orders` lists the real URL `http://api.example.com/orders`
- **WHEN** the service `Billing` is saved with the same real URL
- **THEN** the save is rejected, and the error names `Orders` and the URL

### Requirement: Scenario attributes
A scenario SHALL belong to exactly one service. It SHALL have a stable identifier, a name, a response body, an HTTP status code that defaults to 200, an ordered list of response headers, a match criterion (none, text, or a rule set), an HTTP method restriction that is empty when any method is accepted, a delay in milliseconds, and a set of tags.

#### Scenario: Defaults for a new scenario
- **WHEN** a scenario is created with only a name and a response body
- **THEN** its status code is 200, and it has no response headers, no match criterion, no method restriction, a delay of 0, and no tags

### Requirement: Scenario name validation
The system SHALL reject a scenario whose name, after trimming, is longer than 250 characters. When a scenario is saved without a name, the system SHALL assign a generated name that contains the name of its service.

#### Scenario: Missing name is generated
- **WHEN** a scenario is created without a name for the service `Customers`
- **THEN** the scenario is stored with a generated name that contains `Customers`

#### Scenario: Overlong name rejected
- **WHEN** a scenario is saved with a name of 251 characters
- **THEN** the save is rejected with an error on the name field

### Requirement: Rule sets are validated on save
The system SHALL reject a service or scenario whose request rule set is not well formed, as defined by the request-inspection capability. The rejection SHALL say what is wrong with the rule set and SHALL leave the stored definition unchanged.

#### Scenario: Malformed rule set rejected
- **WHEN** a scenario is saved with a rule-set criterion that is not valid JSON
- **THEN** the save is rejected with an error on the match criterion, and the stored scenario is unchanged

### Requirement: Effective response mode
A service that has no scenarios SHALL behave as a proxy regardless of its configured response mode, and the system SHALL report proxy as its effective mode.

#### Scenario: No scenarios means proxy
- **GIVEN** a service configured as static that has no scenarios
- **WHEN** its effective response mode is read
- **THEN** the effective response mode is proxy

### Requirement: Default scenario integrity
A service with at least one scenario SHALL always have a default scenario that is one of its own scenarios, and a service without scenarios SHALL have none. When the default scenario is missing or is deleted, the system SHALL make the earliest-created remaining scenario the default.

#### Scenario: First scenario becomes the default
- **WHEN** the first scenario is added to a service
- **THEN** that scenario is the service's default scenario

#### Scenario: Default scenario deleted
- **GIVEN** a service with scenarios A, B, and C created in that order, with C as the default
- **WHEN** scenario C is deleted
- **THEN** scenario A is the default

#### Scenario: Last scenario deleted
- **WHEN** the only scenario of a service is deleted
- **THEN** the service has no default scenario

### Requirement: Error scenario designation
A service SHALL be able to designate at most one of its own scenarios as its error scenario, and the system SHALL be able to designate at most one scenario overall as the universal error scenario. A designation SHALL change only through an explicit request or when the designated scenario is deleted.

#### Scenario: Saving another scenario keeps the universal designation
- **GIVEN** scenario X is the universal error scenario
- **WHEN** an unrelated scenario Y is edited and saved
- **THEN** X is still the universal error scenario

#### Scenario: Designated scenario deleted
- **WHEN** the scenario designated as a service's error scenario is deleted
- **THEN** the service has no error scenario

### Requirement: Tag normalization
Tags on services, scenarios, and plans SHALL be stored in lower case, split on whitespace into individual tags, and free of duplicates.

#### Scenario: Mixed-case input with duplicates
- **WHEN** a service is saved with the tag input `Regression  smoke REGRESSION`
- **THEN** its tags are `regression` and `smoke`

### Requirement: Tag listing and removal across definitions
The system SHALL list every tag in use on any service, scenario, or plan, and SHALL allow one tag to be removed from all of them in a single operation.

#### Scenario: Remove a tag everywhere
- **GIVEN** the tag `smoke` is on two services, three scenarios, and one plan
- **WHEN** the tag `smoke` is removed across definitions
- **THEN** no service, scenario, or plan carries `smoke`, and the tag list no longer includes it

### Requirement: Service deletion
Deleting a service SHALL delete its scenarios. If the universal error scenario belonged to the deleted service, the system SHALL clear the universal error designation.

#### Scenario: Deleting the owner of the universal error scenario
- **GIVEN** the universal error scenario belongs to the service `Errors`
- **WHEN** the service `Errors` is deleted
- **THEN** there is no universal error scenario

### Requirement: Service duplication
Duplicating a service SHALL create a service with a new identifier, a distinct name, a distinct mock URL pattern, and copies of all scenarios under new identifiers. The copy SHALL keep the original's response mode, delay, tags, rule set, response schema, and default and error scenario designations. The copy SHALL NOT list the original's real URLs.

#### Scenario: Duplicate keeps scenarios and designations
- **GIVEN** the service `Customers` with two scenarios, the second of which is the default
- **WHEN** `Customers` is duplicated
- **THEN** a new service exists whose name and mock URL pattern differ from those of `Customers`, it has two scenarios with new identifiers, and its default scenario is the copy of the original's default

### Requirement: Service merge
Merging source services into a destination SHALL copy into the destination every source scenario for which the destination has no scenario with an equal name and response body, ignoring case and surrounding whitespace, and SHALL add the sources' tags. It SHALL report every copied and every skipped scenario.

#### Scenario: Scenarios are copied and reported
- **GIVEN** the source service has scenarios `ok` and `timeout`, and the destination has scenario `empty`
- **WHEN** the source is merged into the destination
- **THEN** the destination has `empty`, `ok`, and `timeout`, and the report lists two copied scenarios

### Requirement: Merge removes sources only when nothing was skipped
When a merge skipped no scenario, the system SHALL move the sources' real URLs to the destination and delete the sources. When any scenario was skipped, the system SHALL keep the sources and their real URLs, and the report SHALL say so.

#### Scenario: Clean merge removes the source
- **GIVEN** no scenario of the source duplicates a scenario of the destination
- **WHEN** the source is merged into the destination
- **THEN** the destination lists the source's real URLs, and the source no longer exists

#### Scenario: Merge with a duplicate keeps the source
- **GIVEN** the source and the destination each have a scenario named `ok` with the same response body
- **WHEN** the source is merged into the destination
- **THEN** the destination still has one `ok` scenario, the source still exists with its real URLs, and the report lists `ok` as skipped

### Requirement: Bulk derivation of real URLs
Given a regular expression and one or more replacement strings, the system SHALL add to every service the URLs obtained by applying each replacement to each of the service's real URLs. It SHALL skip any result that some service already lists.

#### Scenario: Add environment variants
- **GIVEN** a service lists the real URL `http://qa1.example.com/orders`
- **WHEN** derivation runs with the expression `qa1` and the replacements `qa2` and `qa3`
- **THEN** the service lists `http://qa1.example.com/orders`, `http://qa2.example.com/orders`, and `http://qa3.example.com/orders`

### Requirement: Definition diagnostics
The system SHALL report, without blocking any operation, each of the following: services that share a mock URL pattern or a real URL, scenarios within one service that have equal names and response bodies ignoring case and surrounding whitespace, services that name an inspector that is not installed, and rule sets that cannot be parsed.

#### Scenario: Duplicate scenarios reported
- **GIVEN** an import has left one service with two scenarios that have the same name and response body
- **WHEN** diagnostics are requested
- **THEN** the report names the service and the duplicate scenarios

#### Scenario: Unknown inspector reported
- **GIVEN** a service names an inspector that is not installed
- **WHEN** diagnostics are requested
- **THEN** the report names the service and the missing inspector

### Requirement: Response schema report
For a service with a response schema, the system SHALL produce on request a report that states for each scenario whether its response body conforms to the schema and lists the violations. A violation SHALL NOT prevent a scenario from being saved or served. When the schema's enabled flag is on, violations SHALL also appear in the definition diagnostics.

#### Scenario: Non-conforming scenario reported but still served
- **GIVEN** a service whose schema requires the property `id`, and a scenario whose JSON body has no `id`
- **WHEN** the schema report is requested
- **THEN** the report marks that scenario as non-conforming and names the missing property, and the scenario is still served unchanged

### Requirement: Supported schema dialects
The system SHALL validate response schemas written in JSON Schema draft-04 or later. For a schema that declares a dialect it does not support, the system SHALL report the dialect as unsupported and SHALL NOT give a conformance verdict.

#### Scenario: Unsupported dialect
- **WHEN** a schema report is requested for a service whose schema declares `http://json-schema.org/draft-03/schema`
- **THEN** the report states that the dialect is unsupported and contains no conformance verdict
