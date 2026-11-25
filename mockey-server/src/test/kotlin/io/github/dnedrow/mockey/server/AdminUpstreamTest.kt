package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Task 12.5: the operations of the admin API on the outbound proxy and on the upstream session, over real
 * HTTP: on a listener that runs the admin API with these operations, for an engine of the test.
 *
 * A test sends its request to the listener, and looks at the answer and at what the engine holds afterwards.
 * Every answer of every test is looked through for the passwords of the test, see [send]. What the settings
 * do to a forwarded request is the matter of [ServerAdminApiTest], and what the log holds of a password is
 * the matter of [ServerLoggingTest].
 */
class AdminUpstreamTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminUpstream(engine).routes)
    private val listener = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))

    /** The answers that the tests of this class got. */
    private val answers = mutableListOf<RawHttp.Answer>()

    /** The requirement that the password is never returned: no answer holds a password, whatever its request was. */
    @AfterTest
    fun noAnswerHoldsAPasswordAndEverythingStops() {
        try {
            for (answer in answers) {
                assertFalse(PASSWORD in answer.text || OTHER_PASSWORD in answer.text, "An answer holds a password: $answer")
            }
        } finally {
            listener.stop()
            engine.close()
        }
    }

    private fun send(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer {
        val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        val answer = RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))
        answers += answer
        return answer
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /** The outbound proxy as the engine holds it, with its credentials, or `null` when the engine has no settings. */
    private val proxy: OutboundProxy?
        get() = engine.definitions.settings.outboundProxy

    /** Gives the engine the outbound proxy at [URL], which is enabled and has the user `mockey` with [PASSWORD]. */
    private fun defineTheProxy(): OutboundProxy {
        val defined =
            runBlocking { engine.updateOutboundProxy { OutboundProxy(URL, enabled = true, credentials = CREDENTIALS) }.getOrThrow() }
        return checkNotNull(defined)
    }

    private fun assertProblem(
        answer: RawHttp.Answer,
        status: Int,
        title: String,
        detail: String,
        errors: List<FieldErrorDto> = emptyList(),
    ) {
        assertEquals("HTTP/1.1 $status $title", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        assertEquals(ProblemDto("about:blank", title, status, detail, errors), answer.decoded<ProblemDto>())
    }

    /** [answer] is the problem of a request that is not valid, and it names exactly [errors], which are fields with their messages. */
    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertProblem(answer, 422, "Unprocessable Entity", detail, fields)
    }

    // The outbound proxy

    /**
     * The scenario "Set and read outbound proxy settings", and the scenario "Password is never disclosed" of
     * the proxying. The settings are there before a request has set anything: they hold no URL then.
     */
    @Test
    fun theOutboundProxyIsSetAndReadWithoutItsPassword() {
        val attributes = """{"url": " $URL ", "enabled": true, "username": "mockey", "password": "$PASSWORD"}"""

        val before = send("GET", "upstream-proxy")
        val set = send("PATCH", "upstream-proxy", attributes)
        val read = send("GET", "upstream-proxy")

        assertEquals(200 to OutboundProxyDto("", enabled = false), before.status to before.decoded<OutboundProxyDto>())
        assertEquals("HTTP/1.1 200 OK", set.statusLine, "$set")
        val settings = OutboundProxyDto(URL, enabled = true, username = "mockey", passwordSet = true)
        assertEquals(settings, set.decoded<OutboundProxyDto>())
        assertEquals(200 to settings, read.status to read.decoded<OutboundProxyDto>())
        assertEquals("""{"url":"$URL","enabled":true,"username":"mockey","passwordSet":true}""", read.body)
        assertEquals(OutboundProxy(URL, enabled = true, credentials = CREDENTIALS), proxy)
    }

    /** A request that states no password leaves the password as it is, so a client need not know it to change something else. */
    @Test
    fun anUpdateChangesOnlyTheAttributesThatTheRequestStates() {
        val defined = defineTheProxy()

        val disabled = send("PATCH", "upstream-proxy", """{"enabled": false}""")
        val afterTheSwitch = proxy
        val moved = send("PATCH", "upstream-proxy", """{"url": "http://other.example"}""")
        val afterTheUrl = proxy
        val ofAnotherUser = send("PATCH", "upstream-proxy", """{"username": "someone"}""")
        val afterTheUser = proxy
        val withAnotherPassword = send("PATCH", "upstream-proxy", """{"password": "$OTHER_PASSWORD"}""")
        val afterThePassword = proxy
        val unchanged = send("PATCH", "upstream-proxy", "{}")

        val updates = listOf(disabled, moved, ofAnotherUser, withAnotherPassword, unchanged)
        assertEquals(List(updates.size) { 200 }, updates.map { it.status })
        assertEquals(defined.copy(enabled = false), afterTheSwitch)
        assertEquals(OutboundProxyDto(URL, enabled = false, username = "mockey", passwordSet = true), disabled.decoded<OutboundProxyDto>())
        assertEquals(OutboundProxy("http://other.example", enabled = false, credentials = CREDENTIALS), afterTheUrl)
        val ofAnotherUserStill = ProxyCredentials("someone", PASSWORD)
        assertEquals(OutboundProxy("http://other.example", enabled = false, credentials = ofAnotherUserStill), afterTheUser)
        val changed = OutboundProxy("http://other.example", enabled = false, credentials = ProxyCredentials("someone", OTHER_PASSWORD))
        assertEquals(changed, afterThePassword)
        assertEquals(changed, proxy)
        val stated = OutboundProxyDto("http://other.example", enabled = false, username = "someone", passwordSet = true)
        assertEquals(stated, unchanged.decoded<OutboundProxyDto>())
    }

    /** There are credentials while a user name or a password has a value. Each is taken away with `null`, and an empty text is none. */
    @Test
    fun theCredentialsAreTakenAwayWithNull() {
        defineTheProxy()

        val withoutAPassword = send("PATCH", "upstream-proxy", """{"password": null}""")
        val afterThePassword = proxy
        val withoutAUser = send("PATCH", "upstream-proxy", """{"username": null}""")
        val afterTheUser = proxy
        val withEmptyTexts = send("PATCH", "upstream-proxy", """{"username": "", "password": ""}""")

        assertEquals(OutboundProxyDto(URL, enabled = true, username = "mockey"), withoutAPassword.decoded<OutboundProxyDto>())
        assertEquals(OutboundProxy(URL, enabled = true, credentials = ProxyCredentials("mockey", "")), afterThePassword)
        assertEquals(200 to OutboundProxyDto(URL, enabled = true), withoutAUser.status to withoutAUser.decoded<OutboundProxyDto>())
        assertEquals(OutboundProxy(URL, enabled = true), afterTheUser)
        assertEquals(200 to OutboundProxyDto(URL, enabled = true), withEmptyTexts.status to withEmptyTexts.decoded<OutboundProxyDto>())
        assertEquals(OutboundProxy(URL, enabled = true), proxy)
    }

    /** Credentials can be set without a URL, for a proxy that is not enabled. A password alone has no user name. */
    @Test
    fun aPasswordIsSetWithoutAUserNameAndWithoutAUrl() {
        val answer = send("PATCH", "upstream-proxy", """{"password": "$PASSWORD"}""")

        assertEquals(200 to OutboundProxyDto("", enabled = false, passwordSet = true), answer.status to answer.decoded<OutboundProxyDto>())
        assertEquals(OutboundProxy("", enabled = false, credentials = ProxyCredentials("", PASSWORD)), proxy)
    }

    /** Settings without a URL and without credentials, for a proxy that is not enabled, are no settings. The resource is there still. */
    @Test
    fun settingsThatHoldNothingAreNoSettings() {
        defineTheProxy()

        val emptied = send("PATCH", "upstream-proxy", """{"url": " ", "enabled": false, "username": null, "password": null}""")
        val read = send("GET", "upstream-proxy")

        assertEquals(200 to OutboundProxyDto("", enabled = false), emptied.status to emptied.decoded<OutboundProxyDto>())
        assertEquals(200 to OutboundProxyDto("", enabled = false), read.status to read.decoded<OutboundProxyDto>())
        assertNull(proxy)
    }

    /**
     * The engine says what the settings of an outbound proxy are. The problem names the member of the request
     * that is not valid and says nothing of its value, which can be a password in the wrong place.
     */
    @Test
    fun settingsThatAreNotThoseOfAnOutboundProxyChangeNothing() {
        val defined = defineTheProxy()
        val noMember = "The request can have no member of this name here."
        val requests =
            mapOf(
                """{"url": "https://proxy.example:3128"}""" to ("url" to "The URL of the outbound proxy must start with http://."),
                """{"url": "http://mockey:$OTHER_PASSWORD@proxy.example:3128"}""" to
                    ("url" to "The URL of the outbound proxy must not hold a user name or a password."),
                """{"url": "http://proxy.example:3128/$OTHER_PASSWORD"}""" to
                    ("url" to "The URL of the outbound proxy must name a host and a port and nothing else."),
                """{"url": "http://proxy.example:0"}""" to ("url" to "The URL of the outbound proxy names a port that does not exist."),
                """{"url": "$OTHER_PASSWORD"}""" to ("url" to "The URL of the outbound proxy must start with http://."),
                """{"url": ""}""" to ("url" to "An outbound proxy needs a URL before it can be enabled."),
                """{"username": "mockey:$OTHER_PASSWORD"}""" to
                    ("username" to "The user name for an outbound proxy must not contain a colon."),
                """{"password": 7, "username": "$OTHER_PASSWORD"}""" to ("password" to "The value has to be a text."),
                """{"enabled": "$OTHER_PASSWORD"}""" to ("enabled" to "The value has to be true or false."),
                """{"url": null}""" to ("url" to "The member must have a value, and null is none."),
                """{"passwordSet": true, "password": "$OTHER_PASSWORD"}""" to ("passwordSet" to noMember),
                """{"credentials": {"username": "someone", "password": "$OTHER_PASSWORD"}}""" to ("credentials" to noMember),
            )

        val refused = requests.mapValues { (request, _) -> send("PATCH", "upstream-proxy", request) }

        for ((request, error) in requests) {
            assertInvalid(refused.getValue(request), error)
        }
        assertEquals(defined, proxy)
    }

    /** A body that the API does not take at all is not repeated either: no JSON, no object, another content type, too long. */
    @Test
    fun aBodyThatIsNotTakenIsNotRepeated() {
        val defined = defineTheProxy()
        val attributes = """{"password": "$OTHER_PASSWORD"}"""
        val asAForm = listOf("Content-Type: application/x-www-form-urlencoded")
        val target = "/api/v1/upstream-proxy"

        val cut = send("PATCH", "upstream-proxy", attributes.dropLast(1))
        val ofAList = send("PATCH", "upstream-proxy", "[$attributes]")
        val ofAForm = RawHttp.exchange(listener.address, RawHttp.request("PATCH", target, asAForm, attributes)).also { answers += it }
        val tooLong = send("PATCH", "upstream-proxy", """{"password": "$OTHER_PASSWORD", "url": "${"x".repeat(70_000)}"}""")
        val replaced = send("PUT", "upstream-proxy", attributes)

        assertProblem(cut, 400, "Bad Request", "The body of the request is no JSON object.")
        assertProblem(ofAList, 400, "Bad Request", "The body of the request is no JSON object.")
        assertEquals(listOf<Int?>(415, 413, 405), listOf(ofAForm.status, tooLong.status, replaced.status))
        assertEquals(listOf("GET, HEAD, PATCH"), replaced.headerValues("Allow"))
        assertEquals(defined, proxy)
    }

    // The upstream session

    /** The session is emptied, also when it holds nothing, and it is not read: its cookies are secrets of the real services. */
    @Test
    fun theUpstreamSessionIsEmptiedAndNotRead() {
        val emptied = send("DELETE", "system/upstream-session")
        val again = send("DELETE", "system/upstream-session")
        val read = send("GET", "system/upstream-session")

        assertEquals("HTTP/1.1 204 No Content" to "", emptied.statusLine to emptied.body)
        assertEquals(emptyList(), emptied.headerValues("Content-Type") + emptied.headerValues("Content-Length"))
        assertEquals(204, again.status)
        assertEquals(405 to listOf("DELETE"), read.status to read.headerValues("Allow"))
    }

    private companion object {
        const val URL = "http://proxy.example:3128"
        const val PASSWORD = "pw-3f9a1c7e5b02"
        const val OTHER_PASSWORD = "pw-8d2e6b0a4c91"
        val CREDENTIALS = ProxyCredentials("mockey", PASSWORD)
    }
}
