package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.8: the page for tags. It shows the server's tag filter with the form that sets it and the button
 * that turns it off, and it lists every tag in use with a link that removes the tag from all definitions
 * after a confirmation.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiTagsTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /**
     * Starts a stand-in server that holds [services] and [plans] and uses [filter] as its tag filter. With
     * the samples, the tags in use are `happy` on a scenario, `nightly` on a plan, and `team-a` on a service.
     */
    private fun holding(
        filter: List<String> = emptyList(),
        services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
        plans: List<PlanDto> = listOf(Samples.plan),
        paths: Map<String, String> = emptyMap(),
    ): FakeAdminApi {
        val api = started.api()
        val held = api.hold(services)
        api.holdTags(held, api.holdPlans(held, plans), paths)
        api.system = api.system.copy(tagFilter = filter)
        return api
    }

    /** Returns the sentence on [page] that says whether the tag filter is on. */
    private fun filterState(page: Document): String = page.select("main > p:not(.notice)")[1].text()

    // The page

    /** While the tag filter is off, the page says so, offers the form that sets it, and lists the tags in use. */
    @Test
    fun thePageShowsTheTagFilterAndTheTagsInUse() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/tags")

        val page = answer.page
        val form = answer.form()
        val tags = page.select("main ul.tags li").map { item -> item.select("a").map { it.text() to it.attr("href") } }
        val expected =
            listOf(
                listOf("happy" to "/services?tag=happy", "Remove" to "/tag/remove?tag=happy"),
                listOf("nightly" to "/services?tag=nightly", "Remove" to "/tag/remove?tag=nightly"),
                listOf("team-a" to "/services?tag=team-a", "Remove" to "/tag/remove?tag=team-a"),
            )
        assertEquals(200 to "Tags - Mockey", answer.status to page.title())
        assertEquals(listOf("Tag filter", "Tags in use"), page.select("main h2").map { it.text() })
        assertEquals("The tag filter is off. Every service answers requests.", filterState(page))
        assertEquals("post" to "/tags/filter", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("tags" to listOf("")), fieldsOf(form))
        assertEquals("Set the tag filter", form.select("button").text())
        assertEquals(expected, tags)
        val label = page.select("main ul.tags li a[aria-label]").first()!!.attr("aria-label")
        assertEquals("Remove the tag happy from all definitions", label)
        assertEquals(listOf("/tags"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET tags"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** While the tag filter is on, the page shows its tags and offers the button that turns it off. */
    @Test
    fun thePageShowsAnActiveTagFilter() {
        val browser = started.browserFor(holding(listOf("team-a", "nightly")))

        val answer = browser.get("/tags")

        val forms = answer.page.select("main form")
        assertEquals("The tag filter is on: team-a nightly", filterState(answer.page))
        assertEquals(listOf("team-a nightly"), fieldsOf(answer.form("main form.filter"))["tags"])
        assertEquals(listOf("/tags/filter", "/tags/filter-off"), forms.map { it.attr("action") })
        assertEquals("Turn the tag filter off", forms.last()!!.select("button").text())
    }

    /** Without any tags in use, the page says so. */
    @Test
    fun thePageSaysWhenNoTagIsInUse() {
        val browser = started.browserFor(holding(services = listOf(Samples.orders), plans = emptyList()))

        val page = browser.get("/tags").page

        assertEquals(0, page.select("main ul.tags").size)
        assertEquals("No service, scenario, or plan carries a tag.", page.select("main > p").last()!!.text())
    }

    // The tag filter

    /**
     * The form sets the tag filter to the tags that are entered, and an empty field turns the filter off. The
     * button below the form turns the filter off as well.
     */
    @Test
    fun theFormSetsTheTagFilterAndTheButtonTurnsItOff() {
        val api = holding()
        val browser = started.browserFor(api)

        val set = browser.submit(browser.get("/tags").form(), mapOf("tags" to " Team-A  nightly "))
        val afterSet = browser.follow(set)
        val off = browser.submit(afterSet.form("main form[action=/tags/filter-off]"))
        val afterOff = browser.follow(off)
        val again = browser.submit(afterOff.form(), mapOf("tags" to "x"))
        val emptied = browser.submit(browser.follow(again).form("main form.filter"), mapOf("tags" to " "))
        val afterEmptied = browser.follow(emptied)

        assertEquals(listOf(303, 303, 303, 303), listOf(set, off, again, emptied).map { it.status })
        assertEquals(setOf("/tags"), listOf(set, off, again, emptied).map { it.header("location") }.toSet())
        assertEquals("The tag filter is now team-a nightly.", afterSet.notice)
        assertEquals("The tag filter is on: team-a nightly", filterState(afterSet.page))
        val isOff = "The tag filter is now off. Every service answers requests."
        assertEquals(listOf(isOff, isOff), listOf(afterOff, afterEmptied).map { it.notice })
        assertEquals(1, afterOff.page.select("main form").size)
        val asked =
            listOf(
                "PUT tag-filter" to """{"tags":["Team-A","nightly"]}""",
                "DELETE tag-filter" to "",
                "PUT tag-filter" to """{"tags":["x"]}""",
                "DELETE tag-filter" to "",
            )
        assertEquals(asked, api.changes())
        assertEquals(emptyList(), api.violations)
    }

    /** The overview says when the server's tag filter is on, because the filter decides which services answer requests. */
    @Test
    fun theOverviewSaysWhenTheTagFilterIsOn() {
        val api = holding(listOf("team-a", "nightly"))
        val browser = started.browserFor(api)

        val on = browser.get("/services").page.select("main p.note")
        api.system = api.system.copy(tagFilter = emptyList())
        val off = browser.get("/services").page.select("main p.note")

        val said =
            "The tag filter of the server is on. A service answers requests only if the service or one of its scenarios " +
                "carries one of these tags: team-a nightly. Change the tag filter"
        assertEquals(said to listOf("/tags"), on.text() to on.select("a").map { it.attr("href") })
        assertEquals(0, off.size)
    }

    // Removing a tag

    /**
     * The requirement "Confirmation of destructive actions" for a tag: the link leads to a page that asks
     * for confirmation and says where the tag is used. Nothing is removed until the form on that page is
     * submitted.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItRemovesATagFromAllDefinitions() {
        val tagged = Samples.customers.copy(scenarios = Samples.customers.scenarios.map { it.copy(tags = listOf("happy", "team-a")) })
        val api = holding(services = listOf(tagged, Samples.orders.copy(tags = listOf("team-a"))))
        val browser = started.browserFor(api)

        val asked = browser.get("/tag/remove?tag=team-a")
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post("/tag/remove?tag=team-a", listOf("_token" to token))
        val before = api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        assertEquals(200 to "Remove the tag team-a from all definitions?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf("/tags"), asked.page.select("nav a[aria-current=page]").map { it.attr("href") })
        val carried = "The tag is on 2 services, 2 scenarios, and 0 plans. Each of them loses the tag."
        assertEquals(listOf(carried), asked.page.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Remove the tag" to setOf("danger"), button.text() to button.classNames())
        assertEquals(listOf("Cancel" to "/tags"), asked.page.select("main form a").map { it.text() to it.attr("href") })
        assertEquals(200 to emptyList(), notConfirmed.status to before)
        assertEquals(303 to "/tags", sent.status to sent.header("location"))
        assertEquals("The tag team-a was removed from all definitions.", after.notice)
        assertEquals(listOf("happy", "nightly"), after.page.select("main ul.tags a.tag").map { it.text() })
        assertEquals(listOf("DELETE tags/team-a" to ""), api.changes())
        assertEquals(listOf("GET system", "GET services", "GET plans"), api.requests.map { it.line }.take(3))
        assertEquals(emptyList(), api.violations)
    }

    /** The confirmation page counts in the singular and the plural, and it warns if the tag filter uses the tag. */
    @Test
    fun theConfirmationPageSaysWhereTheTagIsUsedAndWarnsAboutTheTagFilter() {
        val browser = started.browserFor(holding(listOf("nightly", "other")))

        val consequences = { tag: String ->
            val asked = browser.get("/tag/remove?tag=$tag").page
            asked.select("main > p").map { it.text() }
        }

        val inFilter =
            "The tag filter of the server uses this tag. Removing the tag does not change the filter, " +
                "so no service will match this tag afterward."
        val onPlan = "The tag is on 0 services, 0 scenarios, and 1 plan. Each of them loses the tag."
        assertEquals(listOf(onPlan, inFilter), consequences("nightly"))
        assertEquals(listOf("The tag is on 0 services, 1 scenario, and 0 plans. Each of them loses the tag."), consequences("happy"))
        assertEquals(listOf("The tag is on 1 service, 0 scenarios, and 0 plans. Each of them loses the tag."), consequences("TEAM-A"))
    }

    /** A tag that nothing carries has nothing to confirm: the page says so, and the address without a tag is no page at all. */
    @Test
    fun aTagThatIsNotInUseHasNothingToRemove() {
        val api = holding()
        val browser = started.browserFor(api)
        val token = browser.token("/tags")
        api.on("DELETE", "tags/nope") { problem(404, "No service, scenario, or plan carries the tag 'nope'.") }

        val asked = browser.get("/tag/remove?tag=nope")
        val confirmed = browser.post("/tag/remove?tag=nope", listOf("_token" to token, "confirmed" to "yes"))
        val incomplete = listOf("/tag/remove", "/tag/remove?tag=").map { browser.get(it).status }
        val ofNoTag = browser.post("/tag/remove?tag=", listOf("_token" to token, "confirmed" to "yes"))

        assertEquals(404 to "The tag nope is not in use", asked.status to asked.page.select("main h1").text())
        assertEquals("No service, scenario, or plan carries this tag.", asked.wholeTextOf("main > p"))
        val back = asked.page.select("main p.actions a").map { it.attr("href") }
        assertEquals(0 to listOf("/tags"), asked.page.select("main form").size to back)
        assertEquals(404 to "Not there", confirmed.status to confirmed.page.select("main h1").text())
        assertTrue("No service, scenario, or plan carries the tag 'nope'." in confirmed.page.select("main .problem").text())
        assertEquals(listOf(404, 404), incomplete)
        assertEquals(404 to listOf("DELETE tags/nope"), ofNoTag.status to api.changes().map { it.first })
        assertEquals(emptyList(), api.violations)
    }

    // Tags of any text

    /** A tag can contain characters that mean something in an address. The pages pass such a tag as an address parameter. */
    @Test
    fun thePagesWorkWithATagOfAnyText() {
        val tags = mapOf("a/b" to "a%2Fb", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1", ".." to "..")
        val api = holding(services = listOf(Samples.orders.copy(tags = tags.keys.toList())), plans = emptyList(), paths = tags)
        val browser = started.browserFor(api)

        for ((tag, written) in tags) {
            val page = browser.get("/tags").page
            val link = page.select("main ul.tags li").single { it.select("a.tag").text() == tag }.select("a[aria-label]")
            val asked = browser.get(link.attr("href"))
            val sent = browser.submit(asked.form())

            val leading = page.select("main [href], main [action]").map { it.attr("href") + it.attr("action") }
            assertEquals(emptyList(), leading.filterNot(Hostile.SAFE_ADDRESS::matches), tag)
            assertEquals("Remove the tag $tag from all definitions?", asked.page.select("main h1").text(), tag)
            assertEquals(303 to "DELETE tags/$written", sent.status to api.changes().last().first, tag)
        }
        assertEquals(emptyList(), api.violations)
    }

    /** The requirement "Stored content is shown as text" for the tag pages: tags are shown as plain text. */
    @Test
    fun theTagPagesShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        // A tag is a single word, so the texts with whitespace in them are not tags.
        for (text in (Hostile.MARKUP + Hostile.ADDRESSES).filter { tagsIn(it) == listOf(it) }) {
            val services = api.hold(listOf(Samples.orders.copy(tags = listOf(text))))
            api.holdTags(services, api.holdPlans(services, emptyList()), mapOf(text to Hostile.written(text)))
            api.system = api.system.copy(tagFilter = listOf(text))

            val page = browser.get("/tags")
            val asked = browser.get(Address.of("/tag/remove", "tag" to text))

            assertEquals(listOf(text), page.page.select("main ul.tags a.tag").map { it.wholeText() }, text)
            assertEquals(listOf(text), fieldsOf(page.form("main form.filter"))["tags"], text)
            assertEquals("Remove the tag $text from all definitions?", asked.wholeTextOf("main h1"), text)
            for (answer in listOf(page, asked)) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            }
        }
        assertEquals(emptyList(), api.violations)
    }
}
