package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.runBlocking

/**
 * The definitions of the parity fixture `automation.xml`, from which the recordings of the automation
 * endpoints of Mockey 2.x start. A test of the legacy shim defines them, and it then compares an answer of
 * the shim with the one that a recording states for the new server.
 */
internal object ShimFixture {
    /**
     * Defines the services of the fixture in [engine] and returns them in the order of the fixture:
     * `Customers`, a static service with three scenarios, `Orders`, a dynamic service with a delay and two
     * scenarios, and `Billing`, a proxy service without scenarios.
     */
    fun services(engine: MockeyEngine): List<Service> =
        runBlocking {
            val customers =
                engine.create {
                    it.copy(
                        name = "Customers",
                        mockUrl = "customers",
                        description = "A static service with three scenarios and two real URLs.",
                        realUrls = listOf("$REAL_SERVICE/customers/a", "$REAL_SERVICE/customers/b"),
                        responseMode = ResponseMode.STATIC,
                    )
                }
            engine.add(customers) { it.copy(name = "silver", responseBody = "silver customer") }
            engine.add(customers) { it.copy(name = "gold", responseBody = "gold customer") }
            engine.add(customers) { it.copy(name = "missing", responseBody = "no such customer", statusCode = 404) }
            val orders =
                engine.create {
                    it.copy(
                        name = "Orders",
                        mockUrl = "orders",
                        description = "A dynamic service with a delay of 100 ms.",
                        responseMode = ResponseMode.DYNAMIC,
                        delayMillis = 100,
                    )
                }
            for (state in listOf("open", "closed")) {
                engine.add(orders) { it.copy(name = state, responseBody = "$state orders", matchCriterion = MatchCriterion.Text(state)) }
            }
            engine.create {
                it.copy(
                    name = "Billing",
                    mockUrl = "$REAL_SERVICE/billing",
                    description = "A proxy service without scenarios.",
                    realUrls = listOf("$REAL_SERVICE/billing"),
                )
            }
            engine.definitions.services
        }

    /** Creates the service that [define] describes, and returns its identifier. */
    private suspend fun MockeyEngine.create(define: (Service) -> Service): ServiceId = services.create(define).getOrThrow().id

    /** Adds the scenario that [define] describes to [service]. */
    private suspend fun MockeyEngine.add(
        service: ServiceId,
        define: (Scenario) -> Scenario,
    ) {
        scenarios.create(service, define).getOrThrow()
    }

    /** Where the real services of the fixture are. Nothing listens there in a test of the shim. */
    private const val REAL_SERVICE = "http://127.0.0.1:18091"
}
