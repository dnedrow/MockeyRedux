package io.github.dnedrow.mockey.parity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun sendsTheRequestsBeforeTheRecordedOneInOrder() {
        StubHttpServer.upstream().use { server ->
            val directory = Files.createTempDirectory("mockey-parity-").resolve("sequence")
            val case = sequenceCase()

            ParityRecorder().record(case, server.baseUrl(), directory)

            assertEquals(listOf("/first", "/second", "/third"), server.requests.map { it.target })
            assertContentEquals("upstream:/third".toByteArray(), ParityCaseStore().read(directory).expectedResponse?.body)

            assertTrue(ParityReplayer().replay(directory, server.baseUrl()).matches)
            assertEquals(listOf("/first", "/second", "/third", "/first", "/second", "/third"), server.requests.map { it.target })
        }
    }

    @Test
    fun storesTheRequestsBeforeTheRecordedOne() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("sequence")
        val case = sequenceCase().copy(expectedResponse = ParityResponse(200, emptyMap(), ByteArray(0)))
        val store = ParityCaseStore()

        store.write(case, directory)

        val before = store.read(directory).before
        assertEquals(listOf("/first", "/second"), before.map { it.target })
        assertEquals("POST", before[1].method)
        assertEquals(mapOf("X-Step" to listOf("2")), before[1].headers)
        assertContentEquals("two".toByteArray(), before[1].body)

        store.write(case.copy(before = case.before.take(1)), directory)
        assertEquals(listOf("/first"), store.read(directory).before.map { it.target })

        store.write(case.copy(before = emptyList()), directory)
        assertTrue(store.read(directory).before.isEmpty())
    }

    @Test
    fun sendsEveryRequestOverHttp11WithAFixedUserAgent() {
        StubHttpServer.upstream().use { server ->
            val directory = Files.createTempDirectory("mockey-parity-").resolve("client")
            val case = ParityCase(ParityCase.UNREVIEWED, "<mockey/>", ParityRequest("GET", "/probe"))

            ParityRecorder().record(case, server.baseUrl(), directory)
            ParityRecorder().record(
                case.copy(request = ParityRequest("GET", "/probe", mapOf("user-agent" to listOf("custom")))),
                server.baseUrl(),
                directory,
            )

            val (default, custom) = server.requests.map { request -> request.headers.mapKeys { it.key.lowercase() } }
            assertEquals(listOf(ParityHttp.USER_AGENT), default["user-agent"])
            assertFalse("upgrade" in default, "an HTTP/2 upgrade was offered")
            assertFalse("http2-settings" in default, "an HTTP/2 upgrade was offered")
            assertEquals(listOf("custom"), custom["user-agent"])
        }
    }

    @Test
    fun storesTheStatedResponseOfTheNewServer() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("stated")
        val store = ParityCaseStore()
        val headers = mapOf("Content-Type" to listOf("text/plain"), "Set-Cookie" to listOf("a=1", "b=2"))
        val statements =
            listOf(
                NewResponse(201, headers, ExpectedBody.Exact(byteArrayOf(0, -1, 10)), ParityTiming(atLeastMillis = 1500)),
                NewResponse(200, body = ExpectedBody.Json("""{"result": {"planid": "<any>", "text": "caf\u00e9 = 1:2 #3"}}""")),
                NewResponse(404, headers, ExpectedBody.Contains(listOf("foo/bar", " leading space", "second = line"))),
                NewResponse(404, timing = ParityTiming(atLeastMillis = 100, lessThanMillis = 900)),
            )

        statements.forEach { stated ->
            store.write(reviewedCase("DEV-06 DEV-13", stated), directory)

            val read = store.read(directory)
            assertEquals("DEV-06 DEV-13", read.label)
            assertEquals(stated, read.newResponse)
        }

        store.write(reviewedCase(ParityCase.SAME, newResponse = null), directory)
        assertNull(store.read(directory).newResponse)
        assertFalse(Files.exists(directory.resolve("expected-new-response.properties")))
    }

    @Test
    fun storesAStatedBodyAsTextWhereAPersonCanReadIt() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("stated")
        val store = ParityCaseStore()
        val stated = directory.resolve("expected-new-response.properties")
        val bodies =
            mapOf(
                "gold customer" to "body.text=gold customer",
                "" to "body.text=",
                " two\nlines\t= caf\u00e9 \u20ac " to "body.text=\\ two\\nlines\\t\\= caf\u00e9 \u20ac ",
            )

        bodies.forEach { (body, line) ->
            store.write(reviewedCase("DEV-06", NewResponse(200, body = ExpectedBody.Exact(body.toByteArray()))), directory)

            assertTrue(line in Files.readAllLines(stated), Files.readString(stated))
            assertEquals(ExpectedBody.Exact(body.toByteArray()), store.read(directory).newResponse?.body)
        }

        listOf(byteArrayOf(0, 1, 2), byteArrayOf(99, 97, 102, -23), "bell\u0007".toByteArray()).forEach { bytes ->
            store.write(reviewedCase("DEV-06", NewResponse(200, body = ExpectedBody.Exact(bytes))), directory)

            assertTrue(Files.readAllLines(stated).any { it.startsWith("body=") }, Files.readString(stated))
            assertEquals(ExpectedBody.Exact(bytes), store.read(directory).newResponse?.body)
        }

        Files.writeString(stated, "status=200\nbody.text=written by hand\n")
        assertEquals(ExpectedBody.Exact("written by hand".toByteArray()), store.read(directory).newResponse?.body)
    }

    @Test
    fun refusesAStatedResponseThatSaysNothingOrTwoThingsAboutItsBody() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("stated")
        val store = ParityCaseStore()
        store.write(reviewedCase("DEV-06", NewResponse(200, body = ExpectedBody.Json("{}"))), directory)
        val stated = directory.resolve("expected-new-response.properties")

        Files.writeString(stated, "status=200\n")
        assertNull(store.readOrNull(directory))
        assertFailsWith<IllegalStateException> { store.read(directory) }

        Files.writeString(stated, "status=200\nbody.json={}\nbody.unspecified=true\n")
        assertFailsWith<IllegalStateException> { store.read(directory) }
    }

    @Test
    fun storesASnapshotMark() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("snapshot")
        val store = ParityCaseStore()

        store.write(reviewedCase(ParityCase.UNREVIEWED, newResponse = null).copy(snapshot = true), directory)
        assertTrue(store.read(directory).snapshot)

        store.write(reviewedCase(ParityCase.UNREVIEWED, newResponse = null), directory)
        assertFalse(store.read(directory).snapshot)
    }

    @Test
    fun writesTheSameBytesForTheSameRecording() {
        val directory = Files.createTempDirectory("mockey-parity-").resolve("stable")
        val stated = NewResponse(200, body = ExpectedBody.Contains(listOf("one")))
        val case = reviewedCase("DEV-06", stated).copy(timing = ParityTiming(10, 20, 15))
        val store = ParityCaseStore()

        store.write(case, directory)
        val first = contentOf(directory)
        Thread.sleep(MORE_THAN_A_SECOND)
        store.write(case, directory)

        assertEquals(first, contentOf(directory))
        val request = first.getValue("request.properties")
        assertTrue(request.startsWith("# Mockey parity request\nbody=\nmethod=GET\ntarget="), request)
    }

    @Test
    fun keepsTheReviewOfARecordingThatDidNotChange() {
        val body = AtomicReference("answer")
        val server = answeringServer(body)
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("reviewed")
            val case = ParityCase(ParityCase.UNREVIEWED, "<mockey/>", ParityRequest("GET", "/service/answer"))
            val stated = NewResponse(200, mapOf("X-Stub" to listOf("1")), ExpectedBody.Exact("answer".toByteArray()))
            val recorder = ParityRecorder()
            val store = ParityCaseStore()

            assertEquals(ParityCase.UNREVIEWED, recorder.record(case, server.baseUrl(), directory).label)
            store.write(store.read(directory).copy(label = "DEV-06", newResponse = stated), directory)

            val again = recorder.record(case, server.baseUrl(), directory)
            assertEquals("DEV-06", again.label)
            assertEquals(stated, store.read(directory).newResponse)

            body.set("another answer")
            val changed = recorder.record(case, server.baseUrl(), directory)
            assertEquals(ParityCase.UNREVIEWED, changed.label)
            assertNull(store.read(directory).newResponse)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun dropsTheReviewWhenTheCaseItRestedOnChanged() {
        val server = answeringServer(AtomicReference("answer"))
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("reviewed")
            val case = ParityCase(ParityCase.UNREVIEWED, "<mockey/>", ParityRequest("GET", "/service/answer"))
            val recorder = ParityRecorder()
            val store = ParityCaseStore()
            val changes =
                listOf(
                    case.copy(fixtureXml = "<mockey version=\"2\"/>"),
                    case.copy(request = ParityRequest("GET", "/service/answer", mapOf("X-Tier" to listOf("gold")))),
                    case.copy(before = listOf(ParityRequest("GET", "/service/first"))),
                    case.copy(timing = ParityTiming(lessThanMillis = 60_000)),
                    case.copy(snapshot = true),
                )

            changes.forEach { changed ->
                recorder.record(case, server.baseUrl(), directory)
                store.write(store.read(directory).copy(label = ParityCase.SAME), directory)
                assertEquals(ParityCase.SAME, recorder.record(case, server.baseUrl(), directory).label)

                assertEquals(ParityCase.UNREVIEWED, recorder.record(changed, server.baseUrl(), directory).label, "$changed")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun replaysAgainstTheNewServerWhatTheReviewSays() {
        val body = AtomicReference("answer\n")
        val server = answeringServer(body)
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("reviewed")
            val case = ParityCase(ParityCase.UNREVIEWED, "<mockey/>", ParityRequest("GET", "/service/answer"))
            val store = ParityCaseStore()
            val replayer = ParityReplayer()
            ParityRecorder().record(case, server.baseUrl(), directory)
            val recorded = store.read(directory)

            val unreviewed = assertFailsWith<IllegalStateException> { replayer.replay(directory, server.baseUrl(), ParityServer.NEW) }
            assertTrue("is not reviewed" in unreviewed.message.orEmpty(), unreviewed.message)

            store.write(recorded.copy(label = ParityCase.SAME), directory)
            assertTrue(replayer.replay(directory, server.baseUrl(), ParityServer.NEW).matches)

            store.write(recorded.copy(label = "DEV-06"), directory)
            val unstated = assertFailsWith<IllegalStateException> { replayer.replay(directory, server.baseUrl(), ParityServer.NEW) }
            assertTrue("states no new response" in unstated.message.orEmpty(), unstated.message)

            val stated = NewResponse(200, mapOf("X-Stub" to listOf("1")), ExpectedBody.Exact("answer".toByteArray()))
            store.write(recorded.copy(label = "DEV-06", newResponse = stated), directory)
            assertEquals(
                listOf("body: expected 'answer' (6 bytes), got 'answer\n' (7 bytes)"),
                replayer.replay(directory, server.baseUrl(), ParityServer.NEW).differences,
            )
            assertTrue(replayer.replay(directory, server.baseUrl(), ParityServer.LEGACY).matches)

            body.set("answer")
            assertTrue(replayer.replay(directory, server.baseUrl(), ParityServer.NEW).matches)
            assertFalse(replayer.replay(directory, server.baseUrl(), ParityServer.LEGACY).matches)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun appliesTheTimingBoundsThatAreStatedForTheNewServer() {
        val server = answeringServer(AtomicReference("answer"))
        try {
            val directory = Files.createTempDirectory("mockey-parity-").resolve("timed")
            val request = ParityRequest("GET", "/service/answer")
            val case = ParityCase(ParityCase.UNREVIEWED, "<mockey/>", request, timing = ParityTiming(lessThanMillis = 60_000))
            val store = ParityCaseStore()
            ParityRecorder().record(case, server.baseUrl(), directory)
            val stated =
                NewResponse(
                    status = 200,
                    headers = mapOf("X-Stub" to listOf("1")),
                    body = ExpectedBody.Exact("answer".toByteArray()),
                    timing = ParityTiming(atLeastMillis = 60_000),
                )
            store.write(store.read(directory).copy(label = "DEV-42", newResponse = stated), directory)

            assertTrue(ParityReplayer().replay(directory, server.baseUrl(), ParityServer.LEGACY).matches)
            val difference = ParityReplayer().replay(directory, server.baseUrl(), ParityServer.NEW).differences.single()
            assertTrue(difference.startsWith("elapsed time: expected at least 60000 ms, got "), difference)
        } finally {
            server.stop(0)
        }
    }

    private fun reviewedCase(
        label: String,
        newResponse: NewResponse?,
    ) = ParityCase(
        label = label,
        fixtureXml = "<mockey/>",
        request = ParityRequest("GET", "/service/stated"),
        expectedResponse = ParityResponse(200, emptyMap(), ByteArray(0)),
        newResponse = newResponse,
    )

    private fun contentOf(directory: Path): Map<String, String> =
        Files.list(directory).use { files -> files.toList().associate { it.fileName.toString() to Files.readString(it) } }

    /** A server that answers every request with the current text of [body] and the header `X-Stub: 1`. */
    private fun answeringServer(body: AtomicReference<String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") {
                val bytes = body.get().toByteArray()
                it.responseHeaders.add("X-Stub", "1")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.use { output -> output.write(bytes) }
            }
            start()
        }

    private fun sequenceCase() =
        ParityCase(
            label = ParityCase.UNREVIEWED,
            fixtureXml = "<mockey/>",
            request = ParityRequest("GET", "/third"),
            before =
                listOf(
                    ParityRequest("GET", "/first"),
                    ParityRequest("POST", "/second", mapOf("X-Step" to listOf("2")), "two".toByteArray()),
                ),
        )

    private fun StubHttpServer.baseUrl() = URI("http://127.0.0.1:$port")

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

    private companion object {
        const val MORE_THAN_A_SECOND = 1100L
    }
}
