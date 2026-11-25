package io.github.dnedrow.mockey.legacy.xml

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the XML documents of Mockey 2.x definitions and resolves their inclusions, without letting a document
 * reach anything outside the directory that is imported.
 *
 * A document that declares a document type is not read at all, so no entity of it is resolved and nothing is
 * fetched for it.
 *
 * Mockey 2.x splits its definitions over files with XInclude elements. The reader resolves them itself: an
 * `xi:include` element is replaced by the root element of the XML document that it names, or, with
 * `parse="text"`, by the text of the file that it names. An inclusion is followed only to a file inside
 * [importRoot], also where a symbolic link leads. Without an import root, no file is read at all.
 *
 * A reference is resolved as Mockey 2.x resolves it. In a document that [read] or [readText] is given, a
 * relative reference starts at the import root, wherever the document is. In a document that was included,
 * it starts at the directory of that document.
 *
 * What cannot be read is not thrown but reported, see [LegacyXmlReadResult.issues]. An inclusion that fails
 * is taken out of its document, and the rest of the document is read.
 */
public class LegacyXmlReader(
    importRoot: Path?,
) {
    private val root: Path? = importRoot?.toAbsolutePath()?.normalize()

    /** The import root as the file system has it, with every symbolic link followed. */
    private val realRoot: Path? by lazy { root?.let { realPathOf(it) ?: it } }

    /**
     * Reads the XML document in [file], which is a file inside the import root. A relative path starts at
     * the import root.
     */
    public fun read(file: Path): LegacyXmlReadResult {
        val reading = Reading()
        val root = root
        val document =
            if (root == null) {
                reading.outside("The file $file was not read: $NO_DIRECTORY", file)
            } else {
                reading.given(root.resolve(file).normalize(), root)
            }
        return LegacyXmlReadResult(document, reading.issues)
    }

    /** Reads the XML document [document], which is no file. A relative reference of it starts at the import root. */
    public fun readText(document: String): LegacyXmlReadResult {
        val reading = Reading()
        return LegacyXmlReadResult(reading.given(document), reading.issues)
    }

    /**
     * Reads the XML document with the bytes [document], which is no file. The document says in which charset
     * it is written, as the document of a file does, and one that says nothing is in UTF-8. A relative
     * reference of it starts at the import root.
     */
    public fun readBytes(document: ByteArray): LegacyXmlReadResult {
        val reading = Reading()
        return LegacyXmlReadResult(reading.given(document), reading.issues)
    }

    /** One reading of a document with everything that it includes. It collects the [issues] of all of it. */
    private inner class Reading {
        val issues = ArrayList<LegacyXmlIssue>()

        /** The files that are being read, each of which includes the next. */
        private val including = LinkedHashSet<Path>()

        /** Adds an issue, and returns `null` for what could not be read because of it. */
        private fun issue(
            kind: LegacyXmlIssueKind,
            message: String,
            file: Path?,
        ): Nothing? {
            issues += LegacyXmlIssue(kind, message, file)
            return null
        }

        fun outside(
            message: String,
            file: Path?,
        ): Nothing? = issue(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT, message, file)

        private fun unreadable(
            message: String,
            file: Path?,
        ): Nothing? = issue(LegacyXmlIssueKind.UNREADABLE_FILE, message, file)

        /** Reads the XML document in [file], which is read only when it is inside the import root [root]. */
        fun given(
            file: Path,
            root: Path,
        ): Document? = if (isInside(file)) document(file, base = root) else outside("${named(file)} was not read: $OUTSIDE", file)

        /** Reads the XML document [text], which is no file. */
        fun given(text: String): Document? = parsed(InputSource(StringReader(text)), null)?.withInclusionsResolved(root, null)

        /** Reads the XML document with the bytes [bytes], which is no file. */
        fun given(bytes: ByteArray): Document? = parsed(InputSource(ByteArrayInputStream(bytes)), null)?.withInclusionsResolved(root, null)

        /** Reads the XML document in [file], whose relative references start at [base]. */
        private fun document(
            file: Path,
            base: Path,
        ): Document? {
            val reached = realPathOf(file) ?: file
            if (!including.add(reached)) {
                return issue(LegacyXmlIssueKind.INCLUSION_CYCLE, "${named(file)} was not included a second time: $CYCLE", file)
            }
            try {
                val bytes = bytesOf(file) ?: return null
                return parsed(InputSource(ByteArrayInputStream(bytes)), file)?.withInclusionsResolved(base, file)
            } finally {
                including.remove(reached)
            }
        }

        private fun bytesOf(file: Path): ByteArray? =
            try {
                Files.readAllBytes(file)
            } catch (e: IOException) {
                unreadable("${named(file)} ${problemOf(e)}", file)
            }

        private fun parsed(
            source: InputSource,
            file: Path?,
        ): Document? =
            try {
                newDocumentBuilder().parse(source)
            } catch (e: SAXException) {
                if (e.message.orEmpty().contains("DOCTYPE", ignoreCase = true)) {
                    issue(LegacyXmlIssueKind.EXTERNAL_ENTITY, "${named(file)} was not read: $DOCUMENT_TYPE", file)
                } else {
                    issue(LegacyXmlIssueKind.MALFORMED_XML, "${named(file)} is not well-formed XML${whereOf(e)}: ${e.message}", file)
                }
            } catch (e: IOException) {
                unreadable("${named(file)} ${problemOf(e)}", file)
            }

        /**
         * Puts what each inclusion of the document names in the place of the inclusion, and takes out an
         * inclusion that names nothing that can be read. Returns the document, or `null` when nothing is
         * left of it: its root element was an inclusion that failed.
         */
        private fun Document.withInclusionsResolved(
            base: Path?,
            file: Path?,
        ): Document? {
            for (inclusion in inclusionsOf(this)) {
                val included = included(inclusion, base, file)
                val parent = inclusion.parentNode
                if (included == null) parent.removeChild(inclusion) else parent.replaceChild(included, inclusion)
            }
            return takeIf { documentElement != null }
        }

        /**
         * Returns what [inclusion] names, as a node of its document, or `null` when that cannot be read. The
         * document is the one in [file], and its relative references start at [base].
         */
        private fun included(
            inclusion: Element,
            base: Path?,
            file: Path?,
        ): Node? {
            val href = inclusion.getAttribute("href")
            val parse = inclusion.getAttribute("parse").ifEmpty { "xml" }
            val notIncluded = "${named(file)} includes '$href', which was not included:"
            val target =
                when {
                    href.isBlank() -> unreadable("${named(file)} has an inclusion that names no file.", file)
                    parse != "xml" && parse != "text" -> unreadable("$notIncluded '$parse' $NO_PARSE", file)
                    parse == "text" && inclusion.parentNode is Document -> unreadable("$notIncluded $NO_ROOT", file)
                    base == null -> outside("$notIncluded $NO_DIRECTORY", file)
                    isUrl(href) -> outside("$notIncluded $URL", file)
                    else -> targetOf(href, base) ?: outside("$notIncluded $OUTSIDE", file)
                } ?: return null
            val document = inclusion.ownerDocument
            return if (parse == "text") {
                textOf(target, inclusion.getAttribute("encoding"))?.let(document::createTextNode)
            } else {
                document(target, base = target.parent)?.let { document.importNode(it.documentElement, true) }
            }
        }

        /** Returns the text of [file] in the charset [encoding], which is UTF-8 unless an inclusion names another one. */
        private fun textOf(
            file: Path,
            encoding: String,
        ): String? {
            val charset =
                try {
                    if (encoding.isBlank()) StandardCharsets.UTF_8 else Charset.forName(encoding)
                } catch (_: IllegalArgumentException) {
                    return unreadable("${named(file)} was not included: '$encoding' names no charset.", file)
                }
            return try {
                Files.readString(file, charset)
            } catch (_: CharacterCodingException) {
                unreadable("${named(file)} was not included: it is no text in the charset ${charset.name()}.", file)
            } catch (e: IOException) {
                unreadable("${named(file)} ${problemOf(e)}", file)
            }
        }
    }

    /**
     * Tells whether the reference [href] is a URL, and not the path of a file. What looks like a URL with a
     * scheme of one letter is a path that starts with the letter of a drive.
     */
    private fun isUrl(href: String): Boolean =
        try {
            URI(href).scheme.orEmpty().length > 1
        } catch (_: URISyntaxException) {
            false
        }

    /**
     * Returns the file that the path [href] names, or `null` when that is no file inside the import root. A
     * relative path starts at [base].
     */
    private fun targetOf(
        href: String,
        base: Path,
    ): Path? =
        try {
            base.resolve(href).normalize().takeIf(::isInside)
        } catch (_: InvalidPathException) {
            null
        }

    /**
     * Tells whether [path] is inside the import root. A file that exists is asked where it really is, so
     * that a symbolic link inside the import root does not lead out of it.
     */
    private fun isInside(path: Path): Boolean {
        val root = root ?: return false
        return realPathOf(path)?.startsWith(realRoot ?: root) ?: path.startsWith(root)
    }

    private fun realPathOf(path: Path): Path? =
        try {
            path.toRealPath()
        } catch (_: IOException) {
            null
        }

    /** Returns how a message names [file]: by its path from the import root. Without a file, it names the document. */
    private fun named(file: Path?): String {
        if (file == null) return "The document"
        val root = root
        return "The file ${if (root != null && file.startsWith(root)) root.relativize(file) else file}"
    }

    private fun problemOf(e: IOException): String =
        if (e is NoSuchFileException) "does not exist." else "cannot be read: ${e.message ?: e.javaClass.simpleName}"

    private fun whereOf(e: SAXException): String = if (e is SAXParseException && e.lineNumber > 0) " in line ${e.lineNumber}" else ""

    /** The inclusions of [document] in their order. An inclusion inside another one is none: it stands for nothing in the document. */
    private fun inclusionsOf(document: Document): List<Element> {
        val all = document.getElementsByTagNameNS(XINCLUDE_NAMESPACE, INCLUDE)
        return (0 until all.length)
            .map { all.item(it) as Element }
            .filter { inclusion -> generateSequence(inclusion.parentNode) { it.parentNode }.none { it.isInclusion() } }
    }

    private fun Node.isInclusion(): Boolean = namespaceURI == XINCLUDE_NAMESPACE && localName == INCLUDE

    private fun newDocumentBuilder(): DocumentBuilder =
        DocumentBuilderFactory
            .newDefaultInstance()
            .apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }.newDocumentBuilder()
            .apply { setErrorHandler(Faults) }

    /** Ends the parsing of a document at its first fault, and keeps the parser from writing to the error output. */
    private object Faults : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    private companion object {
        const val XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude"
        const val INCLUDE = "include"
        const val OUTSIDE = "it is not inside the directory that is imported."
        const val URL = "it is a URL, and an inclusion is followed to a file of the directory that is imported only."
        const val NO_DIRECTORY = "no directory is imported, so no file is read."
        const val DOCUMENT_TYPE = "it declares a document type, and with it entities, which an import does not process."
        const val NO_PARSE = "is no way to include a file. An inclusion is read as xml or as text."
        const val NO_ROOT = "a text cannot be the root element of a document."
        const val CYCLE = "it includes itself, directly or through a file that it includes."
    }
}

/**
 * What reading a legacy XML document gave: the [document] with its inclusions resolved, or `null` when it
 * could not be read, and the [issues] of the document and of everything that it includes.
 */
public data class LegacyXmlReadResult(
    public val document: Document?,
    public val issues: List<LegacyXmlIssue>,
)
