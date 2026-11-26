package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the UI reads a body that a history entry holds: as text if it is text, in the charset that its headers declare. */
class RecordedContentTest {
    private fun contentType(value: String): List<HeaderDto> = listOf(HeaderDto("Content-Type", value))

    private fun textOf(
        body: RecordedBodyDto,
        headers: List<HeaderDto> = emptyList(),
    ): String? = RecordedContent.of(body, headers).text

    /** A body is text if its bytes decode in the charset and don't contain a NUL character. */
    @Test
    fun aBodyIsTextIfItsBytesAreTextInItsCharset() {
        val utf8 = "Grüße, 世界 \uD83D\uDE00\r\n\tend"

        assertEquals(utf8, textOf(Samples.body(utf8)))
        assertEquals("", textOf(Samples.body("")))
        assertEquals("é", textOf(Entries.bytes(0xE9), contentType("text/plain; charset=ISO-8859-1")))
        assertEquals("\uFEFFa", textOf(Entries.bytes(0xEF, 0xBB, 0xBF, 0x61)))
        assertEquals(null, textOf(Entries.bytes(0xE9)))
        assertEquals(null, textOf(Entries.bytes(0xC3)))
        assertEquals(null, textOf(Entries.bytes(0x61, 0x00, 0x62)))
        assertEquals(null, textOf(Entries.bytes(0xFF, 0xFE, 0x00, 0x00)))
    }

    /** A cut can split a character in two. The incomplete end of a cut body doesn't make the body binary, but a broken middle does. */
    @Test
    fun theEndOfACutBodyDoesNotCountAgainstIt() {
        val split = "caf".toByteArray() + byteArrayOf(0xC3.toByte())
        val broken = byteArrayOf(0x61, 0xC3.toByte(), 0x62, 0x63)

        val cut = RecordedContent.of(Entries.cut(split, 5000), emptyList())

        assertEquals("caf", cut.text)
        assertEquals(listOf(4L, 5000L), listOf(cut.kept.toLong(), cut.size))
        assertEquals(true to true, cut.isCut to cut.isReadable)
        assertEquals(null, textOf(Entries.cut(broken, 5000)))
        assertEquals("", textOf(Entries.cut(ByteArray(0), 12)))
    }

    /** The charset is the one that the Content-Type header declares, and UTF-8 if the header declares none that can be used. */
    @Test
    fun theCharsetComesFromTheContentTypeHeader() {
        val charsetOf = { value: String -> RecordedContent.charsetOf(contentType(value)).name() }

        assertEquals("ISO-8859-1", charsetOf("text/plain; charset=ISO-8859-1"))
        assertEquals("ISO-8859-1", charsetOf("text/plain;CHARSET=\"latin1\""))
        assertEquals("UTF-16", charsetOf("text/plain; boundary=x; charset = utf-16 "))
        assertEquals("UTF-8", charsetOf("text/plain"))
        assertEquals("UTF-8", charsetOf("text/plain; charset="))
        assertEquals("UTF-8", charsetOf("text/plain; charset=no such charset"))
        assertEquals("UTF-8", charsetOf("charset=ISO-8859-1"))
        assertEquals("UTF-8", RecordedContent.charsetOf(emptyList()).name())
        assertEquals("ISO-8859-1", RecordedContent.charsetOf(listOf(HeaderDto("content-TYPE", "a/b; charset=ISO-8859-1"))).name())
        assertEquals("UTF-8", RecordedContent.charsetOf(listOf(HeaderDto("Accept-Charset", "ISO-8859-1"))).name())
    }

    /** A charset that can only decode text can't encode an edited body, so the UI uses UTF-8 in its place. */
    @Test
    fun aCharsetThatCannotEncodeIsNotUsed() {
        val readOnly = Charset.availableCharsets().values.filterNot { it.canEncode() }

        assertTrue(readOnly.isNotEmpty(), "This runtime has no charset that can only read.")
        for (charset in readOnly) {
            assertEquals("UTF-8", RecordedContent.charsetOf(contentType("text/plain; charset=${charset.name()}")).name(), charset.name())
        }
    }

    /** A body that the server sends in something other than Base64 can't be read, and the UI doesn't fail on it. */
    @Test
    fun aBodyThatIsNotBase64IsNotReadable() {
        val content = RecordedContent.of(RecordedBodyDto("not Base64!", 3, isCut = false), emptyList())

        assertEquals(listOf(false, null, 0, 3L), listOf(content.isReadable, content.text, content.kept, content.size))
    }

    /** An edited text goes back to bytes in the same charset. A character that the charset can't encode gives no bytes. */
    @Test
    fun aTextIsEncodedInItsCharset() {
        assertContentEquals(byteArrayOf(0xE9.toByte(), 0x21), RecordedContent.encoded("é!", Charsets.ISO_8859_1))
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), RecordedContent.encoded("é", Charsets.UTF_8))
        assertContentEquals(ByteArray(0), RecordedContent.encoded("", Charsets.UTF_8))
        assertEquals(null, RecordedContent.encoded("€", Charsets.ISO_8859_1))
        assertEquals(null, RecordedContent.encoded("\uD83D", Charsets.UTF_8))
    }

    /** A number of bytes is written with its unit, in the singular for one byte, and with separators for thousands. */
    @Test
    fun aNumberOfBytesIsWrittenWithItsUnit() {
        val written = listOf(0L, 1L, 2L, 1234L, 1048576L).map(::bytesOf)

        assertEquals(listOf("0 bytes", "1 byte", "2 bytes", "1,234 bytes", "1,048,576 bytes"), written)
    }
}
