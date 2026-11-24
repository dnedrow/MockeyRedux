package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.OutboundProxyChanges
import io.github.dnedrow.mockey.core.definitions.ScenarioChanges
import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.Sample
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.core.storage.loaded
import io.github.dnedrow.mockey.core.storage.stored
import io.github.dnedrow.mockey.core.storage.textOf
import io.github.dnedrow.mockey.core.storage.treeOf
import io.github.dnedrow.mockey.core.storage.write
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An engine that keeps its definitions in files: what is in the definitions location, and when. */
class EngineFileStorageTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path
        get() = directory.resolve("definitions")

    /** Returns an engine on the location [root] with the definitions that the location holds. */
    private fun engineOnLocation(writable: Boolean): MockeyEngine {
        val files = FileDefinitionStorage(root)
        val definitions = runBlocking { files.load() }
        return engineOf(definitions) {
            storage = files
            readOnly = !writable
        }
    }

    private fun addOrders(): Change<Service> = ServiceChanges.create { it.copy(name = "Orders", mockUrl = "orders") }

    @Test
    fun aChangeIsInTheLocationWhenItsOperationReturns(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine = engineOnLocation(writable = true)

            val renamed = engine.change(ScenarioChanges.update(Sample.customers.id, Sample.gold.id) { it.copy(name = "premium customer") })

            assertIs<ChangeResult.Changed<*>>(renamed)
            assertContains(textOf(root, "services/customers/scenarios/gold-customer.json"), "\"name\": \"premium customer\"")
            assertEquals(engine.definitions, loaded(root))
        }

    @Test
    fun inReadOnlyModeNothingInTheLocationIsCreatedChangedOrDeleted(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val before = treeOf(root)
            val engine = engineOnLocation(writable = false)

            val created = assertIs<ChangeResult.Changed<Service>>(engine.change(addOrders()))
            engine.change(ServiceChanges.update(created.value.id) { it.copy(responseMode = ResponseMode.STATIC, delayMillis = 5) })
            engine.change(ServiceChanges.delete(Sample.customers.id))
            val handled = engine.handle(requestFor("orders"))

            assertEquals("Orders", handled.entry.service?.name)
            assertEquals(listOf("Orders", "Orders"), engine.definitions.services.map { it.name })
            assertEquals(before, treeOf(root))
        }

    @Test
    fun inReadOnlyModeALocationThatDoesNotExistIsNotCreated(): Unit =
        runBlocking {
            val engine = engineOnLocation(writable = false)

            engine.change(addOrders())

            assertEquals(listOf("Orders"), engine.definitions.services.map { it.name })
            assertFalse(root.exists())
        }

    @Test
    fun leavingReadOnlyModePutsTheChangesThatWereMadeInItIntoTheLocation(): Unit =
        runBlocking {
            val engine = engineOnLocation(writable = false)
            engine.change(addOrders())

            engine.setReadOnly(false)

            assertEquals(listOf("Orders"), loaded(root).services.map { it.name })
            assertEquals(engine.definitions, loaded(root))
        }

    @Test
    fun loadingALocationAndLeavingReadOnlyModeChangesNoFile(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val before = treeOf(root)
            val engine = engineOnLocation(writable = false)

            engine.setReadOnly(false)

            assertFalse(engine.readOnly)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun aChangeThatCannotBeWrittenToTheLocationFailsAndIsNotMade(): Unit =
        runBlocking {
            write(root, "services", "A file where the directory of the services belongs.")
            val engine = engineOnLocation(writable = true)

            val failure = assertFailsWith<StorageException> { engine.change(addOrders()) }

            assertEquals("The definitions could not be saved to $root: ${root.resolve("services")} is in the way.", failure.message)
            assertEquals(Definitions.EMPTY, engine.definitions)
        }

    @Test
    fun thePasswordOfTheOutboundProxyIsInNoFile(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine = engineOnLocation(writable = true)
            val proxy =
                OutboundProxy("http://proxy.example.net:8080", enabled = true, credentials = ProxyCredentials("proxy-user", "s3cret"))

            engine.change(OutboundProxyChanges.update { proxy })

            assertEquals(proxy, engine.definitions.settings.outboundProxy)
            assertContains(textOf(root, "mockey.json"), "http://proxy.example.net:8080")
            assertTrue(treeOf(root).values.none { "s3cret" in it || "proxy-user" in it })
        }
}
