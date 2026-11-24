package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of the legacy `com.mockey.model.ConflictInfoTest`, with the names of its test methods. The legacy
 * class collected the conflicts of a service by hand; the new diagnostics find them in the definitions.
 */
class LegacyConflictInfoTest {
    private fun Definitions.diagnosticsOf(service: Service): List<Diagnostic> =
        diagnostics(installedInspectors = emptySet()).filter { it.serviceId == service.id }

    /** Two conflicts with the same service are both kept, and both name that service. */
    @Test
    fun checkServiceConflict() {
        val a = Service(id = ServiceId("123"), name = "Service Cat", mockUrl = "cat", realUrls = listOf("http://example.com/cat"))
        val b = Service(id = ServiceId("345"), name = "Service Cat", mockUrl = "cat", realUrls = listOf("http://example.com/cat"))

        val conflicts = Definitions(services = listOf(a, b)).diagnosticsOf(a)

        assertEquals(setOf(b.id), conflicts.map { it.otherServiceId }.toSet())
        assertEquals(listOf(DiagnosticKind.SHARED_MOCK_URL, DiagnosticKind.SHARED_REAL_URL), conflicts.map { it.kind })
    }

    @Test
    fun checkDuplicateScenarioConflict() {
        val scenarios = List(2) { Scenario(id = ScenarioId.generate(), name = "ABC") }
        val a = Service(id = ServiceId("345"), name = "Service Cat", mockUrl = "cat", scenarios = scenarios)

        val conflicts = Definitions(services = listOf(a)).diagnosticsOf(a)

        assertEquals(listOf(DiagnosticKind.DUPLICATE_SCENARIOS), conflicts.map { it.kind })
    }
}
