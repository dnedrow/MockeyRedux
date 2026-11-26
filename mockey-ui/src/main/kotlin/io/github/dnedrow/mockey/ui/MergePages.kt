package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.checkBoxInput
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul

/**
 * The pages that merge other services into a service: the form that chooses the services to merge, the
 * confirmation step, and the page that shows the merge report.
 *
 * The form and the confirmation step share one path. A browser sends the form with the method GET, so the
 * choice goes into the address, and the confirmation step asks about exactly the services that its address
 * names. Nothing is merged before the form of the confirmation step is submitted.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val MergeArea =
    Area { routing, ui ->
        routing.getAndPost(MergePages.PAGE) {
            val id = call.partOfAddress(ServicesPages.ID)
            val parameters = call.request.queryParameters
            val chosen = parameters.getAll(MergePages.SOURCE).orEmpty().distinct()
            val confirming = parameters[MergePages.STEP] == MergePages.CONFIRM
            val ask: suspend (View, SystemStatusDto) -> Page = { view, _ ->
                val destination = ui.client.getService(id)
                MergePages.merge(view, destination, ui.client.listServices(), chosen, confirming)
            }
            ui.confirm(call, ask) {
                if (chosen.isEmpty()) throw InvalidInput(MergePages.SOURCES_API, MergePages.NO_SOURCE)
                val destination = ui.client.getService(id)
                val report = ui.client.mergeServices(id, chosen)
                Outcome.Report(MergePages.merged(destination, report), report.copied.size + report.skipped.size)
            }
        }
    }

/** The pages that merge other services into a service. */
internal object MergePages {
    /** The path of the form that chooses the services to merge, and of the confirmation step that follows it. */
    const val PAGE = "/service/merge"

    /** The address parameter that names a service to merge by its identifier. An address can have it more than once. */
    const val SOURCE = "source"

    /** The address parameter that asks for the confirmation step, with the value [CONFIRM]. */
    const val STEP = "step"
    const val CONFIRM = "confirm"

    /** The member of a merge request that holds the services to merge. Messages about the choice name it. */
    const val SOURCES_API = "sourceIds"

    /** The message for a merge without a service to merge. */
    const val NO_SOURCE = "Choose at least one service to merge."

    private val BY_NAME = compareBy<ServiceDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    /** Returns the address of the form that chooses the services to merge into the service [serviceId]. */
    fun addressOf(serviceId: String): String = Address.of(PAGE, ServicesPages.ID to serviceId)

    /**
     * Returns the page for a merge into [destination]. [services] are all services of the server, and
     * [chosen] are the identifiers of the services that the address names.
     *
     * The page is the form that chooses the services, with the boxes of the [chosen] ones checked. If the
     * address asks for the confirmation step ([confirming]) and every chosen service can be merged, the
     * page asks whether to merge them instead. If the choice is empty, or a chosen service isn't there any
     * longer, the form says so.
     */
    fun merge(
        view: View,
        destination: ServiceDto,
        services: List<ServiceDto>,
        chosen: List<String>,
        confirming: Boolean,
    ): Page {
        val others = services.filter { it.id != destination.id }.sortedWith(BY_NAME)
        val sources = others.filter { it.id in chosen }
        val problem =
            when {
                !confirming -> null
                sources.isEmpty() -> NO_SOURCE
                sources.size < chosen.size -> SOURCE_GONE
                else -> return confirmation(view, destination, sources)
            }
        val errors = listOfNotNull(problem?.let { FieldErrorDto(SOURCES_API, it) })
        return choice(destination, others, FormState(mapOf(SOURCE to sources.map { it.id }), errors))
    }

    /**
     * Returns the page that shows what merging services into [destination] did, which is [report]: the
     * scenarios that were copied, the ones that were skipped, and whether the merged services were deleted.
     */
    fun merged(
        destination: ServiceDto,
        report: MergeReportDto,
    ): Page =
        Page("Merge into ${destination.name}") {
            val into = briefly(destination.name)
            p { +"${countsOf(report)} ${sourcesOf(report, into)}" }
            if (report.copied.isNotEmpty()) {
                h2 { +"Copied scenarios" }
                scenariosOf(report.copied)
            }
            if (report.skipped.isNotEmpty()) {
                h2 { +"Skipped scenarios" }
                p { +"$into already has a scenario with the same name and response body as each of these." }
                scenariosOf(report.skipped)
            }
            p(classes = "actions") {
                a(href = ServicePages.addressOf(destination.id)) { +"Back to the service" }
                a(href = ServicesPages.OVERVIEW) { +"Services" }
            }
        }

    /** Returns the form that chooses which of [others] to merge into [destination]. The form is displayed with [state]. */
    private fun choice(
        destination: ServiceDto,
        others: List<ServiceDto>,
        state: FormState,
    ): Page =
        Page("Merge services into ${destination.name}") {
            p { +"$ABOUT_MERGE ${skipRule(THIS_SERVICE)}" }
            p { +sourcesRule(THIS_SERVICE) }
            if (others.isEmpty()) {
                p { +NO_OTHERS }
                p(classes = "actions") { a(href = ServicePages.addressOf(destination.id)) { +"Back to the service" } }
                return@Page
            }
            getForm(PAGE) {
                hiddenInput(name = ServicesPages.ID) { value = destination.id }
                hiddenInput(name = STEP) { value = CONFIRM }
                val marked = state.all(SOURCE).toSet()
                fieldGroup(state, SOURCE, "Services to merge", listOf(SOURCES_API), SOURCES_HINT) {
                    ul(classes = "plain") {
                        for ((index, service) in others.withIndex()) {
                            li {
                                checkBoxInput(name = SOURCE) {
                                    id = "f-$SOURCE-$index"
                                    value = service.id
                                    checked = service.id in marked
                                }
                                +" "
                                label {
                                    htmlFor = "f-$SOURCE-$index"
                                    +service.name
                                }
                                +" "
                                span(classes = "count") { +"(${scenarioCountOf(service)})" }
                            }
                        }
                    }
                }
                p(classes = "actions") {
                    submit("Continue")
                    +" "
                    a(href = ServicePages.addressOf(destination.id)) { +"Cancel" }
                }
            }
        }

    /** Returns the page that asks whether to merge [sources] into [destination]. */
    private fun confirmation(
        view: View,
        destination: ServiceDto,
        sources: List<ServiceDto>,
    ): Page {
        val merged = sources.singleOrNull()?.name ?: "${sources.size} services"
        val into = briefly(destination.name)
        val names = sources.joinToString(", ") { briefly(it.name) }
        val copies = "The scenarios of these services are copied into $into: $names."
        val action = Address.of(PAGE, ServicesPages.ID to destination.id, STEP to CONFIRM, *chosenOf(sources))
        val consequences = listOf(copies, skipRule(into), sourcesRule(into))
        val back = ServicePages.addressOf(destination.id)
        return Confirmation.page(view, "Merge $merged into ${destination.name}?", consequences, "Merge the services", action, back)
    }

    /** Returns the address parameters that name [sources] as the services to merge. */
    private fun chosenOf(sources: List<ServiceDto>): Array<Pair<String, String?>> = sources.map { SOURCE to it.id }.toTypedArray()

    /** Shows [scenarios], each with the name of its service in front of its own name. */
    private fun FlowContent.scenariosOf(scenarios: List<MergedScenarioDto>) {
        ul { scenarios.forEach { li { +"${it.serviceName}: ${it.scenarioName}" } } }
    }

    /** Returns the number of scenarios of [service] in words, for example "2 scenarios". */
    private fun scenarioCountOf(service: ServiceDto): String =
        when (service.scenarios.size) {
            0 -> "no scenario"
            1 -> "1 scenario"
            else -> "${service.scenarios.size} scenarios"
        }

    /** Returns the sentence that says how many scenarios the merge copied and skipped. */
    private fun countsOf(report: MergeReportDto): String {
        val copied = if (report.copied.size == 1) "1 scenario" else "${report.copied.size} scenarios"
        return "The merge copied $copied and skipped ${report.skipped.size}."
    }

    /** Returns the sentences that say what became of the merged services. [into] names the service that they were merged into. */
    private fun sourcesOf(
        report: MergeReportDto,
        into: String,
    ): String =
        when {
            report.sourcesDeleted -> "The merged services were deleted. Their tags and real URLs now belong to $into."
            report.skipped.isNotEmpty() -> "$SOURCES_KEPT Their tags were added to $into."
            else -> "No service was merged."
        }

    /** Returns the sentences about the scenarios that a merge skips, and about the tags. [into] names the service that is merged into. */
    private fun skipRule(into: String): String =
        "A scenario is skipped if $into already has a scenario with the same name and response body. " +
            "The tags of the merged services are added to $into."

    /** Returns the sentences about what becomes of the merged services. [into] names the service that is merged into. */
    private fun sourcesRule(into: String): String =
        "If every scenario is copied, the merged services are deleted, and their real URLs move to $into. " +
            "If a scenario is skipped, the merged services stay as they are."

    private const val THIS_SERVICE = "this service"
    private const val ABOUT_MERGE = "A merge copies the scenarios of other services into this service."
    private const val NO_OTHERS = "There is no other service to merge into this one."
    private const val SOURCES_HINT = "The next step asks for confirmation."
    private const val SOURCE_GONE = "A chosen service is not there any longer. Check the choice, and continue."

    private const val SOURCES_KEPT = "The merged services were kept, because not every scenario was copied."
}
