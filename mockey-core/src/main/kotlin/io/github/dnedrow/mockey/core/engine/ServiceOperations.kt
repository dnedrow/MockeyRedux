package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.DerivedRealUrl
import io.github.dnedrow.mockey.core.definitions.MergeReport
import io.github.dnedrow.mockey.core.definitions.SchemaReport
import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.definitions.ServiceMaintenance
import io.github.dnedrow.mockey.core.definitions.responseSchemaReport
import io.github.dnedrow.mockey.core.definitions.service
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.storage.StorageException

/**
 * What a program does with the services of an engine.
 *
 * Every operation that changes something returns a [ChangeResult]: the definitions after the change, or why
 * nothing changed. A change that would break a rule is rejected with one error for each invalid field, and
 * the definitions stay as they are. In writable mode a change is saved before its operation returns, and an
 * operation whose change cannot be saved fails with a [StorageException] and changes nothing.
 *
 * The services themselves are in the [MockeyEngine.definitions].
 */
public class ServiceOperations internal constructor(
    private val engine: MockeyEngine,
) {
    /**
     * Creates a service. [define] gets a new service that has an identifier and nothing else, and returns it
     * with its attributes, such as `it.copy(name = "Customers", mockUrl = "customer/{id}")`. It must leave
     * the identifier as it is, and it gives the service no scenarios: scenarios are created one by one.
     *
     * A service needs a name of at most 250 characters, a mock URL pattern that no other service has, and
     * real URLs that no other service lists. A service without a name is named after its first real URL.
     */
    public suspend fun create(define: (Service) -> Service): ChangeResult<Service> = engine.change(ServiceChanges.create(define))

    /**
     * Updates the service [id]. [change] gets the service as it is and returns it with its new attributes.
     * It must leave the identifier and the scenarios as they are. It can designate the default scenario and
     * the error scenario, each of which must be a scenario of the service.
     *
     * The rules of [create] hold. A new name goes to the plans that name the service as well.
     */
    public suspend fun update(
        id: ServiceId,
        change: (Service) -> Service,
    ): ChangeResult<Service> = engine.change(ServiceChanges.update(id, change))

    /** Deletes the service [id] with its scenarios, and returns it. A universal error scenario of the service is no longer designated. */
    public suspend fun delete(id: ServiceId): ChangeResult<Service> = engine.change(ServiceChanges.delete(id))

    /**
     * Creates a copy of the service [id] and returns it. The copy has a name and a mock URL pattern that no
     * service has, copies of the scenarios with the same designations, and no real URLs.
     */
    public suspend fun duplicate(id: ServiceId): ChangeResult<Service> = engine.change(ServiceMaintenance.duplicate(id))

    /**
     * Merges the services [sourceIds] into the service [destinationId]. The destination gets every scenario
     * of a source that it does not have with the same name and response body, and the tags of the sources.
     * When no scenario was skipped, it gets the real URLs of the sources as well, and the sources are deleted.
     */
    public suspend fun merge(
        destinationId: ServiceId,
        sourceIds: List<ServiceId>,
    ): ChangeResult<MergeReport> = engine.change(ServiceMaintenance.merge(destinationId, sourceIds))

    /**
     * Adds real URLs to every service, and returns those that were added. Each real URL of a service gives
     * one URL for each of the [replacements]: the URL in which what the regular expression [expression] finds
     * is replaced. A result that some service lists already is not added.
     */
    public suspend fun deriveRealUrls(
        expression: String,
        replacements: List<String>,
    ): ChangeResult<List<DerivedRealUrl>> = engine.change(ServiceMaintenance.deriveRealUrls(expression, replacements))

    /**
     * Returns what the response schema of the service [id] says of the response bodies of its scenarios, or
     * `null` when there is no such service or the service has no response schema.
     */
    public fun schemaReport(id: ServiceId): SchemaReport? = engine.definitions.service(id)?.responseSchemaReport()
}
