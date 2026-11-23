package io.github.dnedrow.mockey.parity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParityHarnessTest {
    @Test
    fun recordsAndReplaysAResponse() {
        val server =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/status") {
                    val body = """{"status":"running"}""".toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.use { output -> output.write(body) }
                }
                start()
            }
        try {
            val directory = Files.createTempDirectory("mockey-parity-")
            val case =
                ParityCase(
                    label = "same",
                    fixtureXml = "<mockey/>",
                    request = ParityRequest("GET", "/status"),
                )
            val recorded =
                ParityRecorder().record(
                    case,
                    URI("http://127.0.0.1:${server.address.port}"),
                    directory.resolve("status"),
                )

            assertEquals(200, recorded.expectedResponse?.status)
            assertContentEquals("""{"status":"running"}""".toByteArray(), recorded.expectedResponse?.body)
            assertTrue(
                ParityReplayer()
                    .replay(
                        directory.resolve("status"),
                        URI("http://127.0.0.1:${server.address.port}"),
                    ).matches,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun replaysRecordedLegacyStatusCase() {
        val baseUrl = System.getProperty("mockey.parity.legacy.baseUrl") ?: return
        val recording = Path.of("src/test/resources/parity/status")
        val result = ParityReplayer().replay(recording, URI(baseUrl))
        assertTrue(result.matches, result.differences.joinToString())
    }
}
