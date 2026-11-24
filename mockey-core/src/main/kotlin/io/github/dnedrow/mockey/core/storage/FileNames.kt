package io.github.dnedrow.mockey.core.storage

import java.text.Normalizer

/** How long the name of a file or a directory is at most, before what tells it from another name. */
internal const val MAX_FILE_NAME_LENGTH: Int = 64

/** The marks that a decomposed letter carries, such as the dots of an umlaut. */
private val MARKS = Regex("""\p{M}+""")

/** Everything in a name that is no letter of ASCII in lower case and no digit. */
private val SEPARATORS = Regex("[^a-z0-9]+")

/** The names that Windows keeps for devices. No file and no directory can have one of them. */
private val DEVICE_NAMES: Set<String> = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

/**
 * Returns a name for a file or a directory that reads like [name], the name of a definition: in lower case,
 * with the letters of ASCII and the digits of [name], and with one hyphen for everything between them. An
 * accented letter gives its base letter. A name that leaves nothing gives [fallback].
 *
 * The file name is there for whoever reads the definitions location. It does not identify the definition:
 * the identifier inside the file does. Two definitions can therefore give the same file name, see
 * [freeFileName].
 */
internal fun fileNameOf(
    name: String,
    fallback: String,
): String {
    val letters = Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
    return letters
        .replace(SEPARATORS, "-")
        .trim('-')
        .take(MAX_FILE_NAME_LENGTH)
        .trim('-')
        .ifEmpty { fallback }
}

/**
 * Returns [wanted] when no name of [taken] is that name, and otherwise [wanted] with the first number from 2
 * that makes a name that is not taken, such as `orders-2`. The name that is returned is taken from then on.
 *
 * [taken] holds its names in lower case, because many file systems do not tell `Orders` from `orders`. A name
 * that Windows keeps for a device counts as taken.
 */
internal fun freeFileName(
    wanted: String,
    taken: MutableSet<String>,
): String {
    var name = wanted
    var number = 2
    while (name in taken || name in DEVICE_NAMES) name = "$wanted-${number++}"
    taken += name
    return name
}
