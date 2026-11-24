package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScenarioResponseTest {
    /** The text `é € 日本`: a character of ISO-8859-1, one of windows-1252, and two that only a Unicode charset has. */
    private val accented = "\u00e9 \u20ac \u65e5\u672c"

    private fun scenario(
        body: String,
        vararg headers: Header,
        status: Int = 200,
    ) = Scenario(id = ScenarioId.generate(), name = "scenario", responseBody = body, statusCode = status, headers = headers.toList())

    private fun contentType(value: String) = Header("Content-Type", value)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** The JSON text that holds [accented], as ISO-8859-1 has it: the charset has the first of its characters only. */
    private val inLatin1 = "{\"text\":\"\u00e9 ? ??\"}".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun theResponseHasTheStatusAndTheHeadersOfTheScenario() {
        val scenario = scenario("""{"id":9}""", Header("Location", "/orders/9"), Header("X-Trace", "a"), status = 201)

        val response = scenario.response()

        assertEquals(201, response.status)
        assertEquals(listOf(Header("Location", "/orders/9"), Header("X-Trace", "a")), response.headers)
    }

    @Test
    fun headersThatShareANameAreAllKeptInTheirOrder() {
        val headers = listOf(Header("Set-Cookie", "a=1"), Header("X-Multi", "one"), Header("Set-Cookie", "b=2"), Header("X-Multi", "two"))

        val response = scenario("cookies", *headers.toTypedArray()).response()

        assertEquals(headers, response.headers)
        assertEquals(listOf("a=1", "b=2"), response.headerValues("Set-Cookie"))
    }

    @Test
    fun noHeaderIsAdded() {
        assertEquals(emptyList(), scenario("""{"ok":true}""").response().headers)
        assertEquals(emptyList(), scenario(accented).response().headers)
        assertEquals(listOf(Header("X-Trace", "a")), scenario("text", Header("X-Trace", "a")).response().headers)
    }

    @Test
    fun theBodyIsSentExactlyAsStored() {
        val response = scenario("""{"ok":true}""").response()

        assertEquals(11, response.body.size)
        assertContentEquals("""{"ok":true}""".toByteArray(Charsets.US_ASCII), response.body)
    }

    @Test
    fun anEmptyBodyStaysEmpty() {
        assertEquals(0, scenario("").response().body.size)
    }

    @Test
    fun whitespaceAroundAndInsideTheBodyIsKept() {
        val body = "\r\n  first line\n\n\tthird line \r\nlast line\n \n"

        assertContentEquals(body.toByteArray(Charsets.US_ASCII), scenario(body).response().body)
    }

    @Test
    fun theBodyIsEncodedAsUtf8ByDefault() {
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9").response().body)
    }

    @Test
    fun aContentTypeWithoutACharsetLeavesUtf8() {
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9", contentType("application/json")).response().body)
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9", contentType("text/plain; format=flowed")).response().body)
    }

    @Test
    fun theDeclaredCharsetIsHonored() {
        assertContentEquals(bytes(0xE9), scenario("\u00e9", contentType("text/plain; charset=ISO-8859-1")).response().body)
        assertContentEquals(bytes(0x80), scenario("\u20ac", contentType("text/plain; charset=windows-1252")).response().body)
        assertContentEquals(bytes(0xFE, 0xFF, 0x00, 0xE9), scenario("\u00e9", contentType("text/plain; charset=UTF-16")).response().body)
    }

    @Test
    fun theCharsetIsFoundInAnyLetterCaseAndInQuotes() {
        val scenario = scenario("\u00e9", Header("content-type", "Text/Plain; Format=flowed; CHARSET=\"iso-8859-1\""))

        assertContentEquals(bytes(0xE9), scenario.response().body)
    }

    @Test
    fun aCharacterThatTheCharsetCannotEncodeIsSentAsAQuestionMark() {
        val scenario = scenario("""{"text":"$accented"}""", contentType("text/plain; charset=ISO-8859-1"))

        assertContentEquals(inLatin1, scenario.response().body)
    }

    @Test
    fun onlyTheContentTypeDeclaresTheCharset() {
        val mentionsUtf8 = scenario("\u00e9", Header("X-Note", "prefers UTF-8 output"))
        val mentionsLatin1 =
            scenario("\u00e9", Header("X-Charset", "text/plain; charset=ISO-8859-1"), Header("Accept-Charset", "ISO-8859-1"))

        assertContentEquals(bytes(0xC3, 0xA9), mentionsUtf8.response().body)
        assertContentEquals(bytes(0xC3, 0xA9), mentionsLatin1.response().body)
    }

    @Test
    fun ofSeveralContentTypesTheFirstCounts() {
        val scenario = scenario("\u00e9", contentType("text/plain; charset=ISO-8859-1"), contentType("text/plain; charset=UTF-8"))

        assertContentEquals(bytes(0xE9), scenario.response().body)
    }

    @Test
    fun aCharsetThatIsNotKnownLeavesUtf8() {
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9", contentType("text/plain; charset=no-such-charset")).response().body)
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9", contentType("text/plain; charset=")).response().body)
        assertContentEquals(bytes(0xC3, 0xA9), scenario("\u00e9", contentType("text/plain; charset=not a name")).response().body)
    }

    /** Such charsets are for reading only, such as `ISO-2022-CN` of the JDK. A runtime without one has nothing to check here. */
    @Test
    fun aCharsetThatCannotEncodeLeavesUtf8() {
        Charset.availableCharsets().values.filterNot { it.canEncode() }.forEach { decodesOnly ->
            val scenario = scenario("\u00e9", contentType("text/plain; charset=${decodesOnly.name()}"))

            assertEquals(Charsets.UTF_8, scenario.responseCharset, decodesOnly.name())
            assertContentEquals(bytes(0xC3, 0xA9), scenario.response().body, decodesOnly.name())
        }
    }

    @Test
    fun theTextOfTheResponseIsTheBodyOfTheScenarioWhateverTheCharset() {
        val declared =
            listOf(null, "application/json", "text/plain; charset=utf-8", "text/plain; charset=UTF-16LE", "text/plain; charset=UTF-16")

        declared.forEach { value ->
            val scenario = scenario(accented, *listOfNotNull(value?.let(::contentType)).toTypedArray())

            assertEquals(accented, scenario.response().bodyText, value)
        }
    }

    /**
     * A parity recording: the scenario that answers its request, as the fixture defines it, and what the stated new
     * response of the recording has as its status, its headers, and its body.
     */
    private class Recording(
        val name: String,
        val scenario: Scenario,
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    private fun recorded(
        name: String,
        scenario: Scenario,
        body: String,
        status: Int = 200,
        vararg headers: Pair<String, String>,
    ) = Recording(name, scenario, status, headers.toList(), body.toByteArray(Charsets.UTF_8))

    private fun assertAnsweredAsStated(recordings: List<Recording>) {
        recordings.forEach { recording ->
            val response = recording.scenario.response()

            assertEquals(recording.status, response.status, recording.name)
            assertEquals(recording.headers, response.headers.map { it.name to it.value }, recording.name)
            assertContentEquals(recording.body, response.body, recording.name)
        }
    }

    /**
     * The scenarios of the parity fixture `responses-status-headers`, with their header text split into headers,
     * and the stated new responses of its recordings. No stated body ends with the line break that legacy adds
     * (DEV-06). `response-headers-repeated` states every header (DEV-34), and `response-headers-default` states
     * none (DEV-09).
     */
    @Test
    fun theScenariosOfTheStatusAndHeaderRecordingsAnswerAsTheRecordingsState() {
        val created = scenario("""{"id":9}""", Header("Location", "/orders/9"), Header("X-Trace", "a"), status = 201)
        val found = scenario("moved", Header("Location", "/service/status/created"), status = 302)
        val unavailable = scenario("try again later", Header("Retry-After", "120"), status = 503)
        val multiple =
            scenario("""{"headers":3}""", contentType("application/json"), Header("X-Time", "12:30:45"), Header("X-Padded", "spaced value"))
        val repeated =
            scenario(
                "repeated header names",
                Header("Set-Cookie", "a=1"),
                Header("Set-Cookie", "b=2"),
                Header("X-Multi", "one"),
                Header("X-Multi", "two"),
            )
        val malformed = scenario("malformed header text", Header("X-After", "kept"))
        val cookies = arrayOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2", "X-Multi" to "one", "X-Multi" to "two")
        val threeHeaders = arrayOf("Content-Type" to "application/json", "X-Time" to "12:30:45", "X-Padded" to "spaced value")

        assertAnsweredAsStated(
            listOf(
                recorded("response-status-201-headers", created, """{"id":9}""", 201, "Location" to "/orders/9", "X-Trace" to "a"),
                recorded("response-status-302", found, "moved", 302, "Location" to "/service/status/created"),
                recorded("response-status-503", unavailable, "try again later", 503, "Retry-After" to "120"),
                recorded("response-headers-multiple", multiple, """{"headers":3}""", 200, *threeHeaders),
                recorded("response-headers-repeated", repeated, "repeated header names", 200, *cookies),
                recorded("response-headers-default", scenario("no header element"), "no header element"),
                recorded("response-headers-malformed", malformed, "malformed header text", 200, "X-After" to "kept"),
            ),
        )
    }

    /**
     * The scenarios of the parity fixture `responses-body-charset` and the stated new responses of its recordings.
     * No stated body ends with the line break that legacy adds (DEV-06), and each is in UTF-8 unless the
     * `Content-Type` of its scenario declares another charset (DEV-08).
     */
    @Test
    fun theScenariosOfTheBodyAndCharsetRecordingsAnswerAsTheRecordingsState() {
        val text = """{"text":"$accented"}"""
        val whitespace = "first line\n\n\tthird line, after a blank line and indented with a tab\nlast line"
        val markup = """<order id="9"><note>Fish &amp; Chips &lt;3</note></order>"""
        val latin1 = scenario(text, contentType("text/plain; charset=ISO-8859-1"))
        val jsonUtf8 = scenario(text, contentType("application/json; charset=UTF-8"))
        val otherHeader = scenario(text, Header("X-Note", "prefers UTF-8 output"))

        assertAnsweredAsStated(
            listOf(
                recorded("response-body-trailing-newline", scenario("""{"ok":true}"""), """{"ok":true}"""),
                recorded("response-body-empty", scenario(""), ""),
                recorded("response-body-whitespace", scenario(whitespace), whitespace),
                recorded("response-body-markup", scenario(markup), markup),
                recorded("response-charset-none", scenario(text), text),
                recorded(
                    "response-charset-utf8",
                    scenario(text, contentType("text/plain; charset=utf-8")),
                    text,
                    200,
                    "Content-Type" to "text/plain; charset=utf-8",
                ),
                Recording("response-charset-latin1", latin1, 200, listOf("Content-Type" to "text/plain; charset=ISO-8859-1"), inLatin1),
                recorded(
                    "response-charset-json",
                    scenario(text, contentType("application/json")),
                    text,
                    200,
                    "Content-Type" to "application/json",
                ),
                recorded("response-charset-json-utf8", jsonUtf8, text, 200, "Content-Type" to "application/json; charset=UTF-8"),
                recorded("response-charset-other-header", otherHeader, text, 200, "X-Note" to "prefers UTF-8 output"),
            ),
        )
    }
}
