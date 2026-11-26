# Spec Delta

## Purpose

Defines how a request is forwarded to a real service in proxy mode: where it is sent, what is forwarded and relayed, how the outbound corporate proxy and the shared upstream session behave, and what the client receives when the upstream fails.

## ADDED Requirements

### Requirement: Forwarding an absolute target
In proxy mode the system SHALL forward a request that has an absolute target to that target as it stands after twisting, together with the request's query string.

#### Scenario: Absolute target is forwarded with its query
- **WHEN** a client sends `GET /service/http://api.example.com/users?page=2` and the request is handled in proxy mode
- **THEN** the upstream receives `GET /users?page=2` on `api.example.com`

### Requirement: Forwarding a relative target
In proxy mode the system SHALL forward a request that has a relative target to the service's default real URL, replacing each variable in that URL with the value captured for the variable of the same name and appending the request's query string. When the service has no real URL, or a variable has no captured value, the system SHALL respond with status 502 and an explanation and SHALL NOT contact any host.

#### Scenario: Captured values are substituted
- **GIVEN** a proxy-mode service with the mock URL pattern `customer/{id}` and the default real URL `http://api.example.com/customers/{id}`
- **WHEN** a client sends `GET /service/customer/Ab12?expand=orders`
- **THEN** the upstream receives `GET /customers/Ab12?expand=orders` on `api.example.com`

#### Scenario: No real URL
- **GIVEN** a proxy-mode service with a relative mock URL pattern and no real URLs
- **WHEN** a client sends a request to its mock URL
- **THEN** the response has status 502 and explains that the service has no real URL, and no upstream host is contacted

#### Scenario: Variable without a captured value
- **GIVEN** a proxy-mode service with the mock URL pattern `customer/{id}` and the default real URL `http://api.example.com/{region}/customers/{id}`
- **WHEN** a client sends `GET /service/customer/7`
- **THEN** the response has status 502 and names the variable `region`, and no upstream host is contacted

### Requirement: Method and body are forwarded unchanged
The system SHALL forward the request with the method the client used and with the request body byte for byte.

#### Scenario: Method is preserved
- **WHEN** a client sends a `PUT`, `PATCH`, or `DELETE` request that is handled in proxy mode
- **THEN** the upstream receives a request with the same method

#### Scenario: Binary body is preserved
- **WHEN** a client posts a body that contains bytes which are not valid text in any single charset
- **THEN** the upstream receives exactly the same bytes

### Requirement: Request headers forwarded upstream
The system SHALL forward the client's request headers except `Host`, `Content-Length`, `Accept-Encoding`, `Cookie`, `If-None-Match`, `If-Modified-Since`, and the hop-by-hop headers `Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, and `Upgrade`. It SHALL set `Host` and `Content-Length` to fit the forwarded request.

#### Scenario: Application headers pass through
- **WHEN** a client sends a proxied request with the headers `Authorization: Bearer abc` and `X-Request-Id: 7`
- **THEN** the upstream receives both headers with the same values

#### Scenario: Conditional and encoding headers are dropped
- **WHEN** a client sends a proxied request with the headers `If-None-Match`, `If-Modified-Since`, and `Accept-Encoding: gzip`
- **THEN** the upstream receives none of those three headers

### Requirement: Upstream response relayed to the client
The system SHALL return the upstream response's status code, headers, and body to the client, with the body byte for byte. It SHALL omit hop-by-hop headers and SHALL set `Content-Length` to fit the relayed body.

#### Scenario: Status and body are relayed
- **WHEN** the upstream answers a proxied request with status 404 and a JSON body
- **THEN** the client receives status 404 and the same body bytes

#### Scenario: Cookie attributes are kept
- **WHEN** the upstream response carries `Set-Cookie: sid=abc; Path=/; HttpOnly; Secure`
- **THEN** the client receives that `Set-Cookie` header with all of its attributes

### Requirement: Shared upstream session
The system SHALL keep the cookies set by upstream services in one cookie store shared by all proxied requests, and SHALL send the stored cookies that apply to the target with every forwarded request. It SHALL offer an operation that empties the store.

#### Scenario: A cookie from one client's exchange is sent for another client
- **GIVEN** an upstream set the cookie `sid=abc` in response to a proxied request from client A
- **WHEN** client B sends a proxied request to the same upstream host
- **THEN** the upstream receives the cookie `sid=abc`

#### Scenario: The client's own cookies are not forwarded
- **WHEN** a client sends a proxied request with the header `Cookie: local=1`
- **THEN** the upstream does not receive the cookie `local`

#### Scenario: Emptying the store
- **GIVEN** the cookie store holds the cookie `sid=abc`
- **WHEN** the store is emptied
- **THEN** the next forwarded request carries no stored cookie

### Requirement: Redirect handling
The system SHALL follow upstream redirects for `GET` and `HEAD` requests and return the final response. For any other method it SHALL return the redirect response to the client unchanged.

#### Scenario: GET follows a redirect
- **WHEN** the upstream answers a proxied `GET` with status 302 and a `Location` that answers with status 200
- **THEN** the client receives the status 200 response

#### Scenario: POST does not follow a redirect
- **WHEN** the upstream answers a proxied `POST` with status 302
- **THEN** the client receives the status 302 response with its `Location` header

### Requirement: Outbound proxy
When an outbound proxy is enabled, the system SHALL send every forwarded request through that proxy, for `http` and for `https` targets, and SHALL authenticate to the proxy with the configured credentials when credentials are set. When no outbound proxy is enabled, the system SHALL connect to targets directly.

#### Scenario: Request goes through the outbound proxy
- **GIVEN** an outbound proxy is enabled with a user name and password
- **WHEN** a request is forwarded to an `https` target
- **THEN** the connection is made through the proxy, and the proxy receives the configured credentials

#### Scenario: Direct connection when disabled
- **GIVEN** outbound proxy settings exist but are not enabled
- **WHEN** a request is forwarded
- **THEN** the connection is made directly to the target

### Requirement: Outbound proxy settings and credentials
Outbound proxy settings SHALL consist of a proxy URL, an enabled flag, and optional credentials. The system SHALL hold credentials in memory only and SHALL NOT write them to definition files or logs, or return the password in any response.

#### Scenario: Password is never disclosed
- **GIVEN** outbound proxy credentials are set
- **WHEN** the outbound proxy settings are read
- **THEN** the result shows the proxy URL, the enabled flag, and that credentials are set, and it does not contain the password

### Requirement: Upstream certificate verification
For `https` targets the system SHALL verify the upstream's certificate against the trust store of its runtime, and SHALL treat a verification failure as an upstream failure.

#### Scenario: Untrusted certificate
- **WHEN** a request is forwarded to an `https` target whose certificate is not trusted
- **THEN** no request data is sent to that target, and the upstream failure handling applies

### Requirement: Upstream timeouts
The system SHALL give up on a forwarded request when no connection is established within the configured connect timeout, or when no complete response arrives within the configured response timeout. It SHALL treat either case as an upstream failure.

#### Scenario: Upstream does not answer in time
- **GIVEN** a response timeout of 2 seconds
- **WHEN** the upstream accepts a forwarded request and sends nothing for 3 seconds
- **THEN** the system stops waiting, and the upstream failure handling applies

### Requirement: Error scenario fallback on upstream failure
When the upstream cannot be reached, fails certificate verification, exceeds a timeout, redirects without end, or does not return a complete response, the system SHALL respond with the service's error scenario if one is designated, and otherwise with the universal error scenario if one is designated. It SHALL use that scenario's status code, headers, and body.

#### Scenario: Service error scenario answers for a failed upstream
- **GIVEN** a proxy-mode service with an error scenario that returns status 503 and the body `backend down`
- **WHEN** a forwarded request cannot connect to the upstream
- **THEN** the client receives status 503 and the body `backend down`

### Requirement: Built-in response on upstream failure
When an upstream failure occurs and neither a service error scenario nor a universal error scenario is designated, the system SHALL respond with status 502 and a JSON object with a `fail` member that describes the failure and a `proxyInfo` member that states whether an outbound proxy is enabled and, if so, its host.

#### Scenario: No error scenario defined
- **GIVEN** no error scenario applies and no outbound proxy is enabled
- **WHEN** a forwarded request cannot connect to the upstream
- **THEN** the client receives status 502 and a JSON object whose `fail` member describes the failure and whose `proxyInfo` member states that no outbound proxy is enabled

### Requirement: Service delay on proxied responses
Before returning a proxied response or an upstream failure response, the system SHALL wait for the service's delay, without postponing the handling of other requests.

#### Scenario: Delay applied to a proxied response
- **GIVEN** a proxy-mode service with a delay of 300 ms
- **WHEN** the upstream answers a forwarded request immediately
- **THEN** the client receives the response no sooner than 300 ms after its request arrived
