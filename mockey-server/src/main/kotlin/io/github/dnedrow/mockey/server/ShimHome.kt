package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.legacy.xml.LegacyDirectoryImporter
import io.github.dnedrow.mockey.legacy.xml.LegacyImportReport
import io.github.dnedrow.mockey.legacy.xml.LegacyImportResult
import io.github.dnedrow.mockey.legacy.xml.LegacyXmlIssueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The endpoint `/home` of the legacy shim. Mockey 2.x served its home page there, and a script used the path
 * for two things, which are all that the endpoint answers:
 *
 * - `action=init` puts the definitions of a document of Mockey 2.x in the place of the definitions of
 *   [engine]. The parameter `file` names the document, which is a file inside the definitions [location].
 *   The files that the document refers to are read with it, as far as they are in its own directory.
 * - `action=deleteAllServices` makes a clean slate, as the reset of the admin API does.
 *
 * Mockey 2.x read `file` as any path of the machine, and it read a document from the URL of the parameter
 * `url`. The endpoint reads no URL at all, and no file outside the definitions location: neither one that
 * the name places there, nor one that a link inside the location leads to. A request for either gets a
 * result that says so, and nothing of it is read.
 *
 * An `init` changes the server only when it has read its document. Then it applies two more parameters,
 * where the request has them: `transientState` switches read-only mode on with `true` and off with every
 * other text, and `filterTag` sets the tag filter. The new definitions are stored when the server is in
 * writable mode after that.
 *
 * The answer is the result envelope of Mockey 2.x, see [ShimResult], with the texts of Mockey 2.x.
 */
internal class ShimHome(
    private val engine: MockeyEngine,
    private val location: Path,
) : ShimEndpoint {
    /**
     * Lets one request at a time replace or clear the definitions. Replacing them takes several steps, and
     * two requests that took them in turns would leave the definitions of both documents.
     */
    private val turn = Mutex()

    override suspend fun answer(parameters: ShimParameters): JsonObject? =
        when (parameters[ACTION]) {
            INIT -> initialized(parameters)
            DELETE_ALL -> cleared()
            else -> null
        }

    /** Makes a clean slate. */
    private suspend fun cleared(): JsonObject {
        turn.withLock { engine.reset() }
        log.info("A request for {} of the legacy shim reset the server. It has no definitions and no history now.", PATH)
        return ShimResult.success("All is deleted. You have a clean slate. Enjoy.")
    }

    /** Puts the definitions of the document that [parameters] name in the place of the definitions of the engine. */
    private suspend fun initialized(parameters: ShimParameters): JsonObject {
        if (parameters[URL] != null) return ShimResult.fail(NO_URL)
        val name = parameters[FILE] ?: return ShimResult.fail(NO_FILE)
        val document =
            when (val named = withContext(Dispatchers.IO) { named(name) }) {
                is Named.Document -> named
                Named.Missing -> return ShimResult.fail("$name does not exist. doing nothing.")
                Named.Outside -> return ShimResult.fail("${unable(name)} $OUTSIDE")
                Named.NoFile -> return ShimResult.fail("${unable(name)} $NO_DOCUMENT")
            }
        val read = withContext(Dispatchers.IO) { LegacyDirectoryImporter(document.file.parent).import(document.file) }
        val unread = read.issues.filter { it.kind != LegacyXmlIssueKind.DANGLING_REFERENCE }
        // A document of which nothing was read, while some of it could not be read, is none to load.
        if (read.definitions == Definitions.EMPTY && unread.isNotEmpty()) return ShimResult.fail("${unable(name)} $NO_DOCUMENT")
        return turn.withLock { replaced(read, document, name, parameters) }
    }

    /**
     * Puts the definitions that [read] holds, which are those of [document], in the place of the definitions
     * of the engine, and applies what [parameters] say of read-only mode and of the tag filter. [name] is
     * the name of the document as the request gives it.
     *
     * The engine takes the new definitions in read-only mode, and it stores them at the end when it's to be
     * in writable mode. So the definitions location holds the old definitions or the new ones, and never
     * none or a part of them.
     */
    private suspend fun replaced(
        read: LegacyImportResult,
        document: Named.Document,
        name: String,
        parameters: ShimParameters,
    ): JsonObject {
        val readOnly = parameters[TRANSIENT_STATE]?.toBoolean() ?: engine.readOnly
        engine.setReadOnly(true)
        engine.reset()
        val report = read.importInto(engine)
        // The reset has cleared the tag filter, so the filter of the request is set after it.
        parameters[FILTER_TAG]?.let { engine.tagFilter = Tags.of(it) }
        log.info(
            "A request for {} of the legacy shim put the definitions of {} in the place of the definitions of the server: {}.",
            PATH,
            document.shown,
            contentOf(engine.definitions),
        )
        remarksOf(report).forEach { log.warn("Of the definitions of {}: {}", document.shown, withoutControls(it)) }
        if (!readOnly) {
            try {
                engine.setReadOnly(false)
            } catch (failure: StorageException) {
                log.error(NOT_STORED_LOG, document.shown, failure)
                return ShimResult.fail("Loaded definitions from $name. $NOT_STORED")
            }
        }
        return ShimResult.success("Loaded definitions from $name")
    }

    /** Returns what there is to say about what the import of [report] did not take as its document has it. */
    private fun remarksOf(report: LegacyImportReport): List<String> =
        report.issues.map { it.message } +
            report.imported.conflicts.map { it.message } +
            report.unreadableRuleSets.map { "The service '${it.serviceName}': ${it.message}" }

    /**
     * Returns what [name] names. A relative name starts at the definitions location. A document has to be
     * inside that location in two ways: by its name as it's written, and by where it really is, after every
     * link on the way to it is followed. A name that leads outside is not followed at all.
     */
    private fun named(name: String): Named {
        val root = location.toAbsolutePath().normalize()
        val written =
            try {
                root.resolve(name).normalize()
            } catch (_: InvalidPathException) {
                return Named.Missing
            }
        if (!written.startsWith(root)) return Named.Outside
        val file =
            try {
                written.toRealPath()
            } catch (_: IOException) {
                // Nothing that the server gets to has the name: no file, or a link that leads nowhere.
                return Named.Missing
            }
        val realRoot = root.toRealPath()
        return when {
            !file.startsWith(realRoot) -> Named.Outside
            !Files.isRegularFile(file) -> Named.NoFile
            else -> Named.Document(file, withoutControls(realRoot.relativize(file).toString()))
        }
    }

    /** What the parameter `file` of a request names. */
    private sealed interface Named {
        /**
         * A file inside the definitions location. [file] is where it really is, and [shown] is its path from
         * the definitions location, as the log may hold it: it's made of the names of files that exist.
         */
        class Document(
            val file: Path,
            val shown: String,
        ) : Named

        /** Nothing: no file has the name. */
        data object Missing : Named

        /** Something outside the definitions location. */
        data object Outside : Named

        /** Something inside the definitions location that is no file, such as a directory. */
        data object NoFile : Named
    }

    internal companion object {
        /** The path of the endpoint. */
        const val PATH = "/home"

        private val log = LoggerFactory.getLogger(ShimHome::class.java)

        private const val ACTION = "action"
        private const val INIT = "init"
        private const val DELETE_ALL = "deleteAllServices"

        private const val FILE = "file"
        private const val URL = "url"
        private const val TRANSIENT_STATE = "transientState"
        private const val FILTER_TAG = "filterTag"

        private const val NO_FILE = "The request names no file to load definitions from."
        private const val NO_URL =
            "Unable to load service definitions from a URL. The server reads a file inside its definitions location only."
        private const val OUTSIDE = "It is not inside the definitions location of the server."
        private const val NO_DOCUMENT = "It is no definitions document of Mockey 2.x."
        private const val NOT_STORED =
            "The server could not store them in its definitions location. It holds them in memory, in read-only mode. " +
                "The log of the server says why."

        private const val NOT_STORED_LOG = "The definitions of {} cannot be stored. The server holds them in memory, in read-only mode."

        /** The characters that end a line or steer a terminal: the control characters and the separators of lines and paragraphs. */
        private val CONTROLS = Regex("""[\p{Cc}\p{Zl}\p{Zp}]""")

        /** Returns how a result starts that says that the file [name] of a request was not loaded, in the words of Mockey 2.x. */
        private fun unable(name: String): String = "Unable to load service definitions with filename: '$name'."

        /** Returns [text] with a space in the place of each of the [CONTROLS], so that it stays on its line of the log. */
        private fun withoutControls(text: String): String = text.replace(CONTROLS, " ")
    }
}
