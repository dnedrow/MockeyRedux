package io.github.dnedrow.mockey.parity

/** The verdict of the review of a recording, as its `label.txt` holds it. */
sealed interface ParityLabel {
    /** The recording has not been reviewed against the specifications. */
    data object Unreviewed : ParityLabel

    /** The new server answers as legacy does. */
    data object Same : ParityLabel

    /** The new server answers differently, because of the entries [ids] of the deviation register. */
    data class Deviations(
        val ids: List<String>,
    ) : ParityLabel

    companion object {
        private val deviationId = Regex("DEV-\\d{2,}")

        /**
         * Reads `unreviewed`, `same`, or one or more deviation IDs. Several IDs are separated by one space and
         * stand in ascending order, so that a set of deviations has one spelling.
         */
        fun parse(text: String): ParityLabel =
            when (text) {
                ParityCase.UNREVIEWED -> Unreviewed
                ParityCase.SAME -> Same
                else -> deviations(text)
            }

        private fun deviations(text: String): Deviations {
            val ids = text.split(" ")
            require(ids.all(deviationId::matches)) { "'$text' is neither 'same' nor a list of deviation IDs" }
            require(ids == ids.distinct().sortedBy(::number)) { "the deviation IDs '$text' are not in ascending order" }
            return Deviations(ids)
        }

        private fun number(id: String): Int = id.substringAfter("-").toInt()
    }
}
