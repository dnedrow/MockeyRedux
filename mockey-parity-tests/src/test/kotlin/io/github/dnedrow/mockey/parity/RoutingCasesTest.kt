package io.github.dnedrow.mockey.parity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class RoutingCasesTest {
    private val resources = Path.of("src/test/resources")

    @Test
    fun everyRoutingCaseHasAFixtureAndARecording() {
        RoutingCases.all.forEach { (name, case) ->
            assertTrue(Files.exists(resources.resolve(case.fixtureXml)), "fixture for $name")
            val recorded = ParityCaseStore().read(resources.resolve("parity/$name"))
            assertEquals(case.request.target, recorded.request.target, name)
            assertTrue(recorded.expectedResponse != null, "recorded response for $name")
        }
    }

    /**
     * Records every case in the fixture group named by mockey.parity.recordGroup against legacy Mockey at
     * mockey.parity.legacy.baseUrl, which must already be running with that group's fixture loaded.
     */
    @Test
    fun recordsGroupFromLegacy() {
        val baseUrl = System.getProperty("mockey.parity.legacy.baseUrl") ?: return
        val group = System.getProperty("mockey.parity.recordGroup") ?: return
        StubHttpServer.upstream(RoutingCases.UPSTREAM_PORT).use {
            RoutingCases.all.filterValues { it.fixtureXml.endsWith("/$group.xml") }.forEach { (name, case) ->
                val fixture = Files.readString(resources.resolve(case.fixtureXml))
                ParityRecorder().record(case.copy(fixtureXml = fixture), URI.create(baseUrl), resources.resolve("parity/$name"))
            }
        }
    }
}
