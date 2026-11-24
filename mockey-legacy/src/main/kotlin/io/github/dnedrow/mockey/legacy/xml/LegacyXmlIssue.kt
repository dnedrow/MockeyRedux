package io.github.dnedrow.mockey.legacy.xml

import java.nio.file.Path

/**
 * Something that an import has to say about its legacy source. [message] says it in sentences that name what
 * it is about, and [file] is the file that it is about, where it is about a file.
 */
public data class LegacyXmlIssue(
    public val kind: LegacyXmlIssueKind,
    public val message: String,
    public val file: Path? = null,
)

/** What a [LegacyXmlIssue] is about. */
public enum class LegacyXmlIssueKind {
    /** A document declares a document type, in which it could declare entities. Such a document is not read. */
    EXTERNAL_ENTITY,

    /** A reference was not followed, because it names what is not inside the directory that is imported. */
    OUTSIDE_IMPORT_ROOT,

    /** A file that the definitions name does not exist or cannot be read. */
    UNREADABLE_FILE,

    /** A document is not well-formed XML. */
    MALFORMED_XML,

    /** A document includes itself, directly or through the documents that it includes. */
    INCLUSION_CYCLE,

    /** A document is XML, but it holds no definitions of Mockey 2.x. */
    NOT_A_DEFINITIONS_DOCUMENT,
}
