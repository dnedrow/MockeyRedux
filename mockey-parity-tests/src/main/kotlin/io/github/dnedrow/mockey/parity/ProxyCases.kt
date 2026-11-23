package io.github.dnedrow.mockey.parity

import java.nio.charset.StandardCharsets

/**
 * Proxy-mode cases recorded from legacy Mockey against the stub upstream and the stub outbound proxies.
 * Fixtures live in src/test/resources/parity-fixtures. The stub routes are described in [StubRoutes].
 */
object ProxyCases {
    const val UPSTREAM_PORT = RoutingCases.UPSTREAM_PORT

    /** An outbound proxy that forwards nothing: it answers with a description of the request it received. */
    const val OUTBOUND_PROXY_PORT = 18092

    /** An outbound proxy that answers 407 until a request carries credentials. */
    const val AUTHENTICATING_PROXY_PORT = 18093

    /** The stub upstream as a mock endpoint target. */
    private const val UPSTREAM = "/service/http://127.0.0.1:$UPSTREAM_PORT/stub"

    /** A loopback port on which nothing listens, so that a connection is refused at once. */
    private const val REFUSED = "/service/http://127.0.0.1:1"

    private val text = mapOf("Content-Type" to "text/plain")

    val groups: List<ParityCaseGroup> =
        listOf(
            parityCaseGroup("proxy-forwarding") {
                get("proxy-method-get", "$UPSTREAM/echo")
                case("proxy-method-post", "POST", "$UPSTREAM/echo", text, "posted".toByteArray())
                case("proxy-method-put", "PUT", "$UPSTREAM/echo", text, "put body".toByteArray())
                case("proxy-method-patch", "PATCH", "$UPSTREAM/echo", text, "patch body".toByteArray())
                case("proxy-method-delete", "DELETE", "$UPSTREAM/echo")
                case("proxy-method-head", "HEAD", "$UPSTREAM/echo")
                case("proxy-method-options", "OPTIONS", "$UPSTREAM/echo")
                case("proxy-method-trace", "TRACE", "$UPSTREAM/echo")
                get("proxy-query-forwarded", "$UPSTREAM/echo?page=2")
                get("proxy-query-order-and-encoding", "$UPSTREAM/echo?b=2&a=1&name=jane%20doe")
                get("proxy-query-repeated-parameter", "$UPSTREAM/echo?id=1&id=2")
                get("proxy-query-empty-value", "$UPSTREAM/echo?flag=&x=1")
                get(
                    "proxy-headers-application",
                    "$UPSTREAM/echo",
                    mapOf("Authorization" to "Bearer parity-token", "X-Request-Id" to "7", "Accept" to "application/json"),
                )
                get(
                    "proxy-headers-conditional-and-encoding",
                    "$UPSTREAM/echo",
                    mapOf(
                        "If-None-Match" to "\"v1\"",
                        "If-Modified-Since" to "Mon, 23 Nov 2026 00:00:00 GMT",
                        "Accept-Encoding" to "gzip",
                    ),
                )
                get("proxy-headers-client-cookie", "$UPSTREAM/echo", mapOf("Cookie" to "local=1"))
                get(
                    "proxy-headers-hop-by-hop",
                    "$UPSTREAM/echo",
                    mapOf("TE" to "trailers", "Keep-Alive" to "timeout=5", "Trailer" to "X-Checksum"),
                )
                post("proxy-body-text", "$UPSTREAM/echo", "plain text body")
                case(
                    "proxy-body-utf8",
                    "POST",
                    "$UPSTREAM/echo",
                    mapOf("Content-Type" to "text/plain; charset=utf-8"),
                    "café €".toByteArray(StandardCharsets.UTF_8),
                )
                case(
                    "proxy-body-binary",
                    "POST",
                    "$UPSTREAM/echo",
                    mapOf("Content-Type" to "application/octet-stream"),
                    ByteArray(256) { it.toByte() },
                )
                post("proxy-body-form", "$UPSTREAM/echo", "a=1&b=two+words", "application/x-www-form-urlencoded")
                case("proxy-body-empty-post", "POST", "$UPSTREAM/echo")
                get("proxy-delay-service", "$UPSTREAM/echo/delayed", timing = ParityTiming(atLeastMillis = 300))
                get("proxy-relative-target", "/service/relative-proxy.invalid/stub/echo")
                get("proxy-relative-target-default-real-url", "/service/relative-second.invalid/stub/echo")
            },
            parityCaseGroup("proxy-relay") {
                get("proxy-relay-status-404", "$UPSTREAM/status/404")
                get("proxy-relay-status-500", "$UPSTREAM/status/500")
                get("proxy-relay-headers", "$UPSTREAM/headers")
                get("proxy-relay-binary", "$UPSTREAM/binary")
                get("proxy-relay-text-latin1", "$UPSTREAM/text/latin1")
                get("proxy-relay-text-undeclared", "$UPSTREAM/text/undeclared")
            },
            parityCaseGroup("proxy-session") {
                val login = listOf(request("GET", "$UPSTREAM/cookie/set"))
                get("proxy-session-set-cookie", "$UPSTREAM/cookie/set")
                get("proxy-session-shared-cookie", "$UPSTREAM/cookie/echo", before = login)
                get("proxy-session-client-cookie", "$UPSTREAM/cookie/echo", mapOf("Cookie" to "local=1"), before = login)
                get("proxy-session-cookie-path", "$UPSTREAM/echo", before = login)
            },
            parityCaseGroup("proxy-redirects") {
                get("proxy-redirect-get-301", "$UPSTREAM/redirect/301")
                get("proxy-redirect-get-302", "$UPSTREAM/redirect/302")
                get("proxy-redirect-get-303", "$UPSTREAM/redirect/303")
                get("proxy-redirect-get-307", "$UPSTREAM/redirect/307")
                case("proxy-redirect-post-302", "POST", "$UPSTREAM/redirect/302", text, "posted".toByteArray())
                case("proxy-redirect-post-303", "POST", "$UPSTREAM/redirect/303", text, "posted".toByteArray())
                case("proxy-redirect-post-307", "POST", "$UPSTREAM/redirect/307", text, "posted".toByteArray())
                case("proxy-redirect-head-302", "HEAD", "$UPSTREAM/redirect/302")
                get("proxy-redirect-loop", "$UPSTREAM/redirect/loop")
            },
            parityCaseGroup("proxy-failures") {
                get("proxy-failure-refused", "$REFUSED/refused")
                get("proxy-failure-refused-error-scenario", "$REFUSED/refused-with-error-scenario")
                get("proxy-failure-connection-closed", "$UPSTREAM/close")
                get("proxy-failure-unknown-host", "/service/http://unknown-host.invalid/anything")
                get("proxy-failure-delay", "$REFUSED/refused-delayed", timing = ParityTiming(atLeastMillis = 300))
            },
            parityCaseGroup("proxy-failures-universal") {
                get("proxy-failure-universal-error", "$REFUSED/refused-universal")
                get("proxy-failure-service-error-first", "$REFUSED/refused-service-first")
            },
            parityCaseGroup("proxy-outbound") {
                get("proxy-outbound-get", "$UPSTREAM/echo?via=proxy")
                post("proxy-outbound-post", "$UPSTREAM/echo", "through the proxy")
            },
            parityCaseGroup(
                "proxy-outbound-credentials",
                OutboundProxyCredentials("http://127.0.0.1:$AUTHENTICATING_PROXY_PORT", "parity-user", "parity-pass"),
            ) {
                get("proxy-outbound-credentials", "$UPSTREAM/echo")
            },
            parityCaseGroup("proxy-outbound-unreachable") {
                get("proxy-outbound-unreachable", "$UPSTREAM/echo")
            },
            parityCaseGroup("proxy-outbound-disabled") {
                get("proxy-outbound-disabled", "$UPSTREAM/echo")
            },
        )

    val all: Map<String, ParityCase> = casesOf(groups)
}
