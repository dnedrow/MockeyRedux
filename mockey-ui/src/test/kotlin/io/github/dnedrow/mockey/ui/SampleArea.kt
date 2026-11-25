package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An area of the tests: a few pages that use what the areas of the UI make their pages and take their forms
 * with, see [Ui], in each of the ways in which the UI uses it. It shows the form of a service and creates
 * the service, asks before it deletes one, applies a plan and imports a document, each with a report.
 */
internal object SampleArea : Area {
    const val NEW = "/sample/services/new"

    override fun install(
        routing: Route,
        ui: Ui,
    ) {
        with(routing) {
            get(NEW) { ui.show(call) { view, _ -> form(view, FormState.of("mode" to "static", "verbose" to CHECKED)) } }
            post("/sample/services") {
                ui.submit(call, again = { view, _, state -> form(view, state) }) { input ->
                    val created = ui.client.createService(attributesOf(input))
                    Outcome.Done("/sample/services/${created.id}", "The service ${created.name} was created.")
                }
            }
            get("/sample/services/{id}") {
                val id = call.parameters["id"].orEmpty()
                ui.show(call) { _, _ ->
                    val service = ui.client.getService(id)
                    Page(service.name) { a(href = "/sample/services/$id/delete") { +"Delete" } }
                }
            }
            getAndPost("/sample/services/{id}/delete") {
                val id = call.parameters["id"].orEmpty()
                val ask: suspend (View, Any) -> Page = { view, _ ->
                    val service = ui.client.getService(id)
                    val gone = listOf("The service has ${service.scenarios.size} scenarios, which are deleted with it.")
                    val action = "/sample/services/$id/delete"
                    Confirmation.page(view, "Delete the service ${service.name}?", gone, "Delete the service", action, NEW)
                }
                ui.confirm(call, ask) {
                    ui.client.deleteService(id)
                    Outcome.Done(NEW, "The service was deleted.")
                }
            }
            get("/sample/plans") { ui.show(call) { view, _ -> actions(view, FormState.EMPTY) } }
            post("/sample/plans/{id}/apply") {
                val id = call.parameters["id"].orEmpty()
                ui.submit(call) {
                    val report = ui.client.applyPlan(id)
                    Outcome.Report(reportOf(report), report.skippedItems.size + report.unresolvedScenarios.size)
                }
            }
            post("/sample/import") {
                ui.submit(call, again = { view, _, state -> actions(view, state) }) { input ->
                    val document = input.upload("document") ?: throw InvalidInput("document", "No file was chosen.")
                    val tags = input.text("tags").split(' ').filter { it.isNotEmpty() }
                    val report = ui.client.importDefinitions(document.bytes, isOfMockey2 = document.fileName.endsWith(".xml"), tags)
                    val page = Page("Imported ${document.fileName}") { p { +"${report.additions.size} additions" } }
                    Outcome.Report(page, report.additions.size)
                }
            }
            post("/sample/elsewhere") { ui.submit(call) { Outcome.Done("//elsewhere.example/", "Gone.") } }
        }
    }

    /** Returns the attributes of a service as the admin API reads them, from what the form holds. */
    private fun attributesOf(input: FormInput) =
        buildJsonObject {
            val delay = input.text("delay").ifEmpty { "0" }
            put("name", input.text("name"))
            put("mockUrl", input.text("mockUrl"))
            put("description", input.lines("description"))
            val tags = input.text("tags").split(' ').filter { it.isNotEmpty() }
            put("tags", JsonArray(tags.map(::JsonPrimitive)))
            put("responseMode", input.text("mode"))
            put("delayMillis", delay.toLongOrNull() ?: throw InvalidInput("delayMillis", "The delay is a number of milliseconds."))
            put("requestRulesEnabled", input.isChecked("rules"))
        }

    private fun form(
        view: View,
        state: FormState,
    ): Page =
        Page("New service") {
            val modes = listOf("proxy" to "Proxy", "static" to "Static", "dynamic" to "Dynamic")
            postForm(view, "/sample/services") {
                rejectionOf(state)
                textField(state, "name", "Name", hint = "What the pages call the service.")
                textField(state, "mockUrl", "Mock URL")
                textAreaField(state, "description", "Description", rows = 4)
                textField(state, "tags", "Tags", hint = "With spaces between them.")
                choiceField(state, "mode", "Response mode", modes, api = "responseMode")
                textField(state, "delay", "Delay", api = "delayMillis")
                checkField(state, "rules", "Evaluate the request rules", api = "requestRulesEnabled")
                checkField(state, "verbose", "Say more")
                otherProblemsOf(state)
                submit("Create the service")
            }
        }

    private fun actions(
        view: View,
        state: FormState,
    ): Page =
        Page("Plans") {
            postForm(view, "/sample/plans/plan-night/apply") { submit("Apply the plan Night") }
            postForm(view, "/sample/import", withFiles = true) {
                rejectionOf(state)
                textField(state, "tags", "Tags")
                fileField(state, "document", "Document")
                submit("Import")
            }
        }

    private fun reportOf(report: PlanApplyReportDto): Page =
        Page("The plan ${report.plan.name} was applied") {
            p { +"Skipped items:" }
            ul { report.skippedItems.forEach { li { +it.serviceName } } }
        }
}
