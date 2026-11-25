package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import io.github.dnedrow.mockey.api.RecordedRequestDto
import io.github.dnedrow.mockey.api.RecordedResponseDto
import io.github.dnedrow.mockey.api.RecordedScenarioDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import java.util.Base64

/** What a server holds in the tests of the UI: one of everything that the admin API states, as its wire type. */
internal object Samples {
    private val asJson = listOf(HeaderDto("Content-Type", "application/json"))

    /** The scenarios of [customers]. */
    val found = ScenarioDto("sc-found", "found", """{"name":"Ada"}""", 200, asJson, MatchCriterionDto.None, tags = listOf("happy"))
    val missing = ScenarioDto("sc-missing", "missing", "no such customer", 404, emptyList(), MatchCriterionDto.Text("nobody"), "GET", 250)

    /** A service in static mode with two scenarios, of which [found] is the default one. */
    val customers =
        ServiceDto(
            id = "svc-customers",
            name = "Customers",
            mockUrl = "customer/{id}",
            description = "The customers of the shop",
            responseMode = ResponseModeDto.STATIC,
            effectiveResponseMode = ResponseModeDto.STATIC,
            delayMillis = 5,
            tags = listOf("team-a"),
            scenarios = listOf(found, missing),
            defaultScenarioId = found.id,
            errorScenarioId = missing.id,
        )

    /** A service in proxy mode with a real URL and without a scenario. */
    val orders = ServiceDto("svc-orders", "Orders", "orders", realUrls = listOf("http://qa1.example.com/orders"))

    private val planItem = PlanItemDto("Customers", "missing", ResponseModeDto.STATIC, 250)
    val plan = PlanDto("plan-night", "Night", "What the night shift needs", listOf("nightly"), listOf(planItem))

    val twist = TwistConfigurationDto("twist-german", "German paths", listOf(TwistPairDto("kunde", "customer")))

    val proxy = OutboundProxyDto("http://proxy.example.com:3128", enabled = true, username = "mockey", passwordSet = true)

    /** The exchange of a request that [customers] answered with [found]. */
    val entry =
        HistoryEntryDto(
            id = 7,
            time = "2026-01-02T03:04:05.678Z",
            clientAddress = "192.0.2.7",
            request =
                RecordedRequestDto(
                    method = "GET",
                    url = "http://mockey.test:8080/service/customer/7",
                    headers = listOf(HeaderDto("Accept", "*/*")),
                    body = RecordedBodyDto("", 0, isCut = false),
                ),
            response = RecordedResponseDto(200, asJson, body(found.responseBody)),
            target = "customer/7",
            service = RecordedServiceDto(customers.id, customers.name, customers.tags),
            responseMode = ResponseModeDto.STATIC,
            scenario = RecordedScenarioDto(found.id, found.name, found.tags),
            outcome = "scenarioServed",
        )

    val statistics = HistoryStatisticsDto(listOf(ServiceStatisticsDto(customers.id, customers.name, 3, entry.time)), entry.time)

    val inspector = InspectorDto("Audit", global = true)

    val diagnostic = DiagnosticDto("duplicateScenarios", customers.id, customers.name, "The scenarios 'found' and 'missing' answer alike.")

    val searchResult = SearchResultDto("scenario", found.id, found.name, customers.id, customers.name)

    val mergeReport =
        MergeReportDto(
            copied = listOf(MergedScenarioDto("Clients", "found")),
            skipped = listOf(MergedScenarioDto("Clients", "missing")),
            sourcesDeleted = true,
        )

    val derived = DerivedRealUrlDto(orders.id, orders.name, "http://qa2.example.com/orders")

    val applyReport = PlanApplyReportDto(plan, listOf(PlanItemDto("Gone")), listOf(UnresolvedPlanScenarioDto("Customers", "lost")))

    val importReport =
        ImportReportDto(
            additions = listOf(ImportItemDto("service", "Customers", "The service 'Customers' was added with 2 scenarios.")),
            conflicts = listOf(ImportItemDto("scenario", "found", "The scenario 'found' is there already.", "Customers")),
        )

    val schemaReport: SchemaReportDto =
        SchemaReportDto.Checked(listOf(ScenarioConformanceDto(found.id, found.name, conforms = true, violations = emptyList())))

    /** Returns [text] as the body of a history entry holds it. */
    fun body(text: String): RecordedBodyDto {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return RecordedBodyDto(Base64.getEncoder().encodeToString(bytes), bytes.size.toLong(), isCut = false)
    }
}
