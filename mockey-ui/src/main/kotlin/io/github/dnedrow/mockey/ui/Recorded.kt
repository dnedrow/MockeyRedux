package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import kotlinx.html.FlowContent
import kotlinx.html.p
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

/**
 * A body that a history entry holds, read for display. The admin API encodes a body in Base64, because a body
 * can hold any bytes. The UI shows a body as text if it is text, and it says so if it isn't.
 *
 * [text] is what the entry holds of the body, as text in [charset]. It's `null` if those bytes aren't text.
 * [kept] is the number of bytes that the entry holds, and [size] is the number of bytes of the whole body.
 * The body [isCut] if the entry only holds its start. The body isn't [isReadable] if the server sent
 * something that isn't Base64.
 */
internal class RecordedContent private constructor(
    val text: String?,
    val charset: Charset,
    val kept: Int,
    val size: Long,
    val isCut: Boolean,
    val isReadable: Boolean,
) {
    internal companion object {
        private const val NUL = '\u0000'

        /** Returns what [body] holds. [headers] are the headers of its request or response, which can name the charset. */
        fun of(
            body: RecordedBodyDto,
            headers: List<HeaderDto>,
        ): RecordedContent {
            val charset = charsetOf(headers)
            val bytes =
                try {
                    Base64.getDecoder().decode(body.base64)
                } catch (_: IllegalArgumentException) {
                    return RecordedContent(null, charset, 0, body.size, body.isCut, isReadable = false)
                }
            return RecordedContent(textOf(bytes, charset, body.isCut), charset, bytes.size, body.size, body.isCut, isReadable = true)
        }

        /**
         * Returns the charset that the `Content-Type` header among [headers] declares. It's UTF-8 if there is
         * no such header, if the header doesn't declare a charset, or if the runtime can't encode text in it.
         */
        fun charsetOf(headers: List<HeaderDto>): Charset {
            val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
            val parameters = contentType.split(';').drop(1)
            val declared = parameters.firstOrNull { it.substringBefore('=').trim().equals("charset", ignoreCase = true) }
            val value = declared?.substringAfter('=', "").orEmpty()
            val name = value.trim().removeSurrounding("\"")
            val charset =
                try {
                    Charset.forName(name)
                } catch (_: IllegalArgumentException) {
                    null
                }
            return charset?.takeIf { it.canEncode() } ?: Charsets.UTF_8
        }

        /**
         * Returns [bytes] as text in [charset], or `null` if they aren't text: they don't decode, or they
         * contain a NUL character. A cut can split a character in two, so the end of a body that [isCut]
         * doesn't count against it.
         */
        private fun textOf(
            bytes: ByteArray,
            charset: Charset,
            isCut: Boolean,
        ): String? {
            val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = CharBuffer.allocate((bytes.size * decoder.maxCharsPerByte().toDouble()).toInt() + 1)
            val read = decoder.decode(ByteBuffer.wrap(bytes), text, !isCut)
            if (read.isError || (!isCut && decoder.flush(text).isError)) return null
            return text.flip().toString().takeIf { NUL !in it }
        }

        /**
         * Returns [text] as bytes in [charset], or `null` if the charset can't encode one of its characters.
         * This is the way back from an edited [RecordedContent.text] to a body.
         */
        fun encoded(
            text: String,
            charset: Charset,
        ): ByteArray? {
            val encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                val encoded = encoder.encode(CharBuffer.wrap(text))
                ByteArray(encoded.remaining()).also { encoded.get(it) }
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}

/** Returns [count] as a number of bytes, for example "1 byte" or "1,234 bytes". */
internal fun bytesOf(count: Long): String = if (count == 1L) "1 byte" else String.format(Locale.ROOT, "%,d bytes", count)

/**
 * Shows [content], which is the body of a request or a response that a history entry holds. A body that is
 * text is shown as it is. For anything else, the page says what the entry holds instead. [none] is the
 * sentence for a request or a response without a body.
 */
internal fun FlowContent.recordedBodyOf(
    content: RecordedContent,
    none: String,
) {
    val text = content.text
    when {
        !content.isReadable -> p(classes = "problem") { +"The server sent this body in a form that the UI cannot read." }
        content.size == 0L -> p(classes = "none") { +none }
        content.kept == 0 -> p { +"The body has ${bytesOf(content.size)}, and the server kept none of them." }
        text == null -> p { +"The body is not text in ${content.charset.name()}. It has ${bytesOf(content.size)}." }
        else -> bodyOf(text)
    }
    if (content.isCut && content.kept > 0) {
        val kept = bytesOf(content.kept.toLong())
        p(classes = "hint") { +"The server kept only the first $kept of this body, which has ${bytesOf(content.size)}." }
    }
}
