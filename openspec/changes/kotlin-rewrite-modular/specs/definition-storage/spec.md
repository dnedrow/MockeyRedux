# Spec Delta

## Purpose

Defines how definitions are kept on disk and moved between installations: what is persisted, how read-only mode works, the guarantees for files in the definitions location, loading at startup, and export, import, reload, and reset.

## ADDED Requirements

### Requirement: Persisted content
In writable mode the system SHALL persist services, scenarios, plans, twist configurations, which twist configuration is enabled, the outbound proxy URL and enabled flag, the universal error scenario designation, and the default plan designation.

#### Scenario: Definitions survive a restart
- **GIVEN** writable mode, with a service, a plan designated as default, and an enabled twist configuration
- **WHEN** the server is restarted
- **THEN** the service, the plan and its designation, and the enabled twist configuration are present again with the same identifiers

### Requirement: Content that is never persisted
The system SHALL NOT write history, last-used times, upstream session cookies, outbound proxy credentials, the tag filter, or the read-only setting to the definitions location.

#### Scenario: Proxy password is not stored
- **GIVEN** writable mode and outbound proxy credentials with the password `s3cret`
- **WHEN** definitions are persisted
- **THEN** no file in the definitions location contains `s3cret`

### Requirement: Read-only mode
In read-only mode the system SHALL keep all changes in memory and SHALL NOT create, change, or delete any file in the definitions location. Read-only mode SHALL be the default.

#### Scenario: Changes stay in memory
- **GIVEN** the server was started without choosing a mode
- **WHEN** a service is created and edited
- **THEN** the service is served and listed, and no file in the definitions location has been created, changed, or deleted

### Requirement: Switching read-only mode while running
The system SHALL allow read-only mode to be switched on and off while it is running. Switching it off SHALL persist the complete current definitions before the switch is reported as done.

#### Scenario: Leaving read-only mode saves earlier changes
- **GIVEN** a service was created while in read-only mode
- **WHEN** read-only mode is switched off
- **THEN** the service is present in the definitions location

### Requirement: Changes are saved before they are acknowledged
In writable mode a change to definitions SHALL be persisted before the operation that made it reports success. When persisting fails, the operation SHALL fail with an explanation and SHALL leave the definitions in memory as they were before it.

#### Scenario: Acknowledged change is on disk
- **GIVEN** writable mode
- **WHEN** an operation that renames a scenario reports success
- **THEN** the definitions location already contains the new name

#### Scenario: Failed save is not applied
- **GIVEN** writable mode and a definitions location that has become unwritable
- **WHEN** a scenario is edited
- **THEN** the edit fails with an explanation, and the scenario in memory is unchanged

### Requirement: No partially written files
A reader of the definitions location SHALL never observe a partially written definition file, including when the server process is killed while saving.

#### Scenario: Process killed during a save
- **GIVEN** writable mode
- **WHEN** the server process is killed while a change is being saved, and the server is started again
- **THEN** every definition file is complete and loadable, and each holds either its content from before the change or its content after it

### Requirement: Only changed definitions are rewritten
A change SHALL rewrite only the files of the definitions it changed. It SHALL NOT delete or rewrite the files of unrelated definitions.

#### Scenario: Unrelated files are untouched
- **GIVEN** writable mode and stored services A and B
- **WHEN** a scenario of service A is edited
- **THEN** no file of service B has been modified

### Requirement: Stable file content
Saving definitions that were loaded and not changed SHALL leave every file in the definitions location byte-identical.

#### Scenario: Load and save without edits
- **GIVEN** a definitions location under version control with no uncommitted changes
- **WHEN** the server loads it in read-only mode and read-only mode is then switched off
- **THEN** version control reports no changed file

### Requirement: Collision-free files
Two different definitions SHALL never be stored in the same file, whatever their names. Names that differ only in letter case, spacing, or punctuation SHALL NOT cause one definition to overwrite another.

#### Scenario: Similar names are both kept
- **GIVEN** writable mode and two services named `Order-Service` and `order service`
- **WHEN** both are saved and the server is restarted
- **THEN** both services are present with their own scenarios

### Requirement: Identity comes from file content
The system SHALL identify stored definitions by the identifiers inside the files, not by the names of files or directories. It SHALL load a definitions location in which files or directories have been renamed.

#### Scenario: Directory renamed by hand
- **GIVEN** a stored service whose directory an operator has renamed
- **WHEN** the server starts
- **THEN** the service is loaded with the same identifier, name, and scenarios as before

### Requirement: Loading at startup
At startup the system SHALL load the definitions in the configured definitions location. A missing or empty location SHALL result in empty definitions. When a definition file cannot be read or parsed, the system SHALL refuse to start and SHALL name the file and the problem.

#### Scenario: Empty location
- **WHEN** the server starts with a definitions location that does not exist
- **THEN** the server starts with no services, plans, or twist configurations

#### Scenario: Corrupt file
- **WHEN** the server starts and one scenario file in the definitions location is not parseable
- **THEN** the server does not start, and its error output names that file and what is wrong with it

### Requirement: Format version
Stored definitions SHALL carry a format version. The system SHALL refuse to load definitions whose format version is newer than the versions it supports, and SHALL say which version it found and which versions it supports.

#### Scenario: Definitions from a newer release
- **WHEN** the server starts with definitions whose format version is newer than it supports
- **THEN** the server does not start, and its error output states the found version and the supported versions

### Requirement: Reloading from the definitions location
The system SHALL offer an operation that discards the definitions in memory and loads those in the configured definitions location again, with the same rules as loading at startup except that a failure SHALL leave the definitions in memory unchanged.

#### Scenario: Reload after files were updated
- **GIVEN** an operator has replaced the files in the definitions location with a newer set
- **WHEN** a reload is requested
- **THEN** the services, plans, and twist configurations are those of the newer set

#### Scenario: Reload of a broken set
- **GIVEN** one file in the definitions location is not parseable
- **WHEN** a reload is requested
- **THEN** the reload fails naming the file, and the definitions in memory are unchanged

### Requirement: Export
The system SHALL export the complete definitions as one document that contains everything that is persisted.

#### Scenario: Export and import into an empty server
- **GIVEN** a server with services, scenarios, plans, and twist configurations
- **WHEN** its definitions are exported and the document is imported into a server with empty definitions
- **THEN** the second server has services, scenarios, plans, and twist configurations with the same names, attributes, and designations

### Requirement: Import adds new services
Importing a definitions document SHALL add each service in the document whose name does not equal the name of an existing service, ignoring letter case and surrounding whitespace, together with its scenarios. An imported item whose identifier is already in use SHALL receive a new identifier.

#### Scenario: New service is added
- **GIVEN** no service named `Orders` exists
- **WHEN** a document that contains the service `Orders` with two scenarios is imported
- **THEN** the service `Orders` exists with two scenarios, and the report lists it as added

### Requirement: Import merges services with the same name
For a service in the document whose name equals the name of an existing service, import SHALL add to the existing service each scenario that it does not already have with an equal name and response body, and SHALL add the document's real URLs and tags for that service. It SHALL NOT change or remove any existing scenario or setting of the existing service.

#### Scenario: Scenarios are added to the existing service
- **GIVEN** the existing service `Orders` has the scenario `ok`
- **WHEN** a document whose service `orders` has the scenarios `ok`, with the same response body, and `timeout` is imported
- **THEN** `Orders` has the scenarios `ok` and `timeout`, the existing `ok` is unchanged, and the report lists `timeout` as added and `ok` as already present

### Requirement: Import of settings, plans, and twist configurations
Import SHALL add each plan and each twist configuration whose name is not already in use and SHALL report the others as conflicts. It SHALL take the outbound proxy settings, the universal error scenario, and the default plan from the document only where the current definitions have none, and SHALL apply a default plan that it set.

#### Scenario: Existing settings win
- **GIVEN** the current definitions have an enabled outbound proxy
- **WHEN** a document with different outbound proxy settings is imported
- **THEN** the outbound proxy settings are unchanged, and the report lists them as a conflict

#### Scenario: Default plan from the document is applied
- **GIVEN** the current definitions have no default plan
- **WHEN** a document that designates the plan `Gold path` as default is imported
- **THEN** `Gold path` is the default plan, and its settings have been applied to the services it names

### Requirement: Import report
Every import SHALL produce a report that lists each addition and each conflict. Import SHALL NOT silently drop any part of the document.

#### Scenario: Nothing is dropped silently
- **WHEN** a document is imported of which some parts were added and some were not
- **THEN** every service, scenario, plan, twist configuration, and setting in the document appears in the report as either an addition or a conflict

### Requirement: Tagging imported definitions
Import SHALL accept optional tags and SHALL add them to every service, scenario, and plan that the import added or merged into.

#### Scenario: Import with a tag
- **WHEN** a document is imported with the tag `sprint42`
- **THEN** every service, scenario, and plan added by the import carries the tag `sprint42`

### Requirement: Reset
The system SHALL offer an operation that removes all services, scenarios, plans, and twist configurations, clears the outbound proxy settings, the universal error and default plan designations, and the tag filter, and empties the history. In writable mode it SHALL also empty the stored definitions.

#### Scenario: Clean slate
- **GIVEN** a server with services, plans, twist configurations, and history
- **WHEN** a reset is requested
- **THEN** the server has no services, plans, twist configurations, or history entries, and no tag filter is active
