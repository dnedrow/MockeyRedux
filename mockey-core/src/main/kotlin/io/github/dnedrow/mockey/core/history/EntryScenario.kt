package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.definitions.ScenarioChanges
import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Scenario

/** The field that a refusal to make a scenario of a history entry names. */
internal const val ENTRY_FIELD: String = "entry"

/**
 * Returns the change that saves the response of the entry as a scenario of the service that handled the
 * request of the entry, and that yields this scenario.
 *
 * The scenario gets the status code of the response, its headers in their order, and its body as text, see
 * [RecordedResponse.bodyText]. The `Content-Length` header is left out: a scenario states the length of its body
 * when it answers, and the header would be wrong as soon as the body is edited. [define] gets that scenario
 * and returns it with its name and whatever else it is to have. A scenario without a name gets a generated
 * one. The scenario is created as any other, see [ScenarioChanges.create]: the first scenario of a service is
 * its default scenario.
 *
 * The change is rejected for an entry without a service, because there is no service to give the scenario to,
 * and for an entry whose response body was cut when it was recorded, because the scenario would not give the
 * response that the client got. It finds nothing when the service of the entry no longer exists.
 */
internal fun HistoryEntry.asScenario(define: (Scenario) -> Scenario = { it }): Change<Scenario> {
    val recorded = response
    val refusal = scenarioRefusal()
    if (service == null || refusal != null) {
        return { ChangeResult.Rejected(ValidationFailure(listOf(FieldError(ENTRY_FIELD, refusal.orEmpty())))) }
    }
    return ScenarioChanges.create(service.id) { scenario ->
        define(
            scenario.copy(
                responseBody = recorded.bodyText,
                statusCode = recorded.status,
                headers = recorded.headers.filterNot { it.name.equals("Content-Length", ignoreCase = true) },
            ),
        )
    }
}

/** Returns why no scenario can be made of the entry, or `null` when one can. */
private fun HistoryEntry.scenarioRefusal(): String? {
    val cut = "The response body of the history entry $id was cut when it was recorded"
    return when {
        service == null -> "The history entry $id has no service, so there is no service to give a scenario to."
        response.body.isCut -> "$cut, so a scenario of it would not give that response."
        else -> null
    }
}
