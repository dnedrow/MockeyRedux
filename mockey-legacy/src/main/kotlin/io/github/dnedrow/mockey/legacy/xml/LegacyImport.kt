package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * One import of a legacy source: of its main document, and of the service files that the main document
 * refers to. [reader] reads the files, and with them it says what of the file system the source can reach.
 *
 * The services of the files that the main document refers to come first, in the order of the references, and
 * then the services that the main document holds itself. That is the order in which Mockey 2.x loads them.
 */
internal class LegacyImport(
    private val reader: LegacyXmlReader,
) {
    private val issues = ArrayList<LegacyXmlIssue>()

    /** Returns what the import makes of the main document, which was read as [main]. A message calls it [name]. */
    fun of(
        main: LegacyXmlReadResult,
        name: String,
    ): LegacyImportResult {
        issues += main.issues
        val root = main.document?.let { rootOf(it, name) } ?: return LegacyImportResult(Definitions.EMPTY, issues.toList())
        val services = root.childElements(SERVICE_REFERENCE).flatMap(::referenced) + root.childElements(LegacyXmlDefinitions.SERVICE)
        return LegacyImportResult(LegacyXmlDefinitions.map(services), issues.toList())
    }

    /** Returns the root element of [document] when the document holds definitions, and reports a document that holds none. */
    private fun rootOf(
        document: Document,
        name: String,
    ): Element? {
        val root = document.documentElement
        if (root.isNamed(LegacyXmlDefinitions.ROOT)) return root
        val found = root.localName ?: root.nodeName
        return issue(
            LegacyXmlIssueKind.NOT_A_DEFINITIONS_DOCUMENT,
            "$name holds no definitions of Mockey 2.x: its root element is '$found', not '${LegacyXmlDefinitions.ROOT}'.",
        )
    }

    /** Returns the `service` elements of the service file that [reference] names. A file that cannot be read has none. */
    private fun referenced(reference: Element): List<Element> {
        val name = reference.getAttribute("file")
        if (name.isBlank()) {
            issue(LegacyXmlIssueKind.UNREADABLE_FILE, "A service reference names no file.")
            return emptyList()
        }
        val file =
            try {
                Path.of(name)
            } catch (_: InvalidPathException) {
                issue(LegacyXmlIssueKind.UNREADABLE_FILE, "The service reference '$name' names no file.")
                return emptyList()
            }
        val read = reader.read(file)
        issues += read.issues
        val root = read.document?.let { rootOf(it, "The file $name") }
        return root?.childElements(LegacyXmlDefinitions.SERVICE).orEmpty()
    }

    private fun issue(
        kind: LegacyXmlIssueKind,
        message: String,
    ): Nothing? {
        issues += LegacyXmlIssue(kind, message)
        return null
    }

    private companion object {
        /** The name of the element with which a main document refers to the file of a service. */
        const val SERVICE_REFERENCE = "serviceref"
    }
}
