package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The settings that belong to the definitions. Each is `null` when it is not set: no twist configuration is
 * enabled, requests go to real services directly, no universal error scenario is designated, and no plan is
 * the default plan.
 */
@Serializable
public data class Settings(
    public val enabledTwistId: TwistId? = null,
    public val outboundProxy: OutboundProxy? = null,
    public val universalErrorScenario: ScenarioReference? = null,
    public val defaultPlanId: PlanId? = null,
)

/** Names one scenario among all services. */
@Serializable
public data class ScenarioReference(
    public val serviceId: ServiceId,
    public val scenarioId: ScenarioId,
)

/**
 * The proxy through which requests to real services are sent while [enabled] is on. [credentials] are held in
 * memory only: they are never serialized, so a value that was read back has none.
 */
@Serializable
public data class OutboundProxy(
    public val url: String,
    public val enabled: Boolean = false,
    @Transient public val credentials: ProxyCredentials? = null,
)

/** The user name and password for an outbound proxy. The text form of the credentials hides the password. */
public data class ProxyCredentials(
    public val username: String,
    public val password: String,
) {
    override fun toString(): String = "ProxyCredentials(username=$username, password=***)"
}
