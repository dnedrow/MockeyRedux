package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The cases of the legacy `com.mockey.model.ServiceTest`, with the names of its test methods. A legacy service
 * took and gave up its tags one text at a time; the new one holds [Tags], which are added and taken away as
 * sets. A legacy service was told to save each real URL; the new one is saved with its list of real URLs.
 */
class LegacyServiceTest {
    private val development = listOf("development", "development", " development ", "DEVELOPMENT")

    private val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customers")

    @Test
    fun addTagsToService() {
        val tagged = development.fold(service) { tagged, tag -> tagged.copy(tags = tagged.tags + Tags.of(tag)) }

        assertEquals(1, tagged.tags.size)
    }

    /** The legacy case also took away `null`, which the type of a tag text does not admit. No tags stand in for it. */
    @Test
    fun removeTagsFromService() {
        val tagged = service.copy(tags = Tags.of(development))

        val withoutAnother = tagged.copy(tags = tagged.tags - Tags.of("somethings"))
        val withoutNone = withoutAnother.copy(tags = withoutAnother.tags - Tags.EMPTY)
        val withoutBoth = withoutNone.copy(tags = withoutNone.tags - Tags.of("development somethings"))

        assertEquals(1, withoutAnother.tags.size)
        assertEquals(1, withoutNone.tags.size)
        assertEquals(0, withoutBoth.tags.size)
    }

    @Test
    fun addTagListToService() {
        assertNotEquals(1, service.tags.size)

        val tagged = service.copy(tags = Tags.of(development))

        assertEquals(1, tagged.tags.size)
    }

    @Test
    fun addSpaceTagListToService() {
        assertEquals(0, service.tags.size)

        val tagged = service.copy(tags = Tags.of(development + "abc def DEF ABC"))

        assertEquals(3, tagged.tags.size)
        assertEquals("abc def development", tagged.tags.toString())
    }

    @Test
    fun addRealUrlsToService() {
        val created = Definitions().changed(ServiceChanges.create { it.copy(name = "Search", mockUrl = "search") })

        val saved =
            listOf("http://www.abc.com", "http://www.ABC.com", "http://www.def.com").fold(created.definitions) { definitions, url ->
                definitions.changed(ServiceChanges.update(created.value.id) { it.copy(realUrls = it.realUrls + url) }).definitions
            }

        assertEquals(listOf("http://www.abc.com", "http://www.def.com"), saved.services.single().realUrls)
    }
}
