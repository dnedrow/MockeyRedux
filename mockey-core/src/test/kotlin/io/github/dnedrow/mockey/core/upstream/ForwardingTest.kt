package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class ForwardingTest {
    private fun headers(vararg headers: Pair<String, String>) = headers.map { (name, value) -> Header(name, value) }

    @Test
    fun applicationHeadersAreForwardedInTheirOrder() {
        val sent = headers("Authorization" to "Bearer abc", "X-Request-Id" to "7", "Accept" to "application/json", "X-Request-Id" to "8")

        assertEquals(sent, forwardedHeaders(sent))
    }

    @Test
    fun theHeadersThatFitOnlyTheRequestOfTheClientAreNotForwarded() {
        val sent =
            headers(
                "Host" to "mockey.test:8080",
                "Content-Length" to "6",
                "Accept-Encoding" to "gzip",
                "Cookie" to "local=1",
                "If-None-Match" to "\"v1\"",
                "If-Modified-Since" to "Mon, 23 Nov 2026 00:00:00 GMT",
                "User-Agent" to "curl/8.7.1",
            )

        assertEquals(headers("User-Agent" to "curl/8.7.1"), forwardedHeaders(sent))
    }

    @Test
    fun hopByHopHeadersAreNotForwarded() {
        val sent =
            headers(
                "Connection" to "keep-alive",
                "Keep-Alive" to "timeout=5",
                "Proxy-Authenticate" to "Basic",
                "Proxy-Authorization" to "Basic dXNlcjpzZWNyZXQ=",
                "TE" to "trailers",
                "Trailer" to "X-Checksum",
                "Transfer-Encoding" to "chunked",
                "Upgrade" to "h2c",
                "Content-Type" to "text/plain",
            )

        assertEquals(headers("Content-Type" to "text/plain"), forwardedHeaders(sent))
    }

    @Test
    fun theNamesOfHeadersAreComparedWithoutRegardToLetterCase() {
        val sent = headers("host" to "mockey.test", "COOKIE" to "a=1", "accept-ENCODING" to "gzip", "te" to "trailers", "x-kept" to "1")

        assertEquals(headers("x-kept" to "1"), forwardedHeaders(sent))
    }

    @Test
    fun theForwardedRequestHasTheMethodAndTheBodyOfTheRequestUnchanged() {
        val body = ByteArray(256) { it.toByte() }
        val sent = headers("Host" to "mockey.test:8080", "Content-Type" to "application/octet-stream", "Content-Length" to "256")
        val request = MockRequest("PATCH", "http://mockey.test:8080/service/customer/7", "expand=orders", sent, body)

        val forwarded = upstreamRequest(request, "http://api.example.com/customers/7?expand=orders")

        assertEquals("PATCH", forwarded.method)
        assertEquals("http://api.example.com/customers/7?expand=orders", forwarded.url)
        assertEquals(headers("Content-Type" to "application/octet-stream"), forwarded.headers)
        assertContentEquals(body, forwarded.body)
    }

    @Test
    fun aRequestKeepsWhatItWasCreatedWith() {
        val sent = mutableListOf(Header("X-Trace", "7"))
        val request = UpstreamRequest("POST", "http://api.example.com/orders?token=secret", sent, byteArrayOf(1, 2, 3))

        sent += Header("X-Trace", "8")
        request.body[0] = 9

        assertEquals(listOf(Header("X-Trace", "7")), request.headers)
        assertContentEquals(byteArrayOf(1, 2, 3), request.body)
        assertNotSame(request.body, request.body)
        assertEquals("UpstreamRequest(POST http://api.example.com/orders)", request.toString())
        assertFalse("secret" in request.toString())
    }

    @Test
    fun theStatusTheHeadersAndTheBodyOfAnAnswerAreRelayed() {
        val body = """{"error":"not found"}""".toByteArray()
        val answer = MockResponse(404, headers("content-type" to "application/json", "content-length" to "21", "x-upstream" to "one"), body)

        val relayed = relayed(answer, "GET")

        assertEquals(404, relayed.status)
        assertEquals(headers("content-type" to "application/json", "content-length" to "21", "x-upstream" to "one"), relayed.headers)
        assertContentEquals(body, relayed.body)
    }

    @Test
    fun everyValueOfARepeatedHeaderIsRelayedAndACookieKeepsItsAttributes() {
        val sent =
            headers(
                "set-cookie" to "sid=abc; Path=/; HttpOnly; Secure",
                "set-cookie" to "theme=dark; Path=/; Max-Age=3600",
                "x-multi" to "a",
                "x-multi" to "b",
            )

        val relayed = relayed(MockResponse(204, sent), "GET")

        assertEquals(sent, relayed.headers)
    }

    @Test
    fun hopByHopHeadersOfAnAnswerAreLeftOut() {
        val sent =
            headers(
                "connection" to "close",
                "keep-alive" to "timeout=5",
                "proxy-authenticate" to "Basic realm=\"x\"",
                "te" to "trailers",
                "trailer" to "X-Checksum",
                "transfer-encoding" to "chunked",
                "upgrade" to "h2c",
                "content-type" to "text/plain",
            )

        val relayed = relayed(MockResponse(200, sent, "body".toByteArray()), "GET")

        assertEquals(headers("content-type" to "text/plain", "Content-Length" to "4"), relayed.headers)
    }

    @Test
    fun theContentLengthFitsTheBodyThatIsRelayed() {
        val body = "seven b".toByteArray()

        val added = relayed(MockResponse(200, headers("transfer-encoding" to "chunked", "x-a" to "1"), body), "GET")
        val kept = relayed(MockResponse(200, headers("content-length" to "7", "x-a" to "1"), body), "POST")
        val corrected = relayed(MockResponse(200, headers("x-a" to "1", "content-length" to "99", "Content-Length" to "98"), body), "GET")

        assertEquals(headers("x-a" to "1", "Content-Length" to "7"), added.headers)
        assertEquals(headers("content-length" to "7", "x-a" to "1"), kept.headers)
        assertEquals(headers("x-a" to "1", "content-length" to "7"), corrected.headers)
    }

    @Test
    fun theContentLengthStaysAsItCameWhereItDoesNotDescribeTheRelayedBody() {
        val toHead = relayed(MockResponse(200, headers("content-length" to "172", "content-type" to "text/plain")), "HEAD")
        val notModified = relayed(MockResponse(304, headers("etag" to "\"v1\"", "content-length" to "172")), "GET")
        val noContent = relayed(MockResponse(204, headers("x-a" to "1")), "DELETE")
        val headWithoutLength = relayed(MockResponse(200, headers("content-type" to "text/plain")), "HEAD")

        assertEquals(headers("content-length" to "172", "content-type" to "text/plain"), toHead.headers)
        assertEquals(headers("etag" to "\"v1\"", "content-length" to "172"), notModified.headers)
        assertEquals(headers("x-a" to "1"), noContent.headers)
        assertEquals(headers("content-type" to "text/plain"), headWithoutLength.headers)
    }

    @Test
    fun anEmptyBodyOfAnAnswerThatCanHaveOneHasTheContentLengthZero() {
        val relayed = relayed(MockResponse(200, headers("x-a" to "1")), "GET")

        assertEquals(headers("x-a" to "1", "Content-Length" to "0"), relayed.headers)
    }
}
