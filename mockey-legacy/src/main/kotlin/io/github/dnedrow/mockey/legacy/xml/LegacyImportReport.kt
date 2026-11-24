package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.definitions.Diagnostic
import io.github.dnedrow.mockey.core.definitions.ImportReport

/**
 * What the import of a legacy source into an engine did, and what there is to say about the source.
 *
 * [imported] is the report of the engine. It names every service, scenario, plan, and twist configuration
 * that was read from the source, and every setting that the source has, as an addition or as a conflict.
 *
 * [issues] say what of the source could not be read or could not be taken as it is: a file that is missing
 * or that cannot be read, a reference that leads out of the directory that is imported, and a reference by
 * number that names nothing. What could not be read is no definition, so it is neither an addition nor a
 * conflict.
 *
 * [unreadableRuleSets] are the rule sets that the import added and that are not well formed: the request
 * rules of a service, and the rule-set criterion of a scenario. The import keeps the text of such a rule set
 * as the source has it. Each one is a diagnostic of the engine, which names the service, identifies the
 * scenario when the rule set is the criterion of one, and says what is wrong with the rule set.
 *
 * A scenario whose rules flag is on has a rule set also when it has no match text. That rule set has no text,
 * so it is not well formed, and it is among these.
 */
public data class LegacyImportReport(
    public val imported: ImportReport,
    public val issues: List<LegacyXmlIssue>,
    public val unreadableRuleSets: List<Diagnostic>,
)
