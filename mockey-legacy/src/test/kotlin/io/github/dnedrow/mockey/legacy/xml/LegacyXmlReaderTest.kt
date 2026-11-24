package io.github.dnedrow.mockey.legacy.xml

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyXmlReaderTest {
    @Test
    fun externalEntityIsNotReadAndIsReported() {
        val root = Files.createTempDirectory("mockey-xml")
        val secret = Files.writeString(root.resolve("secret.txt"), "not exposed")
        val document = Files.writeString(
            root.resolve("main.xml"),
            """<!DOCTYPE root [<!ENTITY secret SYSTEM "${secret.toUri()}">]><root>&secret;</root>""",
        )

        val result = LegacyXmlReader(root).read(document)

        assertNull(result.document)
        assertTrue(result.issues.any { it.kind == LegacyXmlIssueKind.EXTERNAL_ENTITY })
    }

    @Test
    fun inclusionOutsideRootIsNotReadAndIsReported() {
        val root = Files.createTempDirectory("mockey-xml")
        val outside = Files.createTempFile("mockey-outside", ".xml")
        Files.writeString(outside, "<outside/>")
        val document = Files.writeString(
            root.resolve("main.xml"),
            """<root xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="${outside.toAbsolutePath()}"/></root>""",
        )

        val result = LegacyXmlReader(root).read(document)

        assertEquals(1, result.issues.count { it.kind == LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT })
        assertEquals(0, result.document!!.documentElement.childNodes.length)
    }
}
