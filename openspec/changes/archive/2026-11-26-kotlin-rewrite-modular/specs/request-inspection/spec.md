# Spec Delta

## Purpose

Defines the JSON request-rule format and how rules are evaluated, how a service uses a rule set to inspect incoming requests, and how additional inspectors can be plugged in. Dynamic scenario matching uses the same rule evaluation.

## ADDED Requirements

### Requirement: Rule set format
A well-formed rule set SHALL be a JSON object whose members are any of the groups `parameters`, `headers`, `body`, and `url`, each an array of rules. A rule SHALL have a `value_rule_type` of `string_required`, `regex_required`, or `regex_optional` (in any letter case), a `value_rule_arg`, and an optional `desc`. A rule in `parameters` or `headers` SHALL also have a `key`.

#### Scenario: Well-formed rule set
- **WHEN** a rule set has a `headers` rule with `key`, `value_rule_arg`, and `value_rule_type`, and a `url` rule with `value_rule_arg` and `value_rule_type`
- **THEN** the rule set is well formed

#### Scenario: Header rule without a key
- **WHEN** a rule set has a `headers` rule with no `key`
- **THEN** the rule set is not well formed, and the reason names the missing `key`

#### Scenario: Unknown rule type
- **WHEN** a rule has the `value_rule_type` `number_required`
- **THEN** the rule set is not well formed

### Requirement: Regular expressions must compile
A rule set that contains a `regex_required` or `regex_optional` rule whose `value_rule_arg` is not a valid regular expression SHALL NOT be well formed.

#### Scenario: Invalid expression
- **WHEN** a rule has the type `regex_required` and the `value_rule_arg` `([a-z`
- **THEN** the rule set is not well formed, and the reason names the invalid expression

### Requirement: What each group evaluates
The system SHALL evaluate `parameters` rules against the request's query and form parameters, `headers` rules against every request header as received, `body` rules against the request body, and `url` rules against the full URL the client addressed, without its query string.

#### Scenario: URL rule sees the mock endpoint URL
- **GIVEN** a `url` rule of type `regex_required` with the argument `\b123\b`
- **WHEN** a client sends a request to `http://mockey.test:8080/service/customer/123`
- **THEN** the rule is satisfied

#### Scenario: Header rule sees every header
- **GIVEN** a `headers` rule with the key `Cookie` of type `string_required` with the argument `session=`
- **WHEN** a client sends a request with the header `Cookie: session=abc`
- **THEN** the rule is satisfied

#### Scenario: Parameter rule sees form fields
- **GIVEN** a `parameters` rule with the key `ticker` of type `string_required` with the argument `goog`
- **WHEN** a client posts a URL-encoded form that contains `ticker=GOOG`
- **THEN** the rule is satisfied

### Requirement: Keyed rules
A rule with a `key` SHALL be evaluated against the values of the parameter or header whose name equals the key without regard to letter case. A rule whose `key` is `*` SHALL be evaluated against the values of all parameters or all headers.

#### Scenario: Key comparison ignores case
- **GIVEN** a `headers` rule with the key `x-http-method-override` of type `string_required` with the argument `PUT`
- **WHEN** a client sends a request with the header `X-HTTP-Method-Override: PUT`
- **THEN** the rule is satisfied

#### Scenario: Wildcard key
- **GIVEN** a `parameters` rule with the key `*` of type `string_required` with the argument `888`
- **WHEN** a client sends a request with the query string `a=1&b=888`
- **THEN** the rule is satisfied

### Requirement: Rule type string_required
A `string_required` rule SHALL be satisfied when at least one evaluated value contains the `value_rule_arg`, ignoring letter case and surrounding whitespace. With an empty `value_rule_arg` it SHALL be satisfied when at least one value is present. It SHALL fail when there is no value to evaluate.

#### Scenario: Value contains the argument
- **GIVEN** a `parameters` rule with the key `ticker` of type `string_required` with the argument `g`
- **WHEN** a client sends a request with the query string `ticker=GOOG`
- **THEN** the rule is satisfied

#### Scenario: Empty argument requires presence only
- **GIVEN** a `headers` rule with the key `page_id` of type `string_required` with an empty argument
- **WHEN** a client sends a request with the header `page_id: home`
- **THEN** the rule is satisfied

#### Scenario: Absent key fails a required rule
- **GIVEN** a `headers` rule with the key `page_id` of type `string_required`
- **WHEN** a client sends a request without a `page_id` header
- **THEN** the rule fails

### Requirement: Rule type regex_required
A `regex_required` rule SHALL be satisfied when the regular expression in `value_rule_arg` is found in at least one evaluated value. It SHALL fail when there is no value to evaluate.

#### Scenario: Expression found
- **GIVEN** a `body` rule of type `regex_required` with the argument `"amount":\s*\d+`
- **WHEN** a client posts the body `{"amount": 25}`
- **THEN** the rule is satisfied

#### Scenario: Nothing to evaluate
- **GIVEN** a `body` rule of type `regex_required`
- **WHEN** a client sends a request without a body
- **THEN** the rule fails

### Requirement: Rule type regex_optional
A `regex_optional` rule SHALL be satisfied when there is no value to evaluate. When there is a value, it SHALL be satisfied only when the regular expression in `value_rule_arg` is found in at least one evaluated value.

#### Scenario: Absent optional value
- **GIVEN** a `parameters` rule with the key `date` of type `regex_optional` with the argument `^\d{2}/\d{2}/\d{4}$`
- **WHEN** a client sends a request without a `date` parameter
- **THEN** the rule is satisfied

#### Scenario: Present value must match
- **GIVEN** the same rule
- **WHEN** a client sends a request with the query string `date=tomorrow`
- **THEN** the rule fails

### Requirement: Group and rule set satisfaction
A group SHALL be satisfied when every rule in it is satisfied. A rule set SHALL be satisfied when at least one of its groups that contains a rule is satisfied. A rule set without any rule SHALL NOT be satisfied.

#### Scenario: One satisfied group is enough
- **GIVEN** a rule set whose `headers` group fails and whose `url` group is satisfied
- **WHEN** the rule set is evaluated
- **THEN** the rule set is satisfied

#### Scenario: All rules of a group must hold
- **GIVEN** a rule set with only a `parameters` group of two rules, one satisfied and one failed
- **WHEN** the rule set is evaluated
- **THEN** the rule set is not satisfied

### Requirement: Stored rule sets that cannot be read
A stored rule set that is not well formed SHALL never be satisfied. When such a rule set is enabled for service-level inspection, the system SHALL attach to the history entry a message stating that the rules of that service could not be read.

#### Scenario: Imported scenario with unreadable rules
- **GIVEN** an imported scenario whose rule-set criterion is not valid JSON
- **WHEN** a client sends a request to its dynamic-mode service
- **THEN** that scenario does not match

### Requirement: Service-level inspection
When a service's rule set is enabled, the system SHALL evaluate every request routed to the service against it and SHALL attach one message per failed rule to the request's history entry. Each message SHALL state the group, the key where the rule has one, what was required, and the rule's description. Inspection SHALL NOT change or delay the response.

#### Scenario: Failed rule is recorded and the response is unaffected
- **GIVEN** a service with an enabled rule set that requires a `page_id` header, described as `page_id is mandatory`
- **WHEN** a client sends a request without that header
- **THEN** the client receives the same response as it would without inspection, and the history entry carries a message that names the `headers` group, the key `page_id`, and the description `page_id is mandatory`

#### Scenario: Disabled rule set is not evaluated
- **GIVEN** a service whose rule set is present but not enabled
- **WHEN** a client sends a request that would fail the rules
- **THEN** the history entry carries no inspection message

### Requirement: Pluggable inspectors
The system SHALL discover installed inspectors at startup. An inspector that declares itself global SHALL run for every routed request, and any other inspector SHALL run only for services that name it. The system SHALL attach each inspector's non-empty result message to the history entry, without duplicates.

#### Scenario: Global inspector runs for every service
- **GIVEN** an installed inspector that declares itself global and reports `missing trace header`
- **WHEN** clients send requests to two different services
- **THEN** both history entries carry the message `missing trace header`

#### Scenario: Named inspector runs only where it is named
- **GIVEN** an installed non-global inspector named `session-check`, named by service A but not by service B
- **WHEN** clients send requests to services A and B
- **THEN** only the history entry for service A carries a message from `session-check`

### Requirement: Inspector failures are isolated
A failure inside an inspector SHALL NOT change or prevent the response. The system SHALL attach to the history entry a message that names the inspector that failed.

#### Scenario: Inspector throws an error
- **GIVEN** an installed global inspector that fails for every request
- **WHEN** a client sends a request
- **THEN** the client receives the normal response, and the history entry carries a message naming the failed inspector

### Requirement: Listing installed inspectors
The system SHALL list the installed inspectors, giving each inspector's name and whether it is global.

#### Scenario: Inspectors are listed
- **GIVEN** two inspectors are installed, one of them global
- **WHEN** the list of inspectors is requested
- **THEN** the list contains both names and marks one as global
