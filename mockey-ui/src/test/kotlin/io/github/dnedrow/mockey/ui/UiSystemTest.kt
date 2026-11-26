package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.ServiceDto
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 13.10: what the page of the server offers besides the status. It switches between read-only mode and
 * writable mode, reloads the definitions, resets the server, and clears the last-used times. Switching to
 * writable mode, reloading, and resetting each ask for confirmation first.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiSystemTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** What a stand-in server holds for a test: the server itself and live views of its services and its plans. */
    private class Holding(
        val api: FakeAdminApi,
        val services: Map<String, ServiceDto>,
        val plans: Map<String, PlanDto>,
    )

    /**
     * Starts a stand-in server that holds the samples. The service Customers, its scenario `found`, and the
     * plan Night each have a last-used time. The server is in read-only mode unless it's [writable], and a
     * reload fails with [reloadFailure] if there is one.
     */
    private fun holding(
        writable: Boolean = false,
        reloadFailure: String? = null,
    ): Holding {
        val api = started.api()
        val scenarios = listOf(Samples.found.copy(lastUsedTime = USED), Samples.missing)
        val used = Samples.customers.copy(lastUsedTime = USED, scenarios = scenarios)
        val services = api.hold(listOf(used, Samples.orders))
        val plans = api.holdPlans(services, listOf(Samples.plan.copy(lastUsedTime = USED)))
        api.holdSystem(services, plans, reloadFailure)
        api.system = api.system.copy(readOnly = !writable)
        return Holding(api, services, plans)
    }

    /** Returns the paragraphs of the confirmation page [page], which say what happens if the action is confirmed. */
    private fun consequencesOf(page: Document): List<String> = page.select("main > p").map { it.text() }

    /** Returns what [page] says about the mode of the server, in its list of facts. */
    private fun modeOf(page: Document): String = factOf(page, "Mode")

    // The page

    /**
     * In read-only mode, the page links to the confirmation steps for writable mode, a reload, and a reset,
     * and it has the button that clears the last-used times.
     */
    @Test
    fun thePageOffersWhatTheServerCanBeToldToDo() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/system")

        val page = answer.page
        val links =
            listOf(
                "Outbound proxy and upstream session" to "/proxy",
                "Switch to writable mode" to "/system/writable",
                "Reload the definitions" to "/system/reload",
                "Reset the server" to "/system/reset",
            )
        assertEquals(200, answer.status)
        assertEquals(listOf("Read-only mode", "Definitions", "Last-used times"), page.select("main h2").map { it.text() })
        assertEquals(links, page.select("main p.actions a").map { it.text() to it.attr("href") })
        val forms = page.select("main form").map { Triple(it.attr("method"), it.attr("action"), it.select("button").text()) }
        assertEquals(listOf(Triple("post", "/system/clear-last-used", "Clear the last-used times")), forms)
        assertEquals("last-used-times", page.select("main h2").last()!!.id())
        assertEquals(listOf("GET system"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** In writable mode, the page has the button that switches to read-only mode in the place of the link to writable mode. */
    @Test
    fun aWritableServerOffersTheSwitchToReadOnlyMode() {
        val page = started.browserFor(holding(writable = true).api).get("/system").page

        val forms = page.select("main form").map { it.attr("action") to it.select("button").text() }
        val expected = listOf("/system/read-only" to "Switch to read-only mode", "/system/clear-last-used" to "Clear the last-used times")
        assertEquals(expected, forms)
        assertEquals(0, page.select("main a[href=/system/writable]").size)
        assertEquals("Disabled: the server does not answer the automation endpoints of Mockey 2.x.", factOf(page, "Legacy shim"))
    }

    /** The page says whether the legacy shim of the server is enabled. */
    @Test
    fun thePageSaysWhetherTheLegacyShimIsEnabled() {
        val held = holding()
        held.api.system = held.api.system.copy(legacyShimEnabled = true)

        val page = started.browserFor(held.api).get("/system").page

        assertEquals("Enabled: the server answers the automation endpoints of Mockey 2.x.", factOf(page, "Legacy shim"))
    }

    // Read-only mode

    /**
     * Switching to writable mode stores the definitions, so the UI asks first. The confirmation step names
     * the definitions location, and the server stays in read-only mode until the form is submitted.
     */
    @Test
    fun theServerSwitchesToWritableModeAfterAConfirmation() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val asked = browser.get("/system/writable")
        val before = held.api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        val consequences =
            listOf(
                "The server stores the definitions that it holds now in /srv/mockey/mockey-definitions. " +
                    "They take the place of the definitions that are stored there.",
                "From then on, the server stores every change.",
            )
        assertEquals(200 to "Switch the server to writable mode?", asked.status to asked.page.select("main h1").text())
        assertEquals(consequences, consequencesOf(asked.page))
        assertEquals("post" to "/system/writable", asked.form().attr("method") to asked.form().attr("action"))
        assertEquals("Switch to writable mode", asked.form().select("button").text())
        assertEquals(listOf("Cancel" to "/system"), asked.form().select("a").map { it.text() to it.attr("href") })
        assertEquals(emptyList(), before)
        assertEquals(303 to "/system", sent.status to sent.header("location"))
        assertEquals("The server is in writable mode now. It stores every change in its definitions location.", after.notice)
        assertEquals("Writable: the server stores every change in its definitions location.", modeOf(after.page))
        assertEquals(listOf("PATCH system" to """{"readOnly":false}"""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** A server that is writable already has nothing to confirm, and the page says so. */
    @Test
    fun aWritableServerHasNothingToConfirm() {
        val held = holding(writable = true)

        val answer = started.browserFor(held.api).get("/system/writable")

        assertEquals(200 to "The server is in writable mode already", answer.status to answer.page.select("main h1").text())
        assertEquals(0, answer.page.select("main form").size)
        assertEquals(listOf("Back to the server" to "/system"), answer.page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals(emptyList(), held.api.changes())
    }

    /** Switching to read-only mode changes nothing that is stored, so the button acts at once. */
    @Test
    fun theServerSwitchesToReadOnlyModeAtOnce() {
        val held = holding(writable = true)
        val browser = started.browserFor(held.api)

        val sent = browser.submit(browser.get("/system").form("main form[action=/system/read-only]"))
        val after = browser.follow(sent)

        assertEquals(303 to "/system", sent.status to sent.header("location"))
        assertEquals("The server is in read-only mode now. It keeps further changes in memory only.", after.notice)
        assertEquals("Read-only: the server keeps changes in memory, and stores none of them.", modeOf(after.page))
        assertEquals(listOf("/system/writable"), after.page.select("main a[href=/system/writable]").map { it.attr("href") })
        assertEquals(listOf("PATCH system" to """{"readOnly":true}"""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    // Reload

    /**
     * A reload discards what the server holds in memory, so the UI asks first. The confirmation step says
     * what a reload does in the mode of the server, and nothing is reloaded until the form is submitted.
     */
    @Test
    fun theServerReloadsItsDefinitionsAfterAConfirmation() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val asked = browser.get("/system/reload")
        val before = held.api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)
        val ofWritable = started.browserFor(holding(writable = true).api).get("/system/reload").page

        val reads =
            "The server reads the definitions in /srv/mockey/mockey-definitions again. " +
                "They take the place of the definitions that the server holds in memory."
        val keeps =
            "The server forgets the credentials of the outbound proxy, and it applies the default plan if there is one. " +
                "The history, the tag filter, and the last-used times stay as they are."
        val lost = "The server is in read-only mode. Every change that it has not stored is lost."
        val stored = "The server is in writable mode, so it has stored every change already."
        assertEquals(200 to "Reload the definitions?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf(reads, lost, keeps, COPY_ADVICE), consequencesOf(asked.page))
        assertEquals(listOf(reads, stored, keeps, COPY_ADVICE), consequencesOf(ofWritable))
        val download = asked.page.select("main > p a").map { it.text() to it.attr("href") }
        assertEquals(listOf("download the definitions" to "/definitions/export"), download)
        assertEquals("post" to "/system/reload", asked.form().attr("method") to asked.form().attr("action"))
        assertEquals("Reload the definitions", asked.form().select("button").text())
        assertEquals(listOf("Cancel" to "/system"), asked.form().select("a").map { it.text() to it.attr("href") })
        assertEquals(emptyList(), before)
        assertEquals(303 to "/system", sent.status to sent.header("location"))
        assertEquals("The server loaded its definitions again.", after.notice)
        assertEquals(listOf("POST system/reload" to ""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** A reload that fails shows what the server says about it, which names the file that it couldn't read. */
    @Test
    fun aReloadThatFailsShowsWhatTheServerSays() {
        val failure =
            "The server could not read or write its definitions location, and nothing was changed. " +
                "The file services/orders/service.json cannot be read: it is no JSON."
        val held = holding(reloadFailure = failure)
        val browser = started.browserFor(held.api)

        val failed = browser.submit(browser.get("/system/reload").form())

        assertEquals(502 to "The server failed", failed.status to failed.page.select("main h1").text())
        assertEquals(failure, failed.wholeTextOf("main .problem"))
        assertNull(failed.header("location"))
        assertEquals(listOf("POST system/reload"), held.api.changes().map { it.first })
    }

    // Reset

    /**
     * The scenario "Reset needs confirmation": choosing reset shows a confirmation step, and nothing is
     * removed unless the form of that step is submitted.
     */
    @Test
    fun theServerIsResetAfterAConfirmation() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val asked = browser.get("/system/reset")
        val before = held.api.changes() to held.services.size
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)
        val ofWritable = started.browserFor(holding(writable = true).api).get("/system/reset").page

        val removes =
            "The server removes every service with its scenarios, every plan, and every twist configuration. " +
                "It clears the settings of the outbound proxy, the universal error scenario, the default plan, and the tag filter."
        val empties = "It also empties the history and clears the last-used times."
        val stored = "the definitions that are stored in /srv/mockey/mockey-definitions"
        val kept = "The server is in read-only mode, so $stored stay as they are."
        val removed = "The server is in writable mode, so $stored are removed as well."
        assertEquals(200 to "Reset the server?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf(removes, empties, kept, COPY_ADVICE), consequencesOf(asked.page))
        assertEquals(listOf(removes, empties, removed, COPY_ADVICE), consequencesOf(ofWritable))
        assertEquals("post" to "/system/reset", asked.form().attr("method") to asked.form().attr("action"))
        assertEquals("Reset the server" to "danger", asked.form().select("button").text() to asked.form().select("button").attr("class"))
        assertEquals(listOf("Cancel" to "/system"), asked.form().select("a").map { it.text() to it.attr("href") })
        assertEquals(emptyList<Pair<String, String>>() to 2, before)
        assertEquals(303 to "/system", sent.status to sent.header("location"))
        assertEquals("The server was reset. It holds no definitions and no history now.", after.notice)
        assertEquals(listOf("POST system/reset" to ""), held.api.changes())
        assertEquals(0 to 0, held.services.size to held.plans.size)
        assertEquals("The server holds no service.", browser.get("/services").wholeTextOf("main p.count"))
        assertEquals(emptyList(), held.api.violations)
    }

    // Last-used times

    /** The button clears the last-used times at once, and the overview shows that no service was used. */
    @Test
    fun theButtonClearsTheLastUsedTimes() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val usedBefore = usedServicesOn(browser.get("/services").page)

        val sent = browser.submit(browser.get("/system").form("main form[action=/system/clear-last-used]"))
        val after = browser.follow(sent)

        assertEquals(1, usedBefore)
        assertEquals(303 to "/system", sent.status to sent.header("location"))
        assertEquals("The last-used times were cleared.", after.notice)
        assertEquals(listOf("DELETE system/last-used-times" to ""), held.api.changes())
        assertEquals(0, usedServicesOn(browser.get("/services").page))
        val times = held.services.values.flatMap { service -> service.scenarios.map { it.lastUsedTime } + service.lastUsedTime }
        assertEquals(emptySet(), (times + held.plans.values.map { it.lastUsedTime }).filterNotNull().toSet())
        assertEquals(emptyList(), held.api.violations)
    }

    // What is not taken

    /** A request that isn't the form of a confirmation step changes nothing: it gets the step again, or it's refused. */
    @Test
    fun aRequestWithoutTheConfirmationChangesNothing() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val token = browser.token("/system/reset")
        val steps = listOf("/system/writable", "/system/reload", "/system/reset")

        val unconfirmed = steps.map { browser.post(it, listOf("_token" to token)) }
        val buttons = listOf("/system/read-only", "/system/clear-last-used")
        val withoutToken = (steps + buttons).map { browser.post(it, listOf("confirmed" to "yes")) }
        val asPages = buttons.map(browser::get)

        val questions = listOf("Switch the server to writable mode?", "Reload the definitions?", "Reset the server?")
        assertEquals(listOf(200, 200, 200), unconfirmed.map { it.status })
        assertEquals(questions, unconfirmed.map { it.page.select("main h1").text() })
        assertEquals(listOf(403, 403, 403, 403, 403), withoutToken.map { it.status })
        assertEquals(listOf(404, 404), asPages.map { it.status })
        assertEquals(emptyList(), held.api.changes())
        assertEquals(2, held.services.size)
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the confirmation steps of the server: they name
     * the definitions location, which is shown as plain text, whatever the server reports as its location.
     */
    @Test
    fun theConfirmationStepsShowTheDefinitionsLocationAsText() {
        val held = holding()
        val browser = started.browserFor(held.api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            for (readOnly in listOf(true, false)) {
                held.api.system = held.api.system.copy(definitionsLocation = text, readOnly = readOnly)
                val pages = listOf("/system", "/system/writable", "/system/reload", "/system/reset").map(browser::get)

                for (answer in pages) {
                    assertEquals(200, answer.status, text)
                    assertEquals(emptyList(), Hostile.moreThanText(answer), text)
                }
                assertEquals(text, pages.first().wholeTextOf("main dl code"), text)
                // The step for writable mode only names the location while the server is in read-only mode.
                val naming = pages.drop(if (readOnly) 1 else 2)
                val named = naming.map { step -> step.page.select("main > p").any { text in it.wholeText() } }
                assertEquals(naming.map { true }, named, text)
            }
        }
        assertEquals(emptyList(), held.api.violations)
    }

    /** Returns what [page] says about [fact], in its list of facts. */
    private fun factOf(
        page: Document,
        fact: String,
    ): String {
        val term = page.select("main dl dt").first { it.text() == fact }
        return term.nextElementSibling()!!.text()
    }

    /** Returns the number of services on the overview [page] that state a last-used time. */
    private fun usedServicesOn(page: Document): Int = page.select("main table.services time").size

    private companion object {
        const val USED = "2026-01-02T03:04:05Z"
        const val COPY_ADVICE = "To keep a copy of the definitions that the server holds now, download the definitions first."
    }
}
