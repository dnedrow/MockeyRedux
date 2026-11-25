package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.legacy.xml.LegacyDirectoryImporter
import io.github.dnedrow.mockey.legacy.xml.LegacyDocumentImporter
import io.github.dnedrow.mockey.legacy.xml.LegacyImportResult
import io.github.dnedrow.mockey.legacy.xml.LegacyXmlIssueKind
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The definitions that a source holds, as far as reading the source tells. */
internal sealed interface SourceDefinitions {
    /**
     * A definitions document of this system: the JSON text that an export gives. Whether [text] is such a
     * document shows when an engine imports it.
     */
    class Document(
        val text: String,
    ) : SourceDefinitions

    /**
     * What the import of an export document or of a definitions directory of Mockey 2.x read. [advice] says
     * how more of the source can be read, where there is a way.
     */
    class Legacy(
        val result: LegacyImportResult,
        val advice: String? = null,
    ) : SourceDefinitions
}

/** Tells that no definitions could be read from a source. The message names the source and says why. */
internal class UnreadableSource(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A source of definitions that the server program reads once. [name] says where it is:
 *
 * - The path of a file, which holds a definitions document of this system or an export document of
 *   Mockey 2.x. Nothing but that file is read, also when it is the main file of a definitions directory
 *   and refers to other files: what is read with a file is for the one who names the source to say, by
 *   naming the directory.
 * - The path of a directory, which is a definitions directory of Mockey 2.x. The files of the directory
 *   that its main file refers to are read as well, and nothing outside the directory.
 * - A URL with the scheme `http` or `https`, whose server answers with one of the two documents. The
 *   request presents the user information of the URL as credentials, as most programs do. It is followed to
 *   another URL only when it presents none, so that no other server gets them.
 *
 * A document of this system is a JSON object, and one of Mockey 2.x is XML, so the first character of a
 * document tells which of the two it is.
 *
 * [connectTimeout] is the time within which the connection to the server of a URL has to be made, and
 * [answerTimeout] the time within which its complete answer has to arrive.
 */
internal class DefinitionsSource(
    private val name: String,
    private val connectTimeout: Duration = CONNECT_TIMEOUT,
    private val answerTimeout: Duration = ANSWER_TIMEOUT,
) {
    /**
     * The name of the source as a message and the log may hold it: a URL comes without its user information
     * and without its query, which can carry a password or a key.
     */
    val shown: String = withoutSecrets(name)

    /**
     * Reads the source.
     *
     * A source of Mockey 2.x of which some part cannot be read is read without that part, and the issues of
     * the result name it. It counts as unreadable only when nothing was read of it at all.
     *
     * @throws UnreadableSource when the source does not exist, cannot be read, or holds no definitions.
     */
    fun read(): SourceDefinitions = if (URL_START.containsMatchIn(name)) ofUrl() else ofPath()

    /** Returns the failure that says that the source cannot be read, with [explanation] as the sentences that say why. */
    fun unreadable(
        explanation: String,
        cause: Throwable? = null,
    ): UnreadableSource {
        val sentences = explanation.trim().let { if (it.endsWith('.')) it else "$it." }
        return UnreadableSource("The definitions cannot be read from $shown. $sentences", cause)
    }

    // A path

    private fun ofPath(): SourceDefinitions {
        val path =
            try {
                Path.of(name)
            } catch (_: InvalidPathException) {
                throw unreadable("That is no path of a file.")
            }
        if (Files.isDirectory(path)) return ofDirectory(path)
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (failure: NoSuchFileException) {
                throw unreadable("There is no such file or directory.", failure)
            } catch (failure: AccessDeniedException) {
                throw unreadable("Access to the file is denied.", failure)
            } catch (failure: IOException) {
                throw unreadable("The file cannot be read (${failure.message ?: failure.javaClass.simpleName}).", failure)
            }
        return ofDocument(bytes)
    }

    private fun ofDirectory(directory: Path): SourceDefinitions {
        val mainFile = LegacyDirectoryImporter.MAIN_FILE
        if (!Files.isRegularFile(directory.resolve(mainFile))) {
            throw unreadable("The directory holds no file $mainFile, which a definitions directory of Mockey 2.x has.")
        }
        return legacy(LegacyDirectoryImporter(directory).import(), isDocument = false)
    }

    // A document

    /** Returns the definitions of the document with the bytes [bytes], which a file or the answer of a server holds. */
    private fun ofDocument(bytes: ByteArray): SourceDefinitions {
        val start = if (bytes.startsWith(UTF_8_MARK)) UTF_8_MARK.size else 0
        val first = (start until bytes.size).firstOrNull { bytes[it] !in WHITESPACE }?.let { bytes[it] }
        val inUtf16 = UTF_16_MARKS.any { bytes.startsWith(it) }
        return when {
            first == null -> throw unreadable("The document is empty.")
            first == JSON_OBJECT_START -> SourceDefinitions.Document(textOf(bytes))
            first == XML_START || inUtf16 -> legacy(LegacyDocumentImporter.import(bytes), isDocument = true)
            else -> throw unreadable(NEITHER_DOCUMENT)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Returns the text that [bytes] are in UTF-8, in which a document of this system is written. */
    private fun textOf(bytes: ByteArray): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            throw unreadable("The document is no text in UTF-8.", failure)
        }
    }

    /**
     * Returns what was read of a source of Mockey 2.x, unless nothing was read at all while something could
     * not be read. A reference that names nothing is dropped and keeps nothing from being read.
     */
    private fun legacy(
        result: LegacyImportResult,
        isDocument: Boolean,
    ): SourceDefinitions {
        val refersToFiles = isDocument && result.issues.any { it.kind == LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT }
        val advice = if (refersToFiles) NAME_THE_DIRECTORY else null
        val unread = result.issues.filter { it.kind != LegacyXmlIssueKind.DANGLING_REFERENCE }
        if (result.definitions == Definitions.EMPTY && unread.isNotEmpty()) {
            throw unreadable((unread.map { it.message } + listOfNotNull(advice)).joinToString(" "))
        }
        return SourceDefinitions.Legacy(result, advice)
    }

    // A URL

    private fun ofUrl(): SourceDefinitions {
        // No failure of a URL is kept as a cause: its message can hold the URL with everything in it.
        val url =
            try {
                URI(name)
            } catch (_: URISyntaxException) {
                throw unreadable("That is no URL.")
            }
        if (url.scheme.lowercase() !in HTTP_SCHEMES) throw unreadable("The server reads a URL with the scheme http or https only.")
        if (url.host == null) throw unreadable("The URL names no host.")
        // The client of the Java runtime sends nothing of the user information of a URL by itself.
        val credentials = url.userInfo
        val request = HttpRequest.newBuilder(url)
        if (credentials != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
        val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout.toJavaDuration())
                .followRedirects(if (credentials == null) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
                .build()
        val answer =
            try {
                client
                    .sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .get(answerTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (failure: ExecutionException) {
                throw unreadable(explanationOf(failure.cause ?: failure, url))
            } catch (_: TimeoutException) {
                throw unreadable("No answer arrived within $answerTimeout.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw unreadable("The request was interrupted.")
            } finally {
                client.shutdownNow()
            }
        val status = answer.statusCode()
        return when (status) {
            in SUCCESSES -> ofDocument(answer.body())
            in REDIRECTIONS -> throw unreadable("The answer has the status $status, $NOT_FOLLOWED")
            else -> throw unreadable("The answer has the status $status.")
        }
    }

    /**
     * Returns the sentence that says why the request for [url] failed with [failure]. It says what the
     * failure says, without the URL as it was given, and without the user information and the query of it.
     */
    internal fun explanationOf(
        failure: Throwable,
        url: URI,
    ): String {
        if (failure is HttpConnectTimeoutException || failure is ConnectException) {
            return "No connection to ${url.rawAuthority.substringAfterLast('@')} could be made."
        }
        val said = failure.message ?: failure.javaClass.simpleName
        val secrets = listOfNotNull(url.rawUserInfo, url.userInfo, url.rawQuery, url.query).filter { it.isNotEmpty() }
        return "The request failed (${secrets.fold(said.replace(name, shown)) { hidden, secret -> hidden.replace(secret, HIDDEN) }})."
    }

    internal companion object {
        /** The time within which the connection to the server of a URL has to be made. */
        val CONNECT_TIMEOUT: Duration = 10.seconds

        /** The time within which the complete answer of the server of a URL has to arrive. */
        val ANSWER_TIMEOUT: Duration = 60.seconds

        /** How a URL starts. A scheme of one letter is none: it is the letter of a drive. */
        private val URL_START = Regex("""^[A-Za-z][A-Za-z0-9+.\-]+://""")
        private val HTTP_SCHEMES = setOf("http", "https")
        private val SUCCESSES = 200..299
        private val REDIRECTIONS = 300..399

        private const val HIDDEN = "***"
        private const val NOT_FOLLOWED = "which leads to another URL, and the server did not go there."
        private const val NAME_THE_DIRECTORY =
            "A document is read without the files that it refers to. " +
                "A definitions directory of Mockey 2.x is read with its files when the directory is named, and not its main file."
        private const val NEITHER_DOCUMENT =
            "The document is neither a definitions document of this system, which is a JSON object, " +
                "nor one of Mockey 2.x, which is XML."

        private val WHITESPACE = " \t\r\n".map { it.code.toByte() }
        private const val JSON_OBJECT_START = '{'.code.toByte()
        private const val XML_START = '<'.code.toByte()

        /** The byte order marks with which a text can begin. One in UTF-16 is XML: a document of this system is in UTF-8. */
        private val UTF_8_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF_16_MARKS = listOf(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
    }
}
