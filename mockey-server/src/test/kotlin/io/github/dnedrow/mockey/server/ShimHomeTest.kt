package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 14.4: the endpoint `/home` of the legacy shim, over real HTTP. A test starts from the services and
 * the plans of the parity fixture `automation.xml`, with the document `suite-a.xml` in the definitions
 * location, as the recordings of task 2.8 do, and it holds an answer against the one that a recording
 * states for the new server.
 */
class ShimHomeTest {
    private val started = mutableListOf<ShimUnderTest>()
    private val directories = mutableListOf<Path>()
    private val levelBefore = ServerLogging.level()

    @AfterTest
    fun stopEverything() {
        started.forEach(ShimUnderTest::close)
        directories.forEach { it.toFile().deleteRecursively() }
        ServerLogging.configure(levelBefore)
    }

    /** Returns a new directory that holds the document `suite-a.xml`, as a definitions location does in a recording. */
    private fun location(): Path {
        val base = Files.createTempDirectory("mockey-shim-home").also { directories.add(it) }
        // The location is a directory of the base, so that a test can put a file next to it.
        val location = Files.createDirectory(base.resolve("definitions"))
        Files.writeString(location.resolve("suite-a.xml"), SUITE_A)
        return location
    }

    /**
     * Starts a mock listener with the legacy shim for [engine], which gets the services and the plans of
     * the fixture, with [location] as the definitions location.
     */
    private fun script(
        location: Path = location(),
        engine: MockeyEngine = runBlocking { MockeyEngine.create() },
    ): ShimUnderTest {
        val script = ShimUnderTest.start(engine, location = location).also { started += it }
        ShimFixture.services(engine)
        ShimFixture.plans(engine)
        return script
    }

    /** Returns the result of the answer to a `GET` request for [target]. */
    private fun ShimUnderTest.result(target: String): JsonObject = document(target).getValue("result").jsonObject

    /** Returns an engine that stores its definitions in [storage], in writable mode or in read-only mode. */
    private fun engineOn(
        storage: DefinitionStorage,
        writable: Boolean,
    ): MockeyEngine =
        runBlocking {
            MockeyEngine.create {
                this.storage = storage
                readOnly = !writable
            }
        }

    /** Returns what [definitions] hold of each service, and the names of their plans. */
    private fun contentOf(definitions: Definitions): Pair<List<List<Any?>>, List<String>> {
        val services =
            definitions.services.map { service ->
                val scenarios = service.scenarios.map { it.name to it.responseBody }
                listOf(service.name, service.mockUrl, service.responseMode, service.tags.toList(), scenarios)
            }
        return services to definitions.plans.map { it.name }
    }

    /** What the document `suite-a.xml` holds, as [contentOf] states it. */
    private val suiteA: Pair<List<List<Any?>>, List<String>> =
        listOf(
            listOf("Suite tagged", "suite/tagged", ResponseMode.STATIC, listOf("keep"), listOf("tagged answer" to "suite tagged")),
            listOf("Suite untagged", "suite/untagged", ResponseMode.STATIC, NO_TAGS, listOf("untagged answer" to "suite untagged")),
        ) to emptyList()

    /**
     * The recordings `automation-home-init` and `automation-home-init-without-type`, and the scenario
     * "Initialize from a document in the definitions location": the definitions are exactly those of the
     * document, and the result has the text of Mockey 2.x. The answer is the result envelope also without
     * `type=json`, where Mockey 2.x redirected to its home page (DEV-50).
     */
    @Test
    fun anInitPutsTheDefinitionsOfTheDocumentInThePlaceOfTheDefinitionsOfTheServer() {
        val withoutType = "action=init&transientState=true&file=suite-a.xml"
        for (query in listOf("$withoutType&type=json", withoutType)) {
            val script = script()
            script.get("/service/customers")

            val answer = script.get("/home?$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"success":"Loaded definitions from suite-a.xml"}}""", answer.body, query)
            assertEquals(suiteA, contentOf(script.definitions), query)
            assertEquals(Definitions.EMPTY.copy(services = script.definitions.services), script.definitions, query)
            assertEquals(emptyList(), script.engine.history.entries(), query)
            assertTrue(script.engine.readOnly, query)
            val length = "Content-Length: ${answer.body.length}"
            val headers = listOf("Cache-Control: no-store", length, "Content-Type: application/json", "Connection: close")
            assertEquals(headers, answer.headers, query)
        }
    }

    /** The recording `automation-home-init-effect`: after an init, `/definitions` lists the services of the document. */
    @Test
    fun afterAnInitTheDefinitionsEndpointListsTheServicesOfTheDocument() {
        val script = script()

        script.get("/home?action=init&type=json&transientState=true&file=suite-a.xml")
        val listed = script.document("/definitions").objects("serviceDefinitions")

        val stated =
            listed.map { service ->
                val scenarios = service.objects("scenarioArray").map { it.text("scenarioName") }
                val asked = listOf("serviceName", "serviceResponseType", "hangTime", "requestInspectorName", "defaultScenarioName")
                asked.map(service::text) + scenarios
            }
        assertEquals(
            listOf(
                listOf("Suite tagged", "static", "0", "null", "tagged answer", "tagged answer"),
                listOf("Suite untagged", "static", "0", "null", "untagged answer", "untagged answer"),
            ),
            stated,
        )
        assertEquals(script.definitions.services.map { it.id.value }, listed.map { it.text("serviceId") })
    }

    /**
     * The recording `automation-home-init-filter-tag`: the tag filter of the request is set after the
     * definitions are replaced, so a service without the tag isn't reached (DEV-49). Mockey 2.x set the
     * filter first and lost it with the old definitions.
     */
    @Test
    fun theTagFilterOfAnInitIsSetAfterTheDefinitionsAreReplaced() {
        val script = script()
        script.engine.tagFilter = Tags.of("premium")

        script.get("/home?action=init&type=json&transientState=true&file=suite-a.xml&filterTag=keep")
        val untagged = script.get("/service/suite/untagged")
        val tagged = script.get("/service/suite/tagged")

        assertEquals(Tags.of("keep"), script.engine.tagFilter)
        assertEquals(404, untagged.status)
        assertTrue("suite/untagged" in untagged.body, untagged.body)
        assertEquals(200 to "suite tagged", tagged.status to tagged.body)
    }

    /** An init without `filterTag` leaves no tag filter, because the definitions that the filter was for are gone. */
    @Test
    fun anInitWithoutATagFilterLeavesNone() {
        val script = script()
        script.engine.tagFilter = Tags.of("premium")

        script.get("/home?action=init&file=suite-a.xml")
        val several = script().also { it.get("/home?action=init&file=suite-a.xml&filterTag=Keep+smoke") }
        val empty = script().also { it.get("/home?action=init&file=suite-a.xml&filterTag=") }

        assertEquals(Tags.EMPTY, script.engine.tagFilter)
        assertEquals(200, script.get("/service/suite/untagged").status)
        assertEquals(Tags.of("keep", "smoke"), several.engine.tagFilter)
        assertEquals(Tags.EMPTY, empty.engine.tagFilter)
    }

    /**
     * The recording `automation-home-init-missing-file`: the result has the text of Mockey 2.x, and nothing
     * of the server changes: neither the definitions, nor read-only mode, nor the tag filter.
     */
    @Test
    fun anInitFromAFileThatDoesNotExistGetsTheTextOfMockey2xAndChangesNothing() {
        val script = script()
        script.engine.tagFilter = Tags.of("premium")
        script.get("/service/customers")
        val before = script.definitions

        val recorded = script.get("/home?action=init&type=json&transientState=true&file=missing.xml")
        val withMore = script.get("/home?action=init&transientState=false&filterTag=keep&file=sub%2Fmissing.xml")
        val throughAFile = script.get("/home?action=init&transientState=false&file=suite-a.xml%2Fmissing.xml")

        assertEquals("""{"result":{"fail":"missing.xml does not exist. doing nothing."}}""", recorded.body)
        assertEquals("""{"result":{"fail":"sub/missing.xml does not exist. doing nothing."}}""", withMore.body)
        assertEquals("""{"result":{"fail":"suite-a.xml/missing.xml does not exist. doing nothing."}}""", throughAFile.body)
        assertEquals(before, script.definitions)
        assertEquals(true to Tags.of("premium"), script.engine.readOnly to script.engine.tagFilter)
        val history = script.engine.history
        assertEquals(1, history.entries().size)
    }

    /**
     * The recordings `automation-home-init-not-a-document` and its effect, and the scenario "Path outside
     * the definitions location": a file outside the definitions location is not read, and the definitions
     * stay as they are (DEV-27). The file of the test is a document that would replace them if it were read.
     */
    @Test
    fun anInitFromAFileOutsideTheDefinitionsLocationIsRefusedAndTheFileIsNotRead() {
        val location = location()
        val outside = Files.writeString(location.resolveSibling("outside.xml"), SUITE_A)
        val script = script(location)
        script.engine.tagFilter = Tags.of("premium")
        val before = script.definitions

        fun refusal(name: String) =
            """{"result":{"fail":"Unable to load service definitions with filename: '$name'. """ +
                """It is not inside the definitions location of the server."}}"""

        val names =
            mapOf(
                "%2Fetc%2Fhosts" to "/etc/hosts",
                outside.toString().replace("/", "%2F") to outside.toString(),
                "..%2Foutside.xml" to "../outside.xml",
                "sub%2F..%2F..%2Foutside.xml" to "sub/../../outside.xml",
                "..%2Fdefinitions-other%2Fsuite-a.xml" to "../definitions-other/suite-a.xml",
                ".." to "..",
            )
        for ((encoded, name) in names) {
            val answer = script.get("/home?action=init&type=json&transientState=false&filterTag=keep&file=$encoded")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, name)
            assertEquals(refusal(name), answer.body, name)
        }
        assertEquals(before, script.definitions)
        assertEquals(true to Tags.of("premium"), script.engine.readOnly to script.engine.tagFilter)
        val listed = script.document("/definitions").objects("serviceDefinitions")
        assertEquals(listOf("Customers", "Orders", "Billing"), listed.map { it.text("serviceName") })
    }

    /**
     * A link inside the definitions location that leads outside is followed no further than a name that
     * leads outside: the document that it leads to is not read. A test on a file system without links has
     * nothing to show.
     */
    @Test
    fun aLinkThatLeadsOutOfTheDefinitionsLocationIsNotFollowed() {
        val location = location()
        val outside = Files.createDirectory(location.resolveSibling("elsewhere"))
        Files.writeString(outside.resolve("suite-b.xml"), SUITE_A)
        try {
            Files.createSymbolicLink(location.resolve("linked.xml"), outside.resolve("suite-b.xml"))
            Files.createSymbolicLink(location.resolve("linked"), outside)
            Files.createSymbolicLink(location.resolve("inside.xml"), location.resolve("suite-a.xml"))
        } catch (_: IOException) {
            return
        } catch (_: UnsupportedOperationException) {
            return
        }
        val script = script(location)
        val before = script.definitions

        fun failOf(name: String) = script.result("/home?action=init&file=$name").text("fail")

        val notInside = "It is not inside the definitions location of the server."
        assertEquals("Unable to load service definitions with filename: 'linked.xml'. $notInside", failOf("linked.xml"))
        assertEquals("Unable to load service definitions with filename: 'linked/suite-b.xml'. $notInside", failOf("linked%2Fsuite-b.xml"))
        assertEquals("linked/missing.xml does not exist. doing nothing.", failOf("linked%2Fmissing.xml"))
        // A link that leads to itself leads to no file.
        Files.createSymbolicLink(location.resolve("loop.xml"), Path.of("loop.xml"))
        assertEquals("loop.xml does not exist. doing nothing.", failOf("loop.xml"))
        assertEquals(before, script.definitions)
        // A link that stays inside the location is a way to the document like any other.
        val byLink = script.get("/home?action=init&file=inside.xml")
        assertEquals("""{"result":{"success":"Loaded definitions from inside.xml"}}""", byLink.body)
        assertEquals(suiteA, contentOf(script.definitions))
    }

    /**
     * The recordings `automation-home-init-url` and its effect, and the scenario "Remote document": the
     * server reads no URL (DEV-27). No request is made to it, and the definitions stay as they are, also
     * when the request names a file as well.
     */
    @Test
    fun anInitFromAUrlIsRefusedAndNoRequestIsMadeToTheUrl() {
        val script = script()
        val before = script.definitions
        val text = "Unable to load service definitions from a URL. The server reads a file inside its definitions location only."

        ServerSocket().use { remote ->
            remote.bind(RawHttp.loopback(0))
            remote.soTimeout = NO_REQUEST_MILLIS
            val url = "http%3A%2F%2F127.0.0.1%3A${remote.localPort}%2Fsuite-a.xml"

            val recorded = script.get("/home?action=init&type=json&transientState=true&url=$url")
            val withFile = script.get("/home?action=init&type=json&transientState=false&filterTag=keep&file=suite-a.xml&url=$url")
            val empty = script.get("/home?action=init&file=suite-a.xml&url=")

            for (answer in listOf(recorded, withFile, empty)) assertEquals("""{"result":{"fail":"$text"}}""", answer.body)
            assertFailsWith<SocketTimeoutException> { remote.accept() }
        }
        assertEquals(before, script.definitions)
        assertEquals(true to Tags.EMPTY, script.engine.readOnly to script.engine.tagFilter)
    }

    /**
     * A file inside the definitions location that is no definitions document of Mockey 2.x leaves the
     * definitions as they are, where Mockey 2.x deleted them before it looked (DEV-27). Among such files
     * are the ones in which the server stores its own definitions.
     */
    @Test
    fun anInitFromAFileThatIsNoDefinitionsDocumentChangesNothing() {
        val location = location()
        Files.writeString(location.resolve("hosts"), "127.0.0.1 localhost\n")
        Files.writeString(location.resolve("mockey.json"), """{"formatVersion":1}""")
        Files.writeString(location.resolve("other.xml"), """<?xml version="1.0"?><project name="other"/>""")
        Files.writeString(location.resolve("broken.xml"), """<mockservice version="1.0"><service name="Half">""")
        Files.writeString(location.resolve("entities.xml"), """<!DOCTYPE mockservice [<!ENTITY x "y">]><mockservice version="1.0"/>""")
        Files.writeString(location.resolve("empty.xml"), "")
        Files.createDirectory(location.resolve("folder"))
        val script = script(location)
        val before = script.definitions

        for (name in listOf("hosts", "mockey.json", "other.xml", "broken.xml", "entities.xml", "empty.xml", "folder", ".", "")) {
            val answer = script.get("/home?action=init&type=json&transientState=false&filterTag=keep&file=$name")

            val text = "Unable to load service definitions with filename: '$name'. It is no definitions document of Mockey 2.x."
            assertEquals("""{"result":{"fail":"$text"}}""", answer.body, name)
        }
        assertEquals(before, script.definitions)
        assertEquals(true to Tags.EMPTY, script.engine.readOnly to script.engine.tagFilter)
    }

    /**
     * A request for an init that names neither a file nor a URL gets a result that says so. Mockey 2.x
     * answered it with an empty result.
     */
    @Test
    fun anInitThatNamesNoFileGetsAResultThatSaysSo() {
        val script = script()
        val before = script.definitions

        val answer = script.get("/home?action=init&type=json&transientState=true")

        assertEquals("""{"result":{"fail":"The request names no file to load definitions from."}}""", answer.body)
        assertEquals(before, script.definitions)
    }

    /**
     * A document is named by its path from the definitions location, or by its full path, and it can lie in
     * a directory of the location. The result repeats the name as the request gives it.
     */
    @Test
    fun aDocumentIsNamedByItsPathFromTheDefinitionsLocationOrByItsFullPath() {
        val location = location()
        Files.createDirectories(location.resolve("suites/night"))
        Files.writeString(location.resolve("suites/night/b.xml"), SUITE_A)
        val full = location.resolve("suite-a.xml").toString()

        for (name in listOf("suites/night/b.xml", "./suites/../suite-a.xml", full)) {
            val script = script(location)

            val answer = script.get("/home?action=init&file=${name.replace("/", "%2F")}")

            assertEquals(documentOf("""{"result":{"success":"Loaded definitions from $name"}}"""), documentOf(answer.body), name)
            assertEquals(suiteA, contentOf(script.definitions), name)
        }
    }

    /**
     * A document of Mockey 2.x can refer to the files of its services, as the main file of a definitions
     * directory does. The files that it refers to in its own directory are read with it.
     */
    @Test
    fun theFilesThatADocumentRefersToInItsOwnDirectoryAreReadWithIt() {
        val location = location()
        val directory = Files.createDirectories(location.resolve("legacy/depot"))
        Files.writeString(location.resolve("legacy/mock_service_definitions.xml"), MAIN_FILE)
        Files.writeString(directory.resolve("depot.xml"), SERVICE_FILE)
        val script = script(location)

        val answer = script.get("/home?action=init&file=legacy%2Fmock_service_definitions.xml")

        assertEquals("""{"result":{"success":"Loaded definitions from legacy/mock_service_definitions.xml"}}""", answer.body)
        val services = listOf(listOf("Depot", "depot", ResponseMode.STATIC, emptyList<String>(), listOf("stocked" to "in stock")))
        assertEquals(services to listOf("Full shelves"), contentOf(script.definitions))
        assertEquals("in stock", script.get("/service/depot").body)
    }

    /**
     * A file that a document refers to and that isn't there doesn't stop the init, as it stops no import
     * of definitions of Mockey 2.x: what the other files hold is loaded, and the log names the file that
     * is missing.
     */
    @Test
    fun aFileThatADocumentRefersToAndThatIsNotThereDoesNotStopTheInit() {
        val location = location()
        val directory = Files.createDirectories(location.resolve("legacy/depot"))
        val reference = """<serviceref file="depot/depot.xml"/>"""
        val twoReferences = MAIN_FILE.replace(reference, reference + "\n" + """<serviceref file="depot/gone.xml"/>""")
        Files.writeString(location.resolve("legacy/mock_service_definitions.xml"), twoReferences)
        Files.writeString(directory.resolve("depot.xml"), SERVICE_FILE)
        val script = script(location)
        ServerLogging.configure(LogLevel.WARN)
        lateinit var answer: RawHttp.Answer

        val log = TestLog.of { answer = script.get("/home?action=init&file=legacy%2Fmock_service_definitions.xml") }

        assertEquals("""{"result":{"success":"Loaded definitions from legacy/mock_service_definitions.xml"}}""", answer.body)
        assertEquals(listOf("Depot"), script.definitions.services.map { it.name })
        val warning = TestLog.messages(log).single()
        assertTrue(warning.startsWith("Of the definitions of legacy/mock_service_definitions.xml: ") && "gone.xml" in warning, log)
    }

    /**
     * A document that holds no definitions is a document all the same: after it, the server has none. That
     * holds also for one that names a default plan which it doesn't have.
     */
    @Test
    fun anInitFromADocumentWithoutDefinitionsLeavesNone() {
        val location = location()
        Files.writeString(location.resolve("nothing.xml"), """<?xml version="1.0" encoding="UTF-8"?><mockservice version="1.0"/>""")
        Files.writeString(location.resolve("no-plan.xml"), """<mockservice default_service_plan_id="7" version="1.0"/>""")

        for (name in listOf("nothing.xml", "no-plan.xml")) {
            val script = script(location)

            val answer = script.get("/home?action=init&file=$name")

            assertEquals("""{"result":{"success":"Loaded definitions from $name"}}""", answer.body, name)
            assertEquals(Definitions.EMPTY, script.definitions, name)
        }
    }

    /**
     * The parameter `transientState` of an init is the read-only mode of the server: `true`, in any letter
     * case, switches it on, and every other text switches it off. Without the parameter, the mode stays as
     * it is. A server in writable mode stores the definitions of the document in the place of the stored
     * ones, and a server in read-only mode leaves the stored ones as they are.
     */
    @Test
    fun theTransientStateOfAnInitIsTheReadOnlyModeOfTheServer() {
        class Case(
            val writableBefore: Boolean,
            val transientState: String?,
            val writableAfter: Boolean,
        )

        val cases =
            listOf(
                Case(writableBefore = false, transientState = "true", writableAfter = false),
                Case(writableBefore = false, transientState = "false", writableAfter = true),
                Case(writableBefore = false, transientState = null, writableAfter = false),
                Case(writableBefore = true, transientState = "true", writableAfter = false),
                Case(writableBefore = true, transientState = "TRUE", writableAfter = false),
                Case(writableBefore = true, transientState = "false", writableAfter = true),
                Case(writableBefore = true, transientState = null, writableAfter = true),
                Case(writableBefore = false, transientState = "yes", writableAfter = true),
                Case(writableBefore = false, transientState = "", writableAfter = true),
                Case(writableBefore = true, transientState = "+true", writableAfter = true),
            )
        for (case in cases) {
            val storage = InMemoryDefinitionStorage()
            val script = script(engine = engineOn(storage, writable = true))
            val stored = runBlocking { storage.load() }
            runBlocking { script.engine.setReadOnly(!case.writableBefore) }
            val parameter = case.transientState?.let { "&transientState=$it" }.orEmpty()
            val named = "from ${case.writableBefore} with $parameter"

            val answer = script.get("/home?action=init&file=suite-a.xml$parameter")

            assertEquals("""{"result":{"success":"Loaded definitions from suite-a.xml"}}""", answer.body, named)
            assertEquals(suiteA, contentOf(script.definitions), named)
            assertEquals(!case.writableAfter, script.engine.readOnly, named)
            val storedNow = runBlocking { storage.load() }
            assertEquals(if (case.writableAfter) script.definitions else stored, storedNow, named)
            assertEquals(listOf("Customers", "Orders", "Billing"), stored.services.map { it.name }, named)
        }
    }

    /**
     * A server that is to store the definitions of the document and can't holds them in memory, in read-only
     * mode, and the definitions location holds the old ones. The result says so, and the log says why.
     */
    @Test
    fun definitionsThatCannotBeStoredAreHeldInMemoryAndTheResultSaysSo() {
        val fixture =
            runBlocking { MockeyEngine.create() }.use {
                ShimFixture.services(it)
                it.definitions
            }
        val storage = FullDisk(fixture)
        val script = ShimUnderTest.start(engineOn(storage, writable = false), location = location()).also { started += it }
        ServerLogging.configure(LogLevel.INFO)
        lateinit var answer: RawHttp.Answer

        val log = TestLog.of { answer = script.get("/home?action=init&transientState=false&filterTag=keep&file=suite-a.xml") }

        val text =
            "Loaded definitions from suite-a.xml. The server could not store them in its definitions location. " +
                "It holds them in memory, in read-only mode. The log of the server says why."
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals("""{"result":{"fail":"$text"}}""", answer.body)
        assertEquals(suiteA, contentOf(script.definitions))
        assertEquals(true to Tags.of("keep"), script.engine.readOnly to script.engine.tagFilter)
        assertEquals(fixture, runBlocking { storage.load() })
        val error = "The definitions of suite-a.xml cannot be stored. The server holds them in memory, in read-only mode."
        assertEquals(listOf(error), TestLog.messages(log, "ERROR").take(1))
        assertTrue("StorageException: No space is left on /var/lib/mockey." in log, log)
    }

    /**
     * The recording `automation-home-delete-all`, and the scenario "Reset": the server has no services, no
     * plans, and no history entries, and the result has the text of Mockey 2.x, also without `type=json`.
     */
    @Test
    fun deleteAllServicesMakesACleanSlate() {
        for (query in listOf("action=deleteAllServices&type=json", "action=deleteAllServices", "action=deleteAllServices&filterTag=keep")) {
            val script = script()
            script.engine.tagFilter = Tags.of("premium")
            script.get("/service/customers")

            val answer = script.get("/home?$query")

            assertEquals("HTTP/1.1 200 OK", answer.statusLine, query)
            assertEquals("""{"result":{"success":"All is deleted. You have a clean slate. Enjoy."}}""", answer.body, query)
            assertEquals(Definitions.EMPTY, script.definitions, query)
            assertEquals(emptyList(), script.engine.history.entries(), query)
            assertEquals(true to Tags.EMPTY, script.engine.readOnly to script.engine.tagFilter, query)
        }
    }

    /** A reset of a server in writable mode empties the definitions location as well, as the reset of the admin API does. */
    @Test
    fun deleteAllServicesOfAServerInWritableModeEmptiesTheStoreAsWell() {
        val storage = InMemoryDefinitionStorage()
        val script = script(engine = engineOn(storage, writable = true))
        val stored = runBlocking { storage.load() }

        val answer = script.get("/home?action=deleteAllServices&transientState=true")

        assertEquals("""{"result":{"success":"All is deleted. You have a clean slate. Enjoy."}}""", answer.body)
        assertEquals(3 to Definitions.EMPTY, stored.services.size to runBlocking { storage.load() })
        assertFalse(script.engine.readOnly)
    }

    /**
     * The requirement "Legacy initialization and reset endpoint": every other request to `/home` gets the
     * status 404, as a request for a path that the mock listener doesn't serve gets it. Mockey 2.x answered
     * such a request with its home page, and it set the tag filter of a `filterTag` on the way. An action
     * is named as it's written.
     */
    @Test
    fun everyOtherRequestToHomeGetsTheStatus404() {
        val script = script()
        script.engine.tagFilter = Tags.of("premium")
        val before = script.definitions
        val queries =
            listOf(
                "",
                "?",
                "?action=",
                "?action=Init&file=suite-a.xml",
                "?action=INIT&file=suite-a.xml",
                "?action=+init&file=suite-a.xml",
                "?action=deleteallservices",
                "?action=delete",
                "?file=suite-a.xml&type=json",
                "?filterTag=keep",
                "?plan_id=1",
                "?Action=init&file=suite-a.xml",
            )

        for (query in queries) {
            val answer = script.get("/home$query")

            assertEquals("HTTP/1.1 404 Not Found" to "", answer.statusLine to answer.body, query)
            assertEquals(listOf("Content-Length: 0", "Connection: close"), answer.headers, query)
        }
        val posted = script.post("/home", "file=suite-a.xml&transientState=false")
        assertEquals("HTTP/1.1 404 Not Found" to "", posted.statusLine to posted.body)
        assertEquals(before, script.definitions)
        assertEquals(true to Tags.of("premium"), script.engine.readOnly to script.engine.tagFilter)
    }

    /**
     * Two requests that replace the definitions at the same time take turns. After them, the server has the
     * definitions of the one document or of the other, and never those of both.
     */
    @Test
    fun twoInitsAtTheSameTimeDoNotMixTheirDefinitions() {
        val location = location()
        Files.writeString(location.resolve("depot.xml"), SERVICE_FILE)
        val script = script(location)
        val depot = listOf(listOf("Depot", "depot", ResponseMode.STATIC, NO_TAGS, listOf("stocked" to "in stock"))) to emptyList<String>()
        val clients = Executors.newFixedThreadPool(2)
        try {
            repeat(TURNS) { turn ->
                val answers =
                    listOf("suite-a.xml", "depot.xml").map { name ->
                        clients.submit<String> { script.get("/home?action=init&file=$name").body }
                    }

                answers.forEach { assertTrue("\"success\"" in it.get(), it.get()) }
                val content = contentOf(script.definitions)
                assertTrue(content == suiteA || content == depot, "After turn $turn, the server has $content")
            }
        } finally {
            clients.shutdownNow()
        }
    }

    /** The parameters of an init come from a form as well, and the first `action` of a request counts. */
    @Test
    fun anInitTakesItsParametersFromAFormAsWell() {
        val script = script()

        val form = "type=json&transientState=true&file=suite-a.xml&filterTag=keep"
        val answer = script.post("/home?action=init&action=deleteAllServices", form)

        assertEquals("""{"result":{"success":"Loaded definitions from suite-a.xml"}}""", answer.body)
        assertEquals(suiteA to Tags.of("keep"), contentOf(script.definitions) to script.engine.tagFilter)
    }

    /**
     * The scenario "Identifier round trip", after an init: the identifiers that `/definitions` states for
     * the services and the scenarios of the document are the ones that `/config/service` takes. Every init
     * gives the definitions of its document new identifiers, as every load did in Mockey 2.x (DEV-20), so a
     * script reads them after the init, or it names its services.
     */
    @Test
    fun theIdentifiersOfTheLoadedDefinitionsAreStatedAndTaken() {
        val script = script()
        val init = "/home?action=init&type=json&transientState=true&file=suite-a.xml"

        fun untagged() = script.document("/definitions").objects("serviceDefinitions").single { it.text("serviceName") == "Suite untagged" }

        script.get(init)
        val first = untagged()
        val scenario = first.objects("scenarioArray").single()
        val query = "serviceId=${first.text("serviceId")}&scenarioId=${scenario.text("scenarioId")}&hangTime=20"
        val updated = script.result("/config/service?$query")
        script.get(init)
        val second = untagged()
        val afterTheSecondInit = script.result("/config/service?$query")

        val stated = listOf("success", "serviceName", "scenarioName").map(updated::text)
        assertEquals(listOf("updated", "Suite untagged", "untagged answer"), stated)
        assertEquals(first.text("serviceId") to scenario.text("scenarioId"), updated.text("serviceId") to updated.text("scenarioId"))
        assertNotEquals(first.text("serviceId"), second.text("serviceId"))
        assertEquals(setOf("fail"), afterTheSecondInit.keys)
    }

    /**
     * What the server changes for a request to `/home` is in its log: which document took the place of the
     * definitions, what the definitions hold now, and what of the document was not taken as it is. The log
     * names the document by where it is, and it holds nothing of the request.
     */
    @Test
    fun theLogSaysWhichDocumentTookThePlaceOfTheDefinitions() {
        val location = location()
        Files.createDirectory(location.resolve("detour"))
        // A name in the document that breaks the line would begin a line of its own in the log.
        val dangling = SUITE_A.replace("""default_scenario_id="1"""", """default_scenario_id="9"""")
        val broken = dangling.replace("Suite tagged", "Suite&#10;tagged").replace("Suite untagged", "Suite&#x2028;untagged")
        Files.writeString(location.resolve("dangling.xml"), broken)
        val script = script(location)
        ServerLogging.configure(LogLevel.INFO)

        val log =
            TestLog.of {
                script.get("/home?action=init&file=detour%2F..%2Fdangling.xml&filterTag=tk-71c0")
                script.get("/home?action=deleteAllServices&filterTag=tk-71c0")
                script.get("/home?action=init&file=missing-tk-71c0.xml")
                script.get("/home?filterTag=tk-71c0")
            }

        val start = "A request for /home of the legacy shim"
        val content = "2 services with 2 scenarios, 0 plans, and 0 twist configurations"
        assertEquals(
            listOf(
                "$start put the definitions of dangling.xml in the place of the definitions of the server: $content.",
                "$start reset the server. It has no definitions and no history now.",
            ),
            TestLog.messages(log, "INFO"),
        )
        val warnings = TestLog.messages(log, "WARN")
        assertEquals(2, warnings.size, log)
        assertTrue(warnings.all { it.startsWith("Of the definitions of dangling.xml: The service 'Suite") }, log)
        assertTrue(warnings.first().startsWith("Of the definitions of dangling.xml: The service 'Suite tagged' names the scenario 9"), log)
        assertTrue(warnings.last().startsWith("Of the definitions of dangling.xml: The service 'Suite untagged' names the scenario 9"), log)
        assertEquals(4, log.lines().count { it.isNotEmpty() }, log)
        assertFalse("tk-71c0" in log, log)
        assertFalse("detour" in log, log)
    }

    /**
     * What the import of a document doesn't take as the document has it is in the log as a warning, one line
     * for each remark: a reference that names nothing, a service that another service of the document keeps
     * out, and a rule set that is not well formed.
     */
    @Test
    fun theLogSaysWhatOfTheDocumentWasNotTakenAsItIs() {
        val location = location()
        val twice = SUITE_A.replace("""url="suite/untagged"""", """url="suite/tagged"""")
        val rules = """<request_inspector_json_rules enable_flag="true"><![CDATA[{broken]]></request_inspector_json_rules>"""
        val broken = twice.replaceFirst("""<request_inspector_json_rules enable_flag="false"/>""", rules)
        val dangling = broken.replaceFirst("""default_scenario_id="1"""", """default_scenario_id="9"""")
        Files.writeString(location.resolve("remarks.xml"), dangling)
        val script = script(location)
        ServerLogging.configure(LogLevel.WARN)

        val log = TestLog.of { script.get("/home?action=init&file=remarks.xml") }

        val start = "Of the definitions of remarks.xml: "
        val taken = "the service 'Suite tagged' already uses its mock URL pattern 'suite/tagged'."
        val expected =
            listOf(
                "${start}The service 'Suite tagged' names the scenario 9 as its default scenario",
                "${start}The service 'Suite untagged' was not added: $taken",
                "${start}The scenario 'untagged answer' was not added, because its service 'Suite untagged' was not.",
                "${start}The service 'Suite tagged': The request rules of the service cannot be read.",
            )
        val warnings = TestLog.messages(log)
        assertEquals(expected.size, warnings.size, log)
        for ((warning, beginning) in warnings.zip(expected)) {
            assertTrue(warning.startsWith(beginning), "$warning is to begin with $beginning")
        }
        assertEquals(listOf("Suite tagged"), script.definitions.services.map { it.name })
    }

    /**
     * The log names a document by the names of its files. A name that would break a line of the log has a
     * space in the place of the line break. A test on a file system that takes no such name has nothing to
     * show.
     */
    @Test
    fun aDocumentWhoseNameBreaksALineIsNamedOnOneLineOfTheLog() {
        val location = location()
        try {
            Files.writeString(location.resolve("two\nlines.xml"), SUITE_A)
        } catch (_: IOException) {
            return
        } catch (_: InvalidPathException) {
            return
        }
        val script = script(location)
        ServerLogging.configure(LogLevel.INFO)
        lateinit var answer: RawHttp.Answer

        val log = TestLog.of { answer = script.get("/home?action=init&file=two%0Alines.xml") }

        assertEquals("""{"result":{"success":"Loaded definitions from two\nlines.xml"}}""", answer.body)
        assertEquals(1, log.lines().count { it.isNotEmpty() }, log)
        val line = TestLog.messages(log).single()
        assertTrue(line.startsWith("A request for /home of the legacy shim put the definitions of two lines.xml in"), log)
    }

    /** A storage that holds [stored] and takes nothing more. */
    private class FullDisk(
        private val stored: Definitions,
    ) : DefinitionStorage {
        override suspend fun load(): Definitions = stored

        override suspend fun save(definitions: Definitions): Unit = throw StorageException("No space is left on /var/lib/mockey.")
    }

    private companion object {
        /** How long a test waits for a request that is not to come. */
        const val NO_REQUEST_MILLIS = 300

        /** How often two requests at the same time are tried. Without their turns, some of that many mix their definitions. */
        const val TURNS = 60

        /** The tags of a service that has none. */
        val NO_TAGS = emptyList<String>()

        /** The document `suite-a.xml` of the recordings, which is the parity fixture `automation-init.xml`. */
        val SUITE_A =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <mockservice version="1.0" xml:lang="en-US">
            <proxy_settings proxy_enabled="false" proxy_url=""/>
            <service default_real_url_index="0" default_scenario_id="1" description="A service that carries the tag keep."
                error_scenario_id="0" hang_time="0" name="Suite tagged" request_inspector_name="null" service_response_type="1"
                tag="keep" url="suite/tagged">
            <request_inspector_json_rules enable_flag="false"/>
            <response_schema enable_flag="false"/>
            <scenario hang_time="0" http_method_type="" http_resp_status_code="200" id="1" name="tagged answer" tag="">
            <scenario_match scenario_match_evaluation_rules_flag="false"><![CDATA[]]></scenario_match>
            <scenario_response><![CDATA[suite tagged]]></scenario_response>
            <scenario_response_header/>
            </scenario>
            </service>
            <service default_real_url_index="0" default_scenario_id="1" description="A service without a tag."
                error_scenario_id="0" hang_time="0" name="Suite untagged" request_inspector_name="null" service_response_type="1"
                tag="" url="suite/untagged">
            <request_inspector_json_rules enable_flag="false"/>
            <response_schema enable_flag="false"/>
            <scenario hang_time="0" http_method_type="" http_resp_status_code="200" id="1" name="untagged answer" tag="">
            <scenario_match scenario_match_evaluation_rules_flag="false"><![CDATA[]]></scenario_match>
            <scenario_response><![CDATA[suite untagged]]></scenario_response>
            <scenario_response_header/>
            </scenario>
            </service>
            </mockservice>
            """.trimIndent()

        /** The main file of a definitions directory of Mockey 2.x, which refers to the file of its service. */
        val MAIN_FILE =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <mockservice version="1.0" xml:lang="en-US">
            <serviceref file="depot/depot.xml"/>
            <service_plan description="" id="1" name="Full shelves" tag="">
            <plan_item hang_time="0" scenario_name="stocked" service_name="Depot" service_response_type="1"/>
            </service_plan>
            </mockservice>
            """.trimIndent()

        /** The file of a service in a definitions directory of Mockey 2.x. */
        val SERVICE_FILE =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <mockservice version="1.0" xml:lang="en-US">
            <service default_real_url_index="0" default_scenario_id="1" description="" error_scenario_id="0" hang_time="0"
                name="Depot" request_inspector_name="null" service_response_type="1" tag="" url="depot">
            <scenario hang_time="0" http_method_type="" http_resp_status_code="200" id="1" name="stocked" tag="">
            <scenario_match scenario_match_evaluation_rules_flag="false"><![CDATA[]]></scenario_match>
            <scenario_response><![CDATA[in stock]]></scenario_response>
            <scenario_response_header/>
            </scenario>
            </service>
            </mockservice>
            """.trimIndent()
    }
}
