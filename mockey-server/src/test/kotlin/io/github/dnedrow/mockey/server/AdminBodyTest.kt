package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.RealUrlDerivationRequestDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioAttributesDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.ServiceAttributesDto
import io.ktor.http.HttpMethod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * How the admin API reads the body of a request as one of its wire types, over real HTTP: what it takes for
 * an object of a wire type, what it says of a body that is none, and how the attributes that a request
 * states go onto what is there.
 *
 * The API of a test has operations of the test, which answer with what they read.
 */
class AdminBodyTest {
    /** The scenario that the operations of the test update. */
    private val scenario =
        ScenarioAttributesDto(
            name = "found",
            responseBody = """{"id":1}""",
            statusCode = 201,
            headers = listOf(HeaderDto("Content-Type", "application/json"), HeaderDto("X-Trace", "1")),
            matchCriterion = MatchCriterionDto.Text("gold"),
            methodRestriction = "POST",
            delayMillis = 250,
            tags = listOf("nightly", "team-a"),
        )

    private val service = ServiceAttributesDto(name = "Customers", mockUrl = "customer/{id}", description = "The customers of the shop.")

    private val routes =
        listOf(
            AdminRoute(HttpMethod.Put, "reference") { AdminResponse.json(body(ScenarioReferenceDto.serializer())) },
            AdminRoute(HttpMethod.Post, "derivations") { AdminResponse.json(body(RealUrlDerivationRequestDto.serializer())) },
            AdminRoute(HttpMethod.Patch, "scenario") { AdminResponse.json(attributes(ScenarioAttributesDto.serializer()).on(scenario)) },
            AdminRoute(HttpMethod.Patch, "service") { AdminResponse.json(attributes(ServiceAttributesDto.serializer()).on(service)) },
        )

    private val listener =
        Listener.start(
            ListenerKind.ADMIN,
            RawHttp.loopback(0),
            MockeyServer.adminListenerModule(ForwardMark(adminPort = null), AdminApi(token = null, maxRequestBody = 65_536, routes)),
        )

    @AfterTest
    fun stopTheListener() {
        listener.stop()
    }

    private fun send(
        method: String,
        resource: String,
        body: String,
    ): RawHttp.Answer {
        val request = RawHttp.request(method, "/api/v1/$resource", listOf("Content-Type: application/json"), RawHttp.utf8(body))
        return RawHttp.exchange(listener.address, request)
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /** [answer] is the problem of a request that is not valid, and it names exactly [errors], which are fields with their messages. */
    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertEquals("HTTP/1.1 422 Unprocessable Entity", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        assertEquals(ProblemDto("about:blank", "Unprocessable Entity", 422, detail, fields), answer.decoded<ProblemDto>())
    }

    // A whole object

    @Test
    fun aBodyWithEveryMemberOfItsWireTypeIsRead() {
        val reference = send("PUT", "reference", """{"scenarioId":"s-1","serviceId":"svc-1"}""")
        val derivation = send("POST", "derivations", """{"expression":"qa1","replacements":["qa2","qa3 ✓"]}""")

        assertEquals(200 to ScenarioReferenceDto("svc-1", "s-1"), reference.status to reference.decoded<ScenarioReferenceDto>())
        assertEquals(RealUrlDerivationRequestDto("qa1", listOf("qa2", "qa3 ✓")), derivation.decoded<RealUrlDerivationRequestDto>())
    }

    /** The scenario "Validation failure", for a body that is no object of its wire type: every member that is not right is named. */
    @Test
    fun everyMemberThatIsMissingOrUnknownOrOfAnotherKindIsNamed() {
        val empty = send("PUT", "reference", "{}")
        val wrong = send("PUT", "reference", """{"serviceId":7,"more":true,"scenarioId":null,"other":{}}""")
        val half = send("POST", "derivations", """{"replacements":"qa2"}""")

        assertInvalid(empty, "serviceId" to MISSING, "scenarioId" to MISSING)
        assertInvalid(wrong, "more" to UNKNOWN, "other" to UNKNOWN, "serviceId" to A_TEXT, "scenarioId" to NULL)
        assertInvalid(half, "expression" to MISSING, "replacements" to A_LIST)
    }

    // The kinds of values

    /** Every value of the test is invalid for its member, and the problem names the member with what it takes. */
    private fun assertTakes(
        resource: String,
        member: String,
        what: String,
        vararg values: String,
    ) {
        for (value in values) {
            assertInvalid(send("PATCH", resource, """{"$member":$value}"""), member to what)
        }
    }

    @Test
    fun aTextIsATextInQuotes() {
        assertTakes("service", "name", A_TEXT, "7", "true", "[]", "{}", """["Customers"]""")
        assertTakes("service", "name", NULL, "null")
        assertTakes("service", "description", A_TEXT, "7", "false")

        val taken = send("PATCH", "service", """{"name":"","description":null,"mockUrl":"7"}""")

        assertEquals(service.copy(name = "", description = null, mockUrl = "7"), taken.decoded<ServiceAttributesDto>())
    }

    /** The serialization library takes a number in quotes for a number. The API does not. */
    @Test
    fun aWholeNumberIsANumberWithoutQuotesAndWithoutAFraction() {
        val noNumbers = arrayOf(""""250"""", "2.5", "250.0", "1e2", "true", "[250]", "9223372036854775808", "-9223372036854775809")
        assertTakes("service", "delayMillis", A_WHOLE_NUMBER, *noNumbers)
        assertTakes("service", "delayMillis", NULL, "null")
        assertTakes("service", "defaultRealUrlIndex", A_WHOLE_NUMBER, "2147483648", "-2147483649", """"0"""")

        val greatest = send("PATCH", "service", """{"delayMillis":9223372036854775807,"defaultRealUrlIndex":2147483647}""")
        val least = send("PATCH", "service", """{"delayMillis":-9223372036854775808,"defaultRealUrlIndex":-2147483648}""")
        val zero = send("PATCH", "service", """{"delayMillis":-0,"defaultRealUrlIndex":0}""")

        val numbers = listOf(greatest, least, zero).map { it.decoded<ServiceAttributesDto>() }
        assertEquals(listOf(Long.MAX_VALUE, Long.MIN_VALUE, 0L), numbers.map { it.delayMillis })
        assertEquals(listOf(Int.MAX_VALUE, Int.MIN_VALUE, 0), numbers.map { it.defaultRealUrlIndex })
    }

    @Test
    fun aSwitchIsTrueOrFalseWithoutQuotes() {
        assertTakes("service", "requestRulesEnabled", TRUE_OR_FALSE, """"true"""", "1", "0", "[]")
        assertTakes("service", "requestRulesEnabled", NULL, "null")

        val on = send("PATCH", "service", """{"requestRulesEnabled":true,"responseSchemaEnabled":false}""")

        assertEquals(true to false, on.decoded<ServiceAttributesDto>().let { it.requestRulesEnabled to it.responseSchemaEnabled })
    }

    @Test
    fun aValueOfAFewIsOneOfThemAsItIsWritten() {
        val oneOfTheModes = "The value has to be one of proxy, static, dynamic."
        assertTakes("service", "responseMode", oneOfTheModes, """"PROXY"""", """"none"""", "1", """""""")
        assertTakes("service", "responseMode", NULL, "null")

        val modes = listOf("proxy", "static", "dynamic").map { send("PATCH", "service", """{"responseMode":"$it"}""") }

        assertEquals(ResponseModeDto.entries, modes.map { it.decoded<ServiceAttributesDto>().responseMode })
    }

    @Test
    fun aListIsAnArrayAndEachOfItsItemsIsChecked() {
        assertTakes("service", "realUrls", A_LIST, """"http://api.example.com"""", """{"0":"http://api.example.com"}""", "7")
        assertTakes("service", "tags", NULL, "null")

        val items = send("PATCH", "service", """{"realUrls":["http://api.example.com",7,null,["x"]],"tags":[true]}""")
        val empty = send("PATCH", "service", """{"realUrls":[],"tags":["a b","c"]}""")

        assertInvalid(items, "realUrls[1]" to A_TEXT, "realUrls[2]" to NULL, "realUrls[3]" to A_TEXT, "tags[0]" to A_TEXT)
        assertEquals(emptyList<String>() to listOf("a b", "c"), empty.decoded<ServiceAttributesDto>().let { it.realUrls to it.tags })
    }

    /** An object in a list is whole: it has every member of its wire type, and no other. */
    @Test
    fun anObjectInAListIsCheckedMemberByMember() {
        assertTakes("scenario", "headers", A_LIST, """{"name":"Accept","value":"text/plain"}""")
        val headers =
            """[{"name":"Accept","value":"text/plain"},"Accept: text/plain",{"name":"Accept"},""" +
                """{"name":7,"value":null,"more":1},{"value":"","name":""}]"""

        val answer = send("PATCH", "scenario", """{"headers":$headers}""")

        assertInvalid(
            answer,
            "headers[1]" to AN_OBJECT,
            "headers[2].value" to MISSING,
            "headers[3].more" to UNKNOWN,
            "headers[3].name" to A_TEXT,
            "headers[3].value" to NULL,
        )
    }

    /** A match criterion is of one of three kinds, and it says which in its member `type`. */
    @Test
    fun aValueOfSeveralKindsNamesItsKindAndHasTheMembersOfThatKind() {
        val oneOfTheKinds = "The value has to be one of none, rules, text."
        assertTakes("scenario", "matchCriterion", AN_OBJECT, """"text"""", "[]", "7")
        assertTakes("scenario", "matchCriterion", NULL, "null")
        val invalid =
            mapOf(
                "{}" to listOf("matchCriterion.type" to oneOfTheKinds),
                """{"type":"regex"}""" to listOf("matchCriterion.type" to oneOfTheKinds),
                """{"type":"Text","text":"gold"}""" to listOf("matchCriterion.type" to oneOfTheKinds),
                """{"type":7}""" to listOf("matchCriterion.type" to oneOfTheKinds),
                """{"text":"gold"}""" to listOf("matchCriterion.type" to oneOfTheKinds),
                """{"type":"text"}""" to listOf("matchCriterion.text" to MISSING),
                """{"type":"text","text":7}""" to listOf("matchCriterion.text" to A_TEXT),
                """{"type":"text","source":"{}"}""" to listOf("matchCriterion.source" to UNKNOWN, "matchCriterion.text" to MISSING),
                """{"type":"rules","source":null}""" to listOf("matchCriterion.source" to NULL),
                """{"type":"none","text":"gold"}""" to listOf("matchCriterion.text" to UNKNOWN),
            )

        for ((criterion, errors) in invalid) {
            assertInvalid(send("PATCH", "scenario", """{"matchCriterion":$criterion}"""), *errors.toTypedArray())
        }
        val kinds = listOf("""{"type":"none"}""", """{"text":"silver","type":"text"}""", """{"type":"rules","source":"{}"}""")
        val taken = kinds.map { send("PATCH", "scenario", """{"matchCriterion":$it}""").decoded<ScenarioAttributesDto>().matchCriterion }
        assertEquals(listOf(MatchCriterionDto.None, MatchCriterionDto.Text("silver"), MatchCriterionDto.Rules("{}")), taken)
    }

    /** A problem names a member and says what the member takes. What the request has there can be a secret, and stays out. */
    @Test
    fun aProblemRepeatsNoValueOfTheRequest() {
        val bodies =
            listOf(
                """{"name":["$SECRET"],"mockUrl":{"$SECRET":1}}""",
                """{"responseMode":"$SECRET","delayMillis":"$SECRET","requestRulesEnabled":"$SECRET"}""",
                """{"realUrls":["http://api.example.com",{"url":"$SECRET"}]}""",
            )
        var answers = emptyList<RawHttp.Answer>()

        val log = TestLog.of { answers = bodies.map { send("PATCH", "service", it) } }

        assertEquals(List(bodies.size) { 422 }, answers.map { it.status })
        answers.forEach { assertFalse(SECRET in it.body, "$it") }
        assertEquals(emptyList(), TestLog.messages(log))
    }

    // The attributes that a request states

    /** The scenario "Only the delay is changed", as far as reading goes: what a request does not state stays. */
    @Test
    fun onlyTheAttributesThatARequestStatesChange() {
        val delay = send("PATCH", "scenario", """{"delayMillis":0}""")
        val nothing = send("PATCH", "scenario", "{}")
        val several = send("PATCH", "scenario", """{"name":"lost","statusCode":404,"responseBody":""}""")

        assertEquals(scenario.copy(delayMillis = 0), delay.decoded<ScenarioAttributesDto>())
        assertEquals(scenario, nothing.decoded<ScenarioAttributesDto>())
        assertEquals(scenario.copy(name = "lost", statusCode = 404, responseBody = ""), several.decoded<ScenarioAttributesDto>())
    }

    /** An attribute that is stated as `null` is stated: it has no value from then on. That is not the same as leaving it out. */
    @Test
    fun anAttributeThatIsStatedAsNullHasNoValueAfterwards() {
        val cleared = send("PATCH", "scenario", """{"methodRestriction":null}""")
        val kept = send("PATCH", "scenario", """{"name":"found"}""")

        assertEquals(scenario.copy(methodRestriction = null), cleared.decoded<ScenarioAttributesDto>())
        assertEquals("POST", kept.decoded<ScenarioAttributesDto>().methodRestriction)
    }

    /** A stated attribute takes the place of the whole attribute. Nothing of the old value is kept in the new one. */
    @Test
    fun aStatedListOrObjectTakesThePlaceOfTheWholeAttribute() {
        val headers = send("PATCH", "scenario", """{"headers":[{"name":"X-Trace","value":"2"}]}""")
        val none = send("PATCH", "scenario", """{"headers":[],"tags":[],"matchCriterion":{"type":"none"}}""")

        assertEquals(scenario.copy(headers = listOf(HeaderDto("X-Trace", "2"))), headers.decoded<ScenarioAttributesDto>())
        assertEquals(
            scenario.copy(headers = emptyList(), tags = emptyList(), matchCriterion = MatchCriterionDto.None),
            none.decoded<ScenarioAttributesDto>(),
        )
    }

    /** What a wire type of attributes does not have is no attribute that a request sets, such as the identifier of a scenario. */
    @Test
    fun aMemberThatIsNoAttributeIsNamed() {
        val answer = send("PATCH", "scenario", """{"id":"s-1","delay":250,"Name":"lost","name":"lost"}""")

        assertInvalid(answer, "id" to UNKNOWN, "delay" to UNKNOWN, "Name" to UNKNOWN)
    }

    private companion object {
        const val SECRET = "s3cret-7f2e91"
        const val UNKNOWN = "The request can have no member of this name here."
        const val MISSING = "The member is missing."
        const val NULL = "The member must have a value, and null is none."
        const val A_TEXT = "The value has to be a text."
        const val A_WHOLE_NUMBER = "The value has to be a whole number."
        const val TRUE_OR_FALSE = "The value has to be true or false."
        const val A_LIST = "The value has to be a list."
        const val AN_OBJECT = "The value has to be an object."
    }
}
