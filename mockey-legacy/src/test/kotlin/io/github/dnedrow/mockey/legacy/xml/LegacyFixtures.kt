package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.TwistId
import java.nio.file.Files
import java.nio.file.Path

/**
 * The definitions that legacy Mockey at commit `88e31ed` wrote for the tests of the import. How it wrote them
 * is in `docs/parity/running-legacy.md`.
 */
internal object LegacyFixtures {
    /** The definitions directory that legacy wrote in writable mode. */
    val directory: Path = resource("/legacy/directory")

    /** The export document of the instance that wrote [directory]: the same definitions in one document. */
    val export: String = Files.readString(resource("/legacy/export.xml"))

    /** A copy of `web/sample.xml` of legacy Mockey, the document that it brings as an example. */
    val sample: String = Files.readString(resource("/legacy/sample.xml"))

    private fun resource(name: String): Path {
        val url = checkNotNull(LegacyFixtures::class.java.getResource(name)) { "The test resource $name is missing." }
        return Path.of(url.toURI())
    }
}

/**
 * Returns these definitions with identifiers that say where a definition is: the place of a service, of a
 * scenario in its service, of a plan, and of a twist configuration. Every import gives its definitions new
 * identifiers, so the definitions of two imports are compared under these.
 */
internal fun Definitions.withPlacesAsIdentifiers(): Definitions {
    val serviceIds = services.mapIndexed { place, service -> service.id to ServiceId("service ${place + 1}") }.toMap()
    val scenarioIds =
        services
            .flatMap { service ->
                val ofService = serviceIds.getValue(service.id).value
                service.scenarios.mapIndexed { place, scenario -> scenario.id to ScenarioId("$ofService, scenario ${place + 1}") }
            }.toMap()
    val planIds = plans.mapIndexed { place, plan -> plan.id to PlanId("plan ${place + 1}") }.toMap()
    val twistIds = twists.mapIndexed { place, twist -> twist.id to TwistId("twist ${place + 1}") }.toMap()
    return Definitions(
        services =
            services.map { service ->
                service.copy(
                    id = serviceIds.getValue(service.id),
                    scenarios = service.scenarios.map { it.copy(id = scenarioIds.getValue(it.id)) },
                    defaultScenarioId = service.defaultScenarioId?.let(scenarioIds::getValue),
                    errorScenarioId = service.errorScenarioId?.let(scenarioIds::getValue),
                )
            },
        plans = plans.map { it.copy(id = planIds.getValue(it.id)) },
        twists = twists.map { it.copy(id = twistIds.getValue(it.id)) },
        settings =
            settings.copy(
                enabledTwistId = settings.enabledTwistId?.let(twistIds::getValue),
                universalErrorScenario =
                    settings.universalErrorScenario?.let {
                        ScenarioReference(serviceIds.getValue(it.serviceId), scenarioIds.getValue(it.scenarioId))
                    },
                defaultPlanId = settings.defaultPlanId?.let(planIds::getValue),
            ),
    )
}
