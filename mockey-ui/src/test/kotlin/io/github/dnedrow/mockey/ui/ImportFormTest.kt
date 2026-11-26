package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How the import form reads what was uploaded: which document is XML, and what counts as a document at all. */
class ImportFormTest {
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** A document is XML if its first character is `<`. A byte order mark and whitespace in front of it don't count. */
    @Test
    fun aDocumentThatStartsWithAnAngleBracketIsXml() {
        val xml =
            listOf(
                utf8("<?xml version=\"1.0\"?><mockservice/>"),
                utf8("<mockservice/>"),
                utf8(" \t\r\n<mockservice/>"),
                bytes(0xEF, 0xBB, 0xBF) + utf8("<mockservice/>"),
                bytes(0xEF, 0xBB, 0xBF) + utf8("\n  <mockservice/>"),
                utf8("<"),
            )

        assertEquals(xml.map { true }, xml.map(ImportForm::isXml))
    }

    /** A document in UTF-16 can only be XML, because the admin API reads JSON in UTF-8 only. */
    @Test
    fun aDocumentWithAByteOrderMarkOfUtf16IsXml() {
        val bigEndian = bytes(0xFE, 0xFF) + "<a/>".toByteArray(Charsets.UTF_16BE)
        val littleEndian = bytes(0xFF, 0xFE) + "<a/>".toByteArray(Charsets.UTF_16LE)
        val marksOnly = listOf(bytes(0xFE, 0xFF), bytes(0xFF, 0xFE))

        assertEquals(listOf(true, true, true, true), (listOf(bigEndian, littleEndian) + marksOnly).map(ImportForm::isXml))
    }

    /** Anything else goes to the server as a definitions document, which is JSON. The server says whether it is one. */
    @Test
    fun anyOtherDocumentIsSentAsJson() {
        val others =
            listOf(
                utf8("""{"formatVersion":1}"""),
                utf8("  {\"a\":\"<b>\"}"),
                bytes(0xEF, 0xBB, 0xBF) + utf8("{}"),
                utf8("name,value\n<a>,1"),
                utf8("x<"),
                utf8(" \r\n\t "),
                bytes(0xEF, 0xBB, 0xBF),
                bytes(0xEF, 0xBB),
                bytes(0xFE),
                bytes(0xFF),
                bytes(0x00, 0x3C),
                bytes(0xC3, 0xA9, 0x3C),
                ByteArray(0),
            )

        assertEquals(others.map { false }, others.map(ImportForm::isXml))
    }

    /** The form needs a file that has content. A file field without a file, and an empty file, are rejected at the document field. */
    @Test
    fun theFormNeedsAFileWithContent() {
        val chosen = Upload("definitions.json", utf8("{}"))
        val withFile = FormInput(emptyMap(), mapOf("document" to chosen))
        val inAnotherField = FormInput(emptyMap(), mapOf("file" to chosen))
        val empty = FormInput(emptyMap(), mapOf("document" to Upload("empty.json", ByteArray(0))))

        val missing = assertFailsWith<InvalidInput> { ImportForm.documentOf(inAnotherField) }
        val withoutContent = assertFailsWith<InvalidInput> { ImportForm.documentOf(empty) }

        assertEquals(chosen, ImportForm.documentOf(withFile))
        assertEquals(listOf("document" to "Choose the document to import."), missing.errors.map { it.field to it.message })
        val nothing = "The file is empty, so there is nothing to import."
        assertEquals(listOf("document" to nothing), withoutContent.errors.map { it.field to it.message })
    }

    /** The entry count of a report covers everything that its page lists, so that the UI can budget the reports that it keeps. */
    @Test
    fun aReportCountsWithEverythingThatItsPageLists() {
        val item = ImportItemDto("service", "Customers", "The service 'Customers' was added.")
        val ruleSet = DiagnosticDto("unreadableRuleSet", "svc-customers", "Customers", "The request rules are not well formed.")
        val full = ImportReportDto(listOf(item, item), listOf(item), Samples.applyReport, listOf("a", "b", "c"), listOf(ruleSet))

        assertEquals(0, ImportForm.entriesOf(ImportReportDto(emptyList(), emptyList())))
        assertEquals(3, ImportForm.entriesOf(ImportReportDto(listOf(item, item), listOf(item))))
        assertEquals(2 + 1 + 3 + 1 + 2, ImportForm.entriesOf(full))
    }
}
