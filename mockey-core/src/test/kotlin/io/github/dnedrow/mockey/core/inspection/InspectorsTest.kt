package io.github.dnedrow.mockey.core.inspection

import com.example.inspectors.SampleRequestInspector
import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.definitions.diagnostics
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.MockRequest
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InspectorsTest {
    private val traceHeader = TraceHeaderInspector()
    private val sessionCheck = SessionCheckInspector()
    private val traceHeaderInfo = InspectorInfo("trace-header", isGlobal = true)
    private val sessionCheckInfo = InspectorInfo("session-check", isGlobal = false)

    private val pageIdRule =
        """{"key": "page_id", "desc": "page_id is mandatory", "value_rule_arg": "", "value_rule_type": "string_required"}"""

    private fun service(
        name: String,
        inspectorName: String? = null,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name, inspectorName = inspectorName)

    private fun request(
        vararg headers: Header,
        query: String? = null,
    ) = MockRequest("GET", "http://mockey.test:8080/service/customers", query, headers.toList())

    private fun inspectors(vararg inspectors: Inspector): Inspectors = Inspectors.of(inspectors.asList())

    /**
     * Returns the inspectors that the service loader finds in a class path which has, next to the classes of
     * these tests, a provider-configuration file in [directory] with the lines [providers].
     */
    private fun installed(
        directory: Path,
        vararg providers: String,
    ): Inspectors {
        val file = directory.resolve("META-INF/services/${Inspector::class.java.name}")
        Files.createDirectories(file.parent)
        Files.write(file, providers.asList())
        return URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { Inspectors.installed(it) }
    }

    @Test
    fun aGlobalInspectorInspectsTheRequestsOfEveryService() {
        val inspectors = inspectors(traceHeader)

        val messages = listOf(service("customers"), service("orders")).map { inspectors.messages(it, request()) }

        assertEquals(listOf(listOf("missing trace header"), listOf("missing trace header")), messages)
    }

    @Test
    fun anInspectorThatIsNotGlobalInspectsOnlyTheRequestsOfTheServicesThatNameIt() {
        val inspectors = inspectors(sessionCheck)
        val a = service("a", inspectorName = "session-check")
        val b = service("b")
        val other = service("c", inspectorName = "Session-Check")

        val messages = listOf(a, b, other).map { inspectors.messages(it, request()) }

        assertEquals(listOf(listOf("session-check: the request has no session cookie"), emptyList(), emptyList()), messages)
    }

    @Test
    fun aGlobalInspectorInspectsARequestOnceAlsoForAServiceThatNamesIt() {
        val inspectors = inspectors(traceHeader, sessionCheck)

        val messages = inspectors.messages(service("customers", inspectorName = "trace-header"), request())

        assertEquals(listOf("missing trace header"), messages)
    }

    @Test
    fun anInspectorWithNothingToSayGivesNoMessage() {
        val nothing = listOf<String?>(null, "", "  \n")
        val silent = nothing.mapIndexed { index, said -> StubInspector("silent-$index", isGlobal = true) { said } }
        val inspectors = Inspectors.of(silent + traceHeader + sessionCheck)
        val satisfied = request(Header("X-Trace-Id", "7"), Header("Cookie", "theme=dark; session=abc"))

        assertEquals(emptyList(), inspectors.messages(service("customers", inspectorName = "session-check"), satisfied))
    }

    @Test
    fun aMessageIsGivenAsTheInspectorWroteIt() {
        val inspectors = inspectors(StubInspector("padded", isGlobal = true) { "  two\nlines " })

        assertEquals(listOf("  two\nlines "), inspectors.messages(service("customers"), request()))
    }

    @Test
    fun theSameMessageOfTwoInspectorsIsGivenOnce() {
        val inspectors =
            inspectors(
                StubInspector("first", isGlobal = true) { "missing trace header" },
                StubInspector("second", isGlobal = true) { "something else" },
                traceHeader,
            )

        assertEquals(listOf("missing trace header", "something else"), inspectors.messages(service("customers"), request()))
    }

    @Test
    fun inspectorsAreListedAndAskedInTheOrderOfTheirNames() {
        val inspectors =
            inspectors(StubInspector("b", isGlobal = true) { "b" }, StubInspector("c") { "c" }, StubInspector("a", isGlobal = true) { "a" })

        assertEquals(listOf("a", "b", "c"), inspectors.list().map { it.name })
        assertEquals(listOf("a", "b", "c"), inspectors.messages(service("customers", inspectorName = "c"), request()))
    }

    @Test
    fun anInspectorThatFailsGivesAMessageThatNamesItAndTheOthersInspectAsUsual() {
        val inspectors = inspectors(FailingInspector(), traceHeader)

        val messages = inspectors.messages(service("customers"), request())

        assertEquals(2, messages.size)
        assertContains(messages[0], "The inspector 'always-failing' failed")
        assertContains(messages[0], "no luck with GET")
        assertEquals("missing trace header", messages[1])
    }

    @Test
    fun anErrorOfAnInspectorIsAFailureOfThatInspectorOnly() {
        val errors = listOf(AssertionError("an assertion"), NoClassDefFoundError("a.missing.Class"), StackOverflowError())
        val failing = errors.mapIndexed { index, error -> StubInspector("failing-$index", isGlobal = true) { throw error } }

        val messages = Inspectors.of(failing + traceHeader).messages(service("customers"), request())

        assertEquals(4, messages.size)
        assertContains(messages[0], "The inspector 'failing-0' failed: java.lang.AssertionError: an assertion")
        assertContains(messages[1], "The inspector 'failing-1' failed: java.lang.NoClassDefFoundError: a.missing.Class")
        assertContains(messages[2], "The inspector 'failing-2' failed: java.lang.StackOverflowError")
        assertEquals("missing trace header", messages[3])
    }

    @Test
    fun anInspectorThatWasInterruptedLeavesItsThreadInterrupted() {
        val inspectors = inspectors(StubInspector("interrupted", isGlobal = true) { throw InterruptedException("stopped") })

        val messages = inspectors.messages(service("customers"), request())

        assertTrue(Thread.interrupted())
        assertContains(messages.single(), "The inspector 'interrupted' failed")
    }

    @Test
    fun aRuntimeThatIsOutOfMemoryIsNotTakenForAFailureOfAnInspector() {
        val inspectors = inspectors(StubInspector("greedy", isGlobal = true) { throw OutOfMemoryError("pretended") })

        assertFailsWith<OutOfMemoryError> { inspectors.messages(service("customers"), request()) }
    }

    @Test
    fun theInspectorsAreListedWithTheirNamesAndWhetherTheyAreGlobal() {
        val inspectors = inspectors(traceHeader, sessionCheck)

        assertEquals(listOf(sessionCheckInfo, traceHeaderInfo), inspectors.list())
        assertEquals(setOf("session-check", "trace-header"), inspectors.names)
        assertEquals(emptyList(), inspectors.problems)
    }

    @Test
    fun anInspectorThatDoesNotSaySoIsNotGlobal() {
        assertFalse(sessionCheck.isGlobal)
        assertTrue(Inspector::class.java.getMethod("isGlobal").isDefault)
    }

    @Test
    fun whatAnInspectorSaysAboutItselfIsReadOnceWhenItIsInstalled() {
        var reads = 0
        val counting =
            object : Inspector {
                override val name: String
                    get() = " counted ".also { reads++ }

                override val isGlobal: Boolean
                    get() = true.also { reads++ }

                override fun inspect(request: MockRequest): String = "inspected"
            }
        val inspectors = inspectors(counting)

        repeat(3) { inspectors.messages(service("customers"), request()) }

        assertEquals(listOf(InspectorInfo("counted", isGlobal = true)), inspectors.list())
        assertEquals(2, reads)
    }

    @Test
    fun anInspectorWithoutANameIsLeftOut() {
        val inspectors = inspectors(StubInspector("  ", isGlobal = true) { "nameless" }, traceHeader)

        assertEquals(setOf("trace-header"), inspectors.names)
        assertEquals(listOf("missing trace header"), inspectors.messages(service("customers"), request()))
        assertContains(inspectors.problems.single(), StubInspector::class.java.name)
        assertContains(inspectors.problems.single(), "it has no name")
    }

    @Test
    fun ofTwoInspectorsWithTheSameNameTheFirstIsInstalled() {
        val twins = listOf("twin" to "first", " twin " to "second").map { (name, said) -> StubInspector(name, isGlobal = true) { said } }
        val inspectors = Inspectors.of(twins)

        assertEquals(listOf(InspectorInfo("twin", isGlobal = true)), inspectors.list())
        assertEquals(listOf("first"), inspectors.messages(service("customers"), request()))
        assertContains(inspectors.problems.single(), "another inspector has its name 'twin'")
    }

    @Test
    fun anInspectorThatCannotSayItsNameIsLeftOut() {
        val broken =
            object : Inspector {
                override val name: String
                    get() = error("no name today")

                override fun inspect(request: MockRequest): String = "inspected"
            }

        val inspectors = inspectors(broken, sessionCheck)

        assertEquals(setOf("session-check"), inspectors.names)
        assertContains(inspectors.problems.single(), "no name today")
    }

    @Test
    fun noInspectorsHaveNoNamesAndNoMessages() {
        assertEquals(emptyList(), Inspectors.NONE.list())
        assertEquals(emptySet(), Inspectors.NONE.names)
        assertEquals(emptyList(), Inspectors.NONE.messages(service("customers", inspectorName = "session-check"), request()))
    }

    @Test
    fun theInspectorsOfAProviderFileAreInstalled(
        @TempDir directory: Path,
    ) {
        val inspectors =
            installed(directory, TraceHeaderInspector::class.java.name, "# a comment", "", SessionCheckInspector::class.java.name)

        assertEquals(listOf(sessionCheckInfo, traceHeaderInfo), inspectors.list())
        assertEquals(emptyList(), inspectors.problems)
        assertEquals(
            listOf("session-check: the request has no session cookie", "missing trace header"),
            inspectors.messages(service("customers", inspectorName = "session-check"), request()),
        )
    }

    @Test
    fun aClassPathWithoutAProviderFileHasNoInspectors() {
        val inspectors = Inspectors.installed(javaClass.classLoader)

        assertEquals(emptyList(), inspectors.list())
        assertEquals(emptyList(), inspectors.problems)
    }

    @Test
    fun theClassLoaderOfTheCallingThreadIsSearchedUnlessAnotherOneIsGiven(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("META-INF/services/${Inspector::class.java.name}")
        Files.createDirectories(file.parent)
        Files.write(file, listOf(TraceHeaderInspector::class.java.name))
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader

        val names =
            URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
                thread.contextClassLoader = loader
                try {
                    Inspectors.installed().names
                } finally {
                    thread.contextClassLoader = before
                }
            }

        assertEquals(setOf("trace-header"), names)
    }

    @Test
    fun aProviderThatCannotBeLoadedOrCreatedIsLeftOutAndTheOthersAreInstalled(
        @TempDir directory: Path,
    ) {
        val inspectors =
            installed(
                directory,
                "com.example.inspectors.MissingInspector",
                UncreatableInspector::class.java.name,
                InspectorsTest::class.java.name,
                SessionCheckInspector::class.java.name,
            )

        assertEquals(listOf(sessionCheckInfo), inspectors.list())
        assertEquals(3, inspectors.problems.size)
        assertContains(inspectors.problems[0], "com.example.inspectors.MissingInspector")
        assertContains(inspectors.problems[1], UncreatableInspector::class.java.name)
        assertContains(inspectors.problems[2], InspectorsTest::class.java.name)
        inspectors.problems.forEach { assertTrue(it.startsWith("An inspector cannot be installed: "), it) }
    }

    @Test
    fun theNamesOfTheInstalledInspectorsReachTheDiagnostics(
        @TempDir directory: Path,
    ) {
        val inspectors = installed(directory, SessionCheckInspector::class.java.name, SampleRequestInspector::class.java.name)
        val checked = service("checked", inspectorName = "session-check")
        val sampled = service("sampled", inspectorName = "com.mockey.plugin.SampleRequestInspector")
        val legacy = service("legacy", inspectorName = "com.example.LegacyInspector")

        val diagnostics = Definitions(services = listOf(checked, sampled, legacy)).diagnostics(inspectors.names)

        assertEquals(listOf(DiagnosticKind.UNKNOWN_INSPECTOR), diagnostics.map { it.kind })
        assertEquals(legacy.id, diagnostics.single().serviceId)
        assertContains(diagnostics.single().message, "'com.example.LegacyInspector'")
    }

    @Test
    fun theMessagesOfTheInspectorsComeBeforeThoseOfTheRequestRulesAndEachIsGivenOnce() {
        val rules = """{"headers": [$pageIdRule]}"""
        val customers = service("customers", inspectorName = "session-check").copy(requestRules = rules, requestRulesEnabled = true)
        val ruleSets = RuleSets(Definitions(services = listOf(customers)))
        val ruleMessage = ruleSets.ruleMessages(customers, request()).single()
        val echo = StubInspector("echo", isGlobal = true) { ruleMessage }

        val messages = inspectionMessages(customers, request(), inspectors(traceHeader, sessionCheck, echo), ruleSets)

        assertEquals(listOf(ruleMessage, "session-check: the request has no session cookie", "missing trace header"), messages)
        assertContains(ruleMessage, "'headers' for the key 'page_id'")
    }

    @Test
    fun withoutInspectorsARequestGetsTheMessagesOfTheRequestRules() {
        val customers = service("customers").copy(requestRules = """{"headers": [$pageIdRule]}""", requestRulesEnabled = true)
        val ruleSets = RuleSets(Definitions(services = listOf(customers)))

        assertEquals(ruleSets.ruleMessages(customers, request()), inspectionMessages(customers, request(), Inspectors.NONE, ruleSets))
        assertEquals(emptyList(), inspectionMessages(customers, request(Header("page_id", "home")), Inspectors.NONE, ruleSets))
    }

    /**
     * The services of the parity fixture `inspection` and the requests that go before the recording
     * `inspection-history-messages`, with the sample inspector of legacy installed, as it is in the legacy
     * build. Only the last request carries the parameter that the sample inspector reports.
     */
    @Test
    fun theSampleInspectorReportsTheRequestOfTheInspectionRecordingThatCarriesItsParameter() {
        val rules = """{"headers": [$pageIdRule]}"""
        val checked = service("inspection checked").copy(requestRules = rules, requestRulesEnabled = true)
        val disabled = service("inspection disabled").copy(requestRules = rules, requestRulesEnabled = false)
        val ruleSets = RuleSets(Definitions(services = listOf(checked, disabled)))
        val sample = inspectors(SampleRequestInspector())

        val messages =
            listOf(
                checked to request(query = "ticker=msft&date=tomorrow"),
                checked to request(Header("page_id", "home"), query = "ticker=goog"),
                disabled to request(query = "ticker=msft"),
                disabled to request(query = "foobarKey=1"),
            ).map { (service, request) -> inspectionMessages(service, request, sample, ruleSets) }

        assertEquals(listOf(1, 0, 0, 1), messages.map { it.size })
        assertContains(messages.first().single(), "'headers' for the key 'page_id'")
        assertContains(messages.last().single(), "the request parameter 'foobarKey' was in the request")
    }
}
