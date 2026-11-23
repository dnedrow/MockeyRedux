package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * The changes that create, update, and delete a service.
 *
 * A service is stored in a normal form. Its name and its mock URL pattern have no surrounding whitespace. Its
 * real URLs are trimmed, and blank ones and repeated ones are left out. The default real URL stays the one
 * that was designated, or is the first one when none of the remaining URLs was. A description, a rule set, an
 * inspector name, or a response schema that is blank is absent.
 *
 * A service is rejected when its name is empty or longer than [MAX_NAME_LENGTH] characters, when another
 * service has its mock URL pattern, or when another service lists one of its real URLs. Mock URL patterns and
 * real URLs are compared without regard to letter case. The whole service is checked each time, also the
 * attributes that an update leaves as they are.
 *
 * An update can designate the default scenario and the error scenario of the service. A scenario that is
 * newly designated must be one of the scenarios of the service. The scenarios themselves are changed with
 * [ScenarioChanges].
 */
internal object ServiceChanges {
    const val MAX_NAME_LENGTH = 250

    private const val DERIVED_NAME_SUFFIX = " (auto generated)"

    /**
     * Creates a service. [define] gets a new service that has an identifier and nothing else, and returns it
     * with its attributes. A service that gets no name is named after its first real URL, see [derivedName].
     */
    fun create(define: (Service) -> Service): Change<Service> =
        { definitions ->
            val blank = Service(id = ServiceId.generate(), name = "", mockUrl = "")
            val defined = attributesChanged(blank, define)
            val service = normalized(defined).named()
            saved(definitions, service, designationErrors(blank, defined)) { services -> services + service }
        }

    /** Updates the service [id]. [change] gets the service as it is and returns it with its new attributes. */
    fun update(
        id: ServiceId,
        change: (Service) -> Service,
    ): Change<Service> =
        { definitions ->
            val current = definitions.service(id)
            if (current == null) {
                serviceNotFound(id)
            } else {
                val changed = attributesChanged(current, change)
                val service = normalized(changed).withValidDesignations()
                saved(definitions, service, designationErrors(current, changed)) { services ->
                    services.map { if (it.id == id) service else it }
                }
            }
        }

    /**
     * Deletes the service [id] with its scenarios and yields the deleted service. The universal error scenario
     * is no longer designated when it was one of them.
     */
    fun delete(id: ServiceId): Change<Service> =
        { definitions ->
            val service = definitions.service(id)
            if (service == null) {
                serviceNotFound(id)
            } else {
                val settings = definitions.settings
                val universalErrorScenario = settings.universalErrorScenario?.takeUnless { it.serviceId == id }
                ChangeResult.Changed(
                    definitions.copy(
                        services = definitions.services.filter { it.id != id },
                        settings = settings.copy(universalErrorScenario = universalErrorScenario),
                    ),
                    service,
                )
            }
        }

    /**
     * Returns the name for a service that was given none: the last part of [realUrl] between slashes, without
     * the query string, followed by ` (auto generated)`. For a URL without a path that part is the host.
     */
    fun derivedName(realUrl: String): String {
        val lastPart =
            realUrl
                .substringBefore('?')
                .split('/')
                .lastOrNull { it.isNotBlank() }
                .orEmpty()
        return (lastPart.trim() + DERIVED_NAME_SUFFIX).trim()
    }

    /** Applies [change], which may set the attributes of the service but not its identifier or its scenarios. */
    private fun attributesChanged(
        before: Service,
        change: (Service) -> Service,
    ): Service {
        val after = change(before)
        require(after.id == before.id) { "The identifier of a service cannot be changed." }
        require(after.scenarios == before.scenarios) { "The scenarios of a service cannot be changed together with its attributes." }
        return after
    }

    /** Reports a scenario that [after] newly designates and that is not one of its scenarios. */
    private fun designationErrors(
        before: Service,
        after: Service,
    ): List<FieldError> =
        listOfNotNull(
            after.defaultScenarioId.takeIf { it != before.defaultScenarioId }?.let { missingScenario("defaultScenarioId", after, it) },
            after.errorScenarioId.takeIf { it != before.errorScenarioId }?.let { missingScenario("errorScenarioId", after, it) },
        )

    private fun missingScenario(
        field: String,
        service: Service,
        id: ScenarioId,
    ): FieldError? =
        if (service.scenarios.any { it.id == id }) {
            null
        } else {
            FieldError(field, "The service has no scenario with the identifier ${id.value}.")
        }

    private fun normalized(service: Service): Service {
        val realUrls =
            service.realUrls
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
        val designated = service.realUrls.getOrNull(service.defaultRealUrlIndex)?.trim()
        return service.copy(
            name = service.name.trim(),
            mockUrl = service.mockUrl.trim(),
            description = service.description?.takeIf { it.isNotBlank() },
            realUrls = realUrls,
            defaultRealUrlIndex = realUrls.indexOfFirst { it.equals(designated, ignoreCase = true) }.coerceAtLeast(0),
            requestRules = service.requestRules?.takeIf { it.isNotBlank() },
            inspectorName = service.inspectorName?.trim()?.takeIf { it.isNotEmpty() },
            responseSchema = service.responseSchema?.takeIf { it.isNotBlank() },
        )
    }

    private fun Service.named(): Service = if (name.isEmpty() && realUrls.isNotEmpty()) copy(name = derivedName(realUrls.first())) else this

    /** Puts [service] in place with [replace] when it is valid, and rejects it otherwise. */
    private fun saved(
        definitions: Definitions,
        service: Service,
        designationErrors: List<FieldError>,
        replace: (List<Service>) -> List<Service>,
    ): ChangeResult<Service> {
        val errors = errors(service, others = definitions.services.filter { it.id != service.id }) + designationErrors
        return if (errors.isEmpty()) {
            ChangeResult.Changed(definitions.copy(services = replace(definitions.services)), service)
        } else {
            ChangeResult.Rejected(ValidationFailure(errors))
        }
    }

    private fun errors(
        service: Service,
        others: List<Service>,
    ): List<FieldError> = listOfNotNull(nameError(service), mockUrlError(service, others), realUrlError(service, others))

    private fun nameError(service: Service): FieldError? =
        when {
            service.name.isEmpty() -> "The name of a service must not be empty."
            service.name.length > MAX_NAME_LENGTH -> "The name of a service must not be longer than $MAX_NAME_LENGTH characters."
            else -> null
        }?.let { FieldError("name", it) }

    private fun mockUrlError(
        service: Service,
        others: List<Service>,
    ): FieldError? =
        others.firstOrNull { it.mockUrl.trim().equals(service.mockUrl, ignoreCase = true) }?.let { other ->
            FieldError("mockUrl", "The service '${other.name}' already uses the mock URL pattern '${service.mockUrl}'.")
        }

    /** Reports the first real URL of [service] that one of the [others] lists. */
    private fun realUrlError(
        service: Service,
        others: List<Service>,
    ): FieldError? =
        service.realUrls.firstNotNullOfOrNull { url ->
            others.firstOrNull { it.lists(url) }?.let { other ->
                FieldError("realUrls", "The service '${other.name}' already lists the real URL $url.")
            }
        }

    private fun Service.lists(realUrl: String): Boolean = realUrls.any { it.trim().equals(realUrl, ignoreCase = true) }
}

internal fun Definitions.service(id: ServiceId): Service? = services.firstOrNull { it.id == id }

/** Returns the definitions with [service] in the place of the service that has its identifier. */
internal fun Definitions.withService(service: Service): Definitions =
    copy(
        services =
            services.map {
                if (it.id ==
                    service.id
                ) {
                    service
                } else {
                    it
                }
            },
    )

internal fun serviceNotFound(id: ServiceId): ChangeResult.NotFound = ChangeResult.NotFound("No service has the identifier ${id.value}.")
