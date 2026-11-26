package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant

/**
 * A mock listener for a test of the legacy shim: it runs the module of the mock listener over real HTTP,
 * with the shim or without it. A test defines what it needs in the [engine], and it sends the requests of a
 * script.
 */
internal class ShimUnderTest private constructor(
    val engine: MockeyEngine,
    private val listener: Listener,
) : AutoCloseable {
    /** The definitions that the engine has now. */
    val definitions: Definitions
        get() = engine.definitions

    /** Sends a request as a script does, and returns the answer. */
    fun send(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): RawHttp.Answer = RawHttp.exchange(listener.address, RawHttp.request(method, target, headers, body))

    /** Sends a `GET` request for [target] and returns the answer. */
    fun get(target: String): RawHttp.Answer = send("GET", target)

    /** Sends a `POST` request for [target] with [fields] as a URL-encoded form, and returns the answer. */
    fun post(
        target: String,
        fields: String,
    ): RawHttp.Answer = send("POST", target, FORM, fields)

    /** Sends a `GET` request for [target] and returns the JSON document that answers it. */
    fun document(target: String): JsonObject = documentOf(get(target).body)

    /**
     * Defines a service with [scenarios], each of which answers with its name, and returns it. The first
     * scenario is the default scenario. [define] sets what else the service is to have.
     */
    fun service(
        name: String,
        mockUrl: String,
        vararg scenarios: String,
        define: (Service) -> Service = { it },
    ): Service =
        runBlocking {
            val created = engine.services.create { define(it.copy(name = name, mockUrl = mockUrl)) }.getOrThrow()
            for (scenario in scenarios) {
                val added = engine.scenarios.create(created.id) { it.copy(name = scenario, responseBody = scenario) }
                added.getOrThrow()
            }
            definitions.services.single { it.id == created.id }
        }

    override fun close() {
        listener.stop()
        engine.close()
    }

    companion object {
        /** The most bytes that the body of a request may have, unless a test says otherwise. */
        const val MEBIBYTE = 1_048_576L

        /** The header with which a request declares its body as a URL-encoded form. */
        val FORM = listOf("Content-Type: application/x-www-form-urlencoded")

        /** A definitions location that doesn't exist, for a test that has nothing to do with it. */
        val NO_LOCATION: Path = Path.of("no-definitions-location-of-a-shim-test")

        /**
         * Starts a mock listener for [engine], with the legacy shim unless [withShim] says otherwise. The
         * listener takes a body of [maxRequestBody] bytes at most, and the shim takes [location] as the
         * definitions location. Closing what this returns stops the listener and closes the engine.
         */
        fun start(
            engine: MockeyEngine = runBlocking { MockeyEngine.create() },
            withShim: Boolean = true,
            maxRequestBody: Long = MEBIBYTE,
            location: Path = NO_LOCATION,
        ): ShimUnderTest {
            val shim = if (withShim) LegacyShim(engine, maxRequestBody, location) else null
            return start(engine, shim, maxRequestBody)
        }

        /** Starts a mock listener for [engine] with [shim] as its legacy shim. */
        fun start(
            engine: MockeyEngine,
            shim: LegacyShim?,
            maxRequestBody: Long = MEBIBYTE,
        ): ShimUnderTest {
            val module = MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody), StatusEndpoint(Instant.EPOCH), shim)
            return ShimUnderTest(engine, Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), module))
        }
    }
}

/** Returns the JSON object that [body] is. */
internal fun documentOf(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

/** Returns the elements of the array [name] of this object. Each of them is an object. */
internal fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

/** Returns the text of the member [name] of this object. */
internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
