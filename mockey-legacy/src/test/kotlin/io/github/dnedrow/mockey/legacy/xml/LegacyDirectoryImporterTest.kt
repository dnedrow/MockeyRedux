package io.github.dnedrow.mockey.legacy.xml

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyDirectoryImporterTest {
    @Test
    fun importsReferencedServiceAndResponseFile() {
        val root = Files.createTempDirectory("mockey-directory")
        val serviceDirectory = Files.createDirectories(root.resolve("mockey_def_depot/orders/scenarios"))
        Files.writeString(
            root.resolve("mock_service_definitions.xml"),
            """<mockservice><serviceref file="mockey_def_depot/orders/orders.xml"/></mockservice>""",
        )
        Files.writeString(
            serviceDirectory.parent.resolve("orders.xml"),
            """
            <service name="Orders" url="orders">
              <scenario id="7" name="created">
                <scenario_response file="scenarios/created.txt"/>
              </scenario>
            </service>
            """.trimIndent(),
        )
        Files.writeString(serviceDirectory.resolve("created.txt"), """{"created":true}""")

        val result = LegacyDirectoryImporter(root).import()

        assertEquals(emptyList(), result.issues)
        assertEquals(
            "Orders",
            result.definitions.services
                .single()
                .name,
        )
        assertEquals(
            """{"created":true}""",
            result.definitions.services
                .single()
                .scenarios
                .single()
                .responseBody,
        )
    }
}
