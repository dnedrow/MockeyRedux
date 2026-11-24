package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Header
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class MockResponseTest {
    private fun response(
        contentType: String?,
        body: ByteArray,
    ) = MockResponse(200, listOfNotNull(contentType?.let { Header("Content-Type", it) }), body)

    @Test
    fun aResponseWithOnlyAStatusHasNothingElse() {
        val response = MockResponse(204)

        assertEquals(204, response.status)
        assertEquals(emptyList(), response.headers)
        assertEquals(0, response.body.size)
        assertEquals("", response.bodyText)
        assertNull(response.contentType)
    }

    @Test
    fun headersKeepTheirOrderAndTheirRepeatedNames() {
        val headers = listOf(Header("Set-Cookie", "a=1"), Header("X-Trace", "7"), Header("set-cookie", "b=2"))

        val response = MockResponse(200, headers)

        assertEquals(headers, response.headers)
        assertEquals(listOf("a=1", "b=2"), response.headerValues("SET-COOKIE"))
        assertEquals("a=1", response.header("set-cookie"))
        assertEquals(emptyList(), response.headerValues("Location"))
        assertNull(response.header("Location"))
    }

    @Test
    fun aResponseKeepsTheHeadersThatItWasCreatedWith() {
        val headers = mutableListOf(Header("X-Trace", "7"))
        val response = MockResponse(200, headers)

        headers += Header("X-Trace", "8")

        assertEquals(listOf(Header("X-Trace", "7")), response.headers)
    }

    @Test
    fun theStartOfABodyIsAsLongAsItIsAskedForAtMost() {
        val response = MockResponse(200, body = "answer".toByteArray())

        assertEquals(6, response.bodySize)
        assertEquals("ans", String(response.bodyStart(3)))
        assertEquals("answer", String(response.bodyStart(100)))
        assertEquals(0, MockResponse(204).bodySize)
    }

    @Test
    fun everyReadOfTheBodyGivesANewArray() {
        val response = MockResponse(200, body = byteArrayOf(1, 2, 3))

        val first = response.body
        first[0] = 9

        assertNotSame(first, response.body)
        assertContentEquals(byteArrayOf(1, 2, 3), response.body)
    }

    @Test
    fun theContentTypeIsTheFirstHeaderOfThatNameInAnyLetterCase() {
        val response = MockResponse(200, listOf(Header("content-type", "text/plain"), Header("Content-Type", "application/json")))

        assertEquals("text/plain", response.contentType)
    }

    @Test
    fun theBodyTextIsReadAsUtf8WithoutADeclaredCharset() {
        val body = "caf\u00e9 \u20ac".toByteArray(Charsets.UTF_8)

        assertEquals("caf\u00e9 \u20ac", response(contentType = null, body = body).bodyText)
        assertEquals("caf\u00e9 \u20ac", response(contentType = "application/json", body = body).bodyText)
        assertEquals("caf\u00e9 \u20ac", response(contentType = "text/plain; charset=no-such-charset", body = body).bodyText)
    }

    @Test
    fun theBodyTextIsReadInTheDeclaredCharset() {
        val body = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)

        assertEquals("caf\u00e9", response(contentType = "text/plain; charset=ISO-8859-1", body = body).bodyText)
    }

    @Test
    fun bytesThatAreNoTextBecomeTheReplacementCharacter() {
        val response = response(contentType = null, body = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))

        assertEquals("a\uFFFDb", response.bodyText)
    }

    @Test
    fun theTextOfAResponseShowsItsStatusOnly() {
        val response = MockResponse(201, listOf(Header("Set-Cookie", "session=secret")), "secret body".toByteArray())

        assertEquals("MockResponse(201)", response.toString())
        assertFalse("secret" in response.toString())
    }
}
