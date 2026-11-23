package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ScenarioChangesTest {
    private val customersId = ServiceId("svc-customers")
    private val ordersId = ServiceId("svc-orders")
    private val empty =
        Definitions(
            services =
                listOf(
                    Service(id = customersId, name = "Customers", mockUrl = "customer/{id}"),
                    Service(id = ordersId, name = "Orders", mockUrl = "orders"),
                ),
        )

    private fun scenario(
        name: String,
        body: String = "",
        serviceId: ServiceId = customersId,
    ): Change<Scenario> = ScenarioChanges.create(serviceId) { it.copy(name = name, responseBody = body) }

    private val a = empty.changed(scenario("A"))
    private val b = a.definitions.changed(scenario("B"))
    private val c = b.definitions.changed(scenario("C"))
    private val other = c.definitions.changed(scenario("ok", serviceId = ordersId))

    /** The service `Customers` with the scenarios A, B, and C, and the service `Orders` with the scenario `ok`. */
    private val definitions = other.definitions

    private fun Definitions.customers(): Service = service(customersId) ?: error("The service is gone.")

    private fun Definitions.orders(): Service = service(ordersId) ?: error("The service is gone.")

    @Test
    fun aScenarioCreatedWithOnlyANameAndABodyHasTheDefaults() {
        val created = empty.changed(scenario("gold", """{"plan":"gold"}"""))

        val scenario = created.value
        assertEquals(listOf(scenario), created.definitions.customers().scenarios)
        assertEquals("gold", scenario.name)
        assertEquals("""{"plan":"gold"}""", scenario.responseBody)
        assertEquals(200, scenario.statusCode)
        assertEquals(emptyList(), scenario.headers)
        assertEquals(MatchCriterion.None, scenario.matchCriterion)
        assertNull(scenario.methodRestriction)
        assertEquals(0L, scenario.delayMillis)
        assertEquals(Tags.EMPTY, scenario.tags)
    }

    @Test
    fun aScenarioBelongsToTheServiceItWasCreatedFor() {
        assertEquals(listOf("A", "B", "C"), definitions.customers().scenarios.map { it.name })
        assertEquals(listOf("ok"), definitions.orders().scenarios.map { it.name })
    }

    @Test
    fun everyScenarioGetsItsOwnIdentifier() {
        val ids = definitions.services.flatMap { it.scenarios }.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(empty.changed(scenario("A")).value.id, empty.changed(scenario("A")).value.id)
    }

    @Test
    fun aScenarioKeepsItsIdentifierAndItsPlaceWhenItIsRenamed() {
        val renamed = definitions.changed(ScenarioChanges.update(customersId, b.value.id) { it.copy(name = "silver") })

        assertEquals(b.value.id, renamed.value.id)
        assertEquals(
            listOf("A", "silver", "C"),
            renamed.definitions
                .customers()
                .scenarios
                .map { it.name },
        )
        assertEquals(definitions.orders(), renamed.definitions.orders())
    }

    @Test
    fun anUpdateChangesOnlyWhatItSets() {
        val updated = definitions.changed(ScenarioChanges.update(customersId, b.value.id) { it.copy(statusCode = 404) })

        assertEquals(b.value.copy(statusCode = 404), updated.value)
        assertEquals(listOf(a.value, updated.value, c.value), updated.definitions.customers().scenarios)
    }

    @Test
    fun aScenarioCreatedWithoutANameGetsANameThatContainsTheNameOfItsService() {
        val unnamed = empty.changed(ScenarioChanges.create(customersId) { it.copy(responseBody = "{}") })
        val blank = empty.changed(scenario("   "))

        assertContains(unnamed.value.name, "Customers")
        assertEquals(unnamed.value.name, blank.value.name)
    }

    @Test
    fun aScenarioThatIsSavedWithABlankNameGetsAGeneratedName() {
        val updated = definitions.changed(ScenarioChanges.update(customersId, a.value.id) { it.copy(name = " ") })

        assertContains(updated.value.name, "Customers")
    }

    @Test
    fun aScenarioNameOfMoreThan250CharactersIsRejected() {
        val created = definitions.rejected(scenario("n".repeat(251)))
        val updated = definitions.rejected(ScenarioChanges.update(customersId, a.value.id) { it.copy(name = "n".repeat(251)) })

        assertEquals(listOf("name"), created.map { it.field })
        assertEquals(listOf("name"), updated.map { it.field })
        assertContains(created.single().message, "250")
    }

    @Test
    fun aScenarioNameOfUpTo250CharactersIsAcceptedAndStoredWithoutSurroundingWhitespace() {
        val longest = "n".repeat(250)

        assertEquals(longest, definitions.changed(scenario("  $longest ")).value.name)
        assertEquals("gold", definitions.changed(scenario(" gold\t")).value.name)
    }

    @Test
    fun theFirstScenarioOfAServiceBecomesItsDefaultScenario() {
        assertNull(empty.customers().defaultScenarioId)
        assertEquals(a.value.id, a.definitions.customers().defaultScenarioId)
    }

    @Test
    fun aLaterScenarioDoesNotReplaceTheDefaultScenario() {
        assertEquals(a.value.id, definitions.customers().defaultScenarioId)
    }

    @Test
    fun theDefaultScenarioIsDesignatedWithTheService() {
        val designated = definitions.changed(ServiceChanges.update(customersId) { it.copy(defaultScenarioId = c.value.id) })

        assertEquals(c.value.id, designated.value.defaultScenarioId)
    }

    @Test
    fun theEarliestScenarioBecomesTheDefaultWhenTheDefaultScenarioIsDeleted() {
        val withDefaultC = definitions.changed(ServiceChanges.update(customersId) { it.copy(defaultScenarioId = c.value.id) }).definitions

        val deleted = withDefaultC.changed(ScenarioChanges.delete(customersId, c.value.id))

        assertEquals(c.value, deleted.value)
        assertEquals(
            listOf("A", "B"),
            deleted.definitions
                .customers()
                .scenarios
                .map { it.name },
        )
        assertEquals(a.value.id, deleted.definitions.customers().defaultScenarioId)
    }

    @Test
    fun aServiceHasNoDefaultScenarioOnceItsLastScenarioIsDeleted() {
        val deleted = definitions.changed(ScenarioChanges.delete(ordersId, other.value.id))

        assertEquals(emptyList(), deleted.definitions.orders().scenarios)
        assertNull(deleted.definitions.orders().defaultScenarioId)
    }

    @Test
    fun deletingAnotherScenarioKeepsTheDefaultScenario() {
        val deleted = definitions.changed(ScenarioChanges.delete(customersId, b.value.id))

        assertEquals(a.value.id, deleted.definitions.customers().defaultScenarioId)
        assertEquals(
            listOf("A", "C"),
            deleted.definitions
                .customers()
                .scenarios
                .map { it.name },
        )
    }

    @Test
    fun onlyAScenarioOfTheServiceCanBeItsDefaultScenario() {
        val foreign = definitions.rejected(ServiceChanges.update(customersId) { it.copy(defaultScenarioId = other.value.id) })
        val unknown = definitions.rejected(ServiceChanges.update(customersId) { it.copy(defaultScenarioId = ScenarioId.generate()) })

        assertEquals(listOf("defaultScenarioId"), foreign.map { it.field })
        assertEquals(listOf("defaultScenarioId"), unknown.map { it.field })
    }

    @Test
    fun aServiceWithScenariosAlwaysHasADefaultScenario() {
        val cleared = definitions.changed(ServiceChanges.update(customersId) { it.copy(defaultScenarioId = null) })

        assertEquals(a.value.id, cleared.value.defaultScenarioId)
    }

    @Test
    fun theEarliestScenarioTakesThePlaceOfADefaultScenarioThatIsMissing() {
        val dangling = definitions.withService(definitions.customers().copy(defaultScenarioId = ScenarioId("gone")))

        val afterServiceUpdate = dangling.changed(ServiceChanges.update(customersId) { it.copy(delayMillis = 250) })
        val afterScenarioUpdate = dangling.changed(ScenarioChanges.update(customersId, c.value.id) { it.copy(statusCode = 500) })

        assertEquals(a.value.id, afterServiceUpdate.value.defaultScenarioId)
        assertEquals(a.value.id, afterScenarioUpdate.definitions.customers().defaultScenarioId)
        assertEquals(a.value.id, dangling.customers().withValidDesignations().defaultScenarioId)
    }

    @Test
    fun theErrorScenarioIsDesignatedAndClearedWithTheService() {
        val designated = definitions.changed(ServiceChanges.update(customersId) { it.copy(errorScenarioId = b.value.id) })
        val cleared = designated.definitions.changed(ServiceChanges.update(customersId) { it.copy(errorScenarioId = null) })

        assertNull(definitions.customers().errorScenarioId)
        assertEquals(b.value.id, designated.value.errorScenarioId)
        assertNull(cleared.value.errorScenarioId)
    }

    @Test
    fun onlyAScenarioOfTheServiceCanBeItsErrorScenario() {
        val foreign = definitions.rejected(ServiceChanges.update(customersId) { it.copy(errorScenarioId = other.value.id) })

        assertEquals(listOf("errorScenarioId"), foreign.map { it.field })
    }

    @Test
    fun savingAnotherScenarioKeepsTheErrorScenario() {
        val designated = definitions.changed(ServiceChanges.update(customersId) { it.copy(errorScenarioId = b.value.id) }).definitions

        val updated = designated.changed(ScenarioChanges.update(customersId, a.value.id) { it.copy(responseBody = "changed") })
        val created = updated.definitions.changed(scenario("D"))

        assertEquals(b.value.id, updated.definitions.customers().errorScenarioId)
        assertEquals(b.value.id, created.definitions.customers().errorScenarioId)
    }

    @Test
    fun deletingTheErrorScenarioEndsItsDesignation() {
        val designated = definitions.changed(ServiceChanges.update(customersId) { it.copy(errorScenarioId = b.value.id) }).definitions

        val deleted = designated.changed(ScenarioChanges.delete(customersId, b.value.id))

        assertNull(deleted.definitions.customers().errorScenarioId)
    }

    @Test
    fun theUniversalErrorScenarioIsDesignatedAndCleared() {
        val first = definitions.changed(ScenarioChanges.designateUniversalError(ScenarioReference(customersId, b.value.id)))
        val second = first.definitions.changed(ScenarioChanges.designateUniversalError(ScenarioReference(ordersId, other.value.id)))
        val cleared = second.definitions.changed(ScenarioChanges.designateUniversalError(null))

        assertNull(definitions.settings.universalErrorScenario)
        assertEquals(ScenarioReference(customersId, b.value.id), first.definitions.settings.universalErrorScenario)
        assertEquals(ScenarioReference(ordersId, other.value.id), second.definitions.settings.universalErrorScenario)
        assertNull(cleared.definitions.settings.universalErrorScenario)
    }

    @Test
    fun onlyAScenarioThatExistsCanBeTheUniversalErrorScenario() {
        val unknownService = ServiceId.generate()

        val noService = definitions.notFound(ScenarioChanges.designateUniversalError(ScenarioReference(unknownService, b.value.id)))
        val noScenario = definitions.notFound(ScenarioChanges.designateUniversalError(ScenarioReference(ordersId, b.value.id)))

        assertContains(noService, unknownService.value)
        assertContains(noScenario, b.value.id.value)
    }

    @Test
    fun savingAnotherScenarioKeepsTheUniversalErrorScenario() {
        val universal = ScenarioReference(customersId, b.value.id)
        val designated = definitions.changed(ScenarioChanges.designateUniversalError(universal)).definitions

        val updated = designated.changed(ScenarioChanges.update(ordersId, other.value.id) { it.copy(responseBody = "changed") })
        val sibling = updated.definitions.changed(ScenarioChanges.update(customersId, a.value.id) { it.copy(statusCode = 201) })
        val created = sibling.definitions.changed(scenario("D"))

        assertEquals(universal, updated.definitions.settings.universalErrorScenario)
        assertEquals(universal, sibling.definitions.settings.universalErrorScenario)
        assertEquals(universal, created.definitions.settings.universalErrorScenario)
    }

    @Test
    fun deletingTheUniversalErrorScenarioEndsItsDesignation() {
        val designated =
            definitions
                .changed(
                    ScenarioChanges.designateUniversalError(ScenarioReference(customersId, b.value.id)),
                ).definitions

        val deleted = designated.changed(ScenarioChanges.delete(customersId, b.value.id))

        assertNull(deleted.definitions.settings.universalErrorScenario)
    }

    @Test
    fun deletingAnotherScenarioKeepsTheUniversalErrorScenario() {
        val universal = ScenarioReference(customersId, b.value.id)
        val designated = definitions.changed(ScenarioChanges.designateUniversalError(universal)).definitions

        val deleted = designated.changed(ScenarioChanges.delete(customersId, c.value.id))

        assertEquals(universal, deleted.definitions.settings.universalErrorScenario)
    }

    @Test
    fun aScenarioIsNotCreatedForAServiceThatDoesNotExist() {
        val unknown = ServiceId.generate()

        assertContains(definitions.notFound(scenario("gold", serviceId = unknown)), unknown.value)
    }

    @Test
    fun aScenarioThatDoesNotExistIsNeitherUpdatedNorDeleted() {
        val unknownService = ServiceId.generate()
        val unknownScenario = ScenarioId.generate()

        assertContains(definitions.notFound(ScenarioChanges.update(unknownService, a.value.id) { it }), unknownService.value)
        assertContains(definitions.notFound(ScenarioChanges.delete(unknownService, a.value.id)), unknownService.value)
        assertContains(definitions.notFound(ScenarioChanges.update(customersId, unknownScenario) { it }), unknownScenario.value)
        assertContains(definitions.notFound(ScenarioChanges.delete(customersId, unknownScenario)), unknownScenario.value)
        assertContains(definitions.notFound(ScenarioChanges.delete(ordersId, a.value.id)), "'Orders'")
    }

    @Test
    fun theIdentifierOfAScenarioCannotBeChanged() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioChanges.update(customersId, a.value.id) { it.copy(id = ScenarioId.generate()) }(definitions)
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioChanges.create(customersId) { it.copy(id = ScenarioId.generate(), name = "gold") }(definitions)
        }
    }

    @Test
    fun theAttributesOfAScenarioAreKept() {
        val headers = listOf(Header("Set-Cookie", "a=1"), Header("Content-Type", "application/json"), Header("Set-Cookie", "b=2"))
        val created =
            definitions.changed(
                ScenarioChanges.create(customersId) {
                    it.copy(
                        name = "gold customer",
                        responseBody = "  {\"tier\":\"gold\"}\n",
                        statusCode = 201,
                        headers = headers,
                        matchCriterion = MatchCriterion.Text(" ABC123 "),
                        methodRestriction = "post",
                        delayMillis = 500,
                        tags = Tags.of("Smoke regression"),
                    )
                },
            )

        val scenario = created.value
        assertEquals("  {\"tier\":\"gold\"}\n", scenario.responseBody)
        assertEquals(201, scenario.statusCode)
        assertEquals(headers, scenario.headers)
        assertEquals(MatchCriterion.Text(" ABC123 "), scenario.matchCriterion)
        assertEquals("post", scenario.methodRestriction)
        assertEquals(500L, scenario.delayMillis)
        assertEquals(Tags.of("regression smoke"), scenario.tags)
    }

    @Test
    fun aBlankMethodRestrictionIsAbsent() {
        val blank = definitions.changed(ScenarioChanges.update(customersId, a.value.id) { it.copy(methodRestriction = "  ") })
        val padded = definitions.changed(ScenarioChanges.update(customersId, a.value.id) { it.copy(methodRestriction = " PUT ") })

        assertNull(blank.value.methodRestriction)
        assertEquals("PUT", padded.value.methodRestriction)
    }

    @Test
    fun headersAreTrimmedAndThoseWithoutANameAreLeftOut() {
        val updated =
            definitions.changed(
                ScenarioChanges.update(customersId, a.value.id) {
                    it.copy(headers = listOf(Header(" Content-Type ", " text/plain "), Header("  ", "lost"), Header("X-Empty", "")))
                },
            )

        assertEquals(listOf(Header("Content-Type", "text/plain"), Header("X-Empty", "")), updated.value.headers)
    }
}
