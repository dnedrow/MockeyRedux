package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.request.MockRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DynamicModeTest {
    private fun scenario(
        name: String,
        body: String,
        status: Int = 200,
        criterion: String? = null,
        method: String? = null,
        headers: List<Header> = emptyList(),
    ) = Scenario(
        id = ScenarioId.generate(),
        name = name,
        responseBody = body,
        statusCode = status,
        headers = headers,
        matchCriterion = criterion?.let(MatchCriterion::Text) ?: MatchCriterion.None,
        methodRestriction = method,
    )

    private val known = scenario("known", "matched", criterion = "known")
    private val serviceError = scenario("service error", "service error", status = 500)
    private val universalError = scenario("universal error", "universal error", status = 503, headers = listOf(Header("Retry-After", "30")))

    /** The service that holds the universal error scenario. It is static, and no request of these tests goes to it. */
    private val holder =
        Service(
            id = ServiceId.generate(),
            name = "universal error holder",
            mockUrl = "fallback/universal-holder",
            responseMode = ResponseMode.STATIC,
            scenarios = listOf(universalError),
            defaultScenarioId = universalError.id,
        )

    private val universal = ScenarioReference(holder.id, universalError.id)

    private fun service(
        error: Scenario?,
        vararg scenarios: Scenario,
    ) = Service(
        id = ServiceId.generate(),
        name = "Orders",
        mockUrl = "orders",
        responseMode = ResponseMode.DYNAMIC,
        scenarios = scenarios.toList(),
        defaultScenarioId = scenarios.firstOrNull()?.id,
        errorScenarioId = error?.id,
    )

    private fun request(
        query: String,
        method: String = "GET",
    ) = MockRequest(method, "http://mockey.test:8080/service/orders", query)

    /** Returns how [service] answers [request] in dynamic mode, with or without a universal error scenario. */
    private fun answer(
        service: Service,
        request: MockRequest,
        universal: ScenarioReference? = null,
    ): ScenarioAnswer {
        val definitions = Definitions(services = listOf(holder, service), settings = Settings(universalErrorScenario = universal))
        return definitions.dynamicAnswer(service, emptyMap(), request, RuleSets(definitions))
    }

    @Test
    fun theScenarioThatMatchesBestAnswers() {
        val service = service(serviceError, known, scenario("longer", "longer match", criterion = "q=known"), serviceError)

        val answer = answer(service, request("q=known"), universal)

        assertEquals("longer", assertIs<ScenarioAnswer.ScenarioServed>(answer).scenario.name)
        assertContentEquals("longer match".toByteArray(), answer.response().body)
    }

    @Test
    fun theErrorScenarioOfTheServiceAnswersWhenNoScenarioMatches() {
        val service = service(serviceError, known, serviceError)

        val answer = answer(service, request("q=other"), universal)

        assertEquals(ScenarioAnswer.ErrorScenarioServed(serviceError), answer)
        assertEquals(500, answer.response().status)
        assertContentEquals("service error".toByteArray(), answer.response().body)
    }

    @Test
    fun theUniversalErrorScenarioAnswersForAServiceWithoutAnErrorScenario() {
        val service = service(error = null, known)

        val answer = answer(service, request("q=other"), universal)

        assertEquals(ScenarioAnswer.ErrorScenarioServed(universalError), answer)
        assertEquals(503, answer.response().status)
        assertContentEquals("universal error".toByteArray(), answer.response().body)
    }

    @Test
    fun anErrorScenarioAnswersWithItsStatusItsHeadersAndItsBody() {
        val json = Header("Content-Type", "application/json")
        val error =
            scenario("service error", """{"error":"service error"}""", status = 500, headers = listOf(json, Header("X-Error", "service")))

        val response = answer(service(error, known, error), request("q=other")).response()

        assertEquals(500, response.status)
        assertEquals(listOf(json, Header("X-Error", "service")), response.headers)
        assertContentEquals("""{"error":"service error"}""".toByteArray(), response.body)
        assertEquals(listOf(Header("Retry-After", "30")), answer(service(null, known), request("q=other"), universal).response().headers)
    }

    @Test
    fun withoutAnErrorScenarioTheRequestGetsTheStatus404AndAnExplanation() {
        val answer = answer(service(error = null, known), request("q=other"))

        val explanation = assertIs<ScenarioAnswer.NoScenarioMatched>(answer).explanation
        val response = answer.response()

        assertNull(answer.scenario)
        assertEquals(404, response.status)
        assertEquals(listOf(Header("Content-Type", "text/plain; charset=utf-8")), response.headers)
        assertContentEquals(explanation.toByteArray(Charsets.UTF_8), response.body)
        assertContains(explanation, "No scenario of the service 'Orders' matches the request")
        assertContains(explanation, "no error scenario is defined")
    }

    @Test
    fun theExplanationIsSentAsUtf8() {
        val named = service(error = null, known).copy(name = "Caf\u00e9")

        val response = answer(named, request("q=other")).response()

        assertContains(response.bodyText, "the service 'Caf\u00e9'")
        assertContains(response.body.toList(), 0xC3.toByte())
    }

    @Test
    fun aScenarioWithoutACriterionLeavesTheAnswerToTheErrorScenario() {
        val unmatched = scenario("no criterion", "never selected")
        val error = scenario("service error", "a scenario without a criterion is never selected", status = 500)

        assertEquals(ScenarioAnswer.ErrorScenarioServed(error), answer(service(error, unmatched, error), request("q=anything")))
        assertIs<ScenarioAnswer.NoScenarioMatched>(answer(service(error = null, unmatched), request("q=anything")))
    }

    @Test
    fun aScenarioThatIsNotEligibleLeavesTheAnswerToTheErrorScenario() {
        val forPost = scenario("post orders", "POST scenario", criterion = "order", method = "POST")
        val error = scenario("no eligible scenario", "no eligible scenario", status = 405)
        val service = service(error, forPost, error)

        assertEquals(ScenarioAnswer.ErrorScenarioServed(error), answer(service, request("q=order", method = "DELETE")))
        assertEquals(ScenarioAnswer.ScenarioServed(forPost), answer(service, request("q=order", method = "POST")))
    }

    @Test
    fun anErrorScenarioThatMatchesTheRequestAnswersAsAMatch() {
        val error = scenario("service error", "service error", status = 500, criterion = "fail")
        val service = service(error, known, error)

        assertEquals(ScenarioAnswer.ScenarioServed(error), answer(service, request("q=fail")))
        assertEquals(ScenarioAnswer.ErrorScenarioServed(error), answer(service, request("q=other")))
    }

    @Test
    fun theUniversalErrorScenarioCanBeAScenarioOfTheServiceItself() {
        val own = scenario("own error", "own error", status = 502)
        val service = service(error = null, known, own)
        val settings = Settings(universalErrorScenario = ScenarioReference(service.id, own.id))
        val definitions = Definitions(services = listOf(service), settings = settings)

        val answer = definitions.dynamicAnswer(service, emptyMap(), request("q=other"), RuleSets(definitions))

        assertEquals(ScenarioAnswer.ErrorScenarioServed(own), answer)
    }

    @Test
    fun anErrorScenarioThatDoesNotExistIsPassedOver() {
        val deleted = scenario("deleted", "deleted")
        val service = service(error = deleted, known)
        val noSuchScenario = ScenarioReference(holder.id, deleted.id)
        val noSuchService = ScenarioReference(ServiceId.generate(), universalError.id)

        assertEquals(ScenarioAnswer.ErrorScenarioServed(universalError), answer(service, request("q=other"), universal))
        assertIs<ScenarioAnswer.NoScenarioMatched>(answer(service, request("q=other"), noSuchScenario))
        assertIs<ScenarioAnswer.NoScenarioMatched>(answer(service, request("q=other"), noSuchService))
    }

    @Test
    fun aServiceWithoutScenariosHasNoMatch() {
        assertEquals(ScenarioAnswer.ErrorScenarioServed(universalError), answer(service(error = null), request("q=other"), universal))
        assertIs<ScenarioAnswer.NoScenarioMatched>(answer(service(error = null), request("q=other")))
    }

    /**
     * The services of the parity fixtures `responses-fallback` and `responses-universal-error`, and the stated new
     * responses of their recordings. `response-fallback-builtin` states the status 404, a `Content-Type` that
     * begins with `text/plain`, and a body that contains `scenario` (DEV-07).
     */
    @Test
    fun theServicesOfTheFallbackRecordingsAnswerAsTheRecordingsState() {
        val json = Header("Content-Type", "application/json")
        val withHeaders =
            scenario("service error", """{"error":"service error"}""", status = 500, headers = listOf(json, Header("X-Error", "service")))
        val noCriterion = scenario("no criterion", "never selected")
        val afterNoCriterion = scenario("service error", "a scenario without a criterion is never selected", status = 500)
        val other = request("q=other")

        val serviceErrorAnswer = answer(service(withHeaders, known, withHeaders), other).response()
        val builtIn = answer(service(error = null, known), other).response()
        val noCriterionAnswer = answer(service(afterNoCriterion, noCriterion, afterNoCriterion), request("q=anything")).response()
        val serviceFirst = answer(service(serviceError, known, serviceError), other, universal).response()
        val universalSecond = answer(service(error = null, known), other, universal).response()

        assertEquals(500 to """{"error":"service error"}""", serviceErrorAnswer.status to serviceErrorAnswer.bodyText)
        assertEquals(listOf(json, Header("X-Error", "service")), serviceErrorAnswer.headers)
        assertEquals(404, builtIn.status)
        assertEquals(true, builtIn.contentType?.startsWith("text/plain"))
        assertContains(builtIn.bodyText, "scenario")
        assertEquals(500 to "a scenario without a criterion is never selected", noCriterionAnswer.status to noCriterionAnswer.bodyText)
        assertEquals(500 to "service error", serviceFirst.status to serviceFirst.bodyText)
        assertEquals(emptyList(), serviceFirst.headers)
        assertEquals(503 to "universal error", universalSecond.status to universalSecond.bodyText)
        assertEquals(listOf(Header("Retry-After", "30")), universalSecond.headers)
    }
}
