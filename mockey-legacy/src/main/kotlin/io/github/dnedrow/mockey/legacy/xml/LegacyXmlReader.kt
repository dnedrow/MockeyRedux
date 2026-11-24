package io.github.dnedrow.mockey.legacy.xml

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.SAXException

/** Reads legacy XML without allowing XML features to access files outside the import. */
public class LegacyXmlReader(
    importRoot: Path,
) {
    private val root: Path = importRoot.toAbsolutePath().normalize()

    /**
     * Reads [file] and resolves XInclude elements whose targets remain inside [importRoot].
     *
     * A rejected or unreadable file is reported in [LegacyXmlReadResult.issues] and does not escape
     * as an exception. An included document is inserted in place of its `xi:include` element.
     */
    public fun read(file: Path): LegacyXmlReadResult {
        val source = file.toAbsolutePath().normalize()
        if (!source.startsWith(root)) {
            return LegacyXmlReadResult(
                document = null,
                issues = listOf(LegacyXmlIssue(source, LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT, "The XML file is outside the import root.")),
            )
        }
        return readDocument(source, LinkedHashSet())
    }

    private fun readDocument(
        file: Path,
        active: MutableSet<Path>,
    ): LegacyXmlReadResult {
        if (!Files.isRegularFile(file)) {
            return LegacyXmlReadResult(null, listOf(LegacyXmlIssue(file, LegacyXmlIssueKind.UNREADABLE_FILE, "The XML file does not exist or is not a regular file.")))
        }
        if (!active.add(file)) {
            return LegacyXmlReadResult(null, listOf(LegacyXmlIssue(file, LegacyXmlIssueKind.INCLUSION_CYCLE, "The XML inclusion refers back to a file already being read.")))
        }
        return try {
            val document =
                try {
                    Files.newInputStream(file, StandardOpenOption.READ).use { input -> newBuilder().parse(input) }
                } catch (e: SAXException) {
                    LegacyXmlReadResult(null, listOf(LegacyXmlIssue(file, issueKind(e), e.message ?: "The XML document could not be parsed.")))
                } catch (e: Exception) {
                    LegacyXmlReadResult(null, listOf(LegacyXmlIssue(file, LegacyXmlIssueKind.UNREADABLE_FILE, e.message ?: "The XML document could not be read.")))
                }
            if (document is LegacyXmlReadResult) {
                document
            } else {
                resolveIncludes(document as Document, file, active)
            }
        } finally {
            active.remove(file)
        }
    }

    private fun resolveIncludes(
        document: Document,
        includingFile: Path,
        active: MutableSet<Path>,
    ): LegacyXmlReadResult {
        val issues = ArrayList<LegacyXmlIssue>()
        val includes = mutableListOf<Node>()
        collectIncludes(document, includes)
        for (include in includes) {
            val href = include.attributes?.getNamedItem("href")?.nodeValue
            if (href.isNullOrBlank()) {
                issues += LegacyXmlIssue(includingFile, LegacyXmlIssueKind.UNREADABLE_FILE, "An XML inclusion has no href.")
                include.parentNode?.removeChild(include)
                continue
            }
            val target =
                try {
                    val uri = java.net.URI.create(href)
                    if (uri.isAbsolute) {
                        throw OutsideRootReference()
                    }
                    Path.of(href).let { path -> if (path.isAbsolute) path.normalize() else includingFile.parent.resolve(path).normalize() }
                } catch (e: RuntimeException) {
                    issues += LegacyXmlIssue(includingFile, LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT, "The XML inclusion '$href' is not a valid local path.")
                    include.parentNode?.removeChild(include)
                    continue
                }
            val resolvedTarget =
                try {
                    if (Files.exists(target)) target.toRealPath() else target
                } catch (e: Exception) {
                    target
                }
            if (!resolvedTarget.startsWith(root.toRealPath())) {
                issues += LegacyXmlIssue(target, LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT, "The XML inclusion '$href' points outside the import root.")
                include.parentNode?.removeChild(include)
                continue
            }
            val included = readDocument(resolvedTarget, active)
            issues += included.issues
            val parent = include.parentNode
            if (included.document == null || parent == null) {
                parent?.removeChild(include)
                continue
            }
            val fragment = included.document.documentElement
            val replacement = document.createDocumentFragment()
            var child = fragment.firstChild
            while (child != null) {
                val next = child.nextSibling
                replacement.appendChild(document.importNode(child, true))
                child = next
            }
            parent.replaceChild(replacement, include)
        }
        return LegacyXmlReadResult(document, issues)
    }

    private fun collectIncludes(
        node: Node,
        result: MutableList<Node>,
    ) {
        if (node.nodeType == Node.ELEMENT_NODE &&
            node.localName == "include" &&
            node.namespaceURI == XINCLUDE_NAMESPACE
        ) {
            result += node
            return
        }
        var child = node.firstChild
        while (child != null) {
            collectIncludes(child, result)
            child = child.nextSibling
        }
    }

    private fun newBuilder() =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            setExpandEntityReferences(false)
        }.newDocumentBuilder()

    private fun issueKind(exception: SAXException): LegacyXmlIssueKind =
        if (exception.message.orEmpty().contains("DOCTYPE", ignoreCase = true)) {
            LegacyXmlIssueKind.EXTERNAL_ENTITY
        } else {
            LegacyXmlIssueKind.MALFORMED_XML
        }

    private companion object {
        const val XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude"
    }

    private class OutsideRootReference : RuntimeException()
}

/** The result of reading one legacy XML document. */
public data class LegacyXmlReadResult(
    public val document: Document?,
    public val issues: List<LegacyXmlIssue>,
)

/** A problem encountered while reading or resolving legacy XML. */
public data class LegacyXmlIssue(
    public val file: Path,
    public val kind: LegacyXmlIssueKind,
    public val message: String,
)

/** Categories of legacy XML read problems. */
public enum class LegacyXmlIssueKind {
    EXTERNAL_ENTITY,
    OUTSIDE_IMPORT_ROOT,
    UNREADABLE_FILE,
    MALFORMED_XML,
    INCLUSION_CYCLE,
}
