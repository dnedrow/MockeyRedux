package io.github.dnedrow.mockey.core.inspection

import com.example.inspectors.SampleRequestInspector
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

/**
 * The cases of the legacy `com.mockey.plugin.TestPluginStore`, with the names of its test methods. The legacy
 * class commented out every check of its cases. What those checks asked of the plug-in store of legacy is
 * asked here of the inspectors that the service loader finds: the sample inspector is listed, an inspector that
 * is named twice is listed once, and an inspector is created from the name of its class.
 *
 * The sample inspector is the port of the legacy `com.mockey.plugin.SampleRequestInspector`, which keeps that
 * class name as its name.
 */
class LegacyPluginStoreTest {
    private val sampleName = "com.mockey.plugin.SampleRequestInspector"

    /** Returns the inspectors that the service loader finds for a provider-configuration file with the lines [providers]. */
    private fun installed(
        directory: Path,
        vararg providers: String,
    ): Inspectors {
        val file = directory.resolve("META-INF/services/${Inspector::class.java.name}")
        Files.createDirectories(file.parent)
        Files.write(file, providers.asList())
        return URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { Inspectors.installed(it) }
    }

    /** The legacy case expected two inspectors, of which the first is the sample inspector. */
    @Test
    fun testRequestInspector(
        @TempDir directory: Path,
    ) {
        val store = installed(directory, SampleRequestInspector::class.java.name, SessionCheckInspector::class.java.name)

        val list = store.list().map { it.name }

        assertEquals(2, list.size)
        assertEquals(sampleName, list[0])
    }

    /** The legacy case saved the class of the sample inspector once more and expected the list to keep its size. */
    @Test
    fun testForDuplicateRequestInspectorClassNames(
        @TempDir directory: Path,
    ) {
        val sample = SampleRequestInspector::class.java.name

        val store = installed(directory, sample, SessionCheckInspector::class.java.name, sample)
        val given = Inspectors.of(listOf(SampleRequestInspector(), SessionCheckInspector(), SampleRequestInspector()))

        assertEquals(2, store.list().size)
        assertEquals(2, given.list().size)
        assertEquals(store.list(), given.list())
    }

    /** The legacy case created the sample inspector from its class name and expected an instance. */
    @Test
    fun testInstanceCreator(
        @TempDir directory: Path,
    ) {
        val store = installed(directory, SampleRequestInspector::class.java.name)
        val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customers")
        val request = MockRequest("GET", "http://mockey.test:8080/service/customers", "foobarKey=1")

        val messages = store.messages(service, request)

        assertEquals(listOf(sampleName), store.list().map { it.name })
        assertContains(messages.single(), "the request parameter 'foobarKey' was in the request")
    }
}
