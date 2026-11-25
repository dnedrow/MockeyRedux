package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The admin API over real HTTP, on a listener that runs the module of the admin listener: which requests
 * are for the API, how a request comes to its operation, what the API asks of a request, and what it
 * answers when it does not do what a request asks for.
 *
 * The API of a test has operations of the test, on what it calls items. The operations of the server are
 * the matter of other tests.
 */
class AdminApiTest {
    private val levelBefore = ServerLogging.level()
    private val listeners = mutableListOf<Listener>()

    /** What the operations of the test were asked for, in the order of the requests. */
    private val asked = CopyOnWriteArrayList<String>()

    /** The parameters that the operations on items take in a query string, and look at no further. */
    private val ofItems = setOf("x", "token")

    private val routes =
        listOf(
            AdminRoute(HttpMethod.Get, "items", ofItems) {
                asked += "list"
                AdminResponse.json(listOf("first", "zweites ✓"))
            },
            AdminRoute(HttpMethod.Post, "items", ofItems) {
                val item = jsonObject()
                asked += "create $item"
                AdminResponse.json(item, HttpStatusCode.Created)
            },
            AdminRoute(HttpMethod.Get, "items/newest") {
                asked += "newest"
                AdminResponse.json("the newest item")
            },
            AdminRoute(HttpMethod.Get, "items/{itemId}", ofItems) {
                asked += "read ${parameter("itemId")}"
                AdminResponse.json(parameter("itemId"))
            },
            AdminRoute(HttpMethod.Put, "items/{itemId}", ofItems) {
                asked += "change ${parameter("itemId")}"
                AdminResponse.json(changed(parameter("itemId")).orProblem())
            },
            AdminRoute(HttpMethod.Get, "items/{itemId}/parts/{partId}") {
                asked += "read ${parameter("partId")} of ${parameter("itemId")}"
                AdminResponse.json(listOf(parameter("itemId"), parameter("partId")))
            },
            AdminRoute(HttpMethod.Get, "found", query = setOf("term", "tag")) {
                asked += "find"
                AdminResponse.json(mapOf("term" to queryValues("term"), "tag" to queryValues("tag")))
            },
            AdminRoute(HttpMethod.Get, "first", query = setOf("term")) {
                asked += "find the first"
                AdminResponse.json(queryValue("term"))
            },
            AdminRoute(HttpMethod.Post, "copies/{itemId}") {
                asked += "copy ${parameter("itemId")}"
                AdminResponse.created("a copy", "items", parameter("itemId"), "copies", "the first/of 2")
            },
            AdminRoute(HttpMethod.Delete, "copies/{itemId}") {
                asked += "delete the copies of ${parameter("itemId")}"
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Get, "host") { AdminResponse.json(host) },
            AdminRoute(HttpMethod.Get, "broken", ofItems) { error(FAILURE) },
        )

    @AfterTest
    fun stopTheListenersAndRestoreTheLevelOfTheLog() {
        listeners.forEach(Listener::stop)
        ServerLogging.configure(levelBefore)
    }

    /** What the engine says to a change of the item [id]: there is no item `missing`, and the name of the item `taken` is in use. */
    private fun changed(id: String): ChangeResult<String> =
        when (id) {
            "missing" -> {
                ChangeResult.NotFound("No item has the identifier missing.")
            }

            "taken" -> {
                val errors = listOf(FieldError("name", NAME_TAKEN), FieldError("mockUrl", MOCK_URL_TAKEN))
                ChangeResult.Rejected(ValidationFailure(errors))
            }

            else -> {
                ChangeResult.Changed(Definitions.EMPTY, "the item $id as it is now")
            }
        }

    /** Starts a listener that runs the admin API with the operations of the test, on a server with [token] as its admin token. */
    private fun api(
        token: String? = null,
        maxRequestBody: Long = 1_024,
    ): Listener {
        val api = AdminApi(token?.let(::AdminToken), maxRequestBody, routes)
        val module = MockeyServer.adminListenerModule(ForwardMark(adminPort = null), api)
        return Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), module).also { listeners += it }
    }

    /**
     * Sends a request and returns the answer. A request with a [body] says that its body is JSON, unless
     * [headers] say what it is.
     */
    private fun Listener.answerTo(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): RawHttp.Answer {
        val declared = body.isEmpty() || headers.any { it.startsWith("Content-Type", ignoreCase = true) }
        val all = if (declared) headers else headers + JSON
        return RawHttp.exchange(address, RawHttp.request(method, target, all, body))
    }

    /** Sends a request that consists of [lines] and nothing else, and returns the answer. */
    private fun Listener.answerToLines(vararg lines: String): RawHttp.Answer =
        RawHttp.exchange(address, (lines.asList() + listOf("", "")).joinToString(RawHttp.CRLF).toByteArray(Charsets.ISO_8859_1))

    /** Returns the problem document that [answer] is, and fails when it is none, or one with a member that such a document has not. */
    private fun problemOf(answer: RawHttp.Answer): ProblemDto {
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"), "$answer")
        return AdminApiJson.instance.decodeFromString<ProblemDto>(answer.bodyInUtf8)
    }

    private fun assertProblem(
        answer: RawHttp.Answer,
        status: Int,
        title: String,
        detail: String,
        errors: List<FieldErrorDto> = emptyList(),
    ) {
        assertEquals(ProblemDto("about:blank", title, status, detail, errors), problemOf(answer), "$answer")
        assertEquals("HTTP/1.1 $status $title", answer.statusLine)
    }

    // Which requests are for the API

    /** The scenario "Listing services", as far as the API of a test goes: an operation under the prefix answers with JSON. */
    @Test
    fun theApiAnswersTheRequestsForItsPrefixAndForThePathsBelowIt() {
        val api = api()

        val ofOperation = api.answerTo("GET", "/api/v1/items")
        val ofPrefix = api.answerTo("GET", "/api/v1")
        val belowPrefix = api.answerTo("GET", "/api/v1/")

        assertEquals("HTTP/1.1 200 OK" to """["first","zweites ✓"]""", ofOperation.statusLine to ofOperation.bodyInUtf8)
        assertProblem(ofPrefix, 404, "Not Found", NO_RESOURCE)
        assertProblem(belowPrefix, 404, "Not Found", NO_RESOURCE)
        assertEquals(listOf("list"), asked)
    }

    /** Ktor answers what is not for the API, with the status 404 and nothing else, as it does on a listener that serves nothing. */
    @Test
    fun aPathThatOnlyResemblesThePrefixIsNotForTheApi() {
        val api = api()
        val targets =
            listOf(
                "/",
                "/items",
                "/api",
                "/api/",
                "/api/v2/items",
                "/api/v10/items",
                "/api/v1x",
                "/api/v1.0/items",
                "/API/V1/items",
                "/x/api/v1/items",
                "//api/v1/items",
                "/api//v1/items",
                "/%61pi/v1/items",
                "/api/v1%2Fitems",
                "/status",
                "/service/api/v1/items",
            )

        for (target in targets) {
            val answer = api.answerTo("GET", target)

            assertEquals("HTTP/1.1 404 Not Found", answer.statusLine, target)
            assertEquals(listOf("Content-Length: 0", "Connection: close"), answer.headers, target)
            assertEquals("", answer.body, target)
        }
        assertEquals(emptyList(), asked)
    }

    /** A client of a proxy writes a URL as the request target, and a server has to take it. */
    @Test
    fun aRequestTargetThatIsAUrlIsForThePathOfThatUrl() {
        val api = api()

        val ofOperation = api.answerTo("GET", "http://mockey.test:8081/api/v1/items/7")
        val ofNothing = api.answerTo("GET", "http://mockey.test:8081/items/7")

        assertEquals(200 to "\"7\"", ofOperation.status to ofOperation.body)
        assertEquals(404 to "", ofNothing.status to ofNothing.body)
    }

    @Test
    fun theQueryStringIsNoPartOfThePath() {
        val api = api()
        val targets = listOf("/api/v1/items?", "/api/v1/items?x=/api/v1/broken", "/api/v1/items?x=/7", "/api/v1/items/7?x=1")

        val answers = targets.map { api.answerTo("GET", it) }

        assertEquals(List(4) { 200 }, answers.map { it.status })
        assertEquals(listOf("list", "list", "list", "read 7"), asked)
    }

    // The query string

    /** The parameters of a query string are decoded as a form writes them, and a name can be there more than once. */
    @Test
    fun anOperationGetsTheParametersOfTheQueryStringDecodedAndInTheirOrder() {
        val api = api()
        val queries =
            mapOf(
                "" to """{"term":[],"tag":[]}""",
                "?" to """{"term":[],"tag":[]}""",
                "?term=orders" to """{"term":["orders"],"tag":[]}""",
                "?tag=b&term=x&tag=a&tag=b" to """{"term":["x"],"tag":["b","a","b"]}""",
                "?term=a+b%20c%2Bd%26e%3Df" to """{"term":["a b c+d&e=f"],"tag":[]}""",
                "?term=a=b==c" to """{"term":["a=b==c"],"tag":[]}""",
                "?term&tag=" to """{"term":[""],"tag":[""]}""",
                "?&&term=x&" to """{"term":["x"],"tag":[]}""",
                "?%74erm=x" to """{"term":["x"],"tag":[]}""",
                "?term=Kunden%C3%BCbersicht+%E2%9C%93" to """{"term":["Kundenübersicht ✓"],"tag":[]}""",
                "?term=${RawHttp.utf8("Kundenübersicht+✓")}" to """{"term":["Kundenübersicht ✓"],"tag":[]}""",
            )

        val answers = queries.keys.associateWith { api.answerTo("GET", "/api/v1/found$it") }

        assertEquals(queries, answers.mapValues { (_, answer) -> answer.bodyInUtf8 })
        assertEquals(List(queries.size) { 200 }, answers.values.map { it.status })
    }

    /**
     * A client that misspells a parameter is not to get what it would get without the parameter. The problem
     * names the parameters that the operation takes, and nothing of the query string.
     */
    @Test
    fun aParameterThatAnOperationDoesNotTakeGetsTheStatus400AndIsNotRepeated() {
        val api = api()
        val another = "The query string of the request has a parameter that the operation does not take."
        val requests =
            mapOf(
                "/api/v1/found?terms=$SECRET" to "$another It takes these: term, tag.",
                "/api/v1/found?term=x&$SECRET=y" to "$another It takes these: term, tag.",
                "/api/v1/found?Term=x" to "$another It takes these: term, tag.",
                "/api/v1/found?=$SECRET" to "$another It takes these: term, tag.",
                "/api/v1/found?term=x&$SECRET" to "$another It takes these: term, tag.",
                "/api/v1/first?tag=$SECRET" to "$another It takes these: term.",
                "/api/v1/host?$SECRET" to "$another It takes no parameter.",
                "/api/v1/items/newest?x=$SECRET" to "$another It takes no parameter.",
            )
        var answers = emptyMap<String, RawHttp.Answer>()

        val log = TestLog.of { answers = requests.keys.associateWith { api.answerTo("GET", it) } }

        for ((target, answer) in answers) {
            assertProblem(answer, 400, "Bad Request", requests.getValue(target))
        }
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aQueryStringWithAMalformedEscapeGetsTheStatus400AndIsNotRepeated() {
        val api = api()
        val queries = listOf("term=$SECRET%zz", "term=%", "te%4=x", "term=%C3%28$SECRET%")
        val malformed = "The query string of the request holds a percent sign that begins no escape of a character."
        var answers = emptyList<RawHttp.Answer>()

        val log = TestLog.of { answers = queries.map { api.answerTo("GET", "/api/v1/found?$it") } }

        for (answer in answers) {
            assertProblem(answer, 400, "Bad Request", malformed)
        }
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), asked)
    }

    /** An operation that takes one value of a parameter says so. Without the parameter there is none. */
    @Test
    fun aParameterThatIsToBeThereOnceGetsTheStatus400WhenItIsThereTwice() {
        val api = api()

        val once = api.answerTo("GET", "/api/v1/first?term=a+b")
        val never = api.answerTo("GET", "/api/v1/first")
        val twice = api.answerTo("GET", "/api/v1/first?term=$SECRET&term=$SECRET")

        assertEquals(listOf(200 to "\"a b\"", 200 to "null"), listOf(once, never).map { it.status to it.body })
        assertProblem(twice, 400, "Bad Request", "The query string of the request has the parameter term more than once.")
    }

    /** The path and the method come first: a request for nothing, or for no operation, learns that whatever its query string is. */
    @Test
    fun theQueryStringIsLookedAtWhenTheOperationIsKnown() {
        val api = api()

        val ofNothing = api.answerTo("GET", "/api/v1/nothing?term=%zz")
        val withoutOperation = api.answerTo("DELETE", "/api/v1/found?another=1")

        assertEquals(listOf<Int?>(404, 405), listOf(ofNothing.status, withoutOperation.status))
    }

    /** The listener refuses such a request before the API sees it, see [RequestHost]. Its refusal is plain text and no problem document. */
    @Test
    fun aRequestThatNamesNoHostGetsTheRefusalOfTheListenerAndReachesNoOperation() {
        val api = api(token = TOKEN)

        val answer = api.answerToLines("GET /api/v1/items HTTP/1.1", "Connection: close")

        assertEquals("HTTP/1.1 400 Bad Request" to RequestHost.MISSING, answer.statusLine to answer.body)
        assertEquals(listOf("text/plain; charset=utf-8"), answer.headerValues("Content-Type"))
        assertEquals(emptyList(), answer.headerValues("WWW-Authenticate"))
        assertEquals(emptyList(), asked)
    }

    // How a request comes to its operation

    @Test
    fun aRequestGoesToTheOperationWithItsMethodForItsPath() {
        val api = api()

        val read = api.answerTo("GET", "/api/v1/items/7")
        val created = api.answerTo("POST", "/api/v1/items", body = """{"name":"seven"}""")
        val changed = api.answerTo("PUT", "/api/v1/items/7")
        val part = api.answerTo("GET", "/api/v1/items/7/parts/wheel")

        assertEquals("HTTP/1.1 200 OK" to "\"7\"", read.statusLine to read.body)
        assertEquals("HTTP/1.1 201 Created" to """{"name":"seven"}""", created.statusLine to created.body)
        assertEquals("HTTP/1.1 200 OK" to "\"the item 7 as it is now\"", changed.statusLine to changed.body)
        assertEquals("HTTP/1.1 200 OK" to """["7","wheel"]""", part.statusLine to part.body)
        assertEquals(listOf("read 7", """create {"name":"seven"}""", "change 7", "read wheel of 7"), asked)
    }

    @Test
    fun aSegmentThatStandsForItselfComesBeforeAParameter() {
        val api = api()

        val newest = api.answerTo("GET", "/api/v1/items/newest")
        val changed = api.answerTo("PUT", "/api/v1/items/newest")

        assertEquals(200 to "\"the newest item\"", newest.status to newest.body)
        assertEquals(405, changed.status)
        assertEquals(listOf("GET, HEAD"), changed.headerValues("Allow"))
        assertEquals(listOf("newest"), asked)
    }

    /** An escaped slash stays in its segment, and a character beyond ASCII arrives whether the client escaped it or not. */
    @Test
    fun whatAPathHasAtAParameterIsDecodedAfterThePathIsTakenApart() {
        val api = api()
        val targets =
            listOf(
                "/api/v1/items/a%2Fb",
                "/api/v1/items/a%2fb%3Fc%23d",
                "/api/v1/items/caf%C3%A9%20%E2%9C%93",
                "/api/v1/items/${RawHttp.utf8("café")}%20${RawHttp.utf8("✓")}",
                "/api/v1/items/a+b",
                "/api/v1/items/%25zz",
                "/api/v1/items/%7BitemId%7D",
                "/api/v1/items/%20",
            )

        val answers = targets.map { api.answerTo("GET", it) }

        assertEquals(List(targets.size) { 200 }, answers.map { it.status })
        assertEquals(listOf("a/b", "a/b?c#d", "café ✓", "café ✓", "a+b", "%zz", "{itemId}", " ").map { "read $it" }, asked)
    }

    /** A segment that stands for itself is not decoded, so `%69tems` is another path than `items`, with a well-formed escape or not. */
    @Test
    fun aPathThatNoRouteIsForGetsTheStatus404() {
        val api = api()
        val targets =
            listOf(
                "/api/v1/nothing",
                "/api/v1/Items",
                "/api/v1/items/",
                "/api/v1//items",
                "/api/v1/items//parts/wheel",
                "/api/v1/items/7/parts",
                "/api/v1/items/7/parts/",
                "/api/v1/items/7/parts/wheel/spokes",
                "/api/v1/items%2F7",
                "/api/v1/%69tems",
                "/api/v1/%69tems/7",
                "/api/v1/items/7/p%61rts/wheel",
                "/api/v1/%zz",
                "/api/v1/%zz/items",
            )

        for (target in targets) {
            for (method in listOf("GET", "POST", "DELETE", "FOO")) {
                val answer = api.answerTo(method, target)

                assertProblem(answer, 404, "Not Found", NO_RESOURCE)
            }
        }
        assertEquals(emptyList(), asked)
    }

    /** The routing of Ktor answers such a path with the request target, and writes the target with its query string to the log. */
    @Test
    fun aParameterWithAMalformedEscapeGetsTheStatus400AndIsNotRepeated() {
        val api = api()
        val targets =
            listOf(
                "/api/v1/items/%zz",
                "/api/v1/items/%",
                "/api/v1/items/7%2",
                "/api/v1/items/%4?x=$SECRET",
                "/api/v1/items/$SECRET%/parts/wheel",
                "/api/v1/items/7/parts/%G0",
            )
        var answers = emptyList<RawHttp.Answer>()

        val log = TestLog.of { answers = targets.map { api.answerTo("GET", it) } }

        for (answer in answers) {
            assertProblem(answer, 400, "Bad Request", "The path of the request holds a percent sign that begins no escape of a character.")
        }
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aResourceWithoutAnOperationForTheMethodGetsTheStatus405WithTheMethodsThatItHas() {
        val api = api()
        val detail = "The resource has no operation with the method of the request."
        val methods = listOf("DELETE", "PUT", "PATCH", "OPTIONS", "TRACE", "FOO", "get", "Post")

        val ofItems = methods.associateWith { api.answerTo(it, "/api/v1/items") }
        val ofItem = listOf("POST", "DELETE", "PATCH").associateWith { api.answerTo(it, "/api/v1/items/7", body = "{}") }

        for ((method, answer) in ofItems) {
            assertProblem(answer, 405, "Method Not Allowed", detail)
            assertEquals(listOf("GET, HEAD, POST"), answer.headerValues("Allow"), method)
        }
        for ((method, answer) in ofItem) {
            assertProblem(answer, 405, "Method Not Allowed", detail)
            assertEquals(listOf("GET, HEAD, PUT"), answer.headerValues("Allow"), method)
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aHeadRequestGetsTheHeadersOfTheAnswerToAGetRequestAndNoBody() {
        val api = api()

        for (target in listOf("/api/v1/items", "/api/v1/items/7", "/api/v1/nothing", "/api/v1/items/%zz")) {
            val toGet = api.answerTo("GET", target)
            val toHead = api.answerTo("HEAD", target)

            assertEquals(toGet.statusLine to "", toHead.statusLine to toHead.body, target)
            assertEquals(toGet.headers, toHead.headers, target)
        }
        assertEquals(listOf("list", "list", "read 7", "read 7"), asked)
    }

    // The admin token

    @Test
    fun aServerWithoutAnAdminTokenAsksForNone() {
        val api = api(token = null)

        val without = api.answerTo("GET", "/api/v1/items")
        val withOne = api.answerTo("GET", "/api/v1/items", listOf("Authorization: Bearer whatever"))
        val withTwo = api.answerTo("GET", "/api/v1/items", listOf("Authorization: Basic eDp5", "Authorization: Bearer whatever"))

        assertEquals(listOf(200, 200, 200), listOf(without, withOne, withTwo).map { it.status })
    }

    /** The scenario "Request without the token": whatever the request is for, it gets the status 401, and no operation sees it. */
    @Test
    fun aRequestWithoutTheTokenGetsTheStatus401AndNothingIsReadOrChanged() {
        val api = api(token = TOKEN)
        val requests =
            listOf(
                RawHttp.request("GET", "/api/v1/items"),
                RawHttp.request("POST", "/api/v1/items", body = """{"name":"seven"}"""),
                RawHttp.request("POST", "/api/v1/items", body = "not JSON"),
                RawHttp.request("PUT", "/api/v1/items/7", body = "{}"),
                RawHttp.request("DELETE", "/api/v1/items"),
                RawHttp.request("GET", "/api/v1/nothing"),
                RawHttp.request("GET", "/api/v1/items/%zz"),
                RawHttp.request("GET", "/api/v1/broken"),
                RawHttp.request("GET", "/api/v1"),
                RawHttp.request("GET", "http://mockey.test/api/v1/items"),
            )

        val answers = requests.map { RawHttp.exchange(api.address, it) }
        val toHead = RawHttp.exchange(api.address, RawHttp.request("HEAD", "/api/v1/items"))

        for (answer in answers) {
            assertProblem(answer, 401, "Unauthorized", NO_TOKEN)
            assertEquals(
                listOf(
                    "Cache-Control: no-store",
                    "WWW-Authenticate: Bearer",
                    "Content-Length: ${answer.body.length}",
                    "Content-Type: application/problem+json",
                    "Connection: close",
                ),
                answer.headers,
            )
        }
        assertEquals("HTTP/1.1 401 Unauthorized" to "", toHead.statusLine to toHead.body)
        assertEquals(answers.first().headers, toHead.headers)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aRequestThatPresentsSomethingElseThanTheTokenGetsTheStatus401() {
        val api = api(token = TOKEN)
        val presented =
            listOf(
                listOf("Authorization: Bearer"),
                listOf("Authorization: Bearer "),
                listOf("Authorization: Bearer tk-0000000000"),
                listOf("Authorization: Bearer ${TOKEN.dropLast(1)}"),
                listOf("Authorization: Bearer ${TOKEN}0"),
                listOf("Authorization: Bearer ${TOKEN.uppercase()}"),
                listOf("Authorization: Bearer $TOKEN extra"),
                listOf("Authorization: Bearer \"$TOKEN\""),
                listOf("Authorization: Bearer\t$TOKEN"),
                listOf("Authorization: $TOKEN"),
                listOf("Authorization: Basic $TOKEN"),
                listOf("Authorization: Token $TOKEN"),
                listOf("Authorization: BearerX $TOKEN"),
                listOf("Authorization: Bearer $TOKEN", "Authorization: Bearer $TOKEN"),
                listOf("Authorization: Bearer $TOKEN", "authorization: Basic eDp5"),
                listOf("Proxy-Authorization: Bearer $TOKEN"),
                listOf("X-Admin-Token: $TOKEN"),
                listOf("Cookie: token=$TOKEN"),
            )

        val answers = presented.associateWith { api.answerTo("GET", "/api/v1/items?token=$TOKEN", it) }

        for ((headers, answer) in answers) {
            assertEquals(401, answer.status, "$headers")
            assertEquals(ProblemDto("about:blank", "Unauthorized", 401, NO_TOKEN), problemOf(answer), "$headers")
        }
        assertEquals(emptyList(), asked)
    }

    /** The scenario "Request with the token". The name of the scheme is in any letter case, as HTTP has it. */
    @Test
    fun aRequestThatPresentsTheTokenAsABearerTokenIsProcessed() {
        val api = api(token = TOKEN)
        val presented =
            listOf(
                "Authorization: Bearer $TOKEN",
                "authorization: bearer $TOKEN",
                "AUTHORIZATION: BEARER $TOKEN",
                "Authorization:Bearer $TOKEN",
                "Authorization:   Bearer    $TOKEN   ",
            )

        val answers = presented.associateWith { api.answerTo("GET", "/api/v1/items", listOf(it)) }
        val created = api.answerTo("POST", "/api/v1/items", listOf("Authorization: Bearer $TOKEN"), """{"name":"seven"}""")

        for ((header, answer) in answers) {
            assertEquals(200 to """["first","zweites ✓"]""", answer.status to answer.bodyInUtf8, header)
            assertEquals(emptyList(), answer.headerValues("WWW-Authenticate"), header)
        }
        assertEquals(201, created.status)
        assertEquals(List(presented.size) { "list" } + """create {"name":"seven"}""", asked)
    }

    /** A client sends such a token as its bytes in UTF-8, and the HTTP engine hands each byte on as one character. */
    @Test
    fun aTokenWithCharactersBeyondAsciiIsFoundByItsBytes() {
        val word = "schl\u00FCssel"
        val words = "$word \u2713 \u9375"

        /** Returns the status that a server with [token] gives a request that presents [presented], character for byte. */
        fun status(
            token: String,
            presented: String,
        ): Int? = api(token).answerTo("GET", "/api/v1/items", listOf("Authorization: Bearer $presented")).status

        assertEquals(200 to 401, status(word, RawHttp.utf8(word)) to status(word, word), "In UTF-8, and in Latin-1")
        assertEquals(200, status(words, RawHttp.utf8(words)), "With its spaces")
        assertEquals(401, status(words, RawHttp.utf8(words.replace(" ", ""))), "Without its spaces")
        assertEquals(401, status(words, RawHttp.utf8(word)), "Its first word")
        assertEquals(401, status(words, "schl%C3%BCssel%20%E2%9C%93%20%E9%8D%B5"), "With escapes")
    }

    // What the API answers when it does not do what a request asks for

    /** A problem document is JSON with exactly these members, and like every answer of the API it is not to be stored. */
    @Test
    fun aProblemIsADocumentWithATypeATitleTheStatusADetailAndTheInvalidFields() {
        val answer = api().answerTo("GET", "/api/v1/nothing")

        val document = """{"type":"about:blank","title":"Not Found","status":404,"detail":"$NO_RESOURCE","errors":[]}"""
        assertEquals("HTTP/1.1 404 Not Found" to document, answer.statusLine to answer.body)
        assertEquals(
            listOf(
                "Cache-Control: no-store",
                "Content-Length: ${document.length}",
                "Content-Type: application/problem+json",
                "Connection: close",
            ),
            answer.headers,
        )
    }

    /** The scenario "Unknown resource", for an operation that finds nothing of what a request names. */
    @Test
    fun aChangeToWhatDoesNotExistGetsTheStatus404WithWhatTheEngineSays() {
        val answer = api().answerTo("PUT", "/api/v1/items/missing", body = "{}")

        assertProblem(answer, 404, "Not Found", "No item has the identifier missing.")
        assertEquals(listOf("change missing"), asked)
    }

    /** The scenario "Validation failure", for a change that the engine rejects: the problem lists each invalid field with a message. */
    @Test
    fun aChangeThatIsRejectedGetsTheStatus422AndNamesEveryInvalidFieldWithItsMessage() {
        val answer = api().answerTo("PUT", "/api/v1/items/taken", body = "{}")

        assertProblem(
            answer,
            422,
            "Unprocessable Entity",
            "The request is not valid. name: $NAME_TAKEN mockUrl: $MOCK_URL_TAKEN",
            listOf(FieldErrorDto("name", NAME_TAKEN), FieldErrorDto("mockUrl", MOCK_URL_TAKEN)),
        )
    }

    /** The scenario "Unparseable body". The problem says what the body is not, and nothing of what it is. */
    @Test
    fun aBodyThatIsNoJsonObjectGetsTheStatus400AndIsNotRepeated() {
        val api = api()
        val noObject = "The body of the request is no JSON object."
        val noBody = "The request has no body. The operation takes a JSON object."
        val bodies =
            mapOf(
                "{" to noObject,
                """{"name":"$SECRET",}""" to noObject,
                """{"name":"$SECRET"} {}""" to noObject,
                """{"name":$SECRET}""" to noObject,
                """{"name":'$SECRET'}""" to noObject,
                """{name:"$SECRET"}""" to noObject,
                """{"list":[1,TRUE]}""" to noObject,
                """{"number":01}""" to noObject,
                """{"text":"\q"}""" to noObject,
                "name=$SECRET" to noObject,
                "<item>$SECRET</item>" to noObject,
                """["$SECRET"]""" to noObject,
                "\"$SECRET\"" to noObject,
                "7" to noObject,
                "null" to noObject,
                "\u00FF{}" to "The body of the request is not encoded as UTF-8.",
                "  \r\n " to noBody,
            )
        var answers = emptyMap<String, RawHttp.Answer>()

        val log = TestLog.of { answers = bodies.mapValues { (body, _) -> api.answerTo("POST", "/api/v1/items", body = body) } }
        val withoutBody = api.answerTo("POST", "/api/v1/items")

        for ((body, answer) in answers) {
            assertProblem(answer, 400, "Bad Request", bodies.getValue(body))
        }
        assertProblem(withoutBody, 400, "Bad Request", noBody)
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aBodyInUtf8IsReadAsItIs() {
        val item = """{"name":"Kundenübersicht ✓","nested":{"list":[1,-0.5,true,false,null]}}"""

        val answer = api().answerTo("POST", "/api/v1/items", body = "  ${RawHttp.utf8(item)}\r\n")

        assertEquals(201 to item, answer.status to answer.bodyInUtf8)
        assertEquals(listOf("create $item"), asked)
    }

    /** A parser goes deeper with every array in an array, and the one of the server stops before it fails over that. */
    @Test
    fun aBodyThatIsNestedMoreDeeplyThanTheParserGoesGetsTheStatus400() {
        val depth = 100_000
        val api = api(maxRequestBody = 4L * depth)
        val body = """{"list":${"[".repeat(depth)}${"]".repeat(depth)}}"""
        var answer: RawHttp.Answer? = null

        val log = TestLog.of { answer = api.answerTo("POST", "/api/v1/items", body = body) }

        assertProblem(checkNotNull(answer), 400, "Bad Request", "The body of the request is no JSON object.")
        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), asked)
    }

    /** The limit of the server for the body of a request holds for the admin API as well, and such a body is not read. */
    @Test
    fun aBodyThatIsLongerThanTheServerTakesGetsTheStatus413() {
        val api = api(maxRequestBody = 64)
        val atTheLimit = """{"name":"${"n".repeat(53)}"}"""
        val head = listOf("POST /api/v1/items HTTP/1.1", "Host: mockey.test", "Connection: close", JSON)
        val inChunks = head + listOf("Transfer-Encoding: chunked", "")

        val taken = api.answerTo("POST", "/api/v1/items", body = atTheLimit)
        val oneMore = api.answerTo("POST", "/api/v1/items", body = "$atTheLimit ")
        val muchMore = api.answerTo("POST", "/api/v1/items", body = """{"name":"${"n".repeat(100_000)}"}""")
        val chunked = api.answerToLines(*inChunks.toTypedArray(), "41", "$atTheLimit ", "0")

        assertEquals(64, atTheLimit.length)
        assertEquals(201 to atTheLimit, taken.status to taken.body)
        for (answer in listOf(oneMore, muchMore, chunked)) {
            assertProblem(
                answer,
                413,
                "Payload Too Large",
                "The body of the request is longer than 64 bytes, which is the most that this server takes.",
            )
        }
        assertEquals(listOf("create $atTheLimit"), asked)
    }

    /**
     * The scenario "Body that is not declared as JSON". A browser sends a body of the first three types for
     * a page of any origin without asking the server, so the API takes none of them, also where the body
     * is a JSON object. Such a body is not read: one that is too long gets the same answer.
     */
    @Test
    fun aBodyThatIsNotDeclaredAsJsonGetsTheStatus415AndNoOperationSeesIt() {
        val api = api(maxRequestBody = 64)
        val item = """{"name":"seven"}"""
        val notJson =
            listOf(
                listOf("Content-Type: text/plain"),
                listOf("Content-Type: application/x-www-form-urlencoded"),
                listOf("Content-Type: multipart/form-data; boundary=x"),
                listOf("Content-Type: application/json; charset=iso-8859-1"),
                listOf("Content-Type: application/json; charset=klingon"),
                listOf("Content-Type: application/merge-patch+json"),
                listOf("Content-Type: text/json"),
                listOf("Content-Type: json"),
                listOf("Content-Type:"),
                listOf("Content-Type: application/json", "Content-Type: text/plain"),
                listOf("Content-Type: application/json", "Content-Type: application/json"),
            )
        val chunked = listOf("POST /api/v1/items HTTP/1.1", "Host: mockey.test", "Connection: close", "Transfer-Encoding: chunked", "")

        val answers = notJson.map { api.answerTo("POST", "/api/v1/items", it, item) }
        val withoutAType = RawHttp.exchange(api.address, RawHttp.request("POST", "/api/v1/items", body = item))
        val inChunks = api.answerToLines(*chunked.toTypedArray(), "10", item, "0")
        val tooLong = api.answerTo("POST", "/api/v1/items", listOf("Content-Type: text/plain"), "x".repeat(100_000))

        for (answer in answers + withoutAType + inChunks + tooLong) {
            assertProblem(answer, 415, "Unsupported Media Type", NOT_DECLARED_AS_JSON)
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aBodyThatIsDeclaredAsJsonInUtf8IsTaken() {
        val api = api()
        val item = """{"name":"seven"}"""
        val json =
            listOf(
                "Content-Type: application/json",
                "content-type: Application/JSON",
                "Content-Type: application/json; charset=utf-8",
                "Content-Type: application/json;charset=UTF-8",
                "Content-Type: application/json; charset=\"utf-8\"",
            )

        val answers = json.map { api.answerTo("POST", "/api/v1/items", listOf(it), item) }

        assertEquals(List(json.size) { 201 }, answers.map { it.status })
        assertEquals(List(json.size) { "create $item" }, asked)
    }

    /** A request without a body has nothing to declare. The operation that wants a body says that there is none. */
    @Test
    fun aRequestWithoutABodyNeedsNoContentType() {
        val api = api()
        val noBody = "The request has no body. The operation takes a JSON object."

        val without = api.answerTo("POST", "/api/v1/items")
        val ofNoLength = api.answerTo("POST", "/api/v1/items", listOf("Content-Length: 0"))
        val ofAnotherType = api.answerTo("POST", "/api/v1/items", listOf("Content-Type: text/plain"))
        val toAnOperationWithoutABody = api.answerTo("PUT", "/api/v1/items/7", listOf("Content-Type: text/plain"), "not read")

        for (answer in listOf(without, ofNoLength, ofAnotherType)) {
            assertProblem(answer, 400, "Bad Request", noBody)
        }
        assertEquals(200, toAnOperationWithoutABody.status)
        assertEquals(listOf("change 7"), asked)
    }

    // Answers without a body, and answers that say where something is

    /** The scenario "Created service is returned", as far as the API of a test goes: the answer says where the resource is. */
    @Test
    fun theAnswerToACreationHasTheStatus201AndThePathOfWhatWasCreated() {
        val answer = api().answerTo("POST", "/api/v1/copies/a%20b%2Fc")

        assertEquals("HTTP/1.1 201 Created" to "\"a copy\"", answer.statusLine to answer.body)
        assertEquals(
            listOf(
                "Cache-Control: no-store",
                "Location: /api/v1/items/a%20b%2Fc/copies/the%20first%2Fof%202",
                "Content-Length: 8",
                "Content-Type: application/json",
                "Connection: close",
            ),
            answer.headers,
        )
        assertEquals(listOf("copy a b/c"), asked)
    }

    @Test
    fun anAnswerWithoutABodyHasTheStatus204AndNothingThatDescribesABody() {
        val answer = api().answerTo("DELETE", "/api/v1/copies/7")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(listOf("Cache-Control: no-store", "Connection: close"), answer.headers)
        assertEquals(listOf("delete the copies of 7"), asked)
    }

    /** The client learns that the server failed, and the log says at which operation and why. Neither holds anything of the request. */
    @Test
    fun anOperationThatFailsGetsTheStatus500AndTheLogNamesTheOperation() {
        val api = api()
        var answer: RawHttp.Answer? = null

        val log = TestLog.of { answer = api.answerTo("GET", "/api/v1/broken?token=$SECRET", listOf("X-Secret: $SECRET")) }

        assertProblem(checkNotNull(answer), 500, "Internal Server Error", "The server failed to handle the request. Its log says why.")
        assertEquals(listOf("The operation GET broken of the admin API failed."), TestLog.messages(log, "ERROR"))
        assertEquals(1, log.lines().count { it == "java.lang.IllegalStateException: $FAILURE" }, log)
        assertFalse(SECRET in log, log)
    }

    // What every answer has

    /** The scenario "Listing services", as far as the API of a test goes: the body is JSON, and its bytes are UTF-8. */
    @Test
    fun theAnswerOfAnOperationIsJsonInUtf8ThatIsNotToBeStored() {
        val answer = api().answerTo("GET", "/api/v1/items")

        assertEquals("""["first","zweites ✓"]""", answer.bodyInUtf8)
        assertEquals(
            listOf("Cache-Control: no-store", "Content-Length: 23", "Content-Type: application/json", "Connection: close"),
            answer.headers,
        )
    }

    /** The scenario "No cross-origin header on the admin API", for what the API answers itself, whatever a page asks for. */
    @Test
    fun noAnswerPermitsAPageOfAnotherOriginToReadIt() {
        val api = api(token = TOKEN)
        val origin = listOf("Origin: https://pages.example")
        val asking = origin + listOf("Access-Control-Request-Method: PUT", "Access-Control-Request-Headers: authorization, content-type")
        val presenting = listOf("Authorization: Bearer $TOKEN")
        val requests =
            listOf(
                RawHttp.request("GET", "/api/v1/items", origin + presenting) to 200,
                RawHttp.request("POST", "/api/v1/items", origin + presenting + JSON, "{}") to 403,
                RawHttp.request("GET", "/api/v1/nothing", origin + presenting) to 404,
                RawHttp.request("DELETE", "/api/v1/items", origin + presenting) to 403,
                RawHttp.request("OPTIONS", "/api/v1/items", asking + presenting) to 403,
                RawHttp.request("OPTIONS", "/api/v1/items", asking) to 401,
                RawHttp.request("GET", "/api/v1/items", origin) to 401,
            )

        for ((request, status) in requests) {
            val answer = RawHttp.exchange(api.address, request)

            assertEquals(status, answer.status, "$answer")
            assertEquals(emptyList(), answer.headers.filter { it.startsWith("access-control-", ignoreCase = true) }, "$answer")
        }
    }

    /**
     * The scenario "Request of a web page that would change something". A browser sends an `Origin` header
     * with every request of a page whose method is neither `GET` nor `HEAD`, and `null` in it where it has
     * nothing to say. Such a request gets to no operation, whatever it is for. A request that only reads
     * changes nothing, so the header does not count for it.
     */
    @Test
    fun aRequestThatCanChangeSomethingGetsTheStatus403WhenItCarriesAnOriginHeader() {
        val api = api()
        val origins = listOf("Origin: https://pages.example", "Origin: http://127.0.0.1:8081", "Origin: null", "origin:")
        val changing =
            listOf(
                Triple("POST", "/api/v1/items", """{"name":"seven"}"""),
                Triple("PUT", "/api/v1/items/7", ""),
                Triple("PATCH", "/api/v1/items/7", "{}"),
                Triple("DELETE", "/api/v1/items/7", ""),
                Triple("OPTIONS", "/api/v1/items", ""),
                Triple("POST", "/api/v1/nothing", ""),
                Triple("FOO", "/api/v1/items", ""),
            )
        val requests = origins.flatMap { origin -> changing.map { it to listOf(origin) } }

        val refused = requests.map { (request, origin) -> api.answerTo(request.first, request.second, origin, request.third) }
        val reading = origins.flatMap { origin -> listOf("GET", "HEAD").map { api.answerTo(it, "/api/v1/items", listOf(origin)) } }

        for (answer in refused) {
            assertProblem(answer, 403, "Forbidden", OF_A_PAGE)
        }
        assertEquals(List(reading.size) { 200 }, reading.map { it.status })
        assertEquals(List(reading.size) { "list" }, asked)
    }

    /** A program sends no `Origin` header, and what it sends about itself otherwise does not count. */
    @Test
    fun aRequestOfAProgramThatChangesSomethingIsTaken() {
        val api = api()
        val headers = listOf("Referer: https://pages.example/form", "Sec-Fetch-Site: cross-site", "User-Agent: curl/8.7.1")

        val created = api.answerTo("POST", "/api/v1/items", headers, """{"name":"seven"}""")
        val changed = api.answerTo("PUT", "/api/v1/items/7", headers)

        assertEquals(listOf<Int?>(201, 200), listOf(created.status, changed.status))
        assertEquals(listOf("""create {"name":"seven"}""", "change 7"), asked)
    }

    /** The token comes first: a request without it learns nothing else about the server. */
    @Test
    fun aRequestOfAPageWithoutTheTokenGetsTheStatus401() {
        val api = api(token = TOKEN)
        val origin = listOf("Origin: https://pages.example")

        val without = api.answerTo("POST", "/api/v1/items", origin, """{"name":"seven"}""")
        val with = api.answerTo("POST", "/api/v1/items", origin + "Authorization: Bearer $TOKEN", """{"name":"seven"}""")

        assertProblem(without, 401, "Unauthorized", NO_TOKEN)
        assertProblem(with, 403, "Forbidden", OF_A_PAGE)
        assertEquals(emptyList(), asked)
    }

    /** With every part of a request that can hold a secret, at the level at which Ktor and Netty say the most. */
    @Test
    fun theApiWritesNothingOfARequestToTheLogAtTheMostVerboseLevel() {
        ServerLogging.configure(LogLevel.TRACE)
        val api = api(token = TOKEN)
        val presenting = listOf("Authorization: Bearer $TOKEN")
        val statuses = mutableListOf<Int?>()

        val log =
            TestLog.of {
                statuses += api.answerTo("GET", "/api/v1/items?token=$SECRET", presenting + "X-Secret: $SECRET").status
                statuses += api.answerTo("GET", "/api/v1/items/$SECRET?token=$SECRET", presenting).status
                statuses += api.answerTo("POST", "/api/v1/items?token=$SECRET", presenting, """{"password":"$SECRET"}""").status
                statuses += api.answerTo("POST", "/api/v1/items?token=$SECRET", presenting, """{"password":"$SECRET"""").status
                statuses += api.answerTo("PUT", "/api/v1/items/taken?token=$SECRET", presenting, """{"password":"$SECRET"}""").status
                statuses += api.answerTo("GET", "/api/v1/items/$SECRET%zz?token=$SECRET", presenting).status
                statuses += api.answerTo("GET", "/api/v1/$SECRET?token=$SECRET", presenting).status
                statuses += api.answerTo("DELETE", "/api/v1/items/$SECRET?token=$SECRET", presenting).status
                statuses += api.answerTo("GET", "/api/v1/items?token=$SECRET", listOf("Authorization: Bearer $SECRET")).status
                statuses += api.answerTo("GET", "/$SECRET?token=$SECRET", presenting).status
                LoggerFactory.getLogger(AdminApiTest::class.java).trace(AT_TRACE)
            }

        assertEquals(listOf<Int?>(200, 200, 201, 400, 422, 400, 404, 405, 401, 404), statuses)
        assertContains(TestLog.messages(log, "TRACE"), AT_TRACE, "The log is written at the most verbose level")
        for (text in listOf(TOKEN, SECRET, "/api/v1", "items", "password", "X-Secret", "Authorization")) {
            assertFalse(text in log, "The log holds '$text':\n$log")
        }
    }

    // What an operation knows of its request

    /** The status of the server states the URL of the mock endpoint with the host by which the client reached the admin listener. */
    @Test
    fun theHostOfARequestIsTheOneThatItNamesWithoutThePort() {
        val api = api()
        val hosts =
            mapOf(
                "mockey.test" to "mockey.test",
                "mockey.test:8081" to "mockey.test",
                "MOCKEY.test:80" to "MOCKEY.test",
                "127.0.0.1:8081" to "127.0.0.1",
                "[::1]:8081" to "[::1]",
                "[2001:db8::7]" to "[2001:db8::7]",
                ":8081" to "127.0.0.1",
            )

        val answers = hosts.mapValues { (host, _) -> api.answerToLines("GET /api/v1/host HTTP/1.1", "Host: $host", "Connection: close") }
        val ofFirstVersion = api.answerToLines("GET /api/v1/host HTTP/1.0")

        for ((host, answer) in answers) {
            assertEquals(200 to "\"${hosts.getValue(host)}\"", answer.status to answer.body, host)
        }
        assertEquals("HTTP/1.0 200 OK" to "\"127.0.0.1\"", ofFirstVersion.statusLine to ofFirstVersion.body)
    }

    // The routes of an API

    @Test
    fun aRouteNamesEverySegmentOfItsPathAndEveryParameterOnce() {
        for (path in listOf("", "/", "/items", "items/", "items//parts", "items/{}", "items/{id}/parts/{id}")) {
            assertFailsWith<IllegalArgumentException>(path) { AdminRoute(HttpMethod.Get, path) { AdminResponse.json(path) } }
        }
    }

    /** Which of them a request would go to would hang on their order, so an API has one operation for a method and the paths of a form. */
    @Test
    fun anApiHasOneOperationForTheSameRequests() {
        fun route(
            method: HttpMethod,
            path: String,
        ) = AdminRoute(method, path) { AdminResponse.json(path) }

        val refusal =
            assertFailsWith<IllegalArgumentException> {
                AdminApi(
                    null,
                    1_024,
                    listOf(
                        route(HttpMethod.Get, "items/{itemId}"),
                        route(HttpMethod.Put, "items/{id}"),
                        route(HttpMethod.Get, "items/{id}"),
                    ),
                )
            }

        assertEquals(
            "The admin API has more than one operation for the same requests: [GET items/{itemId}, GET items/{id}].",
            refusal.message,
        )
        AdminApi(
            null,
            1_024,
            listOf(route(HttpMethod.Get, "items/{id}"), route(HttpMethod.Put, "items/{id}"), route(HttpMethod.Get, "items/id")),
        )
    }

    private companion object {
        const val TOKEN = "tk-4c1d9a70e2b5"

        /** What a test puts into a request, and what neither an answer to another request nor the log is to hold. */
        const val SECRET = "s3cret-7f2e91"

        /** What a test writes to the log at the most verbose level, to show that the log is written at that level. */
        const val AT_TRACE = "A message at the most verbose level."

        /** What the operation of the test that fails says of its failure, which is for the log and for no client. */
        const val FAILURE = "The operation of the test fails."

        const val NO_RESOURCE = "The admin API has no resource at the path of the request."
        const val NO_TOKEN =
            "The admin API takes a request only with the admin token, which a request presents as a bearer token " +
                "in its Authorization header."
        const val NOT_DECLARED_AS_JSON =
            "The body of a request to the admin API is JSON in UTF-8, and the request has to say so " +
                "with the header Content-Type: application/json."
        const val OF_A_PAGE =
            "The request carries an Origin header, as the request of a web page does, and its method is neither GET nor HEAD. " +
                "The admin API takes a request that can change something only from a program."

        /** The header with which a request says that its body is JSON. */
        const val JSON = "Content-Type: application/json"
        const val NAME_TAKEN = "The item 'Other' already has this name."
        const val MOCK_URL_TAKEN = "The service 'Other' already uses the mock URL pattern 'customer/{id}'."
    }
}
