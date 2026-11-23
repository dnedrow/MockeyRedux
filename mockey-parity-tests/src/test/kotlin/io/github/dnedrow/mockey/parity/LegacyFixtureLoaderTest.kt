package io.github.dnedrow.mockey.parity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyFixtureLoaderTest {
    private val fixture = Path.of("src/test/resources/parity-fixtures/responses-static.xml")

    @Test
    fun loadsAFixtureThroughTheInitializationEndpoint() {
        val queries = mutableListOf<String>()
        val server = homeServer(queries, """{"result":{"success":"Loaded definitions from a file"}}""")
        try {
            LegacyFixtureLoader(server.baseUrl()).load(fixture)

            val parameters =
                queries.single().split("&").associate {
                    it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
                }
            assertEquals(setOf("action", "transientState", "type", "file"), parameters.keys)
            assertEquals("init", parameters["action"])
            assertEquals("true", parameters["transientState"])
            assertEquals("json", parameters["type"])
            assertEquals(fixture.toAbsolutePath().toString(), parameters["file"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun failsWhenLegacyDoesNotReportSuccess() {
        val server = homeServer(mutableListOf(), """{"result":{"fail":"missing.xml does not exist. doing nothing."}}""")
        try {
            val failure = assertFailsWith<IllegalStateException> { LegacyFixtureLoader(server.baseUrl()).load(fixture) }
            assertTrue("does not exist" in failure.message.orEmpty(), failure.message)
        } finally {
            server.stop(0)
        }
    }

    private fun homeServer(
        queries: MutableList<String>,
        result: String,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/home") {
                queries += it.requestURI.rawQuery
                val body = result.toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.use { output -> output.write(body) }
            }
            start()
        }

    private fun HttpServer.baseUrl() = URI("http://127.0.0.1:${address.port}")
}
