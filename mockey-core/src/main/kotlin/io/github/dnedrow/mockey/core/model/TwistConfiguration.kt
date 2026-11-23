package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.Serializable

/** A named list of replacements for request targets. Of its [pairs], the first that applies to a target is used. */
@Serializable
public data class TwistConfiguration(
    public val id: TwistId,
    public val name: String,
    public val pairs: List<TwistPair> = emptyList(),
)

/** One replacement: a target that contains [origination] gets [destination] in its place. */
@Serializable
public data class TwistPair(
    public val origination: String,
    public val destination: String,
)
