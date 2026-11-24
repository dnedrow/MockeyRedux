package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.definitions.twist
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.TwistConfiguration

/** The twist configuration that is enabled, or `null` when twisting is off. */
internal val Definitions.enabledTwist: TwistConfiguration?
    get() = settings.enabledTwistId?.let(::twist)

/**
 * Returns the target that a request for [target] is handled as: the target as the enabled twist configuration
 * rewrites it, or [target] itself when no configuration is enabled or no pair of it applies. Services are
 * looked up for the twisted target, and a request that is forwarded goes to it.
 */
internal fun Definitions.twisted(target: Target): Target = enabledTwist?.twisted(target) ?: target

/**
 * Returns [target] as this configuration rewrites it. The first pair applies whose origination text occurs in
 * the text of the target: every occurrence of that text is replaced with the destination text of the pair. No
 * other pair applies after that, also not one whose origination text occurs in the result.
 *
 * Both texts are taken as they are written, letter for letter and in their letter case. A pair without an
 * origination text never applies.
 */
internal fun TwistConfiguration.twisted(target: Target): Target {
    val pair = pairs.firstOrNull { it.origination.isNotEmpty() && it.origination in target.text } ?: return target
    return Target(target.text.replace(pair.origination, pair.destination))
}
