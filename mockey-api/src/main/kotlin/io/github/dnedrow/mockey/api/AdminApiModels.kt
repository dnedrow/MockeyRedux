package io.github.dnedrow.mockey.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a service answers a request. */
@Serializable
public enum class ResponseModeDto {
    @SerialName("proxy")
    PROXY,

    @SerialName("static")
    STATIC,

    @SerialName("dynamic")
    DYNAMIC,
}

/** What a request must contain for a scenario to answer it in dynamic mode. */
@Serializable
public sealed interface MatchCriterionDto {
    @Serializable
    @SerialName("none")
    public data object None : MatchCriterionDto

    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
    ) : MatchCriterionDto

    @Serializable
    @SerialName("rules")
    public data class Rules(
        public val source: String,
    ) : MatchCriterionDto
}

/** One ordered HTTP header. */
@Serializable
public data class HeaderDto(
    public val name: String,
    public val value: String,
)

/** One service scenario. Header order and repeated names are significant. */
@Serializable
public data class ScenarioDto(
    public val id: String,
    public val name: String,
    public val responseBody: String,
    public val statusCode: Int,
    public val headers: List<HeaderDto>,
    public val matchCriterion: MatchCriterionDto,
    public val methodRestriction: String? = null,
    public val delayMillis: Long = 0,
    public val tags: List<String> = emptyList(),
)

/** A service definition and its scenarios. */
@Serializable
public data class ServiceDto(
    public val id: String,
    public val name: String,
    public val mockUrl: String,
    public val description: String? = null,
    public val realUrls: List<String> = emptyList(),
    public val defaultRealUrlIndex: Int = 0,
    public val responseMode: ResponseModeDto = ResponseModeDto.PROXY,
    public val effectiveResponseMode: ResponseModeDto = ResponseModeDto.PROXY,
    public val delayMillis: Long = 0,
    public val tags: List<String> = emptyList(),
    public val scenarios: List<ScenarioDto> = emptyList(),
    public val defaultScenarioId: String? = null,
    public val errorScenarioId: String? = null,
    public val requestRules: String? = null,
    public val requestRulesEnabled: Boolean = false,
    public val inspectorName: String? = null,
    public val responseSchema: String? = null,
    public val responseSchemaEnabled: Boolean = false,
)

/**
 * The attributes of a scenario that a request sets. A request that creates a scenario states the ones that
 * differ from these defaults, and a request that updates a scenario states the ones that change.
 */
@Serializable
public data class ScenarioAttributesDto(
    public val name: String = "",
    public val responseBody: String = "",
    public val statusCode: Int = 200,
    public val headers: List<HeaderDto> = emptyList(),
    public val matchCriterion: MatchCriterionDto = MatchCriterionDto.None,
    public val methodRestriction: String? = null,
    public val delayMillis: Long = 0,
    public val tags: List<String> = emptyList(),
)

/**
 * The attributes of a service that a request sets. A request that creates a service states the ones that
 * differ from these defaults, and a request that updates a service states the ones that change. The
 * scenarios of a service are no attribute: each is created, updated, and deleted by itself.
 */
@Serializable
public data class ServiceAttributesDto(
    public val name: String = "",
    public val mockUrl: String = "",
    public val description: String? = null,
    public val realUrls: List<String> = emptyList(),
    public val defaultRealUrlIndex: Int = 0,
    public val responseMode: ResponseModeDto = ResponseModeDto.PROXY,
    public val delayMillis: Long = 0,
    public val tags: List<String> = emptyList(),
    public val defaultScenarioId: String? = null,
    public val errorScenarioId: String? = null,
    public val requestRules: String? = null,
    public val requestRulesEnabled: Boolean = false,
    public val inspectorName: String? = null,
    public val responseSchema: String? = null,
    public val responseSchemaEnabled: Boolean = false,
)

/** One scenario among those of all services, such as the universal error scenario. */
@Serializable
public data class ScenarioReferenceDto(
    public val serviceId: String,
    public val scenarioId: String,
)

/** A request to merge the source services into the service that the request addresses. */
@Serializable
public data class ServiceMergeRequestDto(
    public val sourceIds: List<String>,
)

/**
 * A request to derive real URLs for all services. Each real URL gives one URL for each replacement: the URL
 * in which what the regular expression finds is replaced.
 */
@Serializable
public data class RealUrlDerivationRequestDto(
    public val expression: String,
    public val replacements: List<String>,
)

/** A real URL that a derivation added to a service. */
@Serializable
public data class DerivedRealUrlDto(
    public val serviceId: String,
    public val serviceName: String,
    public val realUrl: String,
)

/** A persisted service definition without its derived effective response mode. */
@Serializable
public data class ServiceDefinitionDto(
    public val id: String,
    public val name: String,
    public val mockUrl: String,
    public val description: String? = null,
    public val realUrls: List<String> = emptyList(),
    public val defaultRealUrlIndex: Int = 0,
    public val responseMode: ResponseModeDto = ResponseModeDto.PROXY,
    public val delayMillis: Long = 0,
    public val tags: List<String> = emptyList(),
    public val scenarios: List<ScenarioDto> = emptyList(),
    public val defaultScenarioId: String? = null,
    public val errorScenarioId: String? = null,
    public val requestRules: String? = null,
    public val requestRulesEnabled: Boolean = false,
    public val inspectorName: String? = null,
    public val responseSchema: String? = null,
    public val responseSchemaEnabled: Boolean = false,
)

/** The settings of one service held by a plan. */
@Serializable
public data class PlanItemDto(
    public val serviceName: String,
    public val scenarioName: String? = null,
    public val responseMode: ResponseModeDto = ResponseModeDto.PROXY,
    public val delayMillis: Long = 0,
)

/** A named snapshot of service settings. */
@Serializable
public data class PlanDto(
    public val id: String,
    public val name: String,
    public val description: String? = null,
    public val tags: List<String> = emptyList(),
    public val items: List<PlanItemDto> = emptyList(),
)

/** One literal target replacement in a twist configuration. */
@Serializable
public data class TwistPairDto(
    public val origination: String,
    public val destination: String,
)

/** A named, ordered set of target replacements. */
@Serializable
public data class TwistConfigurationDto(
    public val id: String,
    public val name: String,
    public val pairs: List<TwistPairDto> = emptyList(),
)

/** A proxy setting in an exportable definition document; credentials are deliberately excluded. */
@Serializable
public data class OutboundProxyDefinitionDto(
    public val url: String,
    public val enabled: Boolean,
)

/** Persistent definition settings. Runtime-only state and proxy credentials are not included. */
@Serializable
public data class DefinitionSettingsDto(
    public val enabledTwistId: String? = null,
    public val outboundProxy: OutboundProxyDefinitionDto? = null,
    public val universalErrorServiceId: String? = null,
    public val universalErrorScenarioId: String? = null,
    public val defaultPlanId: String? = null,
)

/** All persisted definitions in the portable JSON document format. */
@Serializable
public data class DefinitionDocumentDto(
    public val formatVersion: Int = 1,
    public val services: List<ServiceDefinitionDto> = emptyList(),
    public val plans: List<PlanDto> = emptyList(),
    public val twists: List<TwistConfigurationDto> = emptyList(),
    public val settings: DefinitionSettingsDto = DefinitionSettingsDto(),
)

/** The outbound proxy settings that may be returned to an API client without revealing the password. */
@Serializable
public data class OutboundProxyDto(
    public val url: String,
    public val enabled: Boolean,
    public val username: String? = null,
    public val passwordSet: Boolean = false,
)

/** Credentials accepted when setting an outbound proxy. The password is input-only. */
@Serializable
public data class OutboundProxyUpdateDto(
    public val url: String,
    public val enabled: Boolean = false,
    public val username: String? = null,
    public val password: String? = null,
)

/** Active routing tags; an empty list means no filter. */
@Serializable
public data class TagFilterDto(
    public val tags: List<String> = emptyList(),
)

/** One request parameter or cookie. */
@Serializable
public data class NameValueDto(
    public val name: String,
    public val value: String,
)

/** A body captured in history, represented as Base64 so arbitrary bytes round-trip safely. */
@Serializable
public data class RecordedBodyDto(
    public val base64: String,
    public val size: Long,
    public val isCut: Boolean,
)

/** A request recorded in history. */
@Serializable
public data class RecordedRequestDto(
    public val method: String,
    public val url: String,
    public val query: String? = null,
    public val headers: List<HeaderDto> = emptyList(),
    public val parameters: List<NameValueDto> = emptyList(),
    public val cookies: List<NameValueDto> = emptyList(),
    public val body: RecordedBodyDto,
)

/** A response recorded in history. */
@Serializable
public data class RecordedResponseDto(
    public val status: Int,
    public val headers: List<HeaderDto> = emptyList(),
    public val body: RecordedBodyDto,
)

/** A service as it was when its request was recorded. */
@Serializable
public data class RecordedServiceDto(
    public val id: String,
    public val name: String,
    public val tags: List<String> = emptyList(),
)

/** A scenario as it was when its response was recorded. */
@Serializable
public data class RecordedScenarioDto(
    public val id: String,
    public val name: String,
    public val tags: List<String> = emptyList(),
)

/** One retained exchange at the mock endpoint. */
@Serializable
public data class HistoryEntryDto(
    public val id: Long,
    public val time: String,
    public val clientAddress: String? = null,
    public val request: RecordedRequestDto,
    public val response: RecordedResponseDto,
    public val target: String,
    public val targetBeforeTwisting: String? = null,
    public val service: RecordedServiceDto? = null,
    public val responseMode: ResponseModeDto? = null,
    public val scenario: RecordedScenarioDto? = null,
    public val outcome: String,
    public val inspectionMessages: List<String> = emptyList(),
    public val explanation: String? = null,
    public val flagged: Boolean = false,
)

/** An edit to a history replay; null fields leave the recorded request unchanged. */
@Serializable
public data class ReplayRequestDto(
    public val headers: List<HeaderDto>? = null,
    public val parameters: List<NameValueDto>? = null,
    public val bodyBase64: String? = null,
)

/** Statistics for one service's retained history entries. */
@Serializable
public data class ServiceStatisticsDto(
    public val serviceId: String,
    public val serviceName: String,
    public val count: Int,
    public val earliestEntryTime: String? = null,
)

/** Statistics for the retained history, optionally restricted to an inclusive time range. */
@Serializable
public data class HistoryStatisticsDto(
    public val services: List<ServiceStatisticsDto>,
    public val oldestEntryTime: String? = null,
    public val from: String? = null,
    public val to: String? = null,
)

/** An inspector available to services. */
@Serializable
public data class InspectorDto(
    public val name: String,
    public val global: Boolean,
)

/** A non-blocking definition diagnostic. */
@Serializable
public data class DiagnosticDto(
    public val kind: String,
    public val serviceId: String,
    public val serviceName: String,
    public val message: String,
    public val otherServiceId: String? = null,
    public val scenarioIds: List<String> = emptyList(),
)

/** A search result for a service, scenario, or plan. */
@Serializable
public data class SearchResultDto(
    public val type: String,
    public val id: String,
    public val name: String,
    public val serviceId: String? = null,
    public val serviceName: String? = null,
)

/** One invalid field in a request. */
@Serializable
public data class FieldErrorDto(
    public val field: String,
    public val message: String,
)

/** The JSON problem document returned for a failed API request. */
@Serializable
public data class ProblemDto(
    public val type: String,
    public val title: String,
    public val status: Int,
    public val detail: String,
    public val errors: List<FieldErrorDto> = emptyList(),
)

/** One scenario copied or skipped by a service merge. */
@Serializable
public data class MergedScenarioDto(
    public val serviceName: String,
    public val scenarioName: String,
)

/** The result of merging source services into a destination. */
@Serializable
public data class MergeReportDto(
    public val copied: List<MergedScenarioDto>,
    public val skipped: List<MergedScenarioDto>,
    public val sourcesDeleted: Boolean,
)

/** One plan item that could not be applied because its service was missing. */
@Serializable
public data class UnresolvedPlanScenarioDto(
    public val serviceName: String,
    public val scenarioName: String,
)

/** The result of applying a plan. */
@Serializable
public data class PlanApplyReportDto(
    public val plan: PlanDto,
    public val skippedItems: List<PlanItemDto>,
    public val unresolvedScenarios: List<UnresolvedPlanScenarioDto>,
)

/** One addition or conflict reported while importing definitions. */
@Serializable
public data class ImportItemDto(
    public val kind: String,
    public val name: String,
    public val message: String,
    public val serviceName: String? = null,
)

/** The complete report of a definitions import. */
@Serializable
public data class ImportReportDto(
    public val additions: List<ImportItemDto>,
    public val conflicts: List<ImportItemDto>,
    public val appliedDefaultPlan: PlanApplyReportDto? = null,
)

/** The result of checking service scenarios against a response JSON Schema. */
@Serializable
public sealed interface SchemaReportDto {
    @Serializable
    @SerialName("checked")
    public data class Checked(
        public val scenarios: List<ScenarioConformanceDto>,
    ) : SchemaReportDto

    @Serializable
    @SerialName("unsupportedDialect")
    public data class UnsupportedDialect(
        public val dialect: String,
    ) : SchemaReportDto

    @Serializable
    @SerialName("unusableSchema")
    public data class UnusableSchema(
        public val problems: List<String>,
    ) : SchemaReportDto
}

/** Schema conformance for one scenario. */
@Serializable
public data class ScenarioConformanceDto(
    public val scenarioId: String,
    public val scenarioName: String,
    public val conforms: Boolean,
    public val violations: List<String>,
)

/** Server status and runtime settings made available to admin clients. */
@Serializable
public data class SystemStatusDto(
    public val version: String,
    public val startTime: String,
    public val definitionsLocation: String,
    public val readOnly: Boolean,
    public val tagFilter: List<String>,
    public val enabledTwist: TwistConfigurationDto? = null,
    public val mockBaseUrl: String,
    public val legacyShimEnabled: Boolean,
)
