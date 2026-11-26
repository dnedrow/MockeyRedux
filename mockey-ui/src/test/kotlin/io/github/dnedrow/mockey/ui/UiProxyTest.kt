package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Task 13.8: the page for the outbound proxy and the upstream session. The form edits the proxy URL, the
 * enabled flag, and the credentials, and a button clears the upstream session.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiProxyTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val none = OutboundProxyDto("", enabled = false)

    /** Starts a stand-in server whose outbound proxy has the settings of [proxy]. */
    private fun holding(proxy: OutboundProxyDto = Samples.proxy): FakeAdminApi = started.api().also { it.holdProxy(proxy) }

    // The page

    /**
     * The page shows the proxy settings in a form. The password field always starts empty, because the
     * server never reveals the password. Submitting the form unchanged doesn't send any attribute.
     */
    @Test
    fun thePageShowsTheProxySettingsAndSendsNothingIfNothingIsChanged() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/proxy")
        val form = answer.form("main form[action=/proxy]")
        val sent = browser.submit(form)
        val after = browser.follow(sent)

        val page = answer.page
        val fields = mapOf("url" to listOf("http://proxy.example.com:3128"), "enabled" to listOf("on"), "username" to listOf("mockey"))
        val password = form.select("[name=password]").single()
        assertEquals(200, answer.status)
        assertEquals("Outbound proxy - Mockey" to "Outbound proxy", page.title() to page.select("main h1").text())
        assertEquals("post", form.attr("method"))
        assertEquals(fields + ("password" to listOf("")), fieldsOf(form))
        assertEquals("password" to "new-password", password.attr("type") to password.attr("autocomplete"))
        assertFalse(password.hasAttr("value"))
        assertEquals("off", form.select("[name=username]").attr("autocomplete"))
        val labels = listOf("Proxy URL", "Send forwarded requests through the proxy", "User name", "Password", "Remove the stored password")
        assertEquals(labels, form.select("label").map { it.text() })
        assertEquals(true, form.select("#f-password-hint").text().startsWith("The server has a password for the proxy."))
        val hidden = listOf("_token") + ProxyForm.FIELDS.map { "_shown.$it" }
        assertEquals(hidden, form.select("input[type=hidden]").map { it.attr("name") })
        assertEquals(listOf("/system"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(303 to "/proxy", sent.status to sent.header("location"))
        val saved = "The proxy settings were saved. Forwarded requests now go through http://proxy.example.com:3128."
        assertEquals(saved, after.notice)
        assertEquals(listOf("PATCH upstream-proxy" to "{}"), api.changes())
        assertEquals(listOf("GET system", "GET upstream-proxy"), api.requests.map { it.line }.take(2))
        assertEquals(emptyList(), api.violations)
    }

    /** Without proxy settings, the form is empty, and it doesn't offer to remove a password that isn't there. */
    @Test
    fun thePageShowsAnEmptyFormWhenThereAreNoProxySettings() {
        val browser = started.browserFor(holding(none))

        val form = browser.get("/proxy").form("main form[action=/proxy]")

        assertEquals(mapOf("url" to listOf(""), "username" to listOf(""), "password" to listOf("")), fieldsOf(form))
        assertEquals(0, form.select("[name=removePassword]").size)
        assertEquals(true, form.select("#f-password-hint").text().startsWith("The server has no password for the proxy."))
    }

    // What the form sends

    /**
     * The form only sends the attributes whose fields were changed. A password is only sent if one was typed
     * in, and the check box that removes the stored password wins over a typed one.
     */
    @Test
    fun theFormOnlySendsTheChangedAttributes() {
        val api = holding()
        val browser = started.browserFor(api)
        val form = browser.get("/proxy").form("main form[action=/proxy]")

        val changes =
            listOf(
                mapOf("url" to "http://other.example.com:8080") to """{"url":"http://other.example.com:8080"}""",
                mapOf("enabled" to null) to """{"enabled":false}""",
                mapOf("username" to "someone") to """{"username":"someone"}""",
                mapOf("username" to "") to """{"username":null}""",
                mapOf("password" to "s3cret") to """{"password":"s3cret"}""",
                mapOf("removePassword" to "on") to """{"password":null}""",
                mapOf("password" to "s3cret", "removePassword" to "on") to """{"password":null}""",
                mapOf("url" to "", "enabled" to null, "username" to "", "removePassword" to "on") to
                    """{"url":"","enabled":false,"username":null,"password":null}""",
            )
        val answers = changes.map { (edits, _) -> browser.submit(form, edits) }
        val after = browser.follow(answers.last())

        assertEquals(changes.map { 303 }, answers.map { it.status })
        assertEquals(changes.map { "PATCH upstream-proxy" to it.second }, api.changes())
        assertEquals("The proxy settings were saved. Forwarded requests now go directly to the real services.", after.notice)
        assertEquals(emptyList(), api.violations)
    }

    /** Browsers change some values on their own. The form doesn't treat such a value as changed. */
    @Test
    fun theFormDoesNotSendAValueThatOnlyTheBrowserAltered() {
        val odd = "one\ntwo\u0000three\r\nfour"
        val api = holding(OutboundProxyDto(odd, enabled = false, username = odd))
        val browser = started.browserFor(api)
        val form = browser.get("/proxy").form("main form[action=/proxy]")

        val sent = browser.submit(form)

        val fields = fieldsOf(form)
        assertEquals(listOf("onetwo\uFFFDthreefour"), listOf("url", "username").flatMap(fields::getValue).distinct())
        assertEquals(303 to listOf("{}"), sent.status to api.changes().map { it.second })
    }

    // A form that is rejected

    /**
     * A form that the server rejects is displayed again with the message next to the field. The page never
     * sends a typed password back to the browser.
     */
    @Test
    fun aRejectedFormShowsTheMessageAndDoesNotRepeatThePassword() {
        val api = holding()
        val browser = started.browserFor(api)
        val errors =
            listOf(
                FieldErrorDto("url", "The URL of an outbound proxy starts with http://."),
                FieldErrorDto("username", "The user name for an outbound proxy must not contain a colon."),
            )
        api.on("PATCH", "upstream-proxy") { problem(422, "The request is not valid.", errors) }
        val form = browser.get("/proxy").form("main form[action=/proxy]")

        val answer = browser.submit(form, mapOf("url" to "ftp://proxy", "username" to "a:b", "password" to "s3cret-typed"))

        val again = answer.form("main form[action=/proxy]")
        val messages = mapOf("url" to listOf(errors[0].message), "username" to listOf(errors[1].message))
        assertEquals(422 to "Outbound proxy", answer.status to answer.page.select("main h1").text())
        assertEquals(messages, answer.fieldProblems)
        assertEquals(listOf("ftp://proxy") to listOf("a:b"), fieldsOf(again).let { it["url"] to it["username"] })
        assertEquals(listOf(""), fieldsOf(again)["password"])
        assertFalse("s3cret-typed" in answer.everything)
        assertEquals(listOf("PATCH upstream-proxy"), api.changes().map { it.first })
    }

    /** The stand-in refuses to enable a proxy without a URL, as a server does, and the message shows up at the URL field. */
    @Test
    fun enablingAProxyWithoutAUrlIsRejectedByTheServer() {
        val api = holding(none)
        val browser = started.browserFor(api)

        val answer = browser.submit(browser.get("/proxy").form("main form[action=/proxy]"), mapOf("enabled" to "on"))

        assertEquals(422, answer.status)
        assertEquals(mapOf("url" to listOf("An outbound proxy needs a URL before it can be enabled.")), answer.fieldProblems)
        assertEquals(listOf("on"), fieldsOf(answer.form("main form[action=/proxy]"))["enabled"])
    }

    // The upstream session

    /** The button clears the upstream session, and the page says that it did. */
    @Test
    fun theButtonClearsTheUpstreamSession() {
        val api = holding()
        val browser = started.browserFor(api)

        val page = browser.get("/proxy")
        val form = page.form("main form[action=/proxy/clear-session]")
        val sent = browser.submit(form)
        val after = browser.follow(sent)

        assertEquals("Upstream session", page.page.select("main h2").text())
        assertEquals("post" to "Clear the upstream session", form.attr("method") to form.select("button").text())
        assertEquals(303 to "/proxy", sent.status to sent.header("location"))
        assertEquals("The upstream session was cleared.", after.notice)
        assertEquals(listOf("DELETE system/upstream-session" to ""), api.changes())
        assertEquals(emptyList(), api.violations)
    }

    // Leading to the page

    /** The server page leads to the proxy page, which belongs to the same section of the navigation. */
    @Test
    fun theServerPageLeadsToTheProxyPage() {
        val browser = started.browserFor(holding())

        val system = browser.get("/system").page
        val links = system.select("main p.actions a").map { it.text() to it.attr("href") }

        assertEquals(listOf("Outbound proxy and upstream session" to "/proxy"), links)
    }

    // Stored content is text

    /** The requirement "Stored content is shown as text" for the proxy page: the URL and the user name are shown as plain text. */
    @Test
    fun theProxyPageShowsStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            api.holdProxy(OutboundProxyDto(text, enabled = true, username = text, passwordSet = true))

            val page = browser.get("/proxy")
            val sent = browser.submit(page.form("main form[action=/proxy]"))

            // A one-line field holds its value without line breaks, just like in a browser.
            val fields = fieldsOf(page.form("main form[action=/proxy]"))
            assertEquals(listOf(text.replace("\n", "")), listOf("url", "username").flatMap(fields::getValue).distinct(), text)
            assertEquals(200 to 303, page.status to sent.status, text)
            assertEquals(emptyList(), Hostile.moreThanText(page), text)
            assertEquals(emptyList(), Hostile.moreThanText(browser.follow(sent)), text)
        }
        assertEquals(emptyList(), api.violations)
    }
}
