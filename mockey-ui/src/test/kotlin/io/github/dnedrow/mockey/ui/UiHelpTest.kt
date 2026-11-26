package io.github.dnedrow.mockey.ui

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.13: the requirement "Built-in help". The help has a page that lists its topics and a page for
 * each topic. It's part of the UI itself, so it's there whatever the server says, and it refers to nothing
 * outside the UI.
 */
class UiHelpTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** Returns a browser of a UI for a stand-in server that holds the samples, and that server. */
    private fun browser(): Pair<Browser, FakeAdminApi> {
        val api = started.api()
        api.holdSamples()
        return started.browserFor(api) to api
    }

    /** Returns the links of the list that follows [heading], each as its text and its address. */
    private fun linksBelow(heading: Element): List<Pair<String, String>> {
        val list = checkNotNull(heading.nextElementSibling()) { "Nothing follows the heading ${heading.text()}." }
        return list.select("a").map { it.text() to it.attr("href") }
    }

    /** Returns what the help of [page] says, as text: what the page shows between its heading and its foot. */
    private fun saidBy(page: Browser.Response): String = page.page.select("main .help").text()

    /**
     * The help explains what the requirement names: services, scenarios, response modes, match criteria,
     * request rules, error scenarios, plans, twisting, tags, storage, and import. Each has a page of its
     * own, which says what the thing is in the words that the pages of the UI use for it.
     */
    @Test
    fun theHelpExplainsEverythingThatTheRequirementNames() {
        val (browser, _) = browser()

        for ((name, expected) in REQUIRED) {
            val (title, words) = expected
            val page = browser.get("/help/$name")
            val said = saidBy(page)
            assertEquals(200 to "Help: $title", page.status to page.page.select("main h1").text(), name)
            assertEquals("Help: $title - Mockey", page.page.title(), name)
            assertEquals(emptyList(), words.filterNot { it in said }, name)
            assertTrue(said.split(' ').size > FEW_WORDS, "The topic $name says little: ${said.length} characters")
        }
        assertEquals(11, REQUIRED.size)
    }

    /**
     * An example keeps its lines, so each line is short enough for the width of the help. A longer line
     * would make a reader scroll the example sideways in a window of any width.
     */
    @Test
    fun noLineOfAnExampleIsLongerThanTheHelpIsWide() {
        val (browser, _) = browser()

        val examples = HelpTopics.all.flatMap { topic -> browser.get("/help/${topic.name}").page.select("main pre.example code") }
        val lines = examples.flatMap { it.wholeText().lines() }

        assertEquals(emptyList(), lines.filter { it.length > WIDTH_OF_AN_EXAMPLE })
        assertEquals(emptyList(), lines.filter { it != it.trimEnd() || it.isEmpty() })
        assertTrue(examples.size > FEW_EXAMPLES && lines.size > examples.size, "The examples are to be read: ${examples.size}")
    }

    /**
     * The page of the help says what Mockey is for and lists every topic with a sentence about it. Each
     * entry leads to the page of its topic, and the navigation of every page leads to the help.
     */
    @Test
    fun thePageOfTheHelpListsEveryTopicAndTheNavigationLeadsToIt() {
        val (browser, _) = browser()

        val overview = browser.get("/services")
        val help = browser.get("/help")

        assertEquals(listOf("Help" to "/help"), overview.linksOf("nav a").takeLast(1))
        assertEquals(200 to "Help", help.status to help.page.select("main h1").text())
        assertTrue(saidBy(help).startsWith("Mockey stands between a client and the services that the client calls."))
        val listed = help.page.select("main dl.topics dt a").map { it.text() to it.attr("href") }
        assertEquals(HelpTopics.all.map { it.title to "/help/${it.name}" }, listed)
        assertEquals(18, listed.size)
        assertEquals(HelpTopics.all.map { it.summary }, help.page.select("main dl.topics dd").map { it.text() })
        // A summary is a sentence about its topic, and no two topics have the same one.
        val sentences = HelpTopics.all.filter { it.summary.length > FEW_CHARACTERS && it.summary.endsWith(".") }
        assertEquals(HelpTopics.all.map { it.name }, sentences.map { it.name })
        val summaries = HelpTopics.all.map { it.summary }
        assertEquals(HelpTopics.all.size, summaries.toSet().size)
        assertEquals(listOf("Topics", "More to read"), help.page.select("main h2").map { it.text() })
        for ((title, address) in listed) {
            val page = browser.get(address)
            assertEquals(200 to "Help: $title", page.status to page.page.select("main h1").text())
            assertEquals(listOf("Help"), page.page.select("nav a[aria-current=page]").map { it.text() }, address)
        }
        assertEquals(listOf("Help"), help.page.select("nav a[aria-current=page]").map { it.text() })
        assertEquals(404, browser.get("/help/nothing").status)
    }

    /**
     * A topic names the pages of the UI that it's about and the topics that continue from it. Each of these
     * links leads to a page that the UI has, and the last one leads back to the list of the topics.
     */
    @Test
    fun everyTopicLeadsToThePagesOfTheUiThatItIsAboutAndToTheTopicsThatGoOn() {
        val (browser, _) = browser()

        for (topic in HelpTopics.all) {
            val page = browser.get("/help/${topic.name}")
            val lists = page.page.select("main .help > h2").takeLast(if (topic.places.isEmpty()) 1 else 2)
            val links = lists.associate { it.text() to linksBelow(it) }
            val related = topic.related.map { HelpTopics.named(it).title to "/help/$it" }
            assertEquals(related + ("All topics" to "/help"), links["More help"], topic.name)
            assertEquals(topic.places.takeIf { it.isNotEmpty() }, links["Where to find it"], topic.name)
            assertTrue(topic.related.isNotEmpty() && topic.name !in topic.related, topic.name)
            for ((_, address) in links.values.flatten()) assertEquals(200, browser.get(address).status, "$address of ${topic.name}")
        }
        assertEquals(HelpTopics.all.map { it.name }.distinct(), HelpTopics.all.map { it.name })
    }

    /**
     * The scenario "Help is reachable offline": a browser that reaches nothing but the UI shows the help in
     * full. A page of the help refers only to addresses of the UI, and each of them answers. The help is
     * also shown in full while the UI doesn't reach its server, with a line that says what's wrong.
     */
    @Test
    fun theHelpIsShownInFullByABrowserThatReachesOnlyTheUi() {
        val (browser, api) = browser()
        val addresses = listOf("/help") + HelpTopics.all.map { "/help/${it.name}" }

        val withServer = addresses.associateWith { browser.get(it) }
        api.close()
        val withoutServer = addresses.associateWith { browser.get(it) }

        for ((address, page) in withServer) {
            val referred = page.page.select("[href], [src], [action]").map { it.attr("href") + it.attr("src") + it.attr("action") }
            assertEquals(emptyList(), referred.filterNot { Hostile.SAFE_LINK.matches(it) }, address)
            assertEquals(emptyList(), Hostile.moreThanText(page), address)
            assertEquals(0, page.page.select("main img, main form, main .problem").size, address)
            val files = page.page.select("head [href], head [src]").map { it.attr("href") + it.attr("src") }
            assertEquals(listOf(200, 200, 200), files.map { browser.get(it).status }, address)
        }
        for ((address, page) in withoutServer) {
            val line = "The UI cannot reach the server at ${api.url}. More about it."
            assertEquals(200 to listOf(line), page.status to page.page.select("main > .problem").map { it.text() }, address)
            assertEquals(saidBy(withServer.getValue(address)), saidBy(page), address)
            val foot = page.page.select("body > footer p").eachText()
            assertEquals("Server at ${api.url}", foot.first(), address)
        }
        assertEquals(19, addresses.size)
    }

    /**
     * The help covers every topic of the help of Mockey 2.x: each of them has a page of this help, which
     * says how the server behaves now. Every page of this help covers one of them, so the help has no topic
     * that Mockey 2.x didn't explain.
     */
    @Test
    fun everyTopicOfTheHelpOfMockey2HasItsPlace() {
        val (browser, _) = browser()
        val pages = listOf("/help") + HelpTopics.all.map { "/help/${it.name}" }

        val taken = OF_MOCKEY_2 + PARTS_OF_ITS_SCENARIO_TOPIC

        assertEquals(20, OF_MOCKEY_2.size)
        assertEquals(emptyList(), taken.map { it.second }.distinct().filter { browser.get(it).status != 200 })
        assertEquals(emptySet(), pages.toSet() - taken.map { it.second }.toSet())
    }

    /** The texts of the help are paragraphs and lists, and what stands between backticks is code. */
    @Test
    fun aTextOfTheHelpIsWrittenAsParagraphsListsAndCode() {
        val written =
            createHTML(prettyPrint = false).div {
                prose(
                    """
                    A paragraph of
                    two lines with `code` and <b>no markup</b>.

                    - The first item,
                      which goes on.
                    - The second item with `a/{b}`.

                    The last paragraph.
                    """,
                )
                example(
                    """
                    {
                      "key": "<value>"
                    }
                    """,
                )
                rows(listOf("Name", "Meaning"), listOf(listOf("`key`", "What it means & more.")))
            }
        val read = Jsoup.parseBodyFragment(written).body().child(0)

        val blocks = read.children().map { "${it.tagName()}: ${it.text()}" }
        val first = "p: A paragraph of two lines with code and <b>no markup</b>."
        val list = "ul: The first item, which goes on. The second item with a/{b}."
        assertEquals(listOf(first, list, "p: The last paragraph."), blocks.take(3))
        assertEquals(listOf("The first item, which goes on.", "The second item with a/{b}."), read.select("ul li").map { it.text() })
        assertEquals(listOf("code", "a/{b}", "{\n  \"key\": \"<value>\"\n}", "key"), read.select("code").map(Element::wholeText))
        val kinds = read.children().drop(3).map { it.tagName() + it.classNames().joinToString("") { c -> ".$c" } }
        assertEquals(listOf("pre.example", "table"), kinds)
        val cells = read.select("th").eachText() to read.select("td").eachText()
        assertEquals(listOf("Name", "Meaning") to listOf("key", "What it means & more."), cells)
        assertEquals(0, read.select("b").size)
        assertTrue("&lt;b&gt;no markup&lt;/b&gt;" in written && "&lt;value&gt;" in written, written)
    }

    /**
     * A line of nothing but spaces separates two blocks, as an empty line does, and so do several empty
     * lines. A text of one block is one paragraph.
     */
    @Test
    fun aLineOfSpacesPartsTwoBlocksOfATextAndSoDoSeveralEmptyLines() {
        val text = "  One block.\n   \t\n  - An item\n  - Another item\n\n\nThe end.\n"

        val written = createHTML(prettyPrint = false).div { prose(text) }
        val alone = createHTML(prettyPrint = false).div { prose("A text of one line.") }

        assertEquals("<div><p>One block.</p><ul><li>An item</li><li>Another item</li></ul><p>The end.</p></div>", written)
        assertEquals("<div><p>A text of one line.</p></div>", alone)
    }

    private companion object {
        /** A number of words that a topic has at least. */
        const val FEW_WORDS = 80

        /** A number of characters that the summary of a topic has at least. */
        const val FEW_CHARACTERS = 30

        /** The number of characters of a line that fit the width of the help, in the font of an example. */
        const val WIDTH_OF_AN_EXAMPLE = 72
        const val FEW_EXAMPLES = 5

        /**
         * What the requirement asks the help to explain: the name of each topic, with its title and with words
         * that its page has to say.
         */
        val REQUIRED =
            mapOf(
                "services" to ("Services" to listOf("mock URL pattern", "real URL", "/service/", "customer/{id}")),
                "scenarios" to ("Scenarios" to listOf("status code", "headers", "body", "default scenario")),
                "response-modes" to ("Response modes" to listOf("Proxy", "Static", "Dynamic", "forwards")),
                "match-criteria" to ("Match criteria" to listOf("text", "rule set", "method", "score")),
                "request-rules" to ("Request rules" to listOf("parameters", "headers", "regex_optional", "history")),
                "error-scenarios" to ("Error scenarios" to listOf("universal error scenario", "404", "502")),
                "plans" to ("Plans" to listOf("snapshot", "default plan", "Applying a plan")),
                "twisting" to ("Twisting" to listOf("twist configuration", "target", "first pair")),
                "tags" to ("Tags" to listOf("tag filter of the server", "lower case")),
                "storage" to ("Storage" to listOf("read-only mode", "writable mode", "reset", "reload")),
                "import" to ("Import and export" to listOf("export", "Mockey 2.x", "report", "conflict")),
            )

        /**
         * The topics of the help of Mockey 2.x, in the order of its table of contents, each with the page of
         * this help that covers it.
         */
        val OF_MOCKEY_2 =
            listOf(
                "Big Picture" to "/help",
                "Storage" to "/help/storage",
                "Mock Service" to "/help/services",
                "Service Plan" to "/help/plans",
                "Mock Service Scenario" to "/help/scenarios",
                "Twisting" to "/help/twisting",
                "Get Started - Record Stuff" to "/help/recording",
                "Flush" to "/help/storage",
                "Export/Upload" to "/help/import",
                "URL Injection" to "/help/real-urls",
                "Merge Services" to "/help/merging",
                "Initialization" to "/help/automation",
                "Good Things to Test" to "/help/ideas",
                "URL Config Recommendations" to "/help/clients",
                "Robots" to "/help/automation",
                "Service Request Validation Rules" to "/help/request-rules",
                "Getting the Right Scenario" to "/help/match-criteria",
                "Response JSON Schema" to "/help/schema",
                "Status" to "/help/automation",
                "Start Up Configurations" to "/help/automation",
            )

        /**
         * The parts of the topic "Mock Service Scenario" of Mockey 2.x that have a heading of their own there,
         * each with the page of this help that covers it. The part "Match Argument" explains the response
         * modes as well as the match criteria.
         */
        val PARTS_OF_ITS_SCENARIO_TOPIC =
            listOf(
                "Match Argument" to "/help/response-modes",
                "Match Argument" to "/help/match-criteria",
                "Tags" to "/help/tags",
                "HTTP Response Status" to "/help/scenarios",
                "Error Handling" to "/help/error-scenarios",
            )
    }
}
