package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId

/**
 * The changes that create, update, delete, enable, and disable a twist configuration.
 *
 * A twist configuration is stored in a normal form. Its name has no surrounding whitespace, and a
 * configuration without a name gets a generated one. A pair without an origination text is left out, because
 * it could never apply. The texts of the other pairs stay exactly as they were given, and so does their order.
 *
 * At most one configuration is enabled: enabling one disables the one that was enabled before.
 */
internal object TwistChanges {
    /** The name of a twist configuration that was given none. */
    const val GENERATED_NAME = "TwistInfo (auto-generated)"

    /**
     * Creates a twist configuration. [define] gets a new configuration that has an identifier and nothing
     * else, and returns it with its name and its pairs. Creating a configuration does not enable it.
     */
    fun create(define: (TwistConfiguration) -> TwistConfiguration): Change<TwistConfiguration> =
        { definitions ->
            val twist = normalized(attributesChanged(TwistConfiguration(id = TwistId.generate(), name = ""), define))
            ChangeResult.Changed(definitions.copy(twists = definitions.twists + twist), twist)
        }

    /** Updates the twist configuration [id]. [change] gets the configuration as it is. */
    fun update(
        id: TwistId,
        change: (TwistConfiguration) -> TwistConfiguration,
    ): Change<TwistConfiguration> =
        { definitions ->
            val current = definitions.twist(id)
            if (current == null) {
                twistNotFound(id)
            } else {
                val twist = normalized(attributesChanged(current, change))
                ChangeResult.Changed(definitions.copy(twists = definitions.twists.map { if (it.id == id) twist else it }), twist)
            }
        }

    /** Deletes the twist configuration [id] and yields it. When it was enabled, no configuration is enabled. */
    fun delete(id: TwistId): Change<TwistConfiguration> =
        { definitions ->
            val twist = definitions.twist(id)
            if (twist == null) {
                twistNotFound(id)
            } else {
                val settings = definitions.settings
                val enabled = settings.enabledTwistId.takeUnless { it == id }
                val twists = definitions.twists.filter { it.id != id }
                ChangeResult.Changed(definitions.copy(twists = twists, settings = settings.copy(enabledTwistId = enabled)), twist)
            }
        }

    /** Enables the twist configuration [id], which disables the configuration that was enabled before. */
    fun enable(id: TwistId): Change<TwistConfiguration> =
        { definitions ->
            val twist = definitions.twist(id)
            if (twist == null) {
                twistNotFound(id)
            } else {
                ChangeResult.Changed(definitions.copy(settings = definitions.settings.copy(enabledTwistId = id)), twist)
            }
        }

    /** Disables the twist configuration [id]. A configuration that is not enabled stays as it is, and so do the others. */
    fun disable(id: TwistId): Change<TwistConfiguration> =
        { definitions ->
            val twist = definitions.twist(id)
            if (twist == null) {
                twistNotFound(id)
            } else {
                val settings = definitions.settings
                val enabled = settings.enabledTwistId.takeUnless { it == id }
                ChangeResult.Changed(definitions.copy(settings = settings.copy(enabledTwistId = enabled)), twist)
            }
        }

    /** Applies [change], which may set the name and the pairs of the configuration but not its identifier. */
    private fun attributesChanged(
        before: TwistConfiguration,
        change: (TwistConfiguration) -> TwistConfiguration,
    ): TwistConfiguration {
        val after = change(before)
        require(after.id == before.id) { "The identifier of a twist configuration cannot be changed." }
        return after
    }

    /** Returns [twist] in the normal form in which a twist configuration is stored. */
    fun normalized(twist: TwistConfiguration): TwistConfiguration =
        twist.copy(
            name = twist.name.trim().ifEmpty { GENERATED_NAME },
            pairs = twist.pairs.filter { it.origination.isNotEmpty() },
        )
}

internal fun Definitions.twist(id: TwistId): TwistConfiguration? = twists.firstOrNull { it.id == id }

/**
 * Returns the twist configuration whose name is [name], without regard to letter case and surrounding
 * whitespace, or `null` when there is none. Of several with that name, it is the one that was created first.
 */
internal fun Definitions.twistNamed(name: String): TwistConfiguration? =
    twists.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

internal fun twistNotFound(id: TwistId): ChangeResult.NotFound =
    ChangeResult.NotFound("No twist configuration has the identifier ${id.value}.")
