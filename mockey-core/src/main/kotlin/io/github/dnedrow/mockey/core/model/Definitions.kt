package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.Serializable

/**
 * Everything that is defined: the services with their scenarios, the plans, the twist configurations, and the
 * settings. A value never changes: a change yields a new value. [services] are in the order in which they were
 * created.
 */
@Serializable
public data class Definitions(
    public val services: List<Service> = emptyList(),
    public val plans: List<Plan> = emptyList(),
    public val twists: List<TwistConfiguration> = emptyList(),
    public val settings: Settings = Settings(),
) {
    public companion object {
        /** Definitions without services, plans, twist configurations, or settings. */
        public val EMPTY: Definitions = Definitions()
    }
}
