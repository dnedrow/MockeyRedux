package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.TwistChanges
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId

/**
 * What a program does with the twist configurations of an engine. The configuration that is enabled rewrites
 * the target of every request before a service is looked up for it. The operations return their results as
 * those of [ServiceOperations] do.
 */
public class TwistOperations internal constructor(
    private val engine: MockeyEngine,
) {
    /**
     * Creates a twist configuration. [define] gets a new configuration that has an identifier and nothing
     * else, and returns it with its name and its pairs. It must leave the identifier as it is. A new
     * configuration is not enabled.
     */
    public suspend fun create(define: (TwistConfiguration) -> TwistConfiguration): ChangeResult<TwistConfiguration> =
        engine.change(TwistChanges.create(define))

    /** Updates the twist configuration [id]. [change] gets the configuration as it is and returns it with its new name and pairs. */
    public suspend fun update(
        id: TwistId,
        change: (TwistConfiguration) -> TwistConfiguration,
    ): ChangeResult<TwistConfiguration> = engine.change(TwistChanges.update(id, change))

    /** Deletes the twist configuration [id], and returns it. When it was enabled, none is enabled after that. */
    public suspend fun delete(id: TwistId): ChangeResult<TwistConfiguration> = engine.change(TwistChanges.delete(id))

    /** Enables the twist configuration [id], and returns it. The configuration that was enabled before is enabled no longer. */
    public suspend fun enable(id: TwistId): ChangeResult<TwistConfiguration> = engine.change(TwistChanges.enable(id))

    /** Disables the twist configuration [id], and returns it. Nothing changes when it is not the one that is enabled. */
    public suspend fun disable(id: TwistId): ChangeResult<TwistConfiguration> = engine.change(TwistChanges.disable(id))
}
