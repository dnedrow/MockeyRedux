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

    @Test
    fun disablesTheTwistConfigurationThatLegacyKeptEnabled() {
        val toggles = mutableListOf<String>()
        val info = """{"result":{"twist_enabled":true,"twist-name":"to stub","twist-id":7,"proxy_enabled":"false"}}"""
        val server = homeServer(mutableListOf(), """{"result":{"success":"Loaded"}}""", info, toggles)
        try {
            LegacyFixtureLoader(server.baseUrl()).load(fixture)

            assertEquals(listOf("twist-id=7&twist-enable=false&response-type=json"), toggles)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun appliesTheSettingsThatAFixtureCannotCarry() {
        val toggles = mutableListOf<String>()
        val filters = mutableListOf<String>()
        val server = homeServer(mutableListOf(), """{"result":{"success":"Loaded"}}""", toggles = toggles, filters = filters)
        try {
            val loader = LegacyFixtureLoader(server.baseUrl())

            loader.setTagFilter("keep me")
            loader.enableTwist(3)

            assertEquals(listOf("action=filter_tag_on&tag=keep+me"), filters)
            assertEquals(listOf("twist-id=3&twist-enable=true&response-type=json"), toggles)
        } finally {
            server.stop(0)
        }
    }

    private fun homeServer(
        queries: MutableList<String>,
        result: String,
        configuration: String = """{"result":{"twist_enabled":false,"proxy_enabled":"false"}}""",
        toggles: MutableList<String> = mutableListOf(),
        filters: MutableList<String> = mutableListOf(),
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            answering("/home", result, queries)
            answering("/configuration/info", configuration, mutableListOf())
            answering("/twisting/toggle", """{"result":{"success":"Twist configuration off"}}""", toggles)
            answering("/taghelp", """{"success":"Filter by tag is on."}""", filters)
            start()
        }

    private fun HttpServer.answering(
        path: String,
        result: String,
        queries: MutableList<String>,
    ) {
        createContext(path) {
            queries += it.requestURI.rawQuery.orEmpty()
            val body = result.toByteArray()
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.use { output -> output.write(body) }
        }
    }

    private fun HttpServer.baseUrl() = URI("http://127.0.0.1:${address.port}")
}
