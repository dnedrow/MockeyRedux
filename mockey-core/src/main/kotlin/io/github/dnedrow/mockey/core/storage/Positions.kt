package io.github.dnedrow.mockey.core.storage

/**
 * Returns the positions under which definitions are stored so that they are read back in their order. [ids]
 * are the identifiers of the definitions in that order. [stored] holds every definition that has a file
 * already, with the position that its file states, which is `null` for a file that states none.
 *
 * Definitions are read back in the order of their positions. Of two with the same position, the one whose
 * identifier sorts first comes first, and a definition without a position comes after all that have one.
 *
 * Every stored definition keeps its position when that gives the order of [ids], so that its file need not
 * be written again, and a definition that is new gets the position after the one before it. When the
 * positions cannot be kept, every definition gets its place in [ids] as its position.
 */
internal fun positionsOf(
    ids: List<String>,
    stored: Map<String, Long?>,
): List<Long?> = keptPositions(ids, stored) ?: ids.indices.map { it.toLong() }

/** Where a definition without a position is in the order: after every definition that has one. */
private const val LAST: Long = Long.MAX_VALUE

/** Returns the stored positions with one for each new definition, or `null` when they do not give the order of [ids]. */
private fun keptPositions(
    ids: List<String>,
    stored: Map<String, Long?>,
): List<Long?>? {
    val positions = ArrayList<Long?>(ids.size)
    var previousPlace = -1L
    var previousId: String? = null
    for (id in ids) {
        // Nothing can be put after a definition that is last, so a new definition there keeps no position as it is.
        if (id !in stored && previousPlace == LAST) return null
        val position = if (id in stored) stored[id] else previousPlace + 1
        val place = position ?: LAST
        val inOrder = previousId == null || place > previousPlace || (place == previousPlace && id > previousId)
        if (!inOrder) return null
        positions += position
        previousPlace = place
        previousId = id
    }
    return positions
}
