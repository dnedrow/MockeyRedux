package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.ImportItemDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.OutboundProxyAttributesDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanAttributesDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import io.github.dnedrow.mockey.api.RecordedRequestDto
import io.github.dnedrow.mockey.api.RecordedResponseDto
import io.github.dnedrow.mockey.api.RecordedScenarioDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioAttributesDto
import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.ServiceAttributesDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import io.github.dnedrow.mockey.api.TwistAttributesDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import io.github.dnedrow.mockey.core.definitions.DerivedRealUrl
import io.github.dnedrow.mockey.core.definitions.Diagnostic
import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.definitions.ImportItem
import io.github.dnedrow.mockey.core.definitions.ImportItemKind
import io.github.dnedrow.mockey.core.definitions.ImportReport
import io.github.dnedrow.mockey.core.definitions.MergeReport
import io.github.dnedrow.mockey.core.definitions.PlanApplyReport
import io.github.dnedrow.mockey.core.definitions.ScenarioConformance
import io.github.dnedrow.mockey.core.definitions.SchemaReport
import io.github.dnedrow.mockey.core.definitions.SearchResult
import io.github.dnedrow.mockey.core.definitions.SearchResultType
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryStatistics
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.RecordedBody
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
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.legacy.xml.LegacyImportReport
import java.util.Base64

internal fun Service.toAdminDto(): ServiceDto =
    ServiceDto(
        id = id.value,
        name = name,
        mockUrl = mockUrl,
        description = description,
        realUrls = realUrls,
        defaultRealUrlIndex = defaultRealUrlIndex,
        responseMode = responseMode.toDto(),
        effectiveResponseMode = effectiveResponseMode.toDto(),
        delayMillis = delayMillis,
        tags = tags.toList(),
        scenarios = scenarios.map(Scenario::toAdminDto),
        defaultScenarioId = defaultScenarioId?.value,
        errorScenarioId = errorScenarioId?.value,
        requestRules = requestRules,
        requestRulesEnabled = requestRulesEnabled,
        inspectorName = inspectorName,
        responseSchema = responseSchema,
        responseSchemaEnabled = responseSchemaEnabled,
    )

internal fun ServiceDto.toModel(): Service =
    Service(
        id = ServiceId(id),
        name = name,
        mockUrl = mockUrl,
        description = description,
        realUrls = realUrls,
        defaultRealUrlIndex = defaultRealUrlIndex,
        responseMode = responseMode.toModel(),
        delayMillis = delayMillis,
        tags = Tags.of(tags),
        scenarios = scenarios.map(ScenarioDto::toModel),
        defaultScenarioId = defaultScenarioId?.let(::ScenarioId),
        errorScenarioId = errorScenarioId?.let(::ScenarioId),
        requestRules = requestRules,
        requestRulesEnabled = requestRulesEnabled,
        inspectorName = inspectorName,
        responseSchema = responseSchema,
        responseSchemaEnabled = responseSchemaEnabled,
    )

/** Returns the attributes of the service that a request sets, as the admin API states them. */
internal fun Service.attributes(): ServiceAttributesDto =
    ServiceAttributesDto(
        name = name,
        mockUrl = mockUrl,
        description = description,
        realUrls = realUrls,
        defaultRealUrlIndex = defaultRealUrlIndex,
        responseMode = responseMode.toDto(),
        delayMillis = delayMillis,
        tags = tags.toList(),
        defaultScenarioId = defaultScenarioId?.value,
        errorScenarioId = errorScenarioId?.value,
        requestRules = requestRules,
        requestRulesEnabled = requestRulesEnabled,
        inspectorName = inspectorName,
        responseSchema = responseSchema,
        responseSchemaEnabled = responseSchemaEnabled,
    )

/**
 * Returns the service with [attributes] in the place of its own. Its identifier and its scenarios stay as
 * they are. An identifier among the attributes is not blank.
 */
internal fun Service.with(attributes: ServiceAttributesDto): Service =
    copy(
        name = attributes.name,
        mockUrl = attributes.mockUrl,
        description = attributes.description,
        realUrls = attributes.realUrls,
        defaultRealUrlIndex = attributes.defaultRealUrlIndex,
        responseMode = attributes.responseMode.toModel(),
        delayMillis = attributes.delayMillis,
        tags = Tags.of(attributes.tags),
        defaultScenarioId = attributes.defaultScenarioId?.let(::ScenarioId),
        errorScenarioId = attributes.errorScenarioId?.let(::ScenarioId),
        requestRules = attributes.requestRules,
        requestRulesEnabled = attributes.requestRulesEnabled,
        inspectorName = attributes.inspectorName,
        responseSchema = attributes.responseSchema,
        responseSchemaEnabled = attributes.responseSchemaEnabled,
    )

internal fun Scenario.toAdminDto(): ScenarioDto =
    ScenarioDto(
        id = id.value,
        name = name,
        responseBody = responseBody,
        statusCode = statusCode,
        headers = headers.map(Header::toAdminDto),
        matchCriterion = matchCriterion.toDto(),
        methodRestriction = methodRestriction,
        delayMillis = delayMillis,
        tags = tags.toList(),
    )

internal fun ScenarioDto.toModel(): Scenario =
    Scenario(
        id = ScenarioId(id),
        name = name,
        responseBody = responseBody,
        statusCode = statusCode,
        headers = headers.map(HeaderDto::toModel),
        matchCriterion = matchCriterion.toModel(),
        methodRestriction = methodRestriction,
        delayMillis = delayMillis,
        tags = Tags.of(tags),
    )

/** Returns the attributes of the scenario that a request sets, as the admin API states them. */
internal fun Scenario.attributes(): ScenarioAttributesDto =
    ScenarioAttributesDto(
        name = name,
        responseBody = responseBody,
        statusCode = statusCode,
        headers = headers.map(Header::toAdminDto),
        matchCriterion = matchCriterion.toDto(),
        methodRestriction = methodRestriction,
        delayMillis = delayMillis,
        tags = tags.toList(),
    )

/**
 * Returns the scenario with [attributes] in the place of its own. Its identifier stays as it is. A text
 * criterion among the attributes is not blank.
 */
internal fun Scenario.with(attributes: ScenarioAttributesDto): Scenario =
    copy(
        name = attributes.name,
        responseBody = attributes.responseBody,
        statusCode = attributes.statusCode,
        headers = attributes.headers.map(HeaderDto::toModel),
        matchCriterion = attributes.matchCriterion.toModel(),
        methodRestriction = attributes.methodRestriction,
        delayMillis = attributes.delayMillis,
        tags = Tags.of(attributes.tags),
    )

internal fun ScenarioReference.toAdminDto(): ScenarioReferenceDto = ScenarioReferenceDto(serviceId.value, scenarioId.value)

internal fun MergeReport.toAdminDto(): MergeReportDto =
    MergeReportDto(
        copied = copied.map { MergedScenarioDto(it.serviceName, it.scenarioName) },
        skipped = skipped.map { MergedScenarioDto(it.serviceName, it.scenarioName) },
        sourcesDeleted = sourcesDeleted,
    )

internal fun DerivedRealUrl.toAdminDto(): DerivedRealUrlDto = DerivedRealUrlDto(serviceId.value, serviceName, realUrl)

internal fun Plan.toAdminDto(): PlanDto =
    PlanDto(
        id = id.value,
        name = name,
        description = description,
        tags = tags.toList(),
        items = items.map(PlanItem::toAdminDto),
    )

internal fun PlanDto.toModel(): Plan =
    Plan(
        id = PlanId(id),
        name = name,
        description = description,
        tags = Tags.of(tags),
        items = items.map(PlanItemDto::toModel),
    )

/** Returns the attributes of the plan that a request sets, as the admin API states them. */
internal fun Plan.attributes(): PlanAttributesDto = PlanAttributesDto(name, description, tags.toList(), items.map(PlanItem::toAdminDto))

/** Returns the plan with [attributes] in the place of its own. Its identifier stays as it is. */
internal fun Plan.with(attributes: PlanAttributesDto): Plan =
    copy(
        name = attributes.name,
        description = attributes.description,
        tags = Tags.of(attributes.tags),
        items = attributes.items.map(PlanItemDto::toModel),
    )

internal fun PlanApplyReport.toAdminDto(): PlanApplyReportDto =
    PlanApplyReportDto(
        plan = plan.toAdminDto(),
        skippedItems = skippedItems.map(PlanItem::toAdminDto),
        unresolvedScenarios = unresolvedScenarios.map { UnresolvedPlanScenarioDto(it.serviceName, it.scenarioName) },
    )

internal fun TwistConfiguration.toAdminDto(): TwistConfigurationDto =
    TwistConfigurationDto(
        id = id.value,
        name = name,
        pairs = pairs.map(TwistPair::toAdminDto),
    )

/** Returns the attributes of the twist configuration that a request sets, as the admin API states them. */
internal fun TwistConfiguration.attributes(): TwistAttributesDto = TwistAttributesDto(name, pairs.map(TwistPair::toAdminDto))

/** Returns the twist configuration with [attributes] in the place of its own. Its identifier stays as it is. */
internal fun TwistConfiguration.with(attributes: TwistAttributesDto): TwistConfiguration =
    copy(name = attributes.name, pairs = attributes.pairs.map(TwistPairDto::toModel))

internal fun TwistConfigurationDto.toModel(): TwistConfiguration =
    TwistConfiguration(
        id = TwistId(id),
        name = name,
        pairs = pairs.map(TwistPairDto::toModel),
    )

/**
 * Returns what the admin API states of the outbound proxy, which is `null` when there are no settings: its
 * URL, whether it is enabled, the user name of its credentials, and whether they hold a password. The
 * password is no part of it.
 */
internal fun OutboundProxy?.toAdminDto(): OutboundProxyDto {
    val held = attributes()
    return OutboundProxyDto(held.url, held.enabled, held.username, passwordSet = held.password != null)
}

/**
 * Returns the attributes of the outbound proxy that a request sets, which is `null` when there are no
 * settings. They hold the password, which no answer holds: they are what the attributes of a request are
 * laid over. A user name or a password that is empty is none.
 */
internal fun OutboundProxy?.attributes(): OutboundProxyAttributesDto =
    OutboundProxyAttributesDto(
        url = this?.url.orEmpty(),
        enabled = this?.enabled == true,
        username = this?.credentials?.username?.takeIf { it.isNotEmpty() },
        password = this?.credentials?.password?.takeIf { it.isNotEmpty() },
    )

/**
 * Returns the settings of an outbound proxy with these attributes. The settings have credentials when the
 * attributes hold a user name or a password.
 */
internal fun OutboundProxyAttributesDto.toModel(): OutboundProxy {
    val credentials = if (username == null && password == null) null else ProxyCredentials(username.orEmpty(), password.orEmpty())
    return OutboundProxy(url, enabled, credentials)
}

/**
 * Returns the history entry as the admin API states it. The time is in ISO 8601. A body is in Base64,
 * because it can hold any bytes, and it is what the entry holds of the body, which can be its start alone.
 */
internal fun HistoryEntry.toAdminDto(): HistoryEntryDto =
    HistoryEntryDto(
        id = id.value,
        time = time.toString(),
        clientAddress = clientAddress,
        request =
            RecordedRequestDto(
                method = request.method,
                url = request.url,
                query = request.query,
                headers = request.headers.map(Header::toAdminDto),
                parameters = request.parameters.map { NameValueDto(it.name, it.value) },
                cookies = request.cookies.map { NameValueDto(it.name, it.value) },
                body = request.body.toAdminDto(),
            ),
        response = RecordedResponseDto(response.status, response.headers.map(Header::toAdminDto), response.body.toAdminDto()),
        target = target,
        targetBeforeTwisting = targetBeforeTwisting,
        service = service?.let { RecordedServiceDto(it.id.value, it.name, it.tags.toList()) },
        responseMode = responseMode?.toDto(),
        scenario = scenario?.let { RecordedScenarioDto(it.id.value, it.name, it.tags.toList()) },
        outcome = outcome.toDto(),
        inspectionMessages = inspectionMessages,
        explanation = explanation,
        flagged = flagged,
    )

/** Returns the statistics of the history as the admin API states them, with every time in ISO 8601. */
internal fun HistoryStatistics.toAdminDto(): HistoryStatisticsDto =
    HistoryStatisticsDto(
        services = services.map { ServiceStatisticsDto(it.serviceId.value, it.serviceName, it.count, it.earliestEntryTime?.toString()) },
        oldestEntryTime = oldestEntryTime?.toString(),
        from = from?.toString(),
        to = to?.toString(),
    )

/** Returns the diagnostic as the admin API states it. */
internal fun Diagnostic.toAdminDto(): DiagnosticDto =
    DiagnosticDto(
        kind = kind.toDto(),
        serviceId = serviceId.value,
        serviceName = serviceName,
        message = message,
        otherServiceId = otherServiceId?.value,
        scenarioIds = scenarioIds.map { it.value },
    )

/** Returns the result of a search as the admin API states it. */
internal fun SearchResult.toAdminDto(): SearchResultDto = SearchResultDto(type.toDto(), id, name, serviceId?.value, serviceName)

/** Returns the report of an import of a definitions document of this system as the admin API states it. */
internal fun ImportReport.toAdminDto(): ImportReportDto =
    ImportReportDto(
        additions = additions.map(ImportItem::toAdminDto),
        conflicts = conflicts.map(ImportItem::toAdminDto),
        appliedDefaultPlan = appliedDefaultPlan?.toAdminDto(),
    )

/**
 * Returns the report of an import of a document of Mockey 2.x as the admin API states it: the report of the
 * engine, and besides what the import could not read of the document, and the rule sets that are not well
 * formed among what it added.
 */
internal fun LegacyImportReport.toAdminDto(): ImportReportDto =
    imported.toAdminDto().copy(issues = issues.map { it.message }, unreadableRuleSets = unreadableRuleSets.map(Diagnostic::toAdminDto))

/** Returns what the response schema of a service says of its scenarios, as the admin API states it. */
internal fun SchemaReport.toAdminDto(): SchemaReportDto =
    when (this) {
        is SchemaReport.Checked -> SchemaReportDto.Checked(scenarios.map(ScenarioConformance::toAdminDto))
        is SchemaReport.UnsupportedDialect -> SchemaReportDto.UnsupportedDialect(dialect)
        is SchemaReport.UnusableSchema -> SchemaReportDto.UnusableSchema(problems)
    }

private fun ScenarioConformance.toAdminDto(): ScenarioConformanceDto =
    ScenarioConformanceDto(scenarioId.value, scenarioName, conforms, violations)

private fun ImportItem.toAdminDto(): ImportItemDto = ImportItemDto(kind.toDto(), name, message, serviceName)

/** Returns the kind of a diagnostic as the admin API names it. The names stay when those of the engine change. */
private fun DiagnosticKind.toDto(): String =
    when (this) {
        DiagnosticKind.SHARED_MOCK_URL -> "sharedMockUrl"
        DiagnosticKind.SHARED_REAL_URL -> "sharedRealUrl"
        DiagnosticKind.DUPLICATE_SCENARIOS -> "duplicateScenarios"
        DiagnosticKind.UNKNOWN_INSPECTOR -> "unknownInspector"
        DiagnosticKind.UNREADABLE_RULE_SET -> "unreadableRuleSet"
        DiagnosticKind.SCHEMA_VIOLATION -> "schemaViolation"
    }

/** Returns the kind of what a search found as the admin API names it. */
private fun SearchResultType.toDto(): String =
    when (this) {
        SearchResultType.SERVICE -> "service"
        SearchResultType.SCENARIO -> "scenario"
        SearchResultType.PLAN -> "plan"
    }

/** Returns the kind of a part of an imported document as the admin API names it. */
private fun ImportItemKind.toDto(): String =
    when (this) {
        ImportItemKind.SERVICE -> "service"
        ImportItemKind.SCENARIO -> "scenario"
        ImportItemKind.PLAN -> "plan"
        ImportItemKind.TWIST_CONFIGURATION -> "twistConfiguration"
        ImportItemKind.SETTING -> "setting"
    }

private fun PlanItem.toAdminDto(): PlanItemDto = PlanItemDto(serviceName, scenarioName, responseMode.toDto(), delayMillis)

private fun PlanItemDto.toModel(): PlanItem = PlanItem(serviceName, scenarioName, responseMode.toModel(), delayMillis)

private fun Header.toAdminDto(): HeaderDto = HeaderDto(name, value)

private fun HeaderDto.toModel(): Header = Header(name, value)

private fun RecordedBody.toAdminDto(): RecordedBodyDto = RecordedBodyDto(Base64.getEncoder().encodeToString(bytes), size, isCut)

/** Returns the outcome as the admin API names it. The names stay when those of the engine change. */
private fun Outcome.toDto(): String =
    when (this) {
        Outcome.SCENARIO_SERVED -> "scenarioServed"
        Outcome.ERROR_SCENARIO_SERVED -> "errorScenarioServed"
        Outcome.NO_SCENARIO_MATCHED -> "noScenarioMatched"
        Outcome.PROXIED -> "proxied"
        Outcome.UPSTREAM_FAILED -> "upstreamFailed"
        Outcome.NO_SERVICE_MATCHED -> "noServiceMatched"
    }

private fun TwistPair.toAdminDto(): TwistPairDto = TwistPairDto(origination, destination)

private fun TwistPairDto.toModel(): TwistPair = TwistPair(origination, destination)

private fun MatchCriterion.toDto(): MatchCriterionDto =
    when (this) {
        MatchCriterion.None -> MatchCriterionDto.None
        is MatchCriterion.Text -> MatchCriterionDto.Text(text)
        is MatchCriterion.Rules -> MatchCriterionDto.Rules(source)
    }

private fun MatchCriterionDto.toModel(): MatchCriterion =
    when (this) {
        MatchCriterionDto.None -> MatchCriterion.None
        is MatchCriterionDto.Text -> MatchCriterion.Text(text)
        is MatchCriterionDto.Rules -> MatchCriterion.Rules(source)
    }

private fun ResponseMode.toDto(): ResponseModeDto = ResponseModeDto.valueOf(name)

private fun ResponseModeDto.toModel(): ResponseMode = ResponseMode.valueOf(name)
