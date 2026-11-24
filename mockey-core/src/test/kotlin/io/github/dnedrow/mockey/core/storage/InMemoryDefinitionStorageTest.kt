package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class InMemoryDefinitionStorageTest {
    private val definitions = Definitions(services = listOf(Service(id = ServiceId.generate(), name = "Orders", mockUrl = "orders")))

    @Test
    fun aNewStorageHoldsNoDefinitions() =
        runTest {
            assertSame(Definitions.EMPTY, InMemoryDefinitionStorage().load())
        }

    @Test
    fun aStorageHoldsTheDefinitionsThatItWasCreatedWith() =
        runTest {
            assertSame(definitions, InMemoryDefinitionStorage(definitions).load())
        }

    @Test
    fun aStorageHoldsTheDefinitionsThatWereSavedLast() =
        runTest {
            val storage = InMemoryDefinitionStorage(definitions)
            val later = definitions.copy(services = definitions.services.map { it.copy(name = "Purchases") })

            storage.save(Definitions.EMPTY)
            storage.save(later)

            assertSame(later, storage.load())
        }

    @Test
    fun twoStoragesHoldNothingOfEachOther() =
        runTest {
            val one = InMemoryDefinitionStorage()
            val other = InMemoryDefinitionStorage()

            one.save(definitions)

            assertEquals(listOf(definitions, Definitions.EMPTY), listOf(one.load(), other.load()))
        }
}
