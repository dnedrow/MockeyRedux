package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.10: the page for import and export. It offers the definitions as a download, and its form imports
 * a document with optional tags. After an import, the browser is on the page that shows the import report.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiDefinitionsTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** What a stand-in server holds for a test: the server itself and a live list of the imports that it got. */
    private class Holding(
        val api: FakeAdminApi,
        val imports: List<Imported>,
    )

    /** Starts a stand-in server that holds the samples and answers an import with what [answerTo] returns. */
    private fun holding(answerTo: (Imported) -> FakeAdminApi.Answer = { json(Samples.importReport) }): Holding {
        val api = started.api()
        val services = api.hold()
        return Holding(api, api.holdDefinitions(services, api.holdPlans(services), answerTo))
    }

    /** Returns a file with [name] that holds [text] in UTF-8, as a test chooses it in the file field. */
    private fun file(
        name: String,
        text: String,
    ): Map<String, Browser.Chosen> = mapOf("document" to Browser.Chosen(name, text.toByteArray(Charsets.UTF_8)))

    /** Returns the requests to [api] that imported a document, each as its request line. */
    private fun importing(api: FakeAdminApi): List<String> = api.requests.filter { it.method == "POST" }.map { it.line }

    /**
     * Returns what [page] shows below each of its second-level headings, as the heading and the texts that
     * follow it. Each item of a list is a text of its own.
     */
    private fun sectionsOf(page: Document): Map<String, List<String>> =
        page.select("main h2").associate { heading ->
            val texts = mutableListOf<String>()
            var next: Element? = heading.nextElementSibling()
            while (next != null && next.tagName() != "h2" && !next.hasClass("actions")) {
                texts += next.select("li").map { it.text() }.ifEmpty { listOf(next.text()) }
                next = next.nextElementSibling()
            }
            heading.text() to texts.toList()
        }

    // The page

    /** The page offers the definitions as a download, and it has the form that imports a document. */
    @Test
    fun thePageOffersTheDownloadAndTheImportForm() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/definitions")

        val page = answer.page
        val form = answer.form()
        assertEquals(200 to "Import and export - Mockey", answer.status to page.title())
        assertEquals(listOf("Export", "Import"), page.select("main h2").map { it.text() })
        assertEquals(4, page.select("main > ul li").size)
        val download = page.select("main p.actions a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Download the definitions" to "/definitions/export"), download)
        val sending = listOf("method", "action", "enctype").map(form::attr)
        assertEquals(listOf("post", "/definitions/import", "multipart/form-data"), sending)
        val fields = form.select("input:not([type=hidden])").map { it.attr("name") to it.attr("type") }
        assertEquals(listOf("document" to "file", "tags" to "text"), fields)
        assertEquals(listOf("Document", "Tags"), form.select("label").map { it.text() })
        assertEquals("Import the document", form.select("button").text())
        val current = page.select("nav a[aria-current=page]").map { it.text() to it.attr("href") }
        assertEquals(listOf("Import/Export" to "/definitions"), current)
        assertEquals(listOf("GET system"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    // The export

    /** The download hands out the definitions document exactly as the server sends it, as a file that the browser saves. */
    @Test
    fun theDownloadHandsOutTheDefinitionsDocumentOfTheServer() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/definitions/export")

        assertEquals(200, answer.status)
        assertEquals("""{"formatVersion":1,"services":["Customers","Orders"],"plans":["Night"]}""", answer.body)
        assertEquals("application/json; charset=UTF-8", answer.header("content-type"))
        assertEquals("attachment; filename=\"mockey-definitions.json\"", answer.header("content-disposition"))
        assertEquals("no-store", answer.header("cache-control"))
        assertTrue(Hostile.isDownload(answer))
        assertEquals(listOf("GET system", "GET definitions/export"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** A download needs a server that answers and that the UI can manage. Otherwise the answer is a page, and no file. */
    @Test
    fun aDownloadNeedsAServerThatAnswers() {
        val held = holding()
        val browser = started.browserFor(held.api)

        held.api.on("GET", "definitions/export") { problem(500, "The server failed to handle the request. Its log says why.") }
        val failed = browser.get("/definitions/export")
        held.api.on("GET", "system") { problem(404, "The admin API has no resource at the path of the request.") }
        val incompatible = browser.get("/definitions/export")
        val before = held.api.requests.map { it.line }
        held.api.close()
        val unreachable = browser.get("/definitions/export")

        val titles = listOf("The server failed", "The server is not compatible with this UI", "The server cannot be reached")
        assertEquals(titles, listOf(failed, incompatible, unreachable).map { it.page.select("main h1").text() })
        assertEquals(listOf(502, 502, 502), listOf(failed, incompatible, unreachable).map { it.status })
        assertEquals(listOf(null, null, null), listOf(failed, incompatible, unreachable).map { it.header("content-disposition") })
        assertEquals(listOf("GET system", "GET definitions/export", "GET system"), before)
    }

    // The import

    /**
     * The scenario "Import with a report": a Mockey 2.x export document is uploaded with a tag, and the UI
     * shows the additions and the conflicts from the import report. The document reaches the server as the
     * bytes that it is, declared as XML.
     */
    @Test
    fun anImportOfAMockey2DocumentShowsTheReportOfTheServer() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\r\n<mockservice name=\"K\u00fcche\"/>"
        val document = xml.toByteArray(Charsets.ISO_8859_1)
        val chosen = mapOf("document" to Browser.Chosen("customers.xml", document))

        val sent = browser.submit(browser.get("/definitions").form(), mapOf("tags" to "migrated"), chosen)
        val report = browser.follow(sent)
        val again = browser.follow(sent)

        val page = report.page
        val imported = held.imports.single()
        assertEquals(303, sent.status)
        assertTrue(sent.header("location")!!.startsWith("/reports/"), sent.header("location"))
        assertEquals(200 to "Import report - Mockey", report.status to page.title())
        val summary = "The server read customers.xml. The import made 1 addition and found 1 conflict."
        val tagged = "The import gave these tags to what it added, and to the services that it merged into: migrated"
        assertEquals(listOf(summary, tagged), page.select("main h1 ~ p").take(2).map { it.text() })
        val sections =
            mapOf(
                "Additions" to listOf("Service: The service 'Customers' was added with 2 scenarios."),
                "Conflicts" to listOf(ABOUT_CONFLICTS, "Scenario: The scenario 'found' is there already."),
            )
        assertEquals(sections, sectionsOf(page))
        assertEquals(listOf("Service", "Scenario"), page.select("main ul.report .kind").map { it.text() })
        val leading = listOf("Services" to "/services", "Import another document" to "/definitions")
        assertEquals(leading, page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals(listOf("POST definitions/import?tag=migrated"), importing(held.api))
        assertEquals("application/xml" to listOf("migrated"), imported.contentType to imported.tags)
        assertContentEquals(document, imported.document)
        assertEquals(report.page.select("main").html(), again.page.select("main").html())
        assertEquals(1, held.imports.size)
        assertEquals(emptyList(), held.api.violations)
    }

    /** A definitions document of a Mockey server is JSON, and the UI declares it as JSON. Tags are optional, and several can be given. */
    @Test
    fun aDefinitionsDocumentIsSentAsJsonWithTheTagsThatWereEntered() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val document = """{"formatVersion":1,"services":[]}"""

        val plain = browser.follow(browser.submit(browser.get("/definitions").form(), files = file("definitions.json", document)))
        val edits = mapOf("tags" to "  Sprint42 migrated\tsprint42 ")
        val tagged = browser.follow(browser.submit(browser.get("/definitions").form(), edits, file("definitions.json", document)))

        assertEquals(listOf("application/json", "application/json"), held.imports.map { it.contentType })
        assertEquals(listOf(emptyList(), listOf("Sprint42", "migrated", "sprint42")), held.imports.map { it.tags })
        assertEquals(document, String(held.imports.first().document, Charsets.UTF_8))
        val asked = listOf("POST definitions/import", "POST definitions/import?tag=Sprint42&tag=migrated&tag=sprint42")
        assertEquals(asked, importing(held.api))
        assertEquals(1 to 0, plain.page.select(LEAD).size to plain.page.select("main .tag").size)
        assertEquals(2, tagged.page.select(LEAD).size)
        assertEquals(listOf("sprint42", "migrated"), tagged.page.select("main .tag").map { it.text() })
        assertEquals(emptyList(), held.api.violations)
    }

    /** A long file name is shortened in the sentence that names the file. */
    @Test
    fun aLongFileNameIsShortenedInTheReport() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val name = "d".repeat(100) + ".json"

        val report = browser.follow(browser.submit(browser.get("/definitions").form(), files = file(name, "{}"))).page

        val summary = "The server read ${"d".repeat(79)}\u2026. The import made 1 addition and found 1 conflict."
        assertEquals(summary, report.select(LEAD).text())
    }

    /**
     * The report shows everything that the server reports: the applied default plan, the parts that it
     * couldn't read, and the rule sets that aren't well formed.
     */
    @Test
    fun theReportShowsEveryPartOfWhatTheServerReports() {
        val plan = PlanDto("plan-gold", "Gold path", items = listOf(PlanItemDto("Customers", "found"), PlanItemDto("Gone")))
        val applied = PlanApplyReportDto(plan, listOf(PlanItemDto("Gone")), listOf(UnresolvedPlanScenarioDto("Customers", "lost")))
        val full =
            ImportReportDto(
                additions =
                    listOf(
                        ImportItemDto("plan", "Gold path", "The plan 'Gold path' was added."),
                        ImportItemDto("twistConfiguration", "German paths", "The twist configuration 'German paths' was added."),
                        ImportItemDto("setting", "default plan", "The plan 'Gold path' was designated as the default plan and applied."),
                        ImportItemDto("widget", "w", "A widget was added."),
                    ),
                conflicts = emptyList(),
                appliedDefaultPlan = applied,
                issues = listOf("The file services/broken.xml cannot be read."),
                unreadableRuleSets = listOf(DiagnosticDto("unreadableRuleSet", "svc-customers", "Customers", MALFORMED)),
            )
        val reports = ArrayDeque(listOf(full, ImportReportDto(emptyList(), emptyList())))
        val held = holding { json(reports.removeFirst()) }
        val browser = started.browserFor(held.api)

        val first = browser.follow(browser.submit(browser.get("/definitions").form(), files = file("all.json", "{}"))).page
        val second = browser.follow(browser.submit(browser.get("/definitions").form(), files = file("nothing.json", "{}"))).page

        val additions =
            listOf(
                "Plan: The plan 'Gold path' was added.",
                "Twist configuration: The twist configuration 'German paths' was added.",
                "Setting: The plan 'Gold path' was designated as the default plan and applied.",
                "widget: A widget was added.",
            )
        val ofPlan =
            listOf(
                "The import made Gold path the default plan and applied it.",
                "1 service now has the settings of the plan.",
                "Skipped services",
                "The plan names these services, but they do not exist. Their settings were skipped.",
                "Gone",
                "Scenarios that were not found",
                "These services have no scenario with the name in the plan. Their default scenario was left as it is.",
                "Customers: lost",
            )
        val sections =
            mapOf(
                "Additions" to additions,
                "Conflicts" to listOf("The import found no conflicts."),
                "Default plan" to ofPlan,
                "Parts that could not be read" to listOf(ABOUT_ISSUES, "The file services/broken.xml cannot be read."),
                "Rule sets that are not well formed" to listOf(ABOUT_RULE_SETS, "Customers: $MALFORMED"),
            )
        assertEquals(sections, sectionsOf(first))
        assertEquals("The server read all.json. The import made 4 additions and found 0 conflicts.", first.select("main h1 + p").text())
        assertEquals(listOf("Skipped services", "Scenarios that were not found"), first.select("main h3").map { it.text() })
        val links = first.select("main h2 ~ * a:not(.actions a)").map { it.text() to it.attr("href") }
        assertEquals(listOf("Gold path" to "/plan?id=plan-gold", "Customers" to "/service?id=svc-customers"), links)
        val empty = mapOf("Additions" to listOf("The import added nothing."), "Conflicts" to listOf("The import found no conflicts."))
        assertEquals(empty, sectionsOf(second))
        val nothing = "The server read nothing.json. The import made 0 additions and found 0 conflicts."
        assertEquals(nothing, second.select("main h1 + p").text())
        assertEquals(emptyList(), held.api.violations)
    }

    // What is not taken

    /**
     * An import needs a file with content. Without one, the form is displayed again with a message at the
     * file field, and the server isn't asked.
     */
    @Test
    fun anImportWithoutADocumentIsRejectedByTheUi() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val without = browser.submit(browser.get("/definitions").form(), mapOf("tags" to "sprint42"))
        val empty = browser.submit(browser.get("/definitions").form(), mapOf("tags" to "sprint42"), file("empty.json", ""))

        val noFile = mapOf("document" to listOf("Choose the document to import."))
        val noContent = mapOf("document" to listOf("The file is empty, so there is nothing to import."))
        assertEquals(listOf(422 to noFile, 422 to noContent), listOf(without, empty).map { it.status to it.fieldProblems })
        assertEquals(listOf("sprint42", "sprint42"), listOf(without, empty).map { it.form().select("[name=tags]").attr("value") })
        assertEquals(1, without.page.select("main .problem[role=alert]").size)
        assertEquals(emptyList(), importing(held.api))
        assertEquals(emptyList(), held.imports.toList())
    }

    /**
     * The requirement "Validation feedback" for the import: what the server has against the document is
     * shown at the file field, and what it has against the request as a whole is shown above the form. A
     * message for a member that the form has no field for is shown where the form ends. The tags that were
     * entered are kept.
     */
    @Test
    fun aDocumentThatTheServerRejectsIsReportedOnTheForm() {
        val unreadable = "The document cannot be imported: it is no definitions document."
        val tooLong = "The body of the request is longer than 52428800 bytes, which is the most that this server takes."
        val answers =
            ArrayDeque(
                listOf(
                    problem(422, "The request is not valid.", listOf(FieldErrorDto("document", unreadable), FieldErrorDto("other", ELSE))),
                    problem(413, tooLong),
                    problem(400, "The body of the request is not encoded as UTF-8."),
                ),
            )
        val held = holding { answers.removeFirst() }
        val browser = started.browserFor(held.api)
        val edits = mapOf("tags" to "sprint42")

        val rejected = (1..3).map { browser.submit(browser.get("/definitions").form(), edits, file("other.json", """{"a":1}""")) }

        assertEquals(listOf(422, 422, 422), rejected.map { it.status })
        assertEquals(mapOf("document" to listOf(unreadable)), rejected[0].fieldProblems)
        assertEquals(listOf(emptyMap(), emptyMap()), rejected.drop(1).map { it.fieldProblems })
        val above = rejected.map { it.page.select("main form .problem[role=alert]").text() }
        val seeBelow = "Nothing was changed: the form holds what the server does not take. See below."
        assertEquals(listOf(seeBelow, tooLong, "The body of the request is not encoded as UTF-8."), above)
        val others = rejected.map { answer -> answer.page.select("main form > .field-problem p").map { it.text() } }
        assertEquals(listOf(listOf("other: $ELSE"), emptyList(), emptyList()), others)
        assertEquals(listOf("sprint42", "sprint42", "sprint42"), rejected.map { it.form().select("[name=tags]").attr("value") })
        assertEquals(3, held.imports.size)
        assertEquals(emptyList(), held.api.violations)
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the import report: whatever the server reports,
     * and whatever the file is called, the page shows it as plain text.
     */
    @Test
    fun theImportReportShowsStoredContentAsText() {
        var text = ""
        val held =
            holding {
                val item = ImportItemDto(text, text, text, text)
                val plan = PlanDto(text, text, text, listOf(text), listOf(PlanItemDto(text, text)), text)
                val applied = PlanApplyReportDto(plan, listOf(PlanItemDto(text, text)), listOf(UnresolvedPlanScenarioDto(text, text)))
                json(ImportReportDto(listOf(item), listOf(item), applied, listOf(text), listOf(DiagnosticDto(text, text, text, text))))
            }
        val browser = started.browserFor(held.api)

        for (hostile in Hostile.MARKUP + Hostile.ADDRESSES) {
            text = hostile
            val sent = browser.submit(browser.get("/definitions").form(), mapOf("tags" to hostile), file(hostile, "{}"))
            val report = browser.follow(sent)

            assertEquals(303 to 200, sent.status to report.status, hostile)
            assertEquals(emptyList(), Hostile.moreThanText(report), hostile)
            val items = report.page.select("main ul.report li").map { it.wholeText() }
            assertEquals(listOf("$hostile: $hostile", "$hostile: $hostile"), items, hostile)
            val linked = report.page.select("main h2 ~ * a:not(.actions a)").map { it.wholeText() }
            assertEquals(listOf(hostile, hostile), linked, hostile)
        }
        assertEquals(emptyList(), held.api.violations)
    }

    private companion object {
        /** Selects the paragraphs of a report page that come before its first section. */
        const val LEAD = "main h1 ~ p:not(h2 ~ p)"
        const val ELSE = "Something else is wrong."
        const val ABOUT_CONFLICTS = "These parts of the document were not added, or not in the way the document has them."
        const val ABOUT_ISSUES = "The import could not read these parts of the document, and it went on without them."
        const val ABOUT_RULE_SETS = "The import added these rule sets as they are. A rule set that is not well formed is never satisfied."
        const val MALFORMED = "The request rules are not well formed."
    }
}
