package io.github.dnedrow.mockey.parity

/**
 * The names of recordings as a document writes them in backticks. A document names a recording by its name,
 * such as `routing-plus-sign`, or several recordings by the start of their names with a star after it, such
 * as `rule-malformed-*`.
 *
 * [recordings] are the names of the recordings that there are. Their first words, such as `routing`, tell a
 * name of a recording from whatever else a document writes in backticks.
 */
internal class RecordingReferences(
    private val recordings: Set<String>,
) {
    private val families = recordings.map { it.substringBefore('-') }.toSet()

    /** Returns what [text] writes in backticks as the name of a recording, or as the start of several names. */
    fun referencesIn(text: String): List<String> {
        val written = quoted.findAll(text).map { it.groupValues[1] }
        return written.filter { isReference(it) }.toList()
    }

    /** Returns the recordings that [reference] stands for. There are none when it names nothing that is there. */
    fun recordingsOf(reference: String): List<String> =
        if (reference.endsWith("*")) {
            recordings.filter { it.startsWith(reference.removeSuffix("*")) }
        } else {
            recordings.filter { it == reference }
        }

    private fun isReference(text: String): Boolean = recordingName.matches(text) && '-' in text && text.substringBefore('-') in families

    private companion object {
        /** What a document writes in backticks. */
        val quoted = Regex("`([^`]+)`")

        /** The name of a recording, or the start of the names of several recordings with a star after it. */
        val recordingName = Regex("[a-z]+(-[a-z0-9]+)*-?\\*?")
    }
}
