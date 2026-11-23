package io.github.dnedrow.mockey.parity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun recordsAndReplaysATimedResponse() {
        val server = delayedServer(delayMillis = 100)
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("delayed")
            val recorded = ParityRecorder().record(delayedCase(ParityTiming(atLeastMillis = 100)), server.baseUrl(), directory)

            val recordedMillis = assertNotNull(recorded.timing?.recordedMillis)
            assertTrue(recordedMillis >= 100, "recorded $recordedMillis ms")
            assertEquals(recorded.timing, ParityCaseStore().read(directory).timing)
            assertTrue(ParityReplayer().replay(directory, server.baseUrl()).matches)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun reportsAReplayOutsideTheTimingBounds() {
        val server = delayedServer(delayMillis = 0)
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("delayed")
            val recorded = ParityRecorder().record(delayedCase(timing = null), server.baseUrl(), directory)
            ParityCaseStore().write(recorded.copy(timing = ParityTiming(atLeastMillis = 60_000, recordedMillis = 60_001)), directory)

            val result = ParityReplayer().replay(directory, server.baseUrl())

            val difference = result.differences.single()
            assertTrue(difference.startsWith("elapsed time: expected at least 60000 ms, got "), difference)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun refusesToRecordAResponseOutsideTheTimingBounds() {
        val server = delayedServer(delayMillis = 0)
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("delayed")
            assertFailsWith<IllegalStateException> {
                ParityRecorder().record(delayedCase(ParityTiming(atLeastMillis = 60_000)), server.baseUrl(), directory)
            }
            assertFalse(Files.exists(directory))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun timingBoundsIncludeTheMinimumAndExcludeTheMaximum() {
        val timing = ParityTiming(atLeastMillis = 200, lessThanMillis = 1000)
        assertFalse(timing.allows(199))
        assertTrue(timing.allows(200))
        assertTrue(timing.allows(999))
        assertFalse(timing.allows(1000))
        assertTrue(ParityTiming().allows(0))
        assertEquals("200 ms to under 1000 ms", timing.describe())
        assertEquals("at least 0 ms", ParityTiming().describe())
    }

    @Test
    fun recordingWithoutTimingRemovesStoredTiming() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("delayed")
        val timed = delayedCase(ParityTiming(atLeastMillis = 100, recordedMillis = 101))
        val case = timed.copy(expectedResponse = ParityResponse(200, emptyMap(), ByteArray(0)))
        val store = ParityCaseStore()
        store.write(case, directory)
        assertEquals(case.timing, store.read(directory).timing)

        store.write(case.copy(timing = null), directory)

        assertNull(store.read(directory).timing)
    }

    private fun delayedCase(timing: ParityTiming?) =
        ParityCase(
            label = ParityCase.UNREVIEWED,
            fixtureXml = "<mockey/>",
            request = ParityRequest("GET", "/delayed"),
            timing = timing,
        )

    private fun delayedServer(delayMillis: Long): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/delayed") {
                Thread.sleep(delayMillis)
                val body = "delayed".toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.use { output -> output.write(body) }
            }
            start()
        }

    private fun HttpServer.baseUrl() = URI("http://127.0.0.1:${address.port}")
}
