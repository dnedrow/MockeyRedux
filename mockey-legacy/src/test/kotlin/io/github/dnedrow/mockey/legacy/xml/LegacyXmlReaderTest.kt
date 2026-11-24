package io.github.dnedrow.mockey.legacy.xml

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How legacy XML is read: what a document is kept from reaching, and what becomes of its inclusions. */
class LegacyXmlReaderTest {
    @TempDir
    lateinit var directory: Path

    /** The directory that is imported. What is next to it in [directory] is outside of it. */
    private lateinit var root: Path

    @BeforeTest
    fun createImportRoot() {
        root = Files.createDirectory(directory.resolve("definitions"))
    }

    /** Writes [content] to the file [path], which starts at the import root. */
    private fun file(
        path: String,
        content: String,
    ): Path {
        val file = root.resolve(path).normalize()
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    private fun read(path: String): LegacyXmlReadResult = LegacyXmlReader(root).read(root.resolve(path))

    private fun LegacyXmlReadResult.root(): Element = assertNotNull(document, "The document was not read: $issues").documentElement

    private fun LegacyXmlReadResult.kinds(): List<LegacyXmlIssueKind> = issues.map { it.kind }

    private fun LegacyXmlReadResult.message(): String = issues.single().message

    /** The names of the elements that are children of this element. */
    private fun Element.names(): List<String> = childElements().map { it.localName }

    /** The one child of this element that is named [name]. */
    private fun Element.only(name: String): Element = childElements(name).single()

    private fun including(
        href: String,
        attributes: String = "",
    ) = """<service $XINCLUDE><xi:include href="$href" $attributes/><real_url url="http://example.com"/></service>"""

    // What a document is kept from

    @Test
    fun externalEntityIsNotReadAndIsReported() {
        val secret = file("secret.txt", "not exposed")
        file("main.xml", """<!DOCTYPE root [<!ENTITY secret SYSTEM "${secret.toUri()}">]><root>&secret;</root>""")

        val result = read("main.xml")

        assertNull(result.document)
        assertEquals(listOf(LegacyXmlIssueKind.EXTERNAL_ENTITY), result.kinds())
        assertEquals(root.resolve("main.xml"), result.issues.single().file)
        assertTrue("main.xml" in result.message(), result.message())
    }

    @Test
    fun aDocumentTypeIsNotProcessedWhateverItDeclares() {
        file("main.xml", """<!DOCTYPE root [<!ENTITY greeting "hello">]><root>&greeting;</root>""")

        val result = read("main.xml")

        assertNull(result.document)
        assertEquals(listOf(LegacyXmlIssueKind.EXTERNAL_ENTITY), result.kinds())
    }

    @Test
    fun inclusionOutsideRootIsNotReadAndIsReported() {
        val outside = Files.writeString(directory.resolve("outside.xml"), "<outside/>")
        file("main.xml", including(outside.toString()))

        val result = read("main.xml")

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), result.kinds())
        assertTrue(outside.toString() in result.message(), result.message())
        assertEquals(listOf("real_url"), result.root().names())
    }

    @Test
    fun aReferenceThatLeavesTheImportRootIsNotFollowed() {
        Files.writeString(directory.resolve("outside.xml"), "<outside/>")
        file("depot/main.xml", including("../outside.xml"))
        file("scenario.xml", including("depot/../../outside.xml", """parse="text""""))

        val fromTheRoot = LegacyXmlReader(root).read(root.resolve("depot/main.xml"))
        val asText = read("scenario.xml")

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), fromTheRoot.kinds())
        assertEquals(listOf("real_url"), fromTheRoot.root().names())
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), asText.kinds())
        assertEquals("", asText.root().textContent)
    }

    @Test
    fun aSymbolicLinkThatLeadsOutOfTheImportRootIsNotFollowed() {
        val outside = Files.writeString(directory.resolve("outside.txt"), "not exposed")
        val link =
            try {
                Files.createSymbolicLink(root.resolve("link.txt"), outside)
            } catch (_: IOException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            }
        assumeTrue(link != null, "This file system has no symbolic links.")
        file("main.xml", including("link.txt", """parse="text""""))

        val result = read("main.xml")

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), result.kinds())
        assertEquals("", result.root().textContent)
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), read("link.txt").kinds())
    }

    @Test
    fun aUrlIsNotFollowed() {
        val inside = file("scenario.xml", "<scenario/>")
        file("by-file-url.xml", including(inside.toUri().toString()))
        file("by-http-url.xml", including("http://127.0.0.1:1/scenario.xml"))

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), read("by-file-url.xml").kinds())
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), read("by-http-url.xml").kinds())
    }

    @Test
    fun aFileOutsideTheImportRootIsNotRead() {
        val outside = Files.writeString(directory.resolve("outside.xml"), "<outside/>")

        val result = LegacyXmlReader(root).read(outside)

        assertNull(result.document)
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), result.kinds())
    }

    @Test
    fun aDocumentThatIsNoFileIncludesNothing() {
        file("scenario.xml", "<scenario/>")

        val withoutDirectory = LegacyXmlReader(null).readText(including("scenario.xml"))
        val file = LegacyXmlReader(null).read(root.resolve("scenario.xml"))

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), withoutDirectory.kinds())
        assertEquals(listOf("real_url"), withoutDirectory.root().names())
        assertNull(file.document)
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), file.kinds())
    }

    // Inclusions

    @Test
    fun anIncludedDocumentTakesThePlaceOfItsInclusion() {
        file("scenario.xml", """<scenario id="2" name="gold"><scenario_match>123</scenario_match></scenario>""")
        file("main.xml", """<service $XINCLUDE><first/><xi:include href="scenario.xml" parse="xml"/><last/></service>""")

        val result = read("main.xml")

        assertEquals(emptyList(), result.issues)
        val children = result.root().childElements()
        assertEquals(listOf("first", "scenario", "last"), children.map { it.localName })
        assertEquals("gold", children[1].getAttribute("name"))
        assertEquals("123", children[1].only("scenario_match").textContent)
    }

    @Test
    fun aReferenceOfADocumentThatIsReadStartsAtTheImportRoot() {
        file("depot/orders/scenarios/created.xml", """<scenario name="created"/>""")
        file("depot/orders/orders.xml", including("depot/orders/scenarios/created.xml"))

        val result = read("depot/orders/orders.xml")

        assertEquals(emptyList(), result.issues)
        assertEquals("created", result.root().only("scenario").getAttribute("name"))
    }

    @Test
    fun aReferenceOfAnIncludedDocumentStartsAtItsDirectory() {
        file("depot/orders/scenarios/created.txt", "created")
        file(
            "depot/orders/scenarios/created.xml",
            """<scenario $XINCLUDE><scenario_response><xi:include href="created.txt" parse="text"/></scenario_response></scenario>""",
        )
        file("depot/orders/orders.xml", including("depot/orders/scenarios/created.xml"))

        val result = read("depot/orders/orders.xml")

        assertEquals(emptyList(), result.issues)
        assertEquals("created", result.root().only("scenario").textContent)
    }

    @Test
    fun anIncludedTextTakesThePlaceOfItsInclusionAsItIs() {
        val text = "<order note=\"caf\u00e9 &amp; cr\u00e8me\"><![CDATA[1 < 2]]></order>\n\u2713 "
        file("body.txt", text)
        file("main.xml", """<scenario_response $XINCLUDE>[<xi:include href="body.txt" parse="text"/>]</scenario_response>""")

        val result = read("main.xml")

        assertEquals(emptyList(), result.issues)
        assertEquals("[$text]", result.root().textContent)
        assertEquals(emptyList(), result.root().childElements())
    }

    @Test
    fun aTextIsReadInTheCharsetThatItsInclusionNames() {
        Files.write(root.resolve("latin.txt"), "caf\u00e9".toByteArray(Charsets.ISO_8859_1))
        file("named.xml", including("latin.txt", """parse="text" encoding="ISO-8859-1""""))
        file("unnamed.xml", including("latin.txt", """parse="text""""))
        file("unknown.xml", including("latin.txt", """parse="text" encoding="no-such-charset""""))

        assertEquals("caf\u00e9", read("named.xml").root().textContent)
        assertEquals(emptyList(), read("named.xml").issues)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("unnamed.xml").kinds())
        assertTrue("UTF-8" in read("unnamed.xml").message(), read("unnamed.xml").message())
        assertEquals("", read("unnamed.xml").root().textContent)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("unknown.xml").kinds())
    }

    @Test
    fun aFileThatDoesNotExistIsReportedAndTheRestIsRead() {
        file("scenario.xml", """<scenario name="present"/>""")
        file(
            "main.xml",
            """<service $XINCLUDE><xi:include href="missing.xml"/><xi:include href="scenario.xml"/><xi:include href="missing.txt" parse="text"/></service>""",
        )

        val result = read("main.xml")

        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE, LegacyXmlIssueKind.UNREADABLE_FILE), result.kinds())
        assertEquals(listOf(root.resolve("missing.xml"), root.resolve("missing.txt")), result.issues.map { it.file })
        assertEquals("The file missing.xml does not exist.", result.issues.first().message)
        assertEquals(listOf("scenario"), result.root().names())
        assertEquals("present", result.root().only("scenario").getAttribute("name"))
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("missing.xml").kinds())
        assertNull(read("missing.xml").document)
    }

    @Test
    fun anInclusionThatCannotBeReadIsReported() {
        file("scenario.xml", "<scenario/>")
        file("without-file.xml", including(""))
        file("unknown-way.xml", including("scenario.xml", """parse="json""""))
        file("text-as-root.xml", """<xi:include $XINCLUDE href="scenario.xml" parse="text"/>""")
        file("missing-root.xml", """<xi:include $XINCLUDE href="missing.xml"/>""")
        file("included-root.xml", """<xi:include $XINCLUDE href="scenario.xml"/>""")

        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("without-file.xml").kinds())
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("unknown-way.xml").kinds())
        assertEquals(listOf("real_url"), read("unknown-way.xml").root().names())
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("text-as-root.xml").kinds())
        assertNull(read("text-as-root.xml").document)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), read("missing-root.xml").kinds())
        assertNull(read("missing-root.xml").document)
        assertEquals("scenario", read("included-root.xml").root().localName)
    }

    @Test
    fun aDocumentThatIncludesItselfIsReported() {
        file("first.xml", """<first $XINCLUDE><xi:include href="second.xml"/></first>""")
        file("second.xml", """<second $XINCLUDE><xi:include href="first.xml"/><kept/></second>""")

        val result = read("first.xml")

        assertEquals(listOf(LegacyXmlIssueKind.INCLUSION_CYCLE), result.kinds())
        assertEquals(root.resolve("first.xml"), result.issues.single().file)
        assertEquals(listOf("kept"), result.root().only("second").names())
    }

    @Test
    fun aDocumentIsIncludedAsOftenAsItIsNamed() {
        file("scenario.xml", "<scenario/>")
        file("main.xml", """<service $XINCLUDE><xi:include href="scenario.xml"/><xi:include href="scenario.xml"/></service>""")

        val result = read("main.xml")

        assertEquals(emptyList(), result.issues)
        assertEquals(2, result.root().childElements("scenario").size)
    }

    // Documents

    @Test
    fun aDocumentThatIsNotWellFormedIsReported() {
        file("main.xml", "<service>\n<scenario>\n</service>")

        val result = read("main.xml")

        assertNull(result.document)
        assertEquals(listOf(LegacyXmlIssueKind.MALFORMED_XML), result.kinds())
        assertTrue(result.message().startsWith("The file main.xml is not well-formed XML in line 3: "), result.message())
    }

    @Test
    fun aRelativePathStartsAtTheImportRoot() {
        file("depot/main.xml", "<service/>")

        val result = LegacyXmlReader(root).read(Path.of("depot", "main.xml"))

        assertEquals(emptyList(), result.issues)
        assertEquals("service", result.root().localName)
    }

    @Test
    fun aDocumentThatIsNoFileIsReadFromItsText() {
        file("scenario.xml", """<scenario name="included"/>""")

        val result = LegacyXmlReader(root).readText(including("scenario.xml"))
        val malformed = LegacyXmlReader(root).readText("<service>")

        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("scenario", "real_url"), result.root().names())
        assertNull(malformed.document)
        assertEquals(listOf(LegacyXmlIssueKind.MALFORMED_XML), malformed.kinds())
        assertNull(malformed.issues.single().file)
        assertTrue(malformed.message().startsWith("The document is not well-formed XML"), malformed.message())
    }

    @Test
    fun aDocumentThatCannotBeReadWritesNothingToTheErrorOutput() {
        file("main.xml", "<service>")
        val written = ByteArrayOutputStream()
        val errorOutput = System.err
        System.setErr(PrintStream(written, true))
        try {
            read("main.xml")
        } finally {
            System.setErr(errorOutput)
        }

        assertEquals("", written.toString())
    }

    private companion object {
        const val XINCLUDE = """xmlns:xi="http://www.w3.org/2001/XInclude""""
    }
}
