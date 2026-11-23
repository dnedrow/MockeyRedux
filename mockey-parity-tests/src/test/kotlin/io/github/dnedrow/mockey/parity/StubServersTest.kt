package io.github.dnedrow.mockey.parity

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubServersTest {
    @Test
    fun upstreamStubRecordsRequests() {
        StubHttpServer.upstream().use { upstream ->
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:${upstream.port}/orders?id=7"))
                        .POST(HttpRequest.BodyPublishers.ofString("payload"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            assertEquals("upstream:/orders?id=7", response.body())
            val request = upstream.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/orders?id=7", request.target)
            assertEquals("payload", String(request.body))
        }
    }

    @Test
    fun outboundProxyStubRecordsProxiedRequests() {
        StubHttpServer.outboundProxy().use { proxy ->
            val client =
                HttpClient
                    .newBuilder()
                    .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))
                    .build()
            val response =
                client.send(
                    HttpRequest.newBuilder(URI("http://example.invalid/resource")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            assertEquals("outbound-proxy:http://example.invalid/resource", response.body())
            val request = proxy.requests.single()
            assertEquals("GET", request.method)
            assertTrue(request.target.endsWith("example.invalid/resource"), request.target)
        }
    }
}
