package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions

/**
 * What an import read from a legacy source: the [definitions] of the source as definitions of this system,
 * and the [issues] of the source, which say what of it could not be taken as it is.
 *
 * The definitions have identifiers of their own, and what the source refers to by a number is referred to
 * by them: the default scenario and the error scenario of a service, the universal error scenario, the twist
 * configuration that is enabled, and the default plan. A reference that names nothing in the source is not
 * in the definitions. It is among the issues.
 */
public data class LegacyImportResult(
    public val definitions: Definitions,
    public val issues: List<LegacyXmlIssue>,
)
