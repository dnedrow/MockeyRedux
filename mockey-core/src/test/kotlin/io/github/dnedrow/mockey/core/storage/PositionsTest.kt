package io.github.dnedrow.mockey.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionsTest {
    /** Returns the identifiers [ids] in the order in which definitions with these [positions] are read back. */
    private fun readBack(
        ids: List<String>,
        positions: List<Long?>,
    ): List<String> =
        ids
            .zip(positions)
            .sortedWith(compareBy({ it.second ?: Long.MAX_VALUE }, { it.first }))
            .map { it.first }

    @Test
    fun newDefinitionsGetTheirPlacesAsPositions() {
        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("c", "a", "b"), emptyMap()))
        assertEquals(emptyList(), positionsOf(emptyList(), emptyMap()))
    }

    @Test
    fun storedDefinitionsKeepTheirPositionsAndANewOneComesAfterTheOneBeforeIt() {
        val stored = mapOf<String, Long?>("a" to 0, "b" to 1, "c" to 2)

        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("a", "b", "c"), stored))
        assertEquals(listOf<Long?>(0, 1, 2, 3, 4), positionsOf(listOf("a", "b", "c", "e", "d"), stored))
    }

    @Test
    fun thePositionsOfDeletedDefinitionsAreNotFilled() {
        val stored = mapOf<String, Long?>("a" to 0, "c" to 2, "f" to 7)

        assertEquals(listOf<Long?>(0, 2, 7, 8), positionsOf(listOf("a", "c", "f", "new"), stored))
    }

    @Test
    fun definitionsWithTheSamePositionKeepItWhileTheirIdentifiersAreInOrder() {
        val stored = mapOf<String, Long?>("a" to 5, "b" to 5, "c" to 5)

        assertEquals(listOf<Long?>(5, 5, 5), positionsOf(listOf("a", "b", "c"), stored))
        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("b", "a", "c"), stored))
    }

    @Test
    fun definitionsWithoutAPositionKeepNoneWhileTheyAreLastInTheOrderOfTheirIdentifiers() {
        val stored = mapOf<String, Long?>("z" to 3, "a" to null, "b" to null)

        assertEquals(listOf<Long?>(3, null, null), positionsOf(listOf("z", "a", "b"), stored))
        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("z", "b", "a"), stored))
        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("a", "z", "b"), stored))
    }

    @Test
    fun aNewDefinitionAfterOneWithoutAPositionGivesEveryDefinitionAPosition() {
        val stored = mapOf<String, Long?>("z" to 3, "a" to null)

        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("z", "a", "new"), stored))
    }

    @Test
    fun positionsThatDoNotGiveTheOrderAreReplacedByThePlaces() {
        val stored = mapOf<String, Long?>("a" to 4, "b" to 2, "c" to 9)

        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("a", "b", "c"), stored))
        assertEquals(listOf<Long?>(0, 1, 2), positionsOf(listOf("new", "c", "a"), stored))
        assertEquals(listOf<Long?>(0, 2, 4, 9), positionsOf(listOf("new", "b", "a", "c"), stored))
    }

    @Test
    fun aNewDefinitionInFrontComesBeforeTheOthersWhenTheirPositionsLeaveRoom() {
        val stored = mapOf<String, Long?>("b" to 3, "c" to 4)

        assertEquals(listOf<Long?>(0, 3, 4), positionsOf(listOf("new", "b", "c"), stored))
        assertEquals(listOf<Long?>(-7, -6, 4), positionsOf(listOf("x", "new", "c"), mapOf("x" to -7, "c" to 4)))
    }

    @Test
    fun definitionsAreReadBackInTheirOrderWhateverTheirStoredPositionsWere() {
        val stored = mapOf<String, Long?>("a" to 2, "b" to 2, "c" to null, "d" to 0, "e" to null, "f" to -3)
        val orders =
            listOf(
                listOf("f", "d", "a", "b", "c", "e"),
                listOf("f", "d", "a", "b", "c", "e", "g"),
                listOf("g", "f", "d", "a", "b"),
                listOf("e", "c", "b", "a", "d", "f"),
                listOf("d", "h", "a", "g", "b"),
                listOf("f", "d", "b", "a"),
                listOf("c", "e"),
            )

        orders.forEach { ids -> assertEquals(ids, readBack(ids, positionsOf(ids, stored))) }
    }
}
