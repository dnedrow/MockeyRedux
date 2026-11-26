# request-history Specification

## Purpose

Defines the record the system keeps of requests handled at the mock endpoint, and what can be done with it: bounded retention, filtering, deletion, flagging, replay, turning a recorded exchange into a scenario, per-service statistics, and last-used times.

## Requirements

### Requirement: Recording handled requests
For every request handled at the mock endpoint the system SHALL record a history entry containing a unique identifier, the time, the client address, the request's method, URL, headers, parameters, cookies, and body, the response's status, headers, and body, the service's identifier, name, and tags, the response mode used, the name and tags of the scenario used, the outcome, and the inspection messages.

#### Scenario: Scenario response is recorded
- **WHEN** a dynamic-mode service answers a request with the scenario `gold customer`
- **THEN** a history entry exists with the request and response data, the service's name, the mode dynamic, the scenario name `gold customer`, and the outcome scenario served

#### Scenario: Proxied exchange is recorded
- **WHEN** a request is forwarded upstream and the upstream answers with status 200
- **THEN** a history entry exists with the mode proxy, no scenario name, the outcome proxied, and the upstream's status, headers, and body

### Requirement: Outcome of a handled request
A history entry SHALL state exactly one outcome: scenario served, error scenario served, no scenario matched, proxied, upstream failed, or no service matched.

#### Scenario: Fallback to an error scenario
- **WHEN** a dynamic-mode service finds no matching scenario and answers with its error scenario
- **THEN** the history entry has the outcome error scenario served and the name of the error scenario

#### Scenario: Request that no service handled
- **WHEN** a client sends a request for an unknown relative target and receives status 404
- **THEN** a history entry exists with the outcome no service matched and without a service

### Requirement: Twisted targets are recorded
When URL twisting changed the target of a request, the history entry SHALL record the target before twisting in addition to the target that was used.

#### Scenario: Original target kept
- **GIVEN** an enabled twist configuration that rewrites `qa1` to `qa2`
- **WHEN** a client sends a request to `/service/http://qa1.example.com/orders`
- **THEN** the history entry records `http://qa1.example.com/orders` as the target before twisting and `http://qa2.example.com/orders` as the target used

### Requirement: Bounded retention
The system SHALL keep at most the configured number of history entries. When a new entry would exceed that number, the system SHALL discard the oldest entry.

#### Scenario: Oldest entry is discarded
- **GIVEN** the history limit is 3 and three entries exist
- **WHEN** a fourth request is handled
- **THEN** the history holds the three most recent entries

### Requirement: Body capture limit
The system SHALL store at most the configured number of bytes of each request body and each response body in a history entry, and SHALL mark a body that was cut. The limit SHALL NOT change what the client or the upstream receives.

#### Scenario: Large response is cut in history only
- **GIVEN** a capture limit of 1,024 bytes
- **WHEN** a request is answered with a body of 5,000 bytes
- **THEN** the client receives all 5,000 bytes, and the history entry holds the first 1,024 bytes of the body and marks it as cut

### Requirement: History is held in memory only
The system SHALL NOT persist history entries. After a restart the history SHALL be empty.

#### Scenario: Restart clears history
- **GIVEN** the history holds entries
- **WHEN** the server is restarted
- **THEN** the history is empty

### Requirement: Listing history
The system SHALL return history entries newest first, and SHALL be able to return only the entries recorded after a given entry.

#### Scenario: Only newer entries
- **GIVEN** entries A, B, and C were recorded in that order
- **WHEN** the entries recorded after A are requested
- **THEN** the result is C followed by B

### Requirement: Filtering history by tokens
The system SHALL return the entries that satisfy every given filter token. A token SHALL be satisfied when it occurs, exactly as written, in the entry's service identifier, service name, service tags, scenario tags, client address, request URL, request headers, request parameters, request body, response headers, or response body. A token that begins with `!` SHALL be satisfied when the rest of the token occurs in none of those.

#### Scenario: All tokens must be satisfied
- **GIVEN** one entry whose request URL contains `orders` and whose response body contains `gold`, and another entry that contains only `orders`
- **WHEN** history is filtered with the tokens `orders` and `gold`
- **THEN** only the first entry is returned

#### Scenario: Negated token
- **GIVEN** the same two entries
- **WHEN** history is filtered with the tokens `orders` and `!gold`
- **THEN** only the second entry is returned

#### Scenario: Letter case matters
- **GIVEN** an entry whose response body contains `Gold` and nowhere contains `gold`
- **WHEN** history is filtered with the token `gold`
- **THEN** the entry is not returned

### Requirement: Deleting history
The system SHALL allow one entry, all entries of one service, or all entries to be deleted.

#### Scenario: Delete the entries of one service
- **GIVEN** the history holds entries for services A and B
- **WHEN** the entries of service A are deleted
- **THEN** the history holds only the entries of service B

### Requirement: Flagging entries
The system SHALL allow a history entry to be flagged and unflagged, and SHALL report the flag with the entry.

#### Scenario: Flag and unflag
- **WHEN** an entry is flagged and then read
- **THEN** the entry is reported as flagged, and after unflagging it is reported as not flagged

### Requirement: Replaying an entry
The system SHALL be able to replay a history entry by handling a new request at the mock endpoint with the entry's method and target. The headers, parameters, and body of the replay SHALL default to those of the entry, and each SHALL be replaceable for the replay. The replay SHALL be handled under the current definitions and SHALL produce its own history entry.

#### Scenario: Replay under changed definitions
- **GIVEN** an entry that was answered by the scenario `ok`, after which the service's default scenario was changed to `error`
- **WHEN** the entry is replayed against the static-mode service
- **THEN** the replay is answered by the scenario `error`, and a new history entry records it

#### Scenario: Replay with an edited body
- **WHEN** an entry is replayed with a replacement body
- **THEN** the replayed request carries the replacement body and the entry's original method, target, headers, and parameters

### Requirement: Saving an entry as a scenario
The system SHALL be able to create a scenario for an entry's service from that entry, taking the response body, status code, and headers from the entry. It SHALL refuse when the entry has no service or when the entry's response body was cut.

#### Scenario: Recorded upstream response becomes a scenario
- **GIVEN** a history entry for a proxied exchange that returned status 200, a `Content-Type` header, and a JSON body
- **WHEN** the entry is saved as a scenario named `recorded order`
- **THEN** the entry's service has a scenario `recorded order` with that status, header, and body

#### Scenario: Cut body is refused
- **GIVEN** a history entry whose response body was cut by the capture limit
- **WHEN** the entry is saved as a scenario
- **THEN** the operation is refused with an explanation, and no scenario is created

### Requirement: Per-service statistics
The system SHALL report for every service the number of retained history entries and the time of the earliest one, optionally limited to a time range, and SHALL include services that have no entries with a count of 0. The report SHALL state the time of the oldest retained entry overall, and SHALL be available as JSON and as CSV.

#### Scenario: Counts within a time range
- **GIVEN** service A has three retained entries of which two fall inside the requested time range, and service B has none
- **WHEN** statistics are requested for that time range
- **THEN** the report shows a count of 2 for service A and a count of 0 for service B

#### Scenario: CSV output
- **WHEN** statistics are requested as CSV
- **THEN** the response is CSV text with one row per service

### Requirement: Last-used times
The system SHALL record for each service and scenario the time it last answered a request, and for each plan the time it was last applied. It SHALL NOT persist these times, and it SHALL allow them to be cleared.

#### Scenario: Scenario use is noted
- **WHEN** a scenario answers a request
- **THEN** the scenario and its service each report that time as their last-used time

#### Scenario: Clearing last-used times
- **WHEN** last-used times are cleared
- **THEN** no service, scenario, or plan reports a last-used time
