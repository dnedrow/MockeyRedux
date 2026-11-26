package io.github.dnedrow.mockey.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.FormElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a [Journey] holds a walk to. The coverage test relies on these rules: a walk that breaks one of them
 * isn't a walk that a browser without scripting could take.
 */
class JourneyTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /**
     * Starts a stand-in server that holds the sample services and the sample plan, starts a UI for it, and
     * returns a walk that hasn't begun.
     */
    private fun journey(): Journey {
        val api = started.api()
        val services = api.hold()
        api.holdDefinitions(services, api.holdPlans(services))
        return Journey(started.browserFor(api), api)
    }

    /** A walk that follows links and submits the forms of its pages has no failures, and it remembers where it went. */
    @Test
    fun aWalkByLinksAndFormsHasNoFailures() {
        val journey = journey()

        val overview = journey.step("the overview") { open("/") }
        val service = journey.step("a service") { follow(overview, "a[href='/service?id=svc-customers']") }
        val found = journey.step("a search") { submit(overview.formTo("/search"), mapOf("term" to "found")) }
        journey.step("a service") { follow(service, "a[href^=/service/edit]") }

        assertEquals(emptyList(), journey.failures)
        assertEquals("Search" to "Customers", found.page.select("main h1").text() to service.page.select("main h1").text())
        assertEquals(setOf("/", "/services", "/service", "/search", "/service/edit"), journey.opened.toSet())
        assertEquals(setOf("GET /search"), journey.submitted.toSet())
        val shown = setOf("GET /search", "GET /services", "POST /services/mode", "POST /services/default-scenario")
        assertEquals(shown + setOf("POST /service/duplicate", "POST /service/edit"), journey.shown.toSet())
        assertEquals(listOf("the overview", "a service", "a search"), journey.reached.keys.toList())
        assertEquals(setOf("getSystem", "listServices", "listDiagnostics"), journey.reached["the overview"])
        assertEquals(setOf("getSystem", "search"), journey.reached["a search"])
        val ofService = setOf("getSystem", "getService", "listDiagnostics", "getUniversalErrorScenario", "listInspectors")
        assertEquals(ofService, journey.reached["a service"])
    }

    /**
     * The walk begins at the first page. Any other address has to be one that a page of the walk refers to,
     * as a link does, and as the head of a page does for its stylesheet and its script.
     */
    @Test
    fun anAddressThatNoPageRefersToIsAFailure() {
        val journey = journey()

        val typed = journey.open("/service?id=svc-customers")
        val overview = journey.open("/")
        journey.open("/service?id=svc-customers")
        journey.follow(overview, "link[rel=stylesheet]")
        val script = journey.follow(overview, "script[src]")
        journey.open("/assets/favicon.svg")
        journey.open("/assets/other.js")

        assertEquals(200, typed.status)
        assertEquals(200 to "text/javascript; charset=UTF-8", script.status to script.header("content-type"))
        val typedByHand = "No page of the walk refers to /service?id=svc-customers."
        val unknown = listOf("No page of the walk refers to /assets/other.js.", "The address /assets/other.js led to the status 404")
        assertEquals(listOf(typedByHand) + unknown, journey.failures.map { it.substringBefore(':') })
        assertEquals(true, "/assets/htmx.min.js" in journey.opened)
    }

    /** A file is no page: what a download holds doesn't count as a link, even if it reads like one. */
    @Test
    fun aFileRefersToNothing() {
        val api = started.api()
        val linking = Samples.orders.copy(name = "<a href='/proxy'>Proxy</a>")
        val services = api.hold(listOf(Samples.customers, linking))
        api.holdDefinitions(services, api.holdPlans(services))
        api.holdProxy()
        val journey = Journey(started.browserFor(api), api)

        val definitions = journey.follow(journey.open("/"), "nav a[href=/definitions]")
        val file = journey.follow(definitions, "a[href=/definitions/export]")
        journey.open("/proxy")

        assertEquals(true, "<a href='/proxy'>Proxy</a>" in file.body)
        assertEquals(listOf("No page of the walk refers to /proxy."), journey.failures)
    }

    /** A form has to be one that a page of the walk shows. A form that a test makes up is not. */
    @Test
    fun aFormThatNoPageShowedIsAFailure() {
        val journey = journey()
        val overview = journey.open("/")
        val made = Jsoup.parse("""<form method="get" action="/diagnostics"></form>""").select("form").single() as FormElement

        journey.submit(made)
        journey.submit(overview.formTo("/search"), mapOf("term" to "found"))

        assertEquals(listOf("No page of the walk showed the form GET /diagnostics."), journey.failures)
    }

    /**
     * A walk with the script sends a form that is marked for the script through the script. It goes on
     * from the page that the script makes of the answer: the page that the answer names, or the page that
     * showed the form, with the answer in it. Every other form is sent as without the script.
     */
    @Test
    fun aWalkWithTheScriptGoesOnFromThePageThatTheScriptMakes() {
        val api = started.api()
        api.holdSamples()
        val journey = Journey(started.browserFor(api), api, withScript = true)

        val overview = journey.open("/")
        val switch = overview.formTo("/services/mode")
        val switched = journey.submit(switch, pressed = switch.select("button[value=proxy]").first())
        val form = journey.follow(switched, "a[href=/service/new]").formTo("/service/new")
        val created = journey.submit(form, mapOf("name" to "Billing", "mockUrl" to "billing"))
        val copied = journey.submit(created.formTo("/service/duplicate"))
        val rejected = journey.submit(form, mapOf("name" to "Kept", "delay" to "later"))

        assertEquals(setOf("POST /services/mode", "POST /service/new"), journey.sentByScript.toSet())
        assertEquals(setOf("POST /services/mode", "POST /service/new", "POST /service/duplicate"), journey.submitted.toSet())
        assertEquals("Services" to "The service Customers is in proxy mode now.", switched.page.select("main h1").text() to switched.notice)
        assertEquals("Billing" to "The service Billing was created.", created.page.select("main h1").text() to created.notice)
        assertEquals("Billing (copy)", copied.page.select("main h1").text())
        assertEquals(setOf("/", "/services", "/service/new", "/service"), journey.opened.toSet())
        assertEquals(listOf("The form POST /service/new led to the status 422"), journey.failures.map { it.substringBefore(':') })
        assertEquals("New service" to setOf("delay"), rejected.page.select("main h1").text() to rejected.fieldProblems.keys)
        val headings = journey.pages.map { it.page.select("main h1").text() }
        assertEquals(listOf("Services", "Services", "New service", "Billing", "Billing (copy)", "New service"), headings)
        assertEquals(listOf(overview, switched, created, copied, rejected), journey.pages.filterIndexed { index, _ -> index != 2 })
    }

    /** Every answer has to be a page or a file. A page that reports a failure is not, whether a link or a form led to it. */
    @Test
    fun anAnswerThatReportsAFailureIsAFailure() {
        val journey = journey()
        val overview = journey.open("/")

        // The stand-in holds no twist configurations, so the UI has no list of them to show.
        journey.follow(overview, "nav a[href=/twists]")
        val form = journey.follow(overview, "a[href=/service/new]").formTo("/service/new")
        journey.submit(form, mapOf("name" to "Kept", "delay" to "later"))

        assertEquals(2, journey.failures.size, "${journey.failures}")
        assertEquals("The address /twists led to the status 404", journey.failures[0].substringBefore(':'))
        assertEquals("The form POST /service/new led to the status 422", journey.failures[1].substringBefore(':'))
    }
}
