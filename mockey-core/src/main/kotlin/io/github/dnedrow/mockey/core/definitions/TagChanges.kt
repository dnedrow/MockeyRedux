package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags

/** Returns every tag that a service, a scenario, or a plan of these definitions carries. */
internal fun Definitions.tagsInUse(): Tags {
    val ofServices = services.flatMap { service -> service.tags + service.scenarios.flatMap { it.tags } }
    return Tags.of(ofServices + plans.flatMap { it.tags })
}

/** The changes to the tags of all definitions at once. */
internal object TagChanges {
    /** Removes [tag] from every service, scenario, and plan. A text with several tags removes each of them. */
    fun remove(tag: String): Change<Unit> =
        { definitions ->
            val removed = Tags.of(tag)
            val services =
                definitions.services.map { service ->
                    service.copy(
                        tags = service.tags - removed,
                        scenarios = service.scenarios.map { it.copy(tags = it.tags - removed) },
                    )
                }
            val plans = definitions.plans.map { it.copy(tags = it.tags - removed) }
            ChangeResult.Changed(definitions.copy(services = services, plans = plans), Unit)
        }
}
