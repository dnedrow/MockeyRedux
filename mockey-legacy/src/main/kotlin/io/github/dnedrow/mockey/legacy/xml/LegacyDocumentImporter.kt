package io.github.dnedrow.mockey.legacy.xml

/**
 * Imports an export document of Mockey 2.x: one XML document that holds the definitions, with every service
 * and every scenario inline.
 *
 * The document is a text and no file, so an import of it reads no file at all. A document that refers to
 * files, as the main file of a definitions directory does, is imported without what those files hold, and
 * every such reference is among the issues of the result. [LegacyDirectoryImporter] imports such a document.
 */
public object LegacyDocumentImporter {
    /** Imports the export document [document]. */
    public fun import(document: String): LegacyImportResult {
        val reader = LegacyXmlReader(importRoot = null)
        return LegacyImport(reader).of(reader.readText(document), "The document")
    }
}
