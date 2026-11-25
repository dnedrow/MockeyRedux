package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Definitions

/** Returns what [definitions] hold, in the words of a message: how many services, scenarios, plans, and twist configurations. */
internal fun contentOf(definitions: Definitions): String {
    if (definitions == Definitions.EMPTY) return "no definitions"
    val scenarios = definitions.services.sumOf { it.scenarios.size }
    return "${counted(definitions.services.size, "service")} with ${counted(scenarios, "scenario")}, " +
        "${counted(definitions.plans.size, "plan")}, and ${counted(definitions.twists.size, "twist configuration")}"
}

private fun counted(
    count: Int,
    what: String,
): String = if (count == 1) "1 $what" else "$count ${what}s"
