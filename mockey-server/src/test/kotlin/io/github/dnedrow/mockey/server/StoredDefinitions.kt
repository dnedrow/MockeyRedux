package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Definitions that a test stores in a definitions location before it starts a server on them. */
internal object StoredDefinitions {
    /** The body with which the service `Customers` answers. */
    const val CUSTOMER = """{"id":1}"""

    /**
     * Stores the service `Customers` in [directory], as a server in writable mode stores it. The service
     * answers every request for `customer/{id}` with [CUSTOMER].
     */
    fun customers(directory: Path) {
        runBlocking {
            val engine =
                MockeyEngine.create {
                    storage = FileDefinitionStorage(directory)
                    readOnly = false
                }
            engine.use {
                val service =
                    engine.services
                        .create { it.copy(name = "Customers", mockUrl = "customer/{id}", responseMode = ResponseMode.STATIC) }
                        .getOrThrow()
                engine.scenarios.create(service.id) { it.copy(name = "found", responseBody = CUSTOMER) }.getOrThrow()
            }
        }
    }
}
