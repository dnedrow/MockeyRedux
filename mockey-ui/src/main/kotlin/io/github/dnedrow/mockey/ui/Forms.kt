package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import java.net.URLDecoder
import java.security.MessageDigest

/** What separates the tags of a text, as a server reads tags: whitespace of any kind. */
private val BETWEEN_TAGS = Regex("""[\s\p{Z}]+""")

/** Returns the tags that [text] holds, each as it is written. Tags have whitespace between them. */
internal fun tagsIn(text: String): List<String> = text.split(BETWEEN_TAGS).filter { it.isNotEmpty() }

/**
 * What a form says of the values that its page gave its fields, so that the UI finds what a person changed
 * and asks the server to set that, and nothing else. A form that changes a few attributes of something leaves
 * the others as the server holds them, also where they were changed since the page was made.
 *
 * For each field that it says it of, the form has a hidden field with a mark of the value: the name of the
 * field after [PREFIX]. A browser sends the mark back as it is, beside what the field holds then. A form that
 * is shown again keeps the marks that it was sent with, so that they say what the first page gave its fields.
 *
 * The mark of a text is the same however its lines end. A browser sends every line of a field with a carriage
 * return and a line feed, whatever the page gave the field, so the ends of the lines say nothing of a change.
 */
internal object Shown {
    /** What the name of a hidden field begins with that holds the mark of a field. */
    const val PREFIX = "_shown."

    private const val MARK_BYTES = 12

    /** Returns the mark of [values], which are the values of the fields with one name, in the order of the form. */
    fun markOf(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8)
            // The length keeps two values apart from one value that is made of both.
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return ProcessKey.encoded(digest.digest().copyOf(MARK_BYTES))
    }
}

/** What a page needs of the request that it answers: the token for its forms, see [FormTokens]. */
internal class View(
    val formToken: String,
)

/** A file that a form came with: the name that the browser states for it, and its bytes. */
internal class Upload(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * What a form holds that a browser sent: the values of its fields by their names, and the files that came
 * with it. A field that the form did not send has no value, which is how a browser states a check box that
 * is not checked.
 */
internal class FormInput(
    /** The values of the fields, each as the browser sent it. */
    val values: Map<String, List<String>>,
    private val uploads: Map<String, Upload> = emptyMap(),
) {
    /** Returns the value of the field [name] as it was sent, or an empty text when the form has no such field. */
    fun text(name: String): String = values[name]?.firstOrNull().orEmpty()

    /**
     * Returns the value of the field [name], which holds lines, with a line feed at the end of each. A
     * browser sends the lines of a field with a carriage return before the line feed, whatever the page gave
     * the field, so the text would otherwise change every time that a form is sent.
     */
    fun lines(name: String): String = text(name).replace("\r\n", "\n")

    /** Returns whether the form has the field [name], which is how it states that a check box is checked. */
    fun isChecked(name: String): Boolean = name in values

    /** Returns the values of the fields with the name [name], in the order of the form, such as the rows of a list. */
    fun all(name: String): List<String> = values[name].orEmpty()

    /**
     * Returns whether one of the fields [names] holds something else than the page of the form gave it, see
     * [Shown]. A field of which the form does not say what it was given counts as changed.
     */
    fun isChanged(vararg names: String): Boolean = names.any { text(Shown.PREFIX + it) != Shown.markOf(all(it)) }

    /** Returns the file that came in the field [name], or `null` when no file was chosen for it. */
    fun upload(name: String): Upload? = uploads[name]
}

/**
 * A form whose content the UI itself does not take. [errors] say what is the matter, each with the field of
 * the admin API that it concerns.
 */
internal class InvalidInput(
    val errors: List<FieldErrorDto>,
) : Exception(errors.joinToString(" ") { it.message }) {
    constructor(field: String, message: String) : this(listOf(FieldErrorDto(field, message)))
}

/**
 * What a form is shown with: the values of its fields, and what the server or the UI has against them. A
 * form that is shown for the first time has the values that the server holds, and a form that was not taken
 * has the values that the browser sent, so that nothing that was entered is lost.
 *
 * A message stands next to the field that it concerns. A field says with [messagesAt] which messages it
 * shows, and the form shows the ones that no field took where it ends, see [others], so that no message is
 * lost either.
 */
internal class FormState(
    private val values: Map<String, List<String>>,
    private val errors: List<FieldErrorDto> = emptyList(),
    /** What the server says of the form as a whole, or `null` when it says nothing. */
    val detail: String? = null,
) {
    private val shown = mutableSetOf<FieldErrorDto>()

    /** Whether the form was sent and not taken. */
    val isRejected: Boolean = errors.isNotEmpty() || detail != null

    /** Whether there are messages about single fields. */
    val hasMessages: Boolean = errors.isNotEmpty()

    /**
     * Returns the value of the field [name], or an empty text when it has none. Its lines end with a line
     * feed, also where a browser sent them with a carriage return before it, see [FormInput.lines].
     */
    fun value(name: String): String = values[name]?.firstOrNull().orEmpty().replace("\r\n", "\n")

    /** Returns whether the check box [name] is checked. */
    fun isChecked(name: String): Boolean = name in values

    /** Returns the values of the fields with the name [name], in their order, such as the rows of a list. */
    fun all(name: String): List<String> = values[name].orEmpty()

    /**
     * Returns the mark of what the page of the form first gave the fields with the name [name], see [Shown].
     * A form that is shown for the first time gives them what this state holds, and a form that is shown
     * again says what it was sent with.
     */
    fun shownMark(name: String): String = values[Shown.PREFIX + name]?.firstOrNull() ?: Shown.markOf(all(name))

    /**
     * Returns the messages for the field that shows [apiField] of a request to the admin API, and for what
     * that field holds, such as `headers[1].name` for `headers`. The form counts them as shown.
     */
    fun messagesAt(apiField: String): List<String> {
        val concerned = errors.filter { it.field == apiField || it.field.startsWith("$apiField.") || it.field.startsWith("$apiField[") }
        shown += concerned
        return concerned.map { it.message }
    }

    /** Returns the messages that no field has shown so far, each with the field of the admin API that it names. */
    fun others(): List<String> {
        val left = errors.filterNot { it in shown }
        return left.map { if (it.field.isEmpty()) it.message else "${it.field}: ${it.message}" }
    }

    internal companion object {
        /** A form without values. */
        val EMPTY = FormState(emptyMap())

        /** Returns the state of a form that is shown with [values]. A field with the value `null` has no value. */
        fun of(vararg values: Pair<String, String?>): FormState =
            FormState(values.mapNotNull { (name, value) -> value?.let { name to listOf(it) } }.toMap())
    }
}

/** What comes of a form that the UI took. */
internal sealed class Outcome {
    /**
     * The server did what the form asked for. The browser is sent to the page at [to], which is a path of
     * the UI, and that page says [notice].
     */
    class Done(
        val to: String,
        val notice: String,
    ) : Outcome()

    /**
     * The server did what the form asked for and gave a report of it, which [page] shows. The report lists
     * [entries] entries. The browser is sent to the page of the report.
     */
    class Report(
        val page: Page,
        val entries: Int,
    ) : Outcome()

    /** Nothing was changed yet, and [page] says how to go on, such as the page that asks whether something is to be deleted. */
    class Shown(
        val page: Page,
    ) : Outcome()
}

/**
 * Reads the form that a request holds. A form is what a browser sends for a form of a page: fields that are
 * written as a URL writes its query, or parts, among them files. The texts are UTF-8, as the pages are.
 */
internal object FormReader {
    /** The bytes of a form that the UI takes at most. The server takes a body of 50 MiB unless it is told otherwise. */
    const val LIMIT: Long = 64L * 1024 * 1024

    /** The body of a request, from the moment at which it is read. */
    private val BODY = AttributeKey<ByteArray>("MockeyUiFormBody")

    /** A request whose form the UI cannot read. The answer has [status] and says [message]. */
    class Unreadable(
        val status: HttpStatusCode,
        override val message: String,
    ) : Exception(message)

    /**
     * Returns the form of the request of [call], which has at most [limit] bytes. A request that states a
     * length beyond that is not read at all, and any other is read as far as the limit goes.
     *
     * @throws Unreadable when the request holds no form, a form that is too long, or one that is not
     *   written as a browser writes it.
     */
    suspend fun read(
        call: ApplicationCall,
        limit: Long = LIMIT,
    ): FormInput {
        val type = runCatching { call.request.contentType() }.getOrNull()
        val inParts = type?.match(ContentType.MultiPart.FormData) == true
        if (!inParts && type?.match(ContentType.Application.FormUrlEncoded) != true) {
            throw Unreadable(HttpStatusCode.UnsupportedMediaType, "The request holds no form. A browser sends a form of a page as one.")
        }
        val length = call.request.headers[HttpHeaders.ContentLength]
        val stated = length?.trim()?.toLongOrNull()
        val sent = call.request.receiveChannel()
        val body = if (stated != null && stated > limit) null else sent.readBuffer(limit + 1).readByteArray()
        if (body == null || body.size > limit) {
            throw Unreadable(HttpStatusCode.PayloadTooLarge, "The form is longer than $limit bytes, which is the most that the UI takes.")
        }
        return try {
            if (inParts) ofParts(call, body) else ofFields(body)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw Unreadable(HttpStatusCode.BadRequest, "The form of the request is not written as a browser writes a form.")
        }
    }

    /**
     * Returns the body that Ktor is to take a request apart from, where [stream] is the one that the request
     * came with: the bytes that [read] has read of the request of [call], when it has.
     */
    fun bodyFor(
        call: ApplicationCall,
        stream: ByteReadChannel,
    ): ByteReadChannel = call.attributes.getOrNull(BODY)?.let { ByteReadChannel(it) } ?: stream

    /** Returns the form of fields that [body] is. */
    private fun ofFields(body: ByteArray): FormInput {
        val fields = String(body, Charsets.UTF_8).split('&').filter { it.isNotEmpty() }
        return FormInput(fields.groupBy({ decoded(it.substringBefore('=')) }, { decoded(it.substringAfter('=', "")) }))
    }

    private fun decoded(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)

    /**
     * Returns the form of parts that [body] is, which is the body of the request of [call]. Ktor takes the
     * parts apart, from the bytes that are read already, see [bodyFor].
     */
    private suspend fun ofParts(
        call: ApplicationCall,
        body: ByteArray,
    ): FormInput {
        val values = LinkedHashMap<String, MutableList<String>>()
        val uploads = LinkedHashMap<String, Upload>()
        call.attributes.put(BODY, body)
        call.receiveMultipart(formFieldLimit = body.size.toLong() + 1).forEachPart { part ->
            val name = part.name.orEmpty()
            if (part is PartData.FormItem) {
                values.getOrPut(name) { mutableListOf() } += part.value
            } else if (part is PartData.FileItem) {
                val content = part.provider().readBuffer().readByteArray()
                val fileName = part.originalFileName.orEmpty()
                // A browser sends a file field in which no file was chosen as a file without a name and without bytes.
                if (fileName.isNotEmpty() || content.isNotEmpty()) uploads[name] = Upload(fileName, content)
            }
            part.dispose()
        }
        return FormInput(values, uploads)
    }
}
