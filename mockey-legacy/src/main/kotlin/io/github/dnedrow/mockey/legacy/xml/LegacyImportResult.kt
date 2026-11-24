package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions

/**
 * What an import read from a legacy source: the [definitions] of the source as definitions of this system,
 * and the [issues] of the source, which say what of it could not be taken as it is.
 */
public data class LegacyImportResult(
    public val definitions: Definitions,
    public val issues: List<LegacyXmlIssue>,
)
