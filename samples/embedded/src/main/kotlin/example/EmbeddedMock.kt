package example

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * A small application-facing wrapper around the embedded Mockey engine.
 */
class EmbeddedMock private constructor(private val engine: MockeyEngine) : AutoCloseable {
    suspend fun handle(target: String) = engine.handle(MockRequest.forTarget("GET", target))

    override fun close() {
        engine.close()
    }

    companion object {
        suspend fun create(): EmbeddedMock {
            val engine = MockeyEngine.create()
            val service =
                engine.services.create {
                    it.copy(
                        name = "Customers",
                        mockUrl = "customer/{id}",
                        responseMode = ResponseMode.STATIC,
                    )
                }.getOrThrow()
            engine.scenarios.create(service.id) {
                it.copy(name = "found", responseBody = """{"id":1}""")
            }.getOrThrow()
            return EmbeddedMock(engine)
        }
    }
}
