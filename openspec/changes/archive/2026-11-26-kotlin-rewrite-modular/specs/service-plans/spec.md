# Spec Delta

## Purpose

Defines service plans: named snapshots of the settings of several services that can be saved from the current state, applied later to put those services back into that state, designated as the startup default, and kept consistent when services or scenarios are renamed.

## ADDED Requirements

### Requirement: Plan contents
A plan SHALL have a stable identifier, a name, an optional description, a set of tags, and a list of items. Each item SHALL hold a service name, a scenario name, a response mode, and a delay in milliseconds.

#### Scenario: Plan with two items
- **WHEN** a plan is saved for two services
- **THEN** the plan has two items, each with the service's name, a scenario name, a response mode, and a delay

### Requirement: Saving a plan from current settings
The system SHALL create a plan from a given set of services by storing, for each service, its name, the name of its default scenario, its response mode, and its delay. When no name is given, the system SHALL assign a generated name.

#### Scenario: Snapshot of the current settings
- **GIVEN** the service `Customers` is in dynamic mode with the default scenario `gold` and a delay of 250 ms
- **WHEN** a plan named `Gold path` is saved for `Customers`
- **THEN** the plan has one item with the service name `Customers`, the scenario name `gold`, the mode dynamic, and a delay of 250 ms

#### Scenario: Name is generated when missing
- **WHEN** a plan is saved without a name
- **THEN** the plan is stored with a generated name

### Requirement: Updating an existing plan
A save that identifies an existing plan, by identifier or by name without regard to letter case, SHALL update that plan and replace its items with a snapshot of the given services.

#### Scenario: Saving under an existing name replaces the items
- **GIVEN** a plan named `Gold path` with one item
- **WHEN** a plan named `gold path` is saved for three services
- **THEN** there is still one plan with that identifier, and it has three items

### Requirement: Applying a plan
Applying a plan SHALL, for each item whose service name equals the name of an existing service, set that service's delay and response mode to the item's values, and set its default scenario to the scenario of that service whose name equals the item's scenario name. Name comparisons SHALL ignore letter case and surrounding whitespace.

#### Scenario: Services are put into the plan's state
- **GIVEN** a plan item for `Customers` with the scenario name `gold`, the mode static, and a delay of 0, and the service `Customers` is currently in proxy mode with the default scenario `silver`
- **WHEN** the plan is applied
- **THEN** `Customers` is in static mode with the default scenario `gold` and a delay of 0

#### Scenario: Services outside the plan are untouched
- **GIVEN** a plan with an item for `Customers` only
- **WHEN** the plan is applied
- **THEN** the settings of every other service are unchanged

### Requirement: Items that cannot be applied
When applying a plan, the system SHALL skip an item whose service does not exist, and SHALL leave a service's default scenario unchanged when the service has no scenario with the item's scenario name. It SHALL apply all other items and SHALL report each skipped item and each scenario name it could not resolve.

#### Scenario: Missing service
- **GIVEN** a plan with items for `Customers` and for a service that has since been deleted
- **WHEN** the plan is applied
- **THEN** `Customers` receives its settings, and the report names the item that was skipped

#### Scenario: Missing scenario
- **GIVEN** a plan item for `Customers` with the scenario name `platinum`, and `Customers` has no scenario of that name
- **WHEN** the plan is applied
- **THEN** the mode and delay of `Customers` are set, its default scenario is unchanged, and the report names `platinum` as unresolved

### Requirement: Default plan
The system SHALL allow at most one plan to be designated as the default plan and SHALL allow the designation to be removed. It SHALL apply the default plan after definitions have been loaded at startup. Deleting the default plan SHALL remove the designation.

#### Scenario: Default plan is applied at startup
- **GIVEN** stored definitions in which the plan `Gold path` is the default plan
- **WHEN** the server starts and loads those definitions
- **THEN** the services named in `Gold path` have the plan's settings

#### Scenario: Deleting the default plan
- **WHEN** the plan designated as the default plan is deleted
- **THEN** there is no default plan

### Requirement: Renames are propagated to plans
When a service is renamed, the system SHALL replace the old service name in every plan item that refers to it. When a scenario is renamed, the system SHALL replace the old scenario name in every plan item that refers to that scenario's service and to the old scenario name.

#### Scenario: Service renamed
- **GIVEN** a plan item with the service name `Customers`
- **WHEN** the service `Customers` is renamed to `Clients`
- **THEN** the plan item has the service name `Clients`

#### Scenario: Scenario renamed
- **GIVEN** a plan item with the service name `Customers` and the scenario name `gold`
- **WHEN** the scenario `gold` of `Customers` is renamed to `premium`
- **THEN** the plan item has the scenario name `premium`

### Requirement: Deleting a plan
Deleting a plan SHALL remove the plan and SHALL NOT change the settings of any service.

#### Scenario: Services keep their settings
- **GIVEN** a plan that was applied earlier
- **WHEN** the plan is deleted
- **THEN** the plan no longer exists, and every service keeps its current settings
