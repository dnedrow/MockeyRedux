package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignationsTest {
    private val silver = Scenario(id = ScenarioId.generate(), name = "silver")
    private val gold = Scenario(id = ScenarioId.generate(), name = "gold")
    private val service = Service(id = ServiceId.generate(), name = "Plans", mockUrl = "plan", scenarios = listOf(silver, gold))

    @Test
    fun theDefaultScenarioIsTheDesignatedOne() {
        assertEquals(gold, service.copy(defaultScenarioId = gold.id).defaultScenario)
        assertEquals(silver, service.copy(defaultScenarioId = silver.id).defaultScenario)
    }

    @Test
    fun theScenarioCreatedFirstStandsInForADesignationThatIsMissing() {
        assertEquals(silver, service.copy(defaultScenarioId = null).defaultScenario)
        assertEquals(silver, service.copy(defaultScenarioId = ScenarioId.generate()).defaultScenario)
    }

    @Test
    fun aServiceWithoutScenariosHasNoDefaultScenario() {
        assertNull(service.copy(scenarios = emptyList(), defaultScenarioId = gold.id).defaultScenario)
    }

    @Test
    fun theDefaultRealUrlIsTheOneAtTheDesignatedPlace() {
        val urls = listOf("http://qa1.example.com/plans", "http://qa2.example.com/plans")

        assertEquals("http://qa1.example.com/plans", service.copy(realUrls = urls).defaultRealUrl)
        assertEquals("http://qa2.example.com/plans", service.copy(realUrls = urls, defaultRealUrlIndex = 1).defaultRealUrl)
    }

    @Test
    fun theFirstRealUrlStandsInForAPlaceThatHasNone() {
        val urls = listOf("http://qa1.example.com/plans", "http://qa2.example.com/plans")

        assertEquals("http://qa1.example.com/plans", service.copy(realUrls = urls, defaultRealUrlIndex = 2).defaultRealUrl)
        assertEquals("http://qa1.example.com/plans", service.copy(realUrls = urls, defaultRealUrlIndex = -1).defaultRealUrl)
        assertNull(service.copy(realUrls = emptyList(), defaultRealUrlIndex = 0).defaultRealUrl)
    }

    private val failure = Scenario(id = ScenarioId.generate(), name = "failure")
    private val errors = Service(id = ServiceId.generate(), name = "Errors", mockUrl = "errors", scenarios = listOf(failure))

    private fun definitions(
        universal: ScenarioReference?,
        vararg services: Service,
    ) = Definitions(services = services.toList(), settings = Settings(universalErrorScenario = universal))

    @Test
    fun theErrorScenarioIsTheDesignatedOne() {
        assertEquals(gold, service.copy(errorScenarioId = gold.id).errorScenario)
    }

    @Test
    fun aServiceHasNoErrorScenarioUnlessItDesignatesOneOfItsScenarios() {
        assertNull(service.errorScenario)
        assertNull(service.copy(errorScenarioId = failure.id).errorScenario)
        assertNull(service.copy(scenarios = emptyList(), errorScenarioId = gold.id).errorScenario)
    }

    @Test
    fun theUniversalErrorScenarioIsTheOneThatTheSettingsDesignate() {
        val definitions = definitions(ScenarioReference(errors.id, failure.id), service, errors)

        assertEquals(failure, definitions.universalErrorScenario)
    }

    @Test
    fun thereIsNoUniversalErrorScenarioUnlessTheSettingsDesignateAScenarioThatExists() {
        assertNull(definitions(universal = null, service, errors).universalErrorScenario)
        assertNull(definitions(ScenarioReference(errors.id, gold.id), service, errors).universalErrorScenario)
        assertNull(definitions(ScenarioReference(ServiceId.generate(), failure.id), service, errors).universalErrorScenario)
        assertNull(definitions(ScenarioReference(errors.id, failure.id), service).universalErrorScenario)
    }

    @Test
    fun theErrorScenarioOfAServiceComesBeforeTheUniversalErrorScenario() {
        val definitions = definitions(ScenarioReference(errors.id, failure.id), service, errors)

        assertEquals(gold, definitions.errorScenarioFor(service.copy(errorScenarioId = gold.id)))
        assertEquals(failure, definitions.errorScenarioFor(service))
        assertEquals(failure, definitions.errorScenarioFor(service.copy(errorScenarioId = failure.id)))
    }

    @Test
    fun withoutADesignationNoErrorScenarioAnswersForAService() {
        assertNull(definitions(universal = null, service, errors).errorScenarioFor(service))
    }
}
