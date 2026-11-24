package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LegacyXmlDefinitionsTest {
    @Test
    fun mapsSingleFileServiceAndScenarios() {
        val root = Files.createTempDirectory("mockey-xml")
        val file =
            Files.writeString(
                root.resolve("document.xml"),
                """
                <mockservice>
                  <service name="Customers" description="directory" url="customers/{id}" tag="Team-A team-b"
                      hang_time="500" service_response_type="2" default_real_url_index="1"
                      request_inspector_name="null">
                    <real_url url="https://one.example"/>
                    <real_url url="https://two.example"/>
                    <request_inspector_json_rules enable_flag="true"><![CDATA[{"url": []}]]></request_inspector_json_rules>
                    <response_schema enable_flag="false">{"type":"object"}</response_schema>
                    <scenario id="3" name="gold" tag="Gold" hang_time="12" http_resp_status_code="201" http_method_type="POST">
                      <scenario_match scenario_match_evaluation_rules_flag="false">ABC123</scenario_match>
                      <scenario_response>body</scenario_response>
                      <scenario_response_header>Content-Type: application/json; charset=utf-8 | X-Rate: 10 | malformed</scenario_response_header>
                    </scenario>
                    <scenario id="4" name="rules">
                      <scenario_match scenario_match_evaluation_rules_flag="true"><![CDATA[not-json]]></scenario_match>
                    </scenario>
                  </service>
                </mockservice>
                """.trimIndent(),
            )

        val document = LegacyXmlReader(root).read(file).document!!
        val service = LegacyXmlDefinitions.map(document).services.single()

        assertEquals("Customers", service.name)
        assertEquals(ResponseMode.DYNAMIC, service.responseMode)
        assertEquals(500, service.delayMillis)
        assertEquals(1, service.defaultRealUrlIndex)
        assertEquals(listOf("https://one.example", "https://two.example"), service.realUrls)
        assertEquals(null, service.inspectorName)
        assertEquals(true, service.requestRulesEnabled)
        assertEquals("""{"url": []}""", service.requestRules)
        assertEquals(listOf("team-a", "team-b"), service.tags.toList())
        val plain = service.scenarios.first()
        assertEquals("ABC123", assertIs<MatchCriterion.Text>(plain.matchCriterion).text)
        assertEquals(
            listOf("Content-Type" to "application/json; charset=utf-8", "X-Rate" to "10"),
            plain.headers.map { it.name to it.value },
        )
        assertEquals(201, plain.statusCode)
        assertEquals("POST", plain.methodRestriction)
        assertIs<MatchCriterion.Rules>(service.scenarios[1].matchCriterion)
    }
}
