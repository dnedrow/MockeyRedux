package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What an import makes of the definitions of a document: what it adds, what it merges, and what it reports. */
class DefinitionImportTest {
    private fun scenario(
        name: String,
        body: String = name,
        id: String = "scn-$name",
    ) = Scenario(id = ScenarioId(id), name = name, responseBody = body)

    private fun service(
        name: String,
        vararg scenarios: Scenario,
        mockUrl: String = name.lowercase(),
        id: String = "svc-${name.lowercase()}",
    ) = Service(id = ServiceId(id), name = name, mockUrl = mockUrl, scenarios = scenarios.toList()).withValidDesignations()

    private fun plan(
        name: String,
        vararg items: PlanItem,
        id: String = "plan-$name",
    ) = Plan(id = PlanId(id), name = name, items = items.toList())

    private fun twist(
        name: String,
        id: String = "twist-$name",
    ) = TwistConfiguration(id = TwistId(id), name = name, pairs = listOf(TwistPair("qa1", name)))

    private fun servicesOf(vararg services: Service) = Definitions(services = services.toList())

    private fun Definitions.importing(
        document: Definitions,
        tags: Tags = Tags.EMPTY,
    ): ChangeResult.Changed<ImportReport> = changed(DefinitionImport.import(document, tags))

    /** The names of these items, with the name of its service in front of the name of a scenario. */
    private val List<ImportItem>.names: List<String>
        get() = map { item -> listOfNotNull(item.serviceName, item.name).joinToString("/") }

    private fun List<ImportItem>.of(kind: ImportItemKind): List<ImportItem> = filter { it.kind == kind }

    private fun List<ImportItem>.messageOf(name: String): String = single { it.name == name }.message

    private val ChangeResult.Changed<ImportReport>.services: List<Service>
        get() = definitions.services

    private val ChangeResult.Changed<ImportReport>.additions: List<ImportItem>
        get() = value.additions

    private val ChangeResult.Changed<ImportReport>.conflicts: List<ImportItem>
        get() = value.conflicts

    private val ok = scenario("ok")
    private val timeout = scenario("timeout", "timed out")
    private val orders = service("Orders", ok)
    private val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true)

    // New services

    @Test
    fun aServiceWithANameThatNoServiceHasIsAddedWithItsScenarios() {
        val customers = service("Customers")
        val document = servicesOf(service("Orders", ok, timeout))

        val imported = servicesOf(customers).importing(document)

        assertEquals(listOf(customers) + document.services, imported.services)
        assertEquals(listOf("Orders", "Orders/ok", "Orders/timeout"), imported.additions.names)
        assertEquals("The service 'Orders' was added with 2 scenarios.", imported.additions.messageOf("Orders"))
        assertEquals("The scenario 'ok' was added with its service 'Orders'.", imported.additions.messageOf("ok"))
        assertEquals(emptyList(), imported.conflicts)
    }

    @Test
    fun anAddedServiceKeepsEverythingThatTheDocumentSaysOfIt() {
        val gold =
            scenario(
                "gold",
            ).copy(statusCode = 201, matchCriterion = MatchCriterion.Text("gold"), delayMillis = 5, tags = Tags.of("vip"))
        val broken = scenario("broken").copy(matchCriterion = MatchCriterion.Rules("not a rule set"))
        val customers =
            service("Customers", ok, gold, broken, mockUrl = "customer/{id}").copy(
                description = "Customer directory",
                realUrls = listOf("http://qa1.example.com/customers", "http://qa2.example.com/customers"),
                defaultRealUrlIndex = 1,
                responseMode = ResponseMode.DYNAMIC,
                delayMillis = 100,
                tags = Tags.of("crm"),
                defaultScenarioId = gold.id,
                errorScenarioId = broken.id,
                requestRules = "not a rule set either",
                requestRulesEnabled = true,
                inspectorName = "session-check",
                responseSchema = """{"type":"object"}""",
                responseSchemaEnabled = true,
            )

        val imported = Definitions.EMPTY.importing(servicesOf(customers))

        assertEquals(listOf(customers), imported.services)
    }

    @Test
    fun anImportIntoNoDefinitionsGivesTheDefinitionsOfTheDocument() {
        val error = scenario("error")
        val customers = service("Customers", scenario("found"), error)
        val gold = plan("Gold path", PlanItem("Customers", "error", ResponseMode.STATIC, 250))
        val toQa2 = twist("qa2")
        val settings = Settings(toQa2.id, proxy, ScenarioReference(customers.id, error.id), gold.id)
        val document = Definitions(listOf(customers, orders), listOf(gold, plan("Silver path")), listOf(toQa2, twist("qa3")), settings)

        val imported = Definitions.EMPTY.importing(document)

        val planApplied = customers.copy(responseMode = ResponseMode.STATIC, delayMillis = 250, defaultScenarioId = error.id)
        assertEquals(document.copy(services = listOf(planApplied, orders)), imported.definitions)
        assertEquals(emptyList(), imported.conflicts)
        assertEquals(gold, imported.value.appliedDefaultPlan?.plan)
    }

    @Test
    fun aServiceWhoseMockUrlPatternAnotherServiceHasIsNotAdded() {
        val existing = service("Purchases", mockUrl = "Orders/{ID}")
        val document = servicesOf(service("Orders", ok, timeout, mockUrl = " orders/{id} "))

        val imported = servicesOf(existing).importing(document)

        val inUse = "the service 'Purchases' already uses its mock URL pattern 'orders/{id}'."
        assertEquals(listOf(existing), imported.services)
        assertEquals(listOf("Orders", "Orders/ok", "Orders/timeout"), imported.conflicts.names)
        assertEquals("The service 'Orders' was not added: $inUse", imported.conflicts.messageOf("Orders"))
        assertEquals("The scenario 'ok' was not added, because its service 'Orders' was not.", imported.conflicts.messageOf("ok"))
        assertEquals(emptyList(), imported.additions)
    }

    @Test
    fun aServiceWithoutANameOrWithANameThatIsTooLongIsNotAdded() {
        val nameless = service(" ", ok, id = "svc-nameless")
        val long = service("x".repeat(251), id = "svc-long")

        val imported = Definitions.EMPTY.importing(servicesOf(nameless, long))

        val messages =
            listOf(
                "A service without a name was not added.",
                "The scenario 'ok' was not added, because its service has no name.",
                "The service '${"x".repeat(251)}' was not added: its name is longer than 250 characters.",
            )
        assertEquals(Definitions.EMPTY, imported.definitions)
        assertEquals(messages, imported.conflicts.map { it.message })
    }

    @Test
    fun aRealUrlThatAnotherServiceListsIsLeftOutOfAnAddedService() {
        val existing = service("Purchases").copy(realUrls = listOf("http://api.example.com/Orders"))
        val listed = listOf("http://api.example.com/orders", "http://qa1.example.com/orders", "http://qa2.example.com/orders")
        val document = servicesOf(orders.copy(realUrls = listed, defaultRealUrlIndex = 2))

        val imported = servicesOf(existing).importing(document)

        val added = imported.services.last()
        val leftOut = "Its real URL http://api.example.com/orders was left out, because another service lists it."
        assertEquals(listed.drop(1) to 1, added.realUrls to added.defaultRealUrlIndex)
        assertEquals("The service 'Orders' was added with 1 scenario. $leftOut", imported.additions.messageOf("Orders"))
    }

    // Identifiers

    @Test
    fun whatIsAddedKeepsItsIdentifierWhileNoDefinitionHasIt() {
        val error = scenario("error")
        val customers = service("Customers", scenario("found"), error).copy(errorScenarioId = error.id)
        val (gold, toQa2) = plan("Gold path") to twist("qa2")
        val settings = Settings(toQa2.id, null, ScenarioReference(customers.id, error.id), gold.id)
        val document = Definitions(listOf(customers), listOf(gold), listOf(toQa2), settings)

        val imported = servicesOf(orders).importing(document)

        assertEquals(document.copy(services = listOf(orders, customers)), imported.definitions)
    }

    @Test
    fun whatIsAddedGetsANewIdentifierWhenADefinitionHasItsOwn() {
        val (found, error) = scenario("found") to scenario("error")
        val customers = service("Customers", found, error).copy(errorScenarioId = error.id)
        val (gold, toQa2) = plan("Gold path") to twist("qa2")
        val settings = Settings(toQa2.id, null, ScenarioReference(customers.id, error.id), gold.id)
        val document = Definitions(listOf(customers), listOf(gold), listOf(toQa2), settings)
        val billing = service("Billing", scenario("found", "billed"), scenario("error", "failed"), id = customers.id.value)
        val taken = Definitions(listOf(billing), listOf(plan("Silver path", id = gold.id.value)), listOf(twist("qa3", id = toQa2.id.value)))

        val imported = taken.importing(document).definitions

        val service = imported.services.last()
        val (plan, twist) = imported.plans.last() to imported.twists.last()
        val ids = listOf(service.id.value, plan.id.value, twist.id.value) + service.scenarios.map { it.id.value }
        val takenIds = listOf(customers.id.value, gold.id.value, toQa2.id.value, found.id.value, error.id.value)
        assertTrue(ids.none { it in takenIds }, "$ids")
        assertEquals(listOf("Customers", "Gold path", "qa2"), listOf(service.name, plan.name, twist.name))
        // What refers to a definition in the document refers to it under its new identifier.
        val (newFound, newError) = service.scenarios
        assertEquals(listOf(newFound.id, newError.id), listOf(service.defaultScenarioId, service.errorScenarioId))
        assertEquals(Settings(twist.id, null, ScenarioReference(service.id, newError.id), plan.id), imported.settings)
    }

    @Test
    fun twoDefinitionsOfADocumentWithOneIdentifierGetDifferentOnes() {
        val document = servicesOf(service("Orders", ok), service("Customers", ok, id = orders.id.value))

        val imported = Definitions.EMPTY.importing(document).services

        val ids = imported.map { it.id.value } + imported.flatMap { service -> service.scenarios.map { it.id.value } }
        assertEquals(4, ids.distinct().size)
        assertEquals(listOf(orders.id.value, ok.id.value), listOf(ids[0], ids[2]))
    }

    // Services with the name of an existing one

    @Test
    fun aServiceWithTheNameOfAnExistingOneIsMergedIntoIt() {
        val existing =
            orders.copy(
                delayMillis = 100,
                responseMode = ResponseMode.STATIC,
                realUrls = listOf("http://qa1.example.com/orders"),
            )
        val incoming =
            service(" orders ", scenario("OK", " ok ", id = "scn-ok-2"), timeout, id = "svc-other").copy(
                delayMillis = 999,
                responseMode = ResponseMode.DYNAMIC,
                description = "From the document",
                realUrls = listOf("http://QA1.example.com/orders", "http://qa2.example.com/orders"),
                tags = Tags.of("imported"),
            )

        val imported = servicesOf(existing).importing(servicesOf(incoming))

        val realUrls = existing.realUrls + "http://qa2.example.com/orders"
        val because = "the service 'Orders' has"
        assertEquals(
            listOf(existing.copy(scenarios = listOf(ok, timeout), realUrls = realUrls, tags = Tags.of("imported"))),
            imported.services,
        )
        assertEquals(listOf("orders/timeout"), imported.additions.names)
        assertEquals(listOf("orders/OK", "orders"), imported.conflicts.names)
        assertEquals("The scenario 'timeout' was added to the service 'Orders'.", imported.additions.messageOf("timeout"))
        assertEquals(
            "The scenario 'OK' was not added: $because a scenario with that name and response body.",
            imported.conflicts.messageOf("OK"),
        )
        assertEquals(
            "The service 'orders' was not added: $because that name. What that service did not have of it was added to it.",
            imported.conflicts.messageOf("orders"),
        )
    }

    @Test
    fun aScenarioWithTheNameOfAnExistingOneAndAnotherBodyIsAdded() {
        val incoming = service("Orders", scenario("ok", "another body", id = "scn-ok-2"))

        val merged = servicesOf(orders).importing(servicesOf(incoming)).services.single()

        assertEquals(listOf("ok" to "ok", "ok" to "another body"), merged.scenarios.map { it.name to it.responseBody })
        assertEquals(orders.defaultScenarioId, merged.defaultScenarioId)
    }

    @Test
    fun aMergeLeavesOutARealUrlThatAnotherServiceLists() {
        val purchases = service("Purchases").copy(realUrls = listOf("http://api.example.com/purchases"))
        val incoming = orders.copy(realUrls = listOf("http://api.example.com/purchases", "http://api.example.com/orders"))

        val imported = servicesOf(orders, purchases).importing(servicesOf(incoming))

        val leftOut = " Its real URL http://api.example.com/purchases was left out, because another service lists it."
        assertEquals(listOf("http://api.example.com/orders"), imported.services.first().realUrls)
        assertTrue(imported.conflicts.messageOf("Orders").endsWith(leftOut), imported.conflicts.messageOf("Orders"))
    }

    @Test
    fun servicesOfADocumentWithOneNameBecomeOneService() {
        val second = service("ORDERS", scenario("ok", id = "scn-2"), timeout, id = "svc-2", mockUrl = "other")

        val imported = Definitions.EMPTY.importing(servicesOf(orders, second))

        val merged = imported.services.single()
        assertEquals("Orders" to listOf("ok", "timeout"), merged.name to merged.scenarios.map { it.name })
        assertEquals(listOf("Orders"), imported.additions.of(ImportItemKind.SERVICE).names)
        assertEquals(listOf("ORDERS"), imported.conflicts.of(ImportItemKind.SERVICE).names)
    }

    @Test
    fun aServiceWithoutScenariosGetsItsDefaultScenarioFromAMerge() {
        val incoming = service("Orders", ok, timeout, id = "svc-2")

        val merged = servicesOf(service("Orders")).importing(servicesOf(incoming)).services.single()

        assertEquals(ok.id, merged.defaultScenarioId)
    }

    // Plans and twist configurations

    @Test
    fun plansAndTwistConfigurationsAreAddedWhenTheirNamesAreNotInUse() {
        val current = Definitions(plans = listOf(plan("Gold path")), twists = listOf(twist("qa2")))
        val plans = listOf(plan(" gold PATH ", id = "plan-2"), plan("Silver path"))
        val twists = listOf(twist("QA2", id = "twist-2"), twist("qa3"))

        val imported = current.importing(Definitions(plans = plans, twists = twists))

        val messages =
            listOf(
                "The plan 'gold PATH' was not added: a plan has that name.",
                "The twist configuration 'QA2' was not added: a twist configuration has that name.",
            )
        assertEquals(listOf("Gold path", "Silver path"), imported.definitions.plans.map { it.name })
        assertEquals(listOf("qa2", "qa3"), imported.definitions.twists.map { it.name })
        assertEquals(listOf("Silver path", "qa3"), imported.additions.names)
        assertEquals(messages, imported.conflicts.map { it.message })
    }

    // Settings

    @Test
    fun theSettingsOfTheDefinitionsWinOverThoseOfTheDocument() {
        val error = scenario("error")
        val holder = service("Errors", error)
        val gold = plan("Gold path", PlanItem("Errors", "error", ResponseMode.STATIC, 250))
        val toQa2 = twist("qa2")
        val settings = Settings(toQa2.id, proxy, ScenarioReference(holder.id, error.id), gold.id)
        val current = Definitions(listOf(holder), listOf(gold), listOf(toQa2), settings)
        val otherError = scenario("other error")
        val other = service("Other errors", otherError)
        val (silver, toQa3) = plan("Silver path", PlanItem("Errors", "error", ResponseMode.DYNAMIC, 999)) to twist("qa3")
        val otherProxy = OutboundProxy("http://other.example.com:8080")
        val otherSettings = Settings(toQa3.id, otherProxy, ScenarioReference(other.id, otherError.id), silver.id)

        val imported = current.importing(Definitions(listOf(other), listOf(silver), listOf(toQa3), otherSettings))

        val alreadySet =
            listOf(
                "The settings of the outbound proxy were not taken from the document: the outbound proxy has settings already.",
                "The universal error scenario of the document was not designated: a universal error scenario is designated already.",
                "The twist configuration that the document enables was not enabled: a twist configuration is enabled already.",
                "The default plan of the document was not designated: a default plan is designated already.",
            )
        val names =
            listOf(
                ImportItem.OUTBOUND_PROXY,
                ImportItem.UNIVERSAL_ERROR_SCENARIO,
                ImportItem.ENABLED_TWIST_CONFIGURATION,
                ImportItem.DEFAULT_PLAN,
            )
        assertEquals(settings, imported.definitions.settings)
        assertEquals(holder, imported.services.first())
        assertNull(imported.value.appliedDefaultPlan)
        assertEquals(alreadySet, imported.conflicts.map { it.message })
        assertEquals(names, imported.conflicts.of(ImportItemKind.SETTING).names)
    }

    @Test
    fun settingsAreTakenFromTheDocumentWhereTheDefinitionsHaveNone() {
        val error = scenario("error")
        val holder = service("Errors", scenario("fine"), error)
        val items = listOf(PlanItem("errors", "ERROR", ResponseMode.STATIC, 250), PlanItem("Orders", "gone"), PlanItem("Unknown"))
        val gold = plan("Gold path", *items.toTypedArray())
        val toQa2 = twist("qa2")
        val settings = Settings(toQa2.id, proxy, ScenarioReference(holder.id, error.id), gold.id)

        val imported = servicesOf(orders).importing(Definitions(listOf(holder), listOf(gold), listOf(toQa2), settings))

        val messages =
            listOf(
                "The settings of the outbound proxy were taken from the document.",
                "The scenario 'error' of the service 'Errors' was designated as the universal error scenario.",
                "The twist configuration 'qa2' was enabled.",
                "The plan 'Gold path' was designated as the default plan and applied.",
            )
        val applied = imported.value.appliedDefaultPlan
        assertEquals(settings, imported.definitions.settings)
        assertEquals(
            holder.copy(responseMode = ResponseMode.STATIC, delayMillis = 250, defaultScenarioId = error.id),
            imported.services.last(),
        )
        assertEquals(messages, imported.additions.of(ImportItemKind.SETTING).map { it.message })
        assertEquals(listOf(PlanItem("Unknown")), applied?.skippedItems)
        assertEquals(listOf(UnresolvedScenario("Orders", "gone")), applied?.unresolvedScenarios)
    }

    @Test
    fun aDocumentWithoutSettingsLeavesTheSettingsAloneAndReportsNothingAboutThem() {
        val settings = Settings(outboundProxy = proxy)

        val imported = Definitions(settings = settings).importing(servicesOf(orders))

        assertSame(settings, imported.definitions.settings)
        assertEquals(emptyList(), (imported.additions + imported.conflicts).of(ImportItemKind.SETTING))
    }

    @Test
    fun theUniversalErrorScenarioCanBeAScenarioThatItsServiceHadAlready() {
        val incoming = service("orders", scenario("OK", "ok", id = "scn-ok-2"), id = "svc-2")
        val settings = Settings(universalErrorScenario = ScenarioReference(incoming.id, ScenarioId("scn-ok-2")))

        val imported = servicesOf(orders).importing(Definitions(services = listOf(incoming), settings = settings))

        val designated = "The scenario 'ok' of the service 'Orders' was designated as the universal error scenario."
        assertEquals(ScenarioReference(orders.id, ok.id), imported.definitions.settings.universalErrorScenario)
        assertEquals(designated, imported.additions.messageOf(ImportItem.UNIVERSAL_ERROR_SCENARIO))
    }

    @Test
    fun aSettingThatNamesWhatWasNotAddedIsAConflict() {
        val error = scenario("error")
        val refused = service("Refused", error, mockUrl = "orders")
        val (gold, toQa2) = plan("Gold path", id = "plan-2") to twist("qa2", id = "twist-2")
        val settings = Settings(toQa2.id, null, ScenarioReference(refused.id, error.id), gold.id)
        val current = Definitions(listOf(orders), listOf(plan("gold path")), listOf(twist("QA2")))

        val imported = current.importing(Definitions(listOf(refused), listOf(gold), listOf(toQa2), settings))

        val messages =
            listOf(
                "The universal error scenario of the document was not designated: its scenario 'error' was not added.",
                "The twist configuration that the document enables was not enabled: the twist configuration 'qa2' was not added.",
                "The default plan of the document was not designated: the plan 'Gold path' was not added.",
            )
        assertEquals(current, imported.definitions)
        assertEquals(messages, imported.conflicts.of(ImportItemKind.SETTING).map { it.message })
    }

    @Test
    fun aSettingThatNamesWhatTheDocumentDoesNotHoldIsAConflict() {
        val settings = Settings(TwistId("twist-gone"), null, ScenarioReference(orders.id, ScenarioId("scn-gone")), PlanId("plan-gone"))

        val imported = Definitions.EMPTY.importing(Definitions(services = listOf(orders), settings = settings))

        val notDesignated = "of the document was not designated: the document does not hold the"
        val messages =
            listOf(
                "The universal error scenario $notDesignated scenario that it designates.",
                "The twist configuration that the document enables was not enabled: the document does not hold it.",
                "The default plan $notDesignated plan that it designates.",
            )
        assertEquals(Settings(), imported.definitions.settings)
        assertEquals(messages, imported.conflicts.map { it.message })
    }

    // The report

    @Test
    fun everyPartOfADocumentIsAnAdditionOrAConflict() {
        val current = Definitions(listOf(orders, service("Purchases", mockUrl = "taken")), listOf(plan("Gold path")), listOf(twist("qa2")))
        val refused = service("Refused", scenario("a"), scenario("b"), mockUrl = "taken")
        val merged = service("orders", scenario("ok", id = "scn-ok-2"), timeout, id = "svc-2")
        val added = service("Customers", scenario("c"))
        val plans = listOf(plan("gold path", id = "plan-2"), plan("Silver path"))
        val twists = listOf(twist("QA2", id = "twist-2"), twist("qa3"))
        val settings = Settings(twists.last().id, proxy, ScenarioReference(added.id, ScenarioId("scn-c")), plans.last().id)

        val report = current.importing(Definitions(listOf(refused, merged, added), plans, twists, settings)).value

        val settingNames =
            listOf(
                ImportItem.OUTBOUND_PROXY,
                ImportItem.UNIVERSAL_ERROR_SCENARIO,
                ImportItem.ENABLED_TWIST_CONFIGURATION,
                ImportItem.DEFAULT_PLAN,
            )
        assertEquals(listOf("orders/timeout", "Customers", "Customers/c", "Silver path", "qa3") + settingNames, report.additions.names)
        assertEquals(listOf("Refused", "Refused/a", "Refused/b", "orders/ok", "orders", "gold path", "QA2"), report.conflicts.names)
    }

    @Test
    fun aDocumentThatIsImportedTwiceAddsNothingTheSecondTime() {
        val document = Definitions(listOf(service("Orders", ok, timeout)), listOf(plan("Gold path")), listOf(twist("qa2")))
        val once = Definitions.EMPTY.importing(document).definitions

        val twice = once.importing(document)

        assertEquals(once, twice.definitions)
        assertEquals(emptyList(), twice.additions)
        assertEquals(listOf("Orders/ok", "Orders/timeout", "Orders", "Gold path", "qa2"), twice.conflicts.names)
    }

    // Tags

    @Test
    fun theTagsOfAnImportGoToWhatWasAddedAndToTheServicesThatWereMergedInto() {
        val sprint = Tags.of("sprint42")
        val current = Definitions(listOf(orders.copy(tags = Tags.of("existing"))), listOf(plan("Gold path")))
        val merged = service("Orders", scenario("ok", id = "scn-ok-2"), timeout.copy(tags = Tags.of("slow")), id = "svc-2")
        val added = service("Customers", scenario("c")).copy(tags = Tags.of("crm"))
        val plans = listOf(plan("gold path", id = "plan-2"), plan("Silver path"))

        val imported = current.importing(Definitions(listOf(merged, added), plans, listOf(twist("qa2"))), sprint).definitions

        val (mergedInto, customers) = imported.services
        assertEquals(Tags.of("existing sprint42"), mergedInto.tags)
        assertEquals(listOf(Tags.EMPTY, Tags.of("slow sprint42")), mergedInto.scenarios.map { it.tags })
        assertEquals(Tags.of("crm sprint42") to listOf(sprint), customers.tags to customers.scenarios.map { it.tags })
        assertEquals(listOf(Tags.EMPTY, sprint), imported.plans.map { it.tags })
    }

    @Test
    fun anImportWithoutTagsAddsNone() {
        val imported = Definitions.EMPTY.importing(Definitions(listOf(orders), listOf(plan("Gold path")))).definitions

        val service = imported.services.single()
        assertEquals(
            listOf(Tags.EMPTY, Tags.EMPTY, Tags.EMPTY),
            listOf(service.tags, service.scenarios.single().tags, imported.plans.single().tags),
        )
    }

    // The normal form

    @Test
    fun whatIsAddedIsStoredInItsNormalForm() {
        val nameless = Scenario(id = ScenarioId("scn-nameless"), name = "  ")
        val untidy = listOf(" http://api.example.com/orders ", "")
        val incoming = service("  Orders  ", nameless, mockUrl = " orders ").copy(description = " ", realUrls = untidy)
        val plans = listOf(plan("  ", PlanItem(" Orders ", " "), id = "plan-1"))
        val twists = listOf(TwistConfiguration(TwistId("twist-1"), " ", listOf(TwistPair("", "x"))))

        val imported = Definitions.EMPTY.importing(Definitions(listOf(incoming), plans, twists))

        val service = imported.services.single()
        assertEquals(listOf("Orders", "orders", null), listOf(service.name, service.mockUrl, service.description))
        assertEquals(listOf("http://api.example.com/orders"), service.realUrls)
        assertEquals("Scenario for Orders (name auto-generated)", service.scenarios.single().name)
        assertEquals(
            Plan(PlanId("plan-1"), "Plan (auto-generated-name)", items = listOf(PlanItem("Orders"))),
            imported.definitions.plans.single(),
        )
        assertEquals(TwistConfiguration(TwistId("twist-1"), "TwistInfo (auto-generated)"), imported.definitions.twists.single())
        assertEquals(listOf("Orders", "Orders/Scenario for Orders (name auto-generated)"), imported.additions.names.take(2))
    }
}
