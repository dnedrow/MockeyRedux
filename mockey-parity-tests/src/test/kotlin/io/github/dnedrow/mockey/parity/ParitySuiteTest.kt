package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParitySuiteTest {
    private val resources = Path.of("src/test/resources")

    @Test
    fun theRecordingsAreExactlyTheCasesOfTheSuite() {
        val recorded =
            resources
                .resolve("parity")
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet()

        assertEquals(ParitySuite.all.keys, recorded)
    }

    @Test
    fun everyGroupHasAFixtureOfItsName() {
        ParitySuite.groups.forEach { group ->
            assertTrue(Files.exists(resources.resolve(group.fixturePath)), "fixture of the group ${group.name}")
        }
    }
}
