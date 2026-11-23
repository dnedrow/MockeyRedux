package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mocked service. [mockUrl] is the URL pattern under which clients call it, and [realUrls] are the URLs of
 * the real service, of which the one at [defaultRealUrlIndex] is the default. [scenarios] are in the order in
 * which they were created.
 *
 * [requestRules] is the JSON text of the rule set that inspects requests, exactly as it was entered, and
 * [responseSchema] is the text of the JSON Schema for response bodies. Each takes effect only while its
 * enabled flag is on.
 */
@Serializable
public data class Service(
    public val id: ServiceId,
    public val name: String,
    public val mockUrl: String,
    public val description: String? = null,
    public val realUrls: List<String> = emptyList(),
    public val defaultRealUrlIndex: Int = 0,
    public val responseMode: ResponseMode = ResponseMode.PROXY,
    public val delayMillis: Long = 0,
    public val tags: Tags = Tags.EMPTY,
    public val scenarios: List<Scenario> = emptyList(),
    public val defaultScenarioId: ScenarioId? = null,
    public val errorScenarioId: ScenarioId? = null,
    public val requestRules: String? = null,
    public val requestRulesEnabled: Boolean = false,
    public val inspectorName: String? = null,
    public val responseSchema: String? = null,
    public val responseSchemaEnabled: Boolean = false,
) {
    /** The mode in which the service answers. Without scenarios that is proxy, whatever [responseMode] says. */
    public val effectiveResponseMode: ResponseMode
        get() = if (scenarios.isEmpty()) ResponseMode.PROXY else responseMode
}

/** How a service answers a request. */
@Serializable
public enum class ResponseMode {
    /** The request is forwarded to the real service. */
    @SerialName("proxy")
    PROXY,

    /** The default scenario of the service answers. */
    @SerialName("static")
    STATIC,

    /** The scenario that matches the request best answers. */
    @SerialName("dynamic")
    DYNAMIC,
}
