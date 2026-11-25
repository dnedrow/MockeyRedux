package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.DefinitionDocumentDto
import io.github.dnedrow.mockey.api.DefinitionSettingsDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.OutboundProxyDefinitionDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.OutboundProxyUpdateDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDefinitionDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
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

internal fun TwistConfiguration.toAdminDto(): TwistConfigurationDto =
    TwistConfigurationDto(
        id = id.value,
        name = name,
        pairs = pairs.map(TwistPair::toAdminDto),
    )

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
