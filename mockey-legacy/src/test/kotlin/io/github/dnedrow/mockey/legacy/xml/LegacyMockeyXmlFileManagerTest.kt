package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.definitions.ImportItem
import io.github.dnedrow.mockey.core.definitions.ImportItemKind
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The cases of the legacy `com.mockey.storage.xml.MockeyXmlFileManagerTest`, with the names of its test
 * methods. The legacy class read and wrote the definitions directory of Mockey 2.x, and it merged a document
 * into the store. The importers read such a directory and such a document, and an engine merges what they
 * read.
 *
 * Nothing writes the layout of Mockey 2.x any more. A case that asked the legacy class where it writes a
 * file asks here whether the importer reads a file at that place.
 */
class LegacyMockeyXmlFileManagerTest {
    @TempDir
    lateinit var directory: Path

    /** Writes [content] to the file [path] of the directory of the test. */
    private fun write(
        path: String,
        content: String,
    ): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    private fun element(
        name: String,
        vararg attributes: Pair<String, String>,
        content: String = "",
    ): String = "<$name ${attributes.joinToString(" ") { (key, value) -> "$key=\"$value\"" }}>$content</$name>"

    /**
     * Returns the element of a service as Mockey 2.x exports it. The service has no mock URL, as the services
     * of the legacy cases have none.
     */
    private fun service(
        name: String,
        tag: String = "",
        realUrls: List<String> = emptyList(),
        scenarios: List<String> = emptyList(),
    ): String {
        val flags = element("request_inspector_json_rules", "enable_flag" to "false") + element("response_schema", "enable_flag" to "false")
        return element(
            "service",
            "default_real_url_index" to "0",
            "default_scenario_id" to "0",
            "description" to "",
            "error_scenario_id" to "0",
            "hang_time" to "0",
            "name" to name,
            "request_inspector_name" to "null",
            "service_response_type" to "0",
            "tag" to tag,
            "url" to "",
            content = flags + realUrls.joinToString("") { element("real_url", "url" to it) } + scenarios.joinToString(""),
        )
    }

    /** Returns the element of a scenario without a response body as Mockey 2.x exports it. */
    private fun scenario(
        name: String,
        tag: String,
    ): String =
        element(
            "scenario",
            "hang_time" to "0",
            "http_method_type" to "",
            "http_resp_status_code" to "200",
            "id" to "1",
            "name" to name,
            "tag" to tag,
            content =
                element("scenario_match", "scenario_match_evaluation_rules_flag" to "false") +
                    "<scenario_response/><scenario_response_header/>",
        )

    /**
     * Returns the document that Mockey 2.x exports for a store that holds [services]. Mockey 2.x writes the
     * settings of the outbound proxy also when there are none, and then without a URL.
     */
    private fun storeAsXml(
        services: List<String>,
        proxyUrl: String = "",
    ): String {
        val proxy = element("proxy_settings", "proxy_enabled" to "false", "proxy_url" to proxyUrl)
        val root = arrayOf("timestamp" to "1795539867247", "version" to "1.0", "xml:lang" to "en-US")
        return XML_DECLARATION + element("mockservice", *root, content = proxy + services.joinToString(""))
    }

    /** The two services that several legacy cases put into the store. */
    private val serviceList =
        listOf("Service 1", "Service 22").map { service(it, realUrls = listOf("http://www.abc.com", "http://www.nbc.com")) }

    /** The service that a legacy case has in its store when it merges a document. It has no mock URL either. */
    private val serviceOne = Service(id = ServiceId.generate(), name = "Service 1", mockUrl = "")

    // The files of a definitions directory

    /**
     * Legacy named the file of the scenario `happy` of the service `feeling` in its base path:
     * `mockey_def_depot/feeling/scenarios/happy.xml`. The importer reads the scenario in that file.
     */
    @Test
    fun validateServiceScenarioFile() {
        val scenarioFile = "mockey_def_depot/feeling/scenarios/happy.xml"
        write(LegacyDirectoryImporter.MAIN_FILE, """<mockservice><serviceref file="mockey_def_depot/feeling/feeling.xml"/></mockservice>""")
        write(
            "mockey_def_depot/feeling/feeling.xml",
            """<mockservice><service name="feeling" url="/feeling"><xi:include href="$scenarioFile" $XINCLUDE/></service></mockservice>""",
        )
        write(scenarioFile, """<scenario id="1" name="happy"><scenario_response>HAPPY</scenario_response></scenario>""")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(emptyList(), result.issues)
        val feeling = result.definitions.services.single()
        assertEquals("feeling" to "/feeling", feeling.name to feeling.mockUrl)
        assertEquals(listOf("happy" to "HAPPY"), feeling.scenarios.map { it.name to it.responseBody })
    }

    /** Legacy took the directory that it was given as its base path. The importer reads the main file of that directory. */
    @Test
    fun validateDefaultBasePath() {
        write(LegacyDirectoryImporter.MAIN_FILE, """<mockservice><service name="feeling" url="/feeling"/></mockservice>""")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("feeling"), result.definitions.services.map { it.name })
    }

    /**
     * Legacy created the directory of its base path when there was none, and a directory for the service
     * files in it. An import creates nothing: a directory that does not exist holds no definitions, and it
     * does not exist after the import either.
     */
    @Test
    fun validateNewBasePath() {
        val configFiles = directory.resolve("configfiles")

        val result = LegacyDirectoryImporter(configFiles).import()

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), result.issues.map { it.kind })
        assertFalse(Files.exists(configFiles))
    }

    /**
     * Legacy named the file of the service `a_service_name` in its base path:
     * `mockey_def_depot/a_service_name/a_service_name.xml`. The importer reads the service in that file.
     */
    @Test
    fun validateServiceFileWithNewBasePath() {
        val serviceFile = "mockey_def_depot/a_service_name/a_service_name.xml"
        write(LegacyDirectoryImporter.MAIN_FILE, """<mockservice><serviceref file="$serviceFile"/></mockservice>""")
        write(serviceFile, """<mockservice><service name="a_service_name" url="a"/></mockservice>""")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("a_service_name"), result.definitions.services.map { it.name })
    }

    /**
     * Legacy gave the path of a service file from its base path, with the name of the service in lower case:
     * `mockey_def_depot/anaccountservice/anaccountservice.xml`. The importer follows that path from its
     * directory. For a file outside its base path legacy answered `ERROR`. The importer does not read such a
     * file, and it reports the reference.
     */
    @Test
    fun checkForRelativePath() {
        val seed = Files.createDirectory(directory.resolve("seed"))
        val randomFile = write("makebelieve/somenewfile", """<mockservice><service name="Random" url="random"/></mockservice>""")
        val serviceFile = "mockey_def_depot/anaccountservice/anaccountservice.xml"
        val references = """<serviceref file="$serviceFile"/><serviceref file="$randomFile"/>"""
        write("seed/${LegacyDirectoryImporter.MAIN_FILE}", "<mockservice>$references</mockservice>")
        write("seed/$serviceFile", """<mockservice><service name="AnAccountService" url="account"/></mockservice>""")

        val result = LegacyDirectoryImporter(seed).import()

        assertEquals(listOf("AnAccountService"), result.definitions.services.map { it.name })
        val issue = result.issues.single()
        assertEquals(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT to randomFile, issue.kind to issue.file)
    }

    // The store, and a document that is merged into it

    /**
     * Legacy put both services into its store, which checked nothing. Both have no mock URL pattern, and a
     * pattern belongs to one service (DEV-02), also the empty one. So the second service is not added, and
     * the report says why.
     */
    @Test
    fun validateServiceAdd() =
        withEngine { engine ->
            val report = LegacyDocumentImporter.import(storeAsXml(serviceList)).importInto(engine)

            val service = engine.definitions.services.single()
            assertEquals("Service 1", service.name)
            assertEquals(listOf("http://www.abc.com", "http://www.nbc.com"), service.realUrls)
            assertEquals(listOf(ImportItemKind.SERVICE to "Service 1"), report.imported.additions.named())
            assertEquals(listOf(ImportItemKind.SERVICE to "Service 22"), report.imported.conflicts.named())
            assertContains(
                report.imported.conflicts
                    .single()
                    .message,
                "mock URL pattern",
            )
        }

    /**
     * Legacy reported one addition when it loaded the document of its store again: `Proxy settings set.` It
     * reported that for every document while the store had no proxy settings, also for this one, which
     * holds none. Settings without a URL are no settings here, so the document adds nothing. The addition
     * is reported for a document that holds the settings of a proxy.
     */
    @Test
    fun checkProxyAdditionMsg() =
        withEngine { engine ->
            LegacyDocumentImporter.import(storeAsXml(serviceList)).importInto(engine)

            val again = LegacyDocumentImporter.import(storeAsXml(serviceList)).importInto(engine)
            val withProxy = LegacyDocumentImporter.import(storeAsXml(serviceList, "http://proxy.example.com:3128")).importInto(engine)

            assertEquals(emptyList(), again.imported.additions)
            assertEquals(listOf(ImportItemKind.SETTING to ImportItem.OUTBOUND_PROXY), withProxy.imported.additions.named())
            assertEquals(
                "http://proxy.example.com:3128",
                engine.definitions.settings.outboundProxy
                    ?.url,
            )
        }

    /** A service of a document is merged into the service that has its name, which gets the real URL that it did not list. */
    @Test
    fun checkForMergedRealUrls() {
        val existing = serviceOne.copy(realUrls = listOf("http://www.def.com"), tags = Tags.of("def"))
        val document = storeAsXml(listOf(service("Service 1", tag = "abc", realUrls = listOf("http://www.abc.com"))))

        withEngine(Definitions(services = listOf(existing))) { engine ->
            LegacyDocumentImporter.import(document).importInto(engine)

            assertEquals(
                listOf("http://www.def.com", "http://www.abc.com"),
                engine.definitions.services
                    .single()
                    .realUrls,
            )
        }
    }

    /** The service that a service of a document is merged into gets the tags of that service. */
    @Test
    fun checkForMergedServiceTags() {
        val existing = serviceOne.copy(tags = Tags.of("def"))
        val document = storeAsXml(listOf(service("Service 1", tag = "abc")))

        withEngine(Definitions(services = listOf(existing))) { engine ->
            LegacyDocumentImporter.import(document).importInto(engine)

            assertEquals(
                Tags.of("abc", "def"),
                engine.definitions.services
                    .single()
                    .tags,
            )
        }
    }

    /**
     * Legacy gave the scenario of the store the tag of the scenario of the document that has its name and its
     * response body, so the scenario had both tags. An import changes no scenario that is there (DEV-59):
     * the service keeps its one scenario, and the scenario keeps its one tag.
     */
    @Test
    fun checkForMergedServiceScenarioTags() {
        val abc = Scenario(id = ScenarioId.generate(), name = "ABC", tags = Tags.of("def"))
        val existing = serviceOne.copy(scenarios = listOf(abc))
        val document = storeAsXml(listOf(service("Service 1", scenarios = listOf(scenario("ABC", tag = "abc")))))

        withEngine(Definitions(services = listOf(existing))) { engine ->
            LegacyDocumentImporter.import(document).importInto(engine)

            val scenario =
                engine.definitions.services
                    .single()
                    .scenarios
                    .single()
            assertEquals("ABC" to Tags.of("def"), scenario.name to scenario.tags)
        }
    }

    /** A service of a document whose name a service has is one conflict of the import. */
    @Test
    fun checkForConflictingServiceName() =
        withEngine { engine ->
            val document = storeAsXml(listOf(service("Service 1")))
            LegacyDocumentImporter.import(document).importInto(engine)

            val report = LegacyDocumentImporter.import(document).importInto(engine)

            assertEquals(listOf(ImportItemKind.SERVICE to "Service 1"), report.imported.conflicts.named())
            assertEquals(1, engine.definitions.services.size)
        }

    private companion object {
        const val XML_DECLARATION = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>"""
        const val XINCLUDE = """xmlns:xi="http://www.w3.org/2001/XInclude""""
    }
}
