# Spec Delta

## Purpose

Defines how a service in static or dynamic mode produces its response: which scenario is chosen, how competing matches are scored, what is returned when nothing matches, and how the status, headers, body, and delay of the chosen scenario are applied.

## ADDED Requirements

### Requirement: Static mode returns the default scenario
In static mode the system SHALL respond with the status code, headers, and body of the service's default scenario, whatever the content of the request and whatever match criteria or method restrictions the scenarios carry.

#### Scenario: Default scenario served for any request
- **GIVEN** a static-mode service whose default scenario returns status 200 and the body `{"plan":"gold"}`
- **WHEN** a client sends any request that is routed to the service
- **THEN** the response has status 200 and the body `{"plan":"gold"}`

### Requirement: Method restriction in dynamic mode
In dynamic mode a scenario SHALL be eligible for a request only if its method restriction is empty, is `*`, or equals the request method without regard to letter case.

#### Scenario: Restricted scenario is skipped
- **GIVEN** a dynamic-mode service with a scenario restricted to `POST`
- **WHEN** a client sends a `GET` request that would otherwise match that scenario
- **THEN** that scenario is not selected

#### Scenario: Wildcard restriction accepts every method
- **GIVEN** a dynamic-mode service with a scenario whose method restriction is `*`
- **WHEN** a client sends a `DELETE` request that matches that scenario
- **THEN** that scenario is eligible

### Requirement: Evaluation text for text criteria
For text criteria the system SHALL build an evaluation text from the values captured by the matched URL pattern, the request's query parameters written as `name=value` pairs, and the request body, and SHALL percent-decode that text. Literal parts of the request path SHALL NOT be part of the evaluation text.

#### Scenario: Captured variable value is evaluated
- **GIVEN** a dynamic-mode service with the pattern `customer/{id}` and a scenario with the text criterion `123`
- **WHEN** a client sends `GET /service/customer/123`
- **THEN** the scenario matches

#### Scenario: Query parameter is evaluated after decoding
- **GIVEN** a scenario with the text criterion `name=jane doe`
- **WHEN** a client sends a request with the query string `name=jane%20doe`
- **THEN** the scenario matches

#### Scenario: Request body is evaluated
- **GIVEN** a scenario with the text criterion `<tier>gold</tier>`
- **WHEN** a client posts a body that contains `<tier>gold</tier>`
- **THEN** the scenario matches

#### Scenario: Literal path text is not evaluated
- **GIVEN** a dynamic-mode service with the pattern `orders/list` and a scenario with the text criterion `orders`
- **WHEN** a client sends `GET /service/orders/list` with no query string and no body
- **THEN** the scenario does not match

### Requirement: Text criterion matching and score
A scenario with a text criterion SHALL match when the evaluation text contains the criterion, without regard to letter case. Its score SHALL be the number of characters in the criterion after trimming.

#### Scenario: Case-insensitive containment
- **GIVEN** a scenario with the text criterion `GOOG`
- **WHEN** a client sends a request with the query string `ticker=goog`
- **THEN** the scenario matches with a score of 4

### Requirement: Rule criterion matching and score
A scenario with a rule-set criterion SHALL match when the request satisfies the rule set as defined by the request-inspection capability. Its score SHALL be the number of rules the request satisfied.

#### Scenario: Rule set satisfied
- **GIVEN** a scenario whose rule set has one `url` rule requiring the expression `\b123\b`
- **WHEN** a client sends `GET /service/customer/123`
- **THEN** the scenario matches with a score of 1

### Requirement: Best match selection
Among eligible scenarios that match, the system SHALL select the one with the highest score. When scores are equal it SHALL select the scenario whose name sorts first ignoring case, and when names are also equal the one created first. A scenario without a match criterion SHALL never be selected in dynamic mode.

#### Scenario: Longer text criterion wins
- **GIVEN** scenario A with the text criterion `123` and scenario B with the text criterion `ABC123`
- **WHEN** a client sends a request whose evaluation text contains `ABC123`
- **THEN** scenario B is selected

#### Scenario: Equal scores are resolved by name
- **GIVEN** scenarios named `beta` and `Alpha`, each with a different three-character text criterion
- **WHEN** a client sends a request whose evaluation text contains both criteria
- **THEN** the scenario named `Alpha` is selected

#### Scenario: Scenario without a criterion is ignored
- **GIVEN** a dynamic-mode service whose only scenario has no match criterion
- **WHEN** a client sends a request that is routed to the service
- **THEN** that scenario is not selected, and the no-match handling applies

### Requirement: Error scenario fallback when nothing matches
When no scenario matches in dynamic mode, the system SHALL respond with the service's error scenario if one is designated, and otherwise with the universal error scenario if one is designated. It SHALL use that scenario's status code, headers, and body.

#### Scenario: Service error scenario is used first
- **GIVEN** a dynamic-mode service with an error scenario that returns status 500 and the body `service error`, and a universal error scenario that returns the body `universal error`
- **WHEN** a client sends a request that matches no scenario
- **THEN** the response has status 500 and the body `service error`

#### Scenario: Universal error scenario is used second
- **GIVEN** a dynamic-mode service without an error scenario, and a universal error scenario that returns status 503
- **WHEN** a client sends a request that matches no scenario
- **THEN** the response has status 503 and the universal error scenario's body

### Requirement: Built-in response when no fallback exists
When no scenario matches in dynamic mode and neither a service error scenario nor a universal error scenario is designated, the system SHALL respond with status 404 and a plain-text body explaining that no scenario matched and that no error scenario is defined.

#### Scenario: No match and no error scenario
- **GIVEN** a dynamic-mode service with no error scenario, and no universal error scenario
- **WHEN** a client sends a request that matches no scenario
- **THEN** the response has status 404 and an explanatory plain-text body

### Requirement: Status and headers of the selected scenario
The response SHALL carry the selected scenario's status code and every one of its headers in their stored order, including headers that share a name.

#### Scenario: Status and headers are applied
- **GIVEN** a selected scenario with status 201 and the headers `Location: /orders/9` and `X-Trace: a`
- **WHEN** the response is sent
- **THEN** the response has status 201 and carries both headers with those values

#### Scenario: Repeated header names are kept
- **GIVEN** a selected scenario with two `Set-Cookie` headers
- **WHEN** the response is sent
- **THEN** the response carries two `Set-Cookie` headers

### Requirement: Body fidelity
The system SHALL send the selected scenario's response body exactly as stored. It SHALL NOT add, remove, or alter any character.

#### Scenario: No trailing line break is added
- **GIVEN** a selected scenario whose body is the 11 characters `{"ok":true}`
- **WHEN** the response is sent
- **THEN** the response body is exactly those 11 characters and nothing follows them

### Requirement: Body encoding
The system SHALL encode a scenario's body as UTF-8 unless the scenario's `Content-Type` header declares another charset, in which case it SHALL encode the body in that charset.

#### Scenario: UTF-8 by default
- **GIVEN** a selected scenario with no `Content-Type` header and the body `é`
- **WHEN** the response is sent
- **THEN** the body is sent as the two bytes `C3 A9`

#### Scenario: Declared charset is honored
- **GIVEN** a selected scenario with the header `Content-Type: text/plain; charset=ISO-8859-1` and the body `é`
- **WHEN** the response is sent
- **THEN** the body is sent as the single byte `E9`

### Requirement: Response delay
Before sending a response that a scenario produced, the system SHALL wait for that scenario's delay when it is greater than 0, and otherwise for the service's delay. Waiting for one request SHALL NOT postpone the handling of any other request.

#### Scenario: Scenario delay overrides the service delay
- **GIVEN** a service with a delay of 100 ms and a selected scenario with a delay of 500 ms
- **WHEN** a client sends a request
- **THEN** the response is sent no sooner than 500 ms after the request arrived

#### Scenario: Service delay applies when the scenario has none
- **GIVEN** a service with a delay of 100 ms and a selected scenario with a delay of 0
- **WHEN** a client sends a request
- **THEN** the response is sent no sooner than 100 ms after the request arrived

#### Scenario: Other requests are not held up
- **GIVEN** one request is waiting for a delay of 5,000 ms
- **WHEN** a second client sends a request to a service with no delay
- **THEN** the second response is sent without waiting for the first
