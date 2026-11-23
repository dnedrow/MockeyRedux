package io.github.dnedrow.mockey.parity

/** Every group of parity cases, for whoever runs the whole suite against a server. */
object ParitySuite {
    val groups: List<ParityCaseGroup> =
        RoutingCases.groups + ResponseCases.groups + ProxyCases.groups + RuleCases.groups + AutomationCases.groups

    val all: Map<String, ParityCase> = casesOf(groups)
}
