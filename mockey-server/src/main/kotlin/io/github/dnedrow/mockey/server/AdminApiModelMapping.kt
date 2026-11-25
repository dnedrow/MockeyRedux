package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.DefinitionDocumentDto
import io.github.dnedrow.mockey.api.DefinitionSettingsDto
import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.OutboundProxyDefinitionDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.OutboundProxyUpdateDto
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
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.ServiceAttributesDto
import io.github.dnedrow.mockey.api.ServiceDefinitionDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.TwistAttributesDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import io.github.dnedrow.mockey.core.definitions.DerivedRealUrl
import io.github.dnedrow.mockey.core.definitions.MergeReport
import io.github.dnedrow.mockey.core.definitions.PlanApplyReport
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.RecordedBody
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

internal fun Service.toDefinitionDto(): ServiceDefinitionDto =
    ServiceDefinitionDto(
        id = id.value,
        name = name,
        mockUrl = mockUrl,
        description = description,
        realUrls = realUrls,
        defaultRealUrlIndex = defaultRealUrlIndex,
        responseMode = responseMode.toDto(),
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

internal fun ServiceDefinitionDto.toModel(): Service =
    ServiceDto(
        id = id,
        name = name,
        mockUrl = mockUrl,
        description = description,
        realUrls = realUrls,
        defaultRealUrlIndex = defaultRealUrlIndex,
        responseMode = responseMode,
        delayMillis = delayMillis,
        tags = tags,
        scenarios = scenarios,
        defaultScenarioId = defaultScenarioId,
        errorScenarioId = errorScenarioId,
        requestRules = requestRules,
        requestRulesEnabled = requestRulesEnabled,
        inspectorName = inspectorName,
        responseSchema = responseSchema,
        responseSchemaEnabled = responseSchemaEnabled,
    ).toModel()

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

internal fun Definitions.toDefinitionDocumentDto(): DefinitionDocumentDto =
    DefinitionDocumentDto(
        services = services.map(Service::toDefinitionDto),
        plans = plans.map(Plan::toAdminDto),
        twists = twists.map(TwistConfiguration::toAdminDto),
        settings = settings.toDefinitionSettingsDto(),
    )

internal fun DefinitionDocumentDto.toModel(): Definitions {
    require(formatVersion == DEFINITION_FORMAT_VERSION) {
        "Unsupported definitions format version: $formatVersion."
    }
    return Definitions(
        services = services.map(ServiceDefinitionDto::toModel),
        plans = plans.map(PlanDto::toModel),
        twists = twists.map(TwistConfigurationDto::toModel),
        settings = settings.toModel(),
    )
}

internal fun OutboundProxy.toAdminDto(): OutboundProxyDto =
    OutboundProxyDto(
        url = url,
        enabled = enabled,
        username = credentials?.username,
        passwordSet = credentials != null,
    )

internal fun OutboundProxyUpdateDto.toModel(): OutboundProxy {
    require((username == null) == (password == null)) { "A proxy user name and password must be supplied together." }
    return OutboundProxy(
        url = url,
        enabled = enabled,
        credentials = username?.let { ProxyCredentials(it, requireNotNull(password)) },
    )
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

private fun Settings.toDefinitionSettingsDto(): DefinitionSettingsDto =
    DefinitionSettingsDto(
        enabledTwistId = enabledTwistId?.value,
        outboundProxy = outboundProxy?.let { OutboundProxyDefinitionDto(it.url, it.enabled) },
        universalErrorServiceId = universalErrorScenario?.serviceId?.value,
        universalErrorScenarioId = universalErrorScenario?.scenarioId?.value,
        defaultPlanId = defaultPlanId?.value,
    )

internal fun DefinitionSettingsDto.toModel(): Settings {
    require((universalErrorServiceId == null) == (universalErrorScenarioId == null)) {
        "A universal error service and scenario identifier must be supplied together."
    }
    return Settings(
        enabledTwistId = enabledTwistId?.let(::TwistId),
        outboundProxy = outboundProxy?.let { OutboundProxy(it.url, it.enabled) },
        universalErrorScenario =
            universalErrorServiceId?.let {
                ScenarioReference(ServiceId(it), ScenarioId(requireNotNull(universalErrorScenarioId)))
            },
        defaultPlanId = defaultPlanId?.let(::PlanId),
    )
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

private const val DEFINITION_FORMAT_VERSION = 1
