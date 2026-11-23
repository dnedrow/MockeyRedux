package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A response that a service can give. [headers] are sent in their order. [methodRestriction] is `null` when the
 * scenario accepts every HTTP method. A [delayMillis] of 0 leaves the delay to the service.
 */
@Serializable
public data class Scenario(
    public val id: ScenarioId,
    public val name: String,
    public val responseBody: String = "",
    public val statusCode: Int = 200,
    public val headers: List<Header> = emptyList(),
    public val matchCriterion: MatchCriterion = MatchCriterion.None,
    public val methodRestriction: String? = null,
    public val delayMillis: Long = 0,
    public val tags: Tags = Tags.EMPTY,
)

/** One header of a response. A list of headers keeps its order and can hold a name more than once. */
@Serializable
public data class Header(
    public val name: String,
    public val value: String,
)

/** What a request must contain for a scenario to answer it in dynamic mode. */
@Serializable
public sealed interface MatchCriterion {
    /** No criterion: the scenario is never selected in dynamic mode. */
    @Serializable
    @SerialName("none")
    public data object None : MatchCriterion

    /** The request must contain [text]. A text criterion is never blank. */
    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
    ) : MatchCriterion {
        init {
            require(text.isNotBlank()) { "A text criterion must not be blank." }
        }
    }

    /**
     * The request must satisfy a rule set. [source] is the JSON text of the rule set exactly as it was entered,
     * which can be a text that is not a well-formed rule set.
     */
    @Serializable
    @SerialName("rules")
    public data class Rules(
        public val source: String,
    ) : MatchCriterion
}
