package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.Serializable

/** A named snapshot of the settings of several services, which can be applied to put them back into that state. */
@Serializable
public data class Plan(
    public val id: PlanId,
    public val name: String,
    public val description: String? = null,
    public val tags: Tags = Tags.EMPTY,
    public val items: List<PlanItem> = emptyList(),
)

/**
 * The settings that a plan holds for one service. A plan refers to the service and to its default scenario by
 * name. [scenarioName] is `null` for a service that had no default scenario when the plan was saved.
 */
@Serializable
public data class PlanItem(
    public val serviceName: String,
    public val scenarioName: String? = null,
    public val responseMode: ResponseMode = ResponseMode.PROXY,
    public val delayMillis: Long = 0,
)
