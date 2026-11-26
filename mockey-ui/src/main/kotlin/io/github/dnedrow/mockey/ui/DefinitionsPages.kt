package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.ContentType
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul

/**
 * The page for import and export: the link that downloads the definitions as one document, and the form
 * that imports a document. After an import, the browser goes to the page that shows the import report.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val DefinitionsArea =
    Area { routing, ui ->
        with(routing) {
            get(DefinitionsPages.PAGE) { ui.show(call) { view, _ -> DefinitionsPages.exchange(view, FormState.EMPTY) } }
            get(DefinitionsPages.EXPORT) {
                ui.download(call) { Download(DefinitionsPages.EXPORT_NAME, ContentType.Application.Json, ui.client.exportDefinitions()) }
            }
            post(DefinitionsPages.IMPORT) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    DefinitionsPages.exchange(view, state)
                }
                ui.submit(call, again) { input ->
                    val document = ImportForm.documentOf(input)
                    val tags = tagsIn(input.text(ImportForm.TAGS))
                    val report = ui.client.importDefinitions(document.bytes, ImportForm.isXml(document.bytes), tags)
                    Outcome.Report(DefinitionsPages.imported(document.fileName, tags, report), ImportForm.entriesOf(report))
                }
            }
        }
    }

/** The page for import and export, and the page that shows an import report. */
internal object DefinitionsPages {
    /** The path of the page for import and export. */
    const val PAGE = "/definitions"

    /** The path of the download with the definitions document. */
    const val EXPORT = "/definitions/export"

    /** The path of the action that imports a document. */
    const val IMPORT = "/definitions/import"

    /** The file name under which a browser saves the definitions document. */
    const val EXPORT_NAME = "mockey-definitions.json"

    /** What the report calls the kinds of import items that the admin API names. An unknown kind is shown by its name. */
    private val KINDS =
        mapOf(
            "service" to "Service",
            "scenario" to "Scenario",
            "plan" to "Plan",
            "twistConfiguration" to "Twist configuration",
            "setting" to "Setting",
        )

    /** Returns the page with the download link and the import form. The form is displayed with [state]. */
    fun exchange(
        view: View,
        state: FormState,
    ): Page =
        Page("Import and export") {
            h2 { +"Export" }
            p { +ABOUT_EXPORT }
            p(classes = "actions") { a(href = EXPORT) { +"Download the definitions" } }
            h2 { +"Import" }
            p { +ABOUT_IMPORT }
            ul { IMPORT_RULES.forEach { li { +it } } }
            postForm(view, IMPORT, withFiles = true) {
                rejectionOf(state)
                fileField(state, ImportForm.DOCUMENT, "Document", hint = DOCUMENT_HINT)
                textField(state, ImportForm.TAGS, "Tags", hint = TAGS_HINT)
                otherProblemsOf(state)
                submit("Import the document")
            }
        }

    /**
     * Returns the page that shows [report], which is what the server made of the document in the file
     * [fileName]. The import was asked to add [tags] to everything it added.
     */
    fun imported(
        fileName: String,
        tags: List<String>,
        report: ImportReportDto,
    ): Page =
        Page("Import report") {
            p { +summaryOf(fileName, report) }
            // The server holds a tag in lower case, and it holds each tag once.
            val given = tags.map { it.lowercase() }.distinct()
            if (given.isNotEmpty()) {
                p {
                    +"$GIVES_TAGS "
                    tagsOf(given)
                }
            }
            h2 { +"Additions" }
            itemsOf(report.additions, NO_ADDITIONS)
            h2 { +"Conflicts" }
            if (report.conflicts.isNotEmpty()) p { +ABOUT_CONFLICTS }
            itemsOf(report.conflicts, NO_CONFLICTS)
            report.appliedDefaultPlan?.let { applied ->
                h2 { +"Default plan" }
                p {
                    +"The import made "
                    a(href = PlanPages.addressOf(applied.plan.id)) { +applied.plan.name }
                    +" the default plan and applied it."
                }
                planOutcomeOf(applied) { h3 { +it } }
            }
            if (report.issues.isNotEmpty()) {
                h2 { +"Parts that could not be read" }
                p { +ABOUT_ISSUES }
                ul { report.issues.forEach { li { +it } } }
            }
            if (report.unreadableRuleSets.isNotEmpty()) {
                h2 { +"Rule sets that are not well formed" }
                p { +ABOUT_RULE_SETS }
                ul {
                    for (ruleSet in report.unreadableRuleSets) {
                        li {
                            a(href = ServicePages.addressOf(ruleSet.serviceId)) { +ruleSet.serviceName }
                            +": ${ruleSet.message}"
                        }
                    }
                }
            }
            p(classes = "actions") {
                a(href = ServicesPages.OVERVIEW) { +"Services" }
                a(href = PAGE) { +"Import another document" }
            }
        }

    /** Returns the sentences that say which file the server read, and how many additions and conflicts it reports. */
    private fun summaryOf(
        fileName: String,
        report: ImportReportDto,
    ): String {
        val read = if (fileName.isEmpty()) "the document" else briefly(fileName)
        val additions = if (report.additions.size == 1) "1 addition" else "${report.additions.size} additions"
        val conflicts = if (report.conflicts.size == 1) "1 conflict" else "${report.conflicts.size} conflicts"
        return "The server read $read. The import made $additions and found $conflicts."
    }

    /** Shows [items], each with its kind and with the sentence of the server about it, or [none] if there are no items. */
    private fun FlowContent.itemsOf(
        items: List<ImportItemDto>,
        none: String,
    ) {
        if (items.isEmpty()) {
            p { +none }
            return
        }
        ul(classes = "report") {
            for (item in items) {
                li {
                    span(classes = "kind") { +(KINDS[item.kind] ?: item.kind) }
                    +": ${item.message}"
                }
            }
        }
    }

    private const val ABOUT_EXPORT =
        "The definitions document holds everything that the server stores: the services with their scenarios, the plans, " +
            "the twist configurations, and the settings. It does not hold the history, the tag filter, or the credentials " +
            "of the outbound proxy. A server can start from the document, and the import below reads it."
    private const val ABOUT_IMPORT =
        "An import adds what the server does not have yet, and it removes nothing. " +
            "Its report lists every part of the document as an addition or as a conflict."
    private val IMPORT_RULES =
        listOf(
            "A service with a new name is added with its scenarios.",
            "A service whose name is in use is merged into the existing service, " +
                "which gets the scenarios, real URLs, and tags that it lacks.",
            "A plan or a twist configuration is added if its name is not in use.",
            "A setting is taken from the document if the server has none. " +
                "If that makes a plan the default plan, the server applies the plan.",
        )
    private const val DOCUMENT_HINT =
        "A definitions document that a Mockey server exported, or an export document of Mockey 2.x. " +
            "The UI tells the two apart by their content."
    private const val TAGS_HINT =
        "Optional. The import adds these tags to every service, scenario, and plan that it adds, " +
            "and to every service that it merges into. Separate the tags with spaces."

    private const val GIVES_TAGS = "The import gave these tags to what it added, and to the services that it merged into:"
    private const val NO_ADDITIONS = "The import added nothing."
    private const val NO_CONFLICTS = "The import found no conflicts."
    private const val ABOUT_CONFLICTS = "These parts of the document were not added, or not in the way the document has them."
    private const val ABOUT_ISSUES = "The import could not read these parts of the document, and it went on without them."
    private const val ABOUT_RULE_SETS =
        "The import added these rule sets as they are. A rule set that is not well formed is never satisfied."
}

/** The form that imports a document: the names of its fields, and what it reads from a submitted form. */
internal object ImportForm {
    /** The file field for the document. Messages of the admin API about the document name it the same way. */
    const val DOCUMENT = "document"

    /** The field for the tags of the import. */
    const val TAGS = "tags"

    private const val NO_FILE = "Choose the document to import."
    private const val EMPTY_FILE = "The file is empty, so there is nothing to import."

    private val UTF_8_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF_16_MARKS = listOf(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
    private const val WHITESPACE = " \t\r\n"

    /**
     * Returns the file that was uploaded with the form.
     *
     * @throws InvalidInput if no file was chosen, or if the file is empty.
     */
    fun documentOf(input: FormInput): Upload {
        val document = input.upload(DOCUMENT) ?: throw InvalidInput(DOCUMENT, NO_FILE)
        if (document.bytes.isEmpty()) throw InvalidInput(DOCUMENT, EMPTY_FILE)
        return document
    }

    /**
     * Returns whether [document] is XML, which makes it an export document of Mockey 2.x. Anything else is
     * sent to the server as a definitions document, which is JSON in UTF-8.
     *
     * A document is XML if its first character is `<`, after an optional byte order mark and whitespace. A
     * document that starts with the byte order mark of UTF-16 is XML as well, because the admin API only
     * reads JSON in UTF-8.
     */
    fun isXml(document: ByteArray): Boolean {
        if (UTF_16_MARKS.any { document.startsWith(it) }) return true
        val start = if (document.startsWith(UTF_8_MARK)) UTF_8_MARK.size else 0
        val first = (start until document.size).firstOrNull { document[it].toInt().toChar() !in WHITESPACE }
        return first != null && document[first] == '<'.code.toByte()
    }

    /** Returns the number of entries that the page of [report] lists, which is what the UI budgets its kept reports by. */
    fun entriesOf(report: ImportReportDto): Int {
        val ofPlan = report.appliedDefaultPlan?.let { it.skippedItems.size + it.unresolvedScenarios.size } ?: 0
        return report.additions.size + report.conflicts.size + report.issues.size + report.unreadableRuleSets.size + ofPlan
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
