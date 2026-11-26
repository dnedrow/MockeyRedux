package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.FormElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.8: the pages for twist configurations. The pages list the configurations, edit and create one
 * with its replacements, enable and disable it, and delete it after a confirmation.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiTwistsTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val german = Samples.twist
    private val qa = TwistConfigurationDto("twist-qa", "to QA 2", listOf(TwistPairDto("qa1", "qa2"), TwistPairDto("example.com", "")))

    /** Starts a stand-in server that holds [twists]. [enabled] is the identifier of the enabled configuration. */
    private fun holding(
        twists: List<TwistConfigurationDto> = listOf(qa, german),
        enabled: String? = null,
        paths: Map<String, String> = emptyMap(),
    ): FakeAdminApi = started.api().also { it.holdTwists(twists, enabled, paths) }

    private val free = listOf("", "", "")

    // The list

    /** The list shows the twist configurations sorted by name, says which one is enabled, and offers a button that changes that. */
    @Test
    fun theListShowsTheTwistConfigurationsAndWhichOneIsEnabled() {
        val api = holding(enabled = qa.id)
        val browser = started.browserFor(api)

        val answer = browser.get("/twists")

        val page = answer.page
        val rows = page.select("main table.twists tbody tr").map { row -> row.select("th, td").map { it.text() } }
        val switches =
            page.select("main table.twists form").map { form ->
                val button = form.select("button")
                Triple(form.attr("action"), button.attr("value"), button.attr("aria-label"))
            }
        assertEquals(200 to "Twist configurations - Mockey", answer.status to page.title())
        assertEquals(listOf("Twist configuration", "Replacements", "Status"), page.select("main thead th").map { it.text() })
        assertEquals(listOf(listOf("German paths", "1", "disabled Enable"), listOf("to QA 2", "2", "enabled Disable")), rows)
        assertEquals(listOf("/twist?id=twist-german", "/twist?id=twist-qa"), page.select("main tbody th a").map { it.attr("href") })
        val expected =
            listOf(
                Triple("/twist/enabled?id=twist-german&from=list", "yes", "Enable German paths"),
                Triple("/twist/enabled?id=twist-qa&from=list", "no", "Disable to QA 2"),
            )
        assertEquals(expected, switches)
        val ofNew = page.select("main p.actions a").map { it.text() to it.attr("href") }
        assertEquals(listOf("New twist configuration" to "/twist/new"), ofNew)
        assertEquals(listOf("/twists"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET twists"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** Without any twist configurations, the list says so and still offers the form for a new one. */
    @Test
    fun theListSaysWhenThereAreNoTwistConfigurations() {
        val browser = started.browserFor(holding(emptyList()))

        val page = browser.get("/twists").page

        assertEquals(0, page.select("main table").size)
        assertEquals("There are no twist configurations yet.", page.select("main > p").last()!!.text())
        assertEquals(listOf("/twist/new"), page.select("main p.actions a").map { it.attr("href") })
    }

    // The page of a twist configuration

    /**
     * The page of a twist configuration is the form that edits it. It shows the name and one row for each
     * replacement, followed by a few empty rows, and it doesn't send any attribute if it's submitted
     * unchanged.
     */
    @Test
    fun thePageOfATwistConfigurationShowsItsReplacementsAndSendsNothingIfNothingIsChanged() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/twist?id=twist-qa")
        val form = answer.form("main > form")
        val sent = browser.submit(form)
        val after = browser.follow(sent)

        val page = answer.page
        val fields =
            mapOf(
                "name" to listOf("to QA 2"),
                "origination" to listOf("qa1", "example.com") + free,
                "destination" to listOf("qa2", "") + free,
            )
        assertEquals(200, answer.status)
        assertEquals("Twist configuration to QA 2 - Mockey" to "Twist configuration to QA 2", page.title() to page.select("main h1").text())
        assertEquals("post" to "/twist?id=twist-qa", form.attr("method") to form.attr("action"))
        assertEquals(fields, fieldsOf(form))
        val hidden = listOf("_token") + TwistForm.FIELDS.map { "_shown.$it" }
        assertEquals(hidden, form.select("input[type=hidden]").map { it.attr("name") })
        val firstRow = form.select(".row").first()!!
        val labels = firstRow.select("input").map { it.attr("aria-label") }
        assertEquals(listOf("Text to replace in row 1", "Replacement in row 1"), labels)
        assertEquals(listOf("Cancel" to "/twists"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/twists"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(303 to "/twist?id=twist-qa", sent.status to sent.header("location"))
        assertEquals("The twist configuration to QA 2 was saved.", after.notice)
        assertEquals(listOf("PATCH twists/twist-qa" to "{}"), api.changes())
        assertEquals(listOf("GET system", "GET twists/twist-qa"), api.requests.map { it.line }.take(2))
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The form only sends the attributes whose fields were changed. The replacements are sent in the order
     * of their rows, their texts are sent exactly as typed, and a row without a text to replace is left out.
     */
    @Test
    fun theFormOnlySendsTheChangedAttributes() {
        val api = holding()
        val browser = started.browserFor(api)
        val form = browser.get("/twist?id=twist-german").form("main > form")
        val rows = { originations: List<String>, destinations: List<String> ->
            mapOf("origination" to originations, "destination" to destinations)
        }
        val kunde = """{"origination":"kunde","destination":"customer"}"""

        val changes =
            listOf(
                browser.submit(form, mapOf("name" to "Deutsch")) to """{"name":"Deutsch"}""",
                browser.submit(form, rows = rows(listOf("kunde", "bestellung") + free, listOf("customer", "order") + free)) to
                    """{"pairs":[$kunde,{"origination":"bestellung","destination":"order"}]}""",
                browser.submit(form, rows = rows(listOf("", "", " b ", "a"), listOf("", "", " c ", ""))) to
                    """{"pairs":[{"origination":" b ","destination":" c "},{"origination":"a","destination":""}]}""",
                browser.submit(form, rows = mapOf("destination" to listOf("client") + free)) to
                    """{"pairs":[{"origination":"kunde","destination":"client"}]}""",
                browser.submit(form, rows = mapOf("origination" to listOf("kundin") + free)) to
                    """{"pairs":[{"origination":"kundin","destination":"customer"}]}""",
                browser.submit(form, rows = rows(listOf(" ") + free, listOf("x") + free)) to
                    """{"pairs":[{"origination":" ","destination":"x"}]}""",
                browser.submit(form, rows = rows(List(4) { "" }, List(4) { "" })) to """{"pairs":[]}""",
                browser.submit(form, mapOf("name" to ""), rows = rows(listOf("x") + free, listOf("y") + free)) to
                    """{"name":"","pairs":[{"origination":"x","destination":"y"}]}""",
            )

        assertEquals(changes.map { 303 }, changes.map { it.first.status })
        assertEquals(changes.map { "PATCH twists/twist-german" to it.second }, api.changes())
        assertEquals(emptyList(), api.violations)
    }

    /** Browsers change some values on their own. The form doesn't treat such a value as changed. */
    @Test
    fun theFormDoesNotSendAValueThatOnlyTheBrowserAltered() {
        val odd = "one\ntwo\u0000three\r\nfour"
        val api = holding(listOf(TwistConfigurationDto("twist-odd", odd, listOf(TwistPairDto(odd, odd)))))
        val browser = started.browserFor(api)
        val form = browser.get("/twist?id=twist-odd").form("main > form")

        val sent = browser.submit(form)

        val inOneLine = "onetwo\uFFFDthreefour"
        val row = listOf(inOneLine) + free
        assertEquals(mapOf("name" to listOf(inOneLine), "origination" to row, "destination" to row), fieldsOf(form))
        assertEquals(303 to listOf("{}"), sent.status to api.changes().map { it.second })
    }

    // A new twist configuration

    /** The form for a new twist configuration sends every attribute, and the browser ends up on the page of the new configuration. */
    @Test
    fun theFormForANewTwistConfigurationCreatesIt() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/twist/new")
        val form = answer.form()
        val rows = mapOf("origination" to listOf("sandbox", "", "old"), "destination" to listOf("live", "", ""))
        val sent = browser.submit(form, mapOf("name" to "To live"), rows = rows)
        val created = browser.follow(sent)
        val ofDefaults = browser.submit(form)

        assertEquals(200 to "New twist configuration", answer.status to answer.page.select("main h1").text())
        assertEquals("post" to "/twist/new", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("name" to listOf(""), "origination" to free, "destination" to free), fieldsOf(form))
        assertEquals(0, form.select("input[name^=_shown.]").size)
        assertEquals("Create the twist configuration", form.select("button").text())
        assertEquals(303 to "/twist?id=twist-new-1", sent.status to sent.header("location"))
        assertEquals("The twist configuration To live was created.", created.notice)
        val shown = fieldsOf(created.form("main > form"))
        assertEquals(listOf("sandbox", "old") + free to listOf("live", "") + free, shown["origination"] to shown["destination"])
        assertEquals(303 to "/twist?id=twist-new-2", ofDefaults.status to ofDefaults.header("location"))
        val live = """{"origination":"sandbox","destination":"live"}"""
        val asked =
            listOf(
                "POST twists" to """{"name":"To live","pairs":[$live,{"origination":"old","destination":""}]}""",
                "POST twists" to """{"name":"","pairs":[]}""",
            )
        assertEquals(asked, api.changes())
        assertEquals(emptyList(), api.violations)
    }

    // A form that is rejected

    /** A row with a replacement but no text to replace is a mistake. The form is displayed again, and no request is sent. */
    @Test
    fun theUiRejectsARowWithAReplacementButNoTextToReplace() {
        val api = holding()
        val browser = started.browserFor(api)
        val form = browser.get("/twist?id=twist-german").form("main > form")
        val rows = mapOf("origination" to listOf("kunde", "", "", ""), "destination" to listOf("customer", "x", "", "y"))

        val answer = browser.submit(form, mapOf("name" to "Kept"), rows = rows)
        val ofNew = browser.submit(browser.get("/twist/new").form(), rows = mapOf("destination" to listOf("x", "", "")))

        val messages = listOf("Row 2 has a replacement but no text to replace.", "Row 4 has a replacement but no text to replace.")
        assertEquals(422 to mapOf("origination" to messages), answer.status to answer.fieldProblems)
        val again = fieldsOf(answer.form("main > form"))
        assertEquals(listOf("Kept") to rows.getValue("destination"), again["name"] to again["destination"])
        assertEquals(422 to "New twist configuration", ofNew.status to ofNew.page.select("main h1").text())
        assertEquals(mapOf("origination" to messages.take(1).map { it.replace("Row 2", "Row 1") }), ofNew.fieldProblems)
        assertEquals(emptyList(), api.changes())
    }

    /** A form that the server rejects is displayed again with the entered values and the messages of the server. */
    @Test
    fun aFormThatTheServerRejectsShowsTheMessagesNextToTheFields() {
        val api = holding()
        val browser = started.browserFor(api)
        val errors = listOf(FieldErrorDto("name", "About the name."), FieldErrorDto("pairs[0].origination", "About a replacement."))
        api.on("PATCH", "twists/twist-german") { problem(422, "The request is not valid.", errors) }

        val form = browser.get("/twist?id=twist-german").form("main > form")
        val answer = browser.submit(form, mapOf("name" to "Kept"))

        assertEquals(422 to "Twist configuration German paths", answer.status to answer.page.select("main h1").text())
        val messages = mapOf("name" to listOf("About the name."), "origination" to listOf("About a replacement."))
        assertEquals(messages, answer.fieldProblems)
        assertEquals(listOf("Kept"), fieldsOf(answer.form("main > form"))["name"])
        val marks = { ofForm: FormElement -> ofForm.select("input[name^=_shown.]").map { it.attr("name") to it.attr("value") } }
        assertEquals(marks(form), marks(answer.form("main > form")))
    }

    // Enabling and disabling

    /**
     * The buttons enable and disable a twist configuration. A button on the list sends the browser back to
     * the list, and the button on the page of a configuration sends it back to that page.
     */
    @Test
    fun theButtonsEnableAndDisableATwistConfiguration() {
        val api = holding()
        val browser = started.browserFor(api)
        val onList = { label: String ->
            val forms = browser.get("/twists").page.select("main table.twists form")
            val sent = browser.submit(forms.single { it.select("button").attr("aria-label") == label } as FormElement)
            Triple(sent.status, sent.header("location"), browser.follow(sent).notice)
        }
        val onPage = {
            val sent = browser.submit(browser.get("/twist?id=twist-qa").form("main .designation form"))
            Triple(sent.status, sent.header("location"), browser.follow(sent).notice)
        }

        val results = listOf(onList("Enable German paths"), onList("Disable German paths"), onPage(), onPage())

        val expected =
            listOf(
                Triple(303, "/twists", "The twist configuration German paths is now enabled."),
                Triple(303, "/twists", "The twist configuration German paths is now disabled."),
                Triple(303, "/twist?id=twist-qa", "The twist configuration to QA 2 is now enabled."),
                Triple(303, "/twist?id=twist-qa", "The twist configuration to QA 2 is now disabled."),
            )
        val asked =
            listOf(
                "POST twists/twist-german/enable",
                "POST twists/twist-german/disable",
                "POST twists/twist-qa/enable",
                "POST twists/twist-qa/disable",
            )
        assertEquals<List<Any?>>(expected, results)
        assertEquals(asked.map { it to "" }, api.changes())
        assertEquals(emptyList(), api.violations)
    }

    /** The page of a twist configuration says whether the configuration is enabled, and its button says what it does. */
    @Test
    fun thePageSaysWhetherTheTwistConfigurationIsEnabled() {
        val api = holding(enabled = qa.id)
        val browser = started.browserFor(api)

        val ofQa = browser.get("/twist?id=twist-qa").page
        val ofGerman = browser.get("/twist?id=twist-german").page
        val enabled = ofQa.select("main .designation").single()
        val disabled = ofGerman.select("main .designation").single()

        val isEnabled = "This twist configuration is enabled. The server applies it to the target of every request."
        val isDisabled = "This twist configuration is not enabled. Enabling it disables any other twist configuration."
        assertEquals(isEnabled to isDisabled, enabled.select("p").text() to disabled.select("p").text())
        assertEquals("no" to "Disable it", enabled.select("button").let { it.attr("value") to it.text() })
        assertEquals("yes" to "Enable it", disabled.select("button").let { it.attr("value") to it.text() })
        assertEquals("/twist/enabled?id=twist-qa", enabled.select("form").attr("action"))
    }

    /** An enable request that doesn't say whether to enable or to disable is rejected, and nothing changes. */
    @Test
    fun anEnableRequestWithoutAChoiceIsRejected() {
        val api = holding()
        val browser = started.browserFor(api)
        val token = browser.token("/twist/new")

        val answer = browser.post("/twist/enabled?id=twist-qa", listOf("_token" to token))

        assertEquals(422, answer.status)
        assertEquals(emptyList(), api.changes())
    }

    // Deleting a twist configuration

    /**
     * The requirement "Confirmation of destructive actions" for a twist configuration: the link leads to a
     * page that asks for confirmation, nothing is deleted until the form on that page is submitted, and the
     * list then reports the deletion.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItDeletesATwistConfiguration() {
        val api = holding(enabled = qa.id)
        val browser = started.browserFor(api)
        val ofGerman = browser.get("/twist?id=twist-german").page
        val link = ofGerman.select("main > p.actions a").single()

        val asked = browser.get(link.attr("href"))
        val ofEnabled = browser.get("/twist/delete?id=twist-qa").page
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post("/twist/delete?id=twist-german", listOf("_token" to token))
        val before = api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        assertEquals("Delete the twist configuration" to "/twist/delete?id=twist-german", link.text() to link.attr("href"))
        assertEquals(200 to "Delete the twist configuration German paths?", asked.status to asked.page.select("main h1").text())
        val notInUse = "It is not enabled, so deleting it does not change how the server routes requests."
        assertEquals(listOf(notInUse), asked.page.select("main > p").map { it.text() })
        val inUse = "It is the enabled twist configuration. The server will stop rewriting targets."
        assertEquals(listOf(inUse), ofEnabled.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Delete the twist configuration" to setOf("danger"), button.text() to button.classNames())
        assertEquals(listOf("Cancel" to "/twist?id=twist-german"), asked.page.select("main form a").map { it.text() to it.attr("href") })
        assertEquals(200 to emptyList(), notConfirmed.status to before)
        assertEquals(303 to "/twists", sent.status to sent.header("location"))
        assertEquals("The twist configuration German paths was deleted.", after.notice)
        assertEquals(listOf("to QA 2"), after.page.select("main table.twists tbody th").map { it.text() })
        assertEquals(listOf("DELETE twists/twist-german" to ""), api.changes())
        assertEquals(emptyList(), api.violations)
    }

    // Addresses

    /** The pages of a twist configuration that doesn't exist say so, and nothing changes. */
    @Test
    fun thePagesOfAMissingTwistConfigurationSaySo() {
        val api = holding()
        val browser = started.browserFor(api)
        val token = browser.token("/twist/new")
        val noTwist = "No twist configuration has the identifier twist-gone."
        for (method in listOf("GET", "PATCH", "DELETE")) api.on(method, "twists/twist-gone") { problem(404, noTwist) }
        for (action in listOf("enable", "disable")) api.on("POST", "twists/twist-gone/$action") { problem(404, noTwist) }
        val fields = listOf("_token" to token, "confirmed" to "yes", "designate" to "yes", "name" to "x")

        val pages = listOf("/twist", "/twist/delete").map { browser.get("$it?id=twist-gone") }
        val forms = listOf("/twist", "/twist/enabled", "/twist/delete").map { browser.post("$it?id=twist-gone", fields) }
        val incomplete = listOf("/twist", "/twist/delete").map { browser.get(it).status }

        for (answer in pages + forms) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue(noTwist in answer.page.select("main .problem").text())
        }
        assertEquals(listOf(404, 404), incomplete)
        assertEquals(listOf("PATCH twists/twist-gone", "POST twists/twist-gone/enable"), api.changes().map { it.first })
    }

    /** The twist pages work with any identifier, because identifiers are passed as address parameters. */
    @Test
    fun theTwistPagesWorkWithAnyIdentifier() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", ".." to "..", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val twists = ids.keys.mapIndexed { index, id -> german.copy(id = id, name = "Twist $index") }
        val api = holding(twists, paths = ids)
        val browser = started.browserFor(api)

        for ((id, written) in ids) {
            val page = browser.get(Address.of("/twist", "id" to id))
            val saved = browser.submit(page.form("main > form"), mapOf("name" to "x"))
            val enabled = browser.submit(page.form("main .designation form"))
            val deleted = browser.submit(browser.get(page.page.select("main > p.actions a").attr("href")).form())

            val leading = page.page.select("main [href], main [action]").map { it.attr("href") + it.attr("action") }
            assertEquals(emptyList(), leading.filterNot(Hostile.SAFE_ADDRESS::matches), id)
            assertEquals(listOf(200, 303, 303, 303), listOf(page, saved, enabled, deleted).map { it.status }, id)
            assertEquals(Address.of("/twist", "id" to id), saved.header("location"), id)
            val lines = listOf("PATCH twists/$written", "POST twists/$written/enable", "DELETE twists/$written")
            assertEquals(lines, api.changes().takeLast(lines.size).map { it.first }, id)
        }
        assertEquals(emptyList(), api.violations)
    }

    // Stored content is text

    /** The requirement "Stored content is shown as text" for the twist pages: names and replacements are shown as plain text. */
    @Test
    fun theTwistPagesShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val twist = TwistConfigurationDto(text, text, listOf(TwistPairDto(text, text)))
            api.holdTwists(listOf(twist), enabled = text, paths = mapOf(text to Hostile.written(text)))

            val list = browser.get("/twists")
            val page = browser.get(Address.of("/twist", "id" to text))
            val asked = browser.get(Address.of("/twist/delete", "id" to text))

            // A one-line field holds its value without line breaks, just like in a browser.
            val inOneLine = text.replace("\n", "")
            val fields = fieldsOf(page.form("main > form"))
            val shown = listOf("name", "origination", "destination").map { fields.getValue(it).first() }
            assertEquals(listOf(inOneLine), shown.distinct(), text)
            assertEquals(text, list.wholeTextOf("main table.twists tbody th a"), text)
            assertEquals("Twist configuration $text", page.wholeTextOf("main h1"), text)
            assertEquals("Delete the twist configuration $text?", asked.wholeTextOf("main h1"), text)
            for (answer in listOf(list, page, asked)) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            }
        }
        assertEquals(emptyList(), api.violations)
    }
}
