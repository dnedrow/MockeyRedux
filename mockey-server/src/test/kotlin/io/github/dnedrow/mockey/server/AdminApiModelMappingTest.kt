package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.OutboundProxyAttributesDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import io.github.dnedrow.mockey.api.RecordedRequestDto
import io.github.dnedrow.mockey.api.RecordedResponseDto
import io.github.dnedrow.mockey.api.RecordedScenarioDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.core.definitions.Diagnostic
import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.definitions.ImportItem
import io.github.dnedrow.mockey.core.definitions.ImportItemKind
import io.github.dnedrow.mockey.core.definitions.ImportReport
import io.github.dnedrow.mockey.core.definitions.SearchResult
import io.github.dnedrow.mockey.core.definitions.SearchResultType
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.RecordedBody
import io.github.dnedrow.mockey.core.history.RecordedRequest
import io.github.dnedrow.mockey.core.history.RecordedResponse
import io.github.dnedrow.mockey.core.history.RecordedScenario
import io.github.dnedrow.mockey.core.history.RecordedService
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ProxyCredentials
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
import io.github.dnedrow.mockey.core.request.Cookie
import io.github.dnedrow.mockey.core.request.Parameter
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AdminApiModelMappingTest {
    @Test
    fun plansAndTwistConfigurationsMapBothWays() {
        val definitions = definitions()
        val plan = definitions.plans.single()
        val twist = definitions.twists.single()

        val items = listOf(PlanItemDto("orders", "created", ResponseModeDto.DYNAMIC))
        assertEquals(PlanDto("plan-1", "all orders", items = items), plan.toAdminDto())
        assertEquals(plan, plan.toAdminDto().toModel())
        val pairs = listOf(TwistPairDto("orders.example", "test.example"))
        assertEquals(TwistConfigurationDto("twist-1", "to test", pairs), twist.toAdminDto())
        assertEquals(twist, twist.toAdminDto().toModel())
    }

    @Test
    fun servicesMapBothWaysAndDeriveTheirEffectiveResponseMode() {
        val service = definitions().services.single()

        val dto = service.toAdminDto()

        assertEquals(ResponseModeDto.DYNAMIC, dto.effectiveResponseMode)
        assertEquals(service, dto.toModel())
    }

    @Test
    fun proxyResponsesExposeUsernameAndPasswordPresenceButNeverThePassword() {
        val proxy = OutboundProxy("http://proxy.example", true, ProxyCredentials("proxy-user", "secret-password"))

        val dto = proxy.toAdminDto()
        val json = AdminApiJson.instance.encodeToString(dto)

        assertEquals("proxy-user", dto.username)
        assertEquals(true, dto.passwordSet)
        assertContains(json, "proxy-user")
        assertFalse(json.contains("secret-password"))
        assertEquals(proxy, OutboundProxyAttributesDto("http://proxy.example", true, "proxy-user", "secret-password").toModel())
        assertEquals(OutboundProxyAttributesDto("http://proxy.example", true, "proxy-user", "secret-password"), proxy.attributes())
    }

    /** A body is any bytes, so it is stated in Base64, with the length of the whole body and whether the entry holds less. */
    @Test
    fun aHistoryEntryIsStatedWithEverythingThatItHoldsAndItsBodiesInBase64() {
        val dto = historyEntry().toAdminDto()

        assertEquals(
            HistoryEntryDto(
                id = 42,
                time = "2026-01-02T03:04:05.678912Z",
                clientAddress = "192.0.2.7",
                request =
                    RecordedRequestDto(
                        method = "POST",
                        url = "http://mockey.test:8080/service/orders",
                        query = "a=1&b=two%20words",
                        headers = listOf(HeaderDto("Content-Type", "application/json"), HeaderDto("X-Id", "1"), HeaderDto("x-id", "2")),
                        parameters = listOf(NameValueDto("a", "1"), NameValueDto("b", "two words")),
                        cookies = listOf(NameValueDto("session", "s-1")),
                        body = RecordedBodyDto("AP9B", size = 5, isCut = true),
                    ),
                response =
                    RecordedResponseDto(
                        201,
                        listOf(HeaderDto("Location", "/orders/7")),
                        RecordedBodyDto("Y3JlYXRlZCDinJM=", 11, false),
                    ),
                target = "orders",
                targetBeforeTwisting = "old-orders",
                service = RecordedServiceDto("service-1", "Orders", listOf("orders", "team-a")),
                responseMode = ResponseModeDto.DYNAMIC,
                scenario = RecordedScenarioDto("scenario-1", "created", listOf("happy")),
                outcome = "scenarioServed",
                inspectionMessages = listOf("The request has no header X-Trace."),
                explanation = "The scenario 'created' matched.",
                flagged = true,
            ),
            dto,
        )
    }

    @Test
    fun aHistoryEntryOfARequestThatNoServiceHandledHasNoServiceNoModeAndNoScenario() {
        val entry =
            HistoryEntry(
                id = HistoryEntryId(1),
                time = Instant.parse("2026-01-02T03:04:05Z"),
                request = RecordedRequest("GET", "http://mockey.test:8080/service/unknown"),
                response = RecordedResponse(404),
                target = "unknown",
                outcome = Outcome.NO_SERVICE_MATCHED,
            )

        val dto = entry.toAdminDto()

        assertEquals(
            HistoryEntryDto(
                id = 1,
                time = "2026-01-02T03:04:05Z",
                request = RecordedRequestDto("GET", "http://mockey.test:8080/service/unknown", body = RecordedBodyDto("", 0, false)),
                response = RecordedResponseDto(404, body = RecordedBodyDto("", 0, false)),
                target = "unknown",
                outcome = "noServiceMatched",
            ),
            dto,
        )
    }

    /** The names are part of the API, so they stay as they are when an outcome of the engine gets another name. */
    @Test
    fun everyOutcomeOfARequestHasANameOfItsOwnInTheApi() {
        val names = Outcome.entries.associateWith { historyEntry().copy(outcome = it).toAdminDto().outcome }

        assertEquals(
            mapOf(
                Outcome.SCENARIO_SERVED to "scenarioServed",
                Outcome.ERROR_SCENARIO_SERVED to "errorScenarioServed",
                Outcome.NO_SCENARIO_MATCHED to "noScenarioMatched",
                Outcome.PROXIED to "proxied",
                Outcome.UPSTREAM_FAILED to "upstreamFailed",
                Outcome.NO_SERVICE_MATCHED to "noServiceMatched",
            ),
            names,
        )
    }

    /** The names are part of the API as well: of the kinds of a diagnostic, of what a search finds, and of the parts of an import. */
    @Test
    fun everyKindOfADiagnosticOfASearchResultAndOfAnImportItemHasANameOfItsOwnInTheApi() {
        val service = ServiceId("service-1")
        val ofDiagnostics = DiagnosticKind.entries.associateWith { Diagnostic(it, service, "Orders", "A message.").toAdminDto().kind }
        val ofResults = SearchResultType.entries.associateWith { SearchResult(it, "id-1", "Orders").toAdminDto().type }
        val reportOf = { kind: ImportItemKind -> ImportReport(listOf(ImportItem(kind, "Orders", "A message.")), emptyList()).toAdminDto() }
        val ofItems = ImportItemKind.entries.associateWith(reportOf)

        assertEquals(
            mapOf(
                DiagnosticKind.SHARED_MOCK_URL to "sharedMockUrl",
                DiagnosticKind.SHARED_REAL_URL to "sharedRealUrl",
                DiagnosticKind.DUPLICATE_SCENARIOS to "duplicateScenarios",
                DiagnosticKind.UNKNOWN_INSPECTOR to "unknownInspector",
                DiagnosticKind.UNREADABLE_RULE_SET to "unreadableRuleSet",
                DiagnosticKind.SCHEMA_VIOLATION to "schemaViolation",
            ),
            ofDiagnostics,
        )
        assertEquals(
            mapOf(SearchResultType.SERVICE to "service", SearchResultType.SCENARIO to "scenario", SearchResultType.PLAN to "plan"),
            ofResults,
        )
        assertEquals(
            mapOf(
                ImportItemKind.SERVICE to "service",
                ImportItemKind.SCENARIO to "scenario",
                ImportItemKind.PLAN to "plan",
                ImportItemKind.TWIST_CONFIGURATION to "twistConfiguration",
                ImportItemKind.SETTING to "setting",
            ),
            ofItems.mapValues { (_, report) -> report.additions.single().kind },
        )
    }

    /** A diagnostic that names another service and scenarios states their identifiers, and a result of a search its service. */
    @Test
    fun aDiagnosticAndASearchResultAreStatedWithWhatTheyReferTo() {
        val scenarios = listOf(ScenarioId("scenario-1"), ScenarioId("scenario-2"))
        val orders = ServiceId("service-1")
        val diagnostic = Diagnostic(DiagnosticKind.SHARED_REAL_URL, orders, "Orders", "A message.", ServiceId("service-2"), scenarios)
        val result = SearchResult(SearchResultType.SCENARIO, "scenario-1", "created", orders, "Orders")

        val stated = listOf("scenario-1", "scenario-2")
        assertEquals(DiagnosticDto("sharedRealUrl", "service-1", "Orders", "A message.", "service-2", stated), diagnostic.toAdminDto())
        assertEquals(SearchResultDto("scenario", "scenario-1", "created", "service-1", "Orders"), result.toAdminDto())
    }

    private fun historyEntry(): HistoryEntry =
        HistoryEntry(
            id = HistoryEntryId(42),
            time = Instant.parse("2026-01-02T03:04:05.678912Z"),
            clientAddress = "192.0.2.7",
            request =
                RecordedRequest(
                    method = "POST",
                    url = "http://mockey.test:8080/service/orders",
                    query = "a=1&b=two%20words",
                    headers = listOf(Header("Content-Type", "application/json"), Header("X-Id", "1"), Header("x-id", "2")),
                    parameters = listOf(Parameter("a", "1"), Parameter("b", "two words")),
                    cookies = listOf(Cookie("session", "s-1")),
                    body = RecordedBody(byteArrayOf(0, -1, 65), size = 5),
                ),
            response = RecordedResponse(201, listOf(Header("Location", "/orders/7")), RecordedBody("created ✓".toByteArray())),
            target = "orders",
            targetBeforeTwisting = "old-orders",
            service = RecordedService(ServiceId("service-1"), "Orders", Tags.of("team-a Orders")),
            responseMode = ResponseMode.DYNAMIC,
            scenario = RecordedScenario(ScenarioId("scenario-1"), "created", Tags.of("happy")),
            outcome = Outcome.SCENARIO_SERVED,
            inspectionMessages = listOf("The request has no header X-Trace."),
            explanation = "The scenario 'created' matched.",
            flagged = true,
        )

    private fun definitions(): Definitions {
        val scenario =
            Scenario(
                id = ScenarioId("scenario-1"),
                name = "created",
                responseBody = "created body",
                statusCode = 201,
                matchCriterion = MatchCriterion.Text("create"),
                tags = Tags.of("Created", "orders"),
            )
        val service =
            Service(
                id = ServiceId("service-1"),
                name = "orders",
                mockUrl = "/service/orders",
                realUrls = listOf("https://orders.example"),
                responseMode = ResponseMode.DYNAMIC,
                tags = Tags.of("ORDERS"),
                scenarios = listOf(scenario),
                defaultScenarioId = scenario.id,
                requestRules = "{}",
                requestRulesEnabled = true,
                responseSchema = "{}",
                responseSchemaEnabled = true,
            )
        val plan = Plan(PlanId("plan-1"), "all orders", items = listOf(PlanItem(service.name, scenario.name, ResponseMode.DYNAMIC)))
        val twist = TwistConfiguration(TwistId("twist-1"), "to test", listOf(TwistPair("orders.example", "test.example")))
        return Definitions(
            services = listOf(service),
            plans = listOf(plan),
            twists = listOf(twist),
            settings =
                Settings(
                    enabledTwistId = twist.id,
                    outboundProxy = OutboundProxy("http://proxy.example", true, ProxyCredentials("proxy-user", "secret-password")),
                    universalErrorScenario = ScenarioReference(service.id, scenario.id),
                    defaultPlanId = plan.id,
                ),
        )
    }
}
