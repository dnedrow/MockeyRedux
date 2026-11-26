# request-routing Specification

## Purpose

Defines how a request arriving at the mock endpoint is mapped to a service: how the target is read from the path, how URL twisting rewrites it, how URL patterns match, which service wins when several match, and what happens when none does.

## Requirements

### Requirement: Mock endpoint path convention
The system SHALL treat every request on the mock listener whose path begins with `/service/` as mock traffic, for any HTTP method. The text after `/service/`, up to but excluding the query string, is the target. A target that begins with `http://` or `https://` is an absolute target. Any other target is a relative target.

#### Scenario: Absolute target with a query string
- **WHEN** a client sends `GET /service/http://api.example.com/users?page=2`
- **THEN** the target is the absolute target `http://api.example.com/users`, including both slashes after the scheme, and `page=2` is a request parameter

#### Scenario: Relative target
- **WHEN** a client sends `POST /service/customer/123`
- **THEN** the target is the relative target `customer/123`

### Requirement: Target normalization for matching
Before matching, the system SHALL percent-decode the target and ignore one leading and one trailing slash. It SHALL compare literal text without regard to letter case. For an absolute target it SHALL treat an omitted port as the default port of the scheme.

#### Scenario: Default port is implied
- **WHEN** the target is `http://api.example.com:80/users` and a service has the pattern `http://api.example.com/users`
- **THEN** the target matches the pattern

#### Scenario: Letter case and trailing slash are ignored
- **WHEN** the target is `Customer/123/` and a service has the pattern `customer/{id}`
- **THEN** the target matches the pattern

### Requirement: URL pattern matching
A variable in a URL pattern is a name in braces. A pattern SHALL match a target only when the whole target matches, where each variable matches one or more characters other than `/` and all other text matches literally. A match SHALL yield the value captured for each variable, in the letter case in which the request wrote it.

#### Scenario: A variable matches one segment
- **WHEN** the target `customer/Ab12` is matched against the pattern `customer/{customerId}`
- **THEN** the pattern matches, and `customerId` has the value `Ab12`

#### Scenario: A variable does not span segments
- **WHEN** the target `id/23/otherid/23/test` is matched against the pattern `id/{ID}/test`
- **THEN** the pattern does not match

#### Scenario: The whole target must match
- **WHEN** the target `customer/123/orders` is matched against the pattern `customer/{id}`
- **THEN** the pattern does not match

#### Scenario: Several variables
- **WHEN** the target `http://example.com/hotels/1/bookings/42` is matched against the pattern `http://example.com/hotels/{hotel}/bookings/{booking}`
- **THEN** the pattern matches, with `hotel` equal to `1` and `booking` equal to `42`

### Requirement: Service lookup by mock URL and real URLs
The system SHALL consider a service a candidate for a target when the target matches the service's mock URL pattern or matches any of the service's real URLs treated as patterns.

#### Scenario: Match through the mock URL pattern
- **GIVEN** a service with the mock URL pattern `customer/{id}`
- **WHEN** a client sends a request to `/service/customer/7`
- **THEN** that service is a candidate

#### Scenario: Match through a real URL
- **GIVEN** a service with the mock URL pattern `customer/{id}` and the real URL `http://api.example.com/customers/{id}`
- **WHEN** a client sends a request to `/service/http://api.example.com/customers/7`
- **THEN** that service is a candidate

### Requirement: The most specific service is selected
When more than one service is a candidate, the system SHALL select the one whose matching pattern has the fewest variables. Among those it SHALL select the pattern with the most literal characters, and among those the service that was created first.

#### Scenario: A literal segment beats a variable
- **GIVEN** service A with the pattern `api/cid/{customerId}/feature/summary` and service B with the pattern `api/cid/{customerId}/feature/{featureId}`
- **WHEN** a client sends a request to `/service/api/cid/42/feature/summary`
- **THEN** service A is selected

#### Scenario: The variable pattern still serves other values
- **GIVEN** the same services A and B
- **WHEN** a client sends a request to `/service/api/cid/42/feature/details`
- **THEN** service B is selected

### Requirement: Tag filter on routing
When a tag filter is active, the system SHALL select a candidate service only if the service or at least one of its scenarios carries at least one of the filter's tags. When the filter excludes every candidate, the system SHALL handle the request as if no service matched.

#### Scenario: Service without the filter tag is ignored
- **GIVEN** the tag filter is `regression`, and the only candidate service and its scenarios carry no such tag
- **WHEN** a client sends a request to that service's relative mock URL
- **THEN** the request is handled as an unknown relative target

#### Scenario: A scenario tag is enough
- **GIVEN** the tag filter is `regression`, and one scenario of the candidate service is tagged `regression`
- **WHEN** a client sends a request to that service's mock URL
- **THEN** the service is selected

### Requirement: Unknown absolute targets are forwarded
When no service is selected for an absolute target and handling of unknown targets is enabled, the system SHALL forward the request to that target as a proxy-mode request. When handling of unknown targets is disabled, the system SHALL respond with status 404 and SHALL NOT contact the target.

#### Scenario: Unknown target is forwarded
- **GIVEN** no service matches `http://api.example.com/v1/customers`, and handling of unknown targets is enabled
- **WHEN** a client sends `GET /service/http://api.example.com/v1/customers?active=true`
- **THEN** the request is forwarded to `http://api.example.com/v1/customers?active=true`, and the upstream response is returned

#### Scenario: Handling disabled
- **GIVEN** no service matches the absolute target, and handling of unknown targets is disabled
- **WHEN** a client sends a request to that target through the mock endpoint
- **THEN** the response has status 404, and no request reaches the target

### Requirement: Unknown absolute targets are registered as services
When the system forwards a request for an unknown absolute target and no existing service has that target as its mock URL pattern, it SHALL first create a proxy-mode service. The mock URL pattern and the only real URL of that service SHALL be the target without its query string. The service SHALL be named by the derived-name rule and SHALL carry the tags of the active tag filter.

#### Scenario: A service is registered for the new target
- **GIVEN** no service matches `http://api.example.com/v1/customers`
- **WHEN** a client sends `GET /service/http://api.example.com/v1/customers?active=true`
- **THEN** a service named `customers (auto generated)` exists whose mock URL pattern and real URL are `http://api.example.com/v1/customers`

#### Scenario: No second service for a covered URL
- **GIVEN** a service has the mock URL pattern `http://api.example.com/v1/customers` and is excluded by the active tag filter
- **WHEN** a client sends a request to that target through the mock endpoint
- **THEN** the request is forwarded, and no additional service is created

### Requirement: Unknown relative targets are refused
When no service is selected for a relative target, the system SHALL respond with status 404 and a body stating that no service matches the target. It SHALL NOT create a service and SHALL NOT contact any upstream host.

#### Scenario: Unknown relative target
- **GIVEN** no service matches the relative target `foo/bar`
- **WHEN** a client sends `GET /service/foo/bar`
- **THEN** the response has status 404, its body names `foo/bar`, and the set of services is unchanged

### Requirement: Twist configurations
A twist configuration SHALL have a stable identifier, a name, and an ordered list of pairs, each consisting of an origination text and a destination text. At most one twist configuration SHALL be enabled at any time.

#### Scenario: Enabling one configuration disables the other
- **GIVEN** twist configuration `to-qa2` is enabled
- **WHEN** twist configuration `to-sandbox` is enabled
- **THEN** `to-sandbox` is the only enabled twist configuration

### Requirement: URL twisting before lookup
When a twist configuration is enabled, the system SHALL find, before service lookup, the first pair whose origination text occurs in the target and SHALL replace every occurrence of that text with the pair's destination text. Both texts SHALL be treated literally, and no later pair SHALL be applied.

#### Scenario: Only the first matching pair applies
- **GIVEN** an enabled twist configuration with the pairs `qa1` to `qa2` and `example.com` to `example.org`, in that order
- **WHEN** a client sends a request to `/service/http://qa1.example.com/orders`
- **THEN** service lookup and any forwarding use `http://qa2.example.com/orders`

#### Scenario: Origination text is literal
- **GIVEN** an enabled twist configuration with the pair `a.b` to `c`
- **WHEN** a client sends a request whose target contains `axb` but not `a.b`
- **THEN** the target is unchanged

#### Scenario: No twist configuration enabled
- **WHEN** a client sends a request while no twist configuration is enabled
- **THEN** the target is used as received
