package io.github.dnedrow.mockey.legacy.xml

/**
 * Imports an export document of Mockey 2.x: one XML document that holds the definitions, with every service
 * and every scenario inline, and with the plans, the twist configurations, and the settings.
 *
 * The document is a text, or the bytes of one, and no file, so an import of it reads no file at all. A
 * document that refers to files, as the main file of a definitions directory does, is imported without what
 * those files hold, and every such reference is among the issues of the result. [LegacyDirectoryImporter]
 * imports such a document.
 */
public object LegacyDocumentImporter {
    /**
     * Imports the export document [document]. The result holds the definitions of the document.
     * [LegacyImportResult.importInto] imports them into an engine.
     */
    public fun import(document: String): LegacyImportResult {
        val reader = LegacyXmlReader(importRoot = null)
        return LegacyImport(reader).of(reader.readText(document), "The document")
    }

    /**
     * Imports the export document with the bytes [document], as a file or the answer of a server holds it.
     * The document says in which charset it is written, with its XML declaration or with a byte order mark,
     * and one that says nothing is in UTF-8. Bytes that are no text in that charset are reported, and the
     * result holds no definitions then.
     */
    public fun import(document: ByteArray): LegacyImportResult {
        val reader = LegacyXmlReader(importRoot = null)
        return LegacyImport(reader).of(reader.readBytes(document), "The document")
    }
}
