package io.github.dnedrow.mockey.parity

data class ParityCase(
    val label: String,
    val fixtureXml: String,
    val request: ParityRequest,
    val expectedResponse: ParityResponse? = null,
    val timing: ParityTiming? = null,
) {
    companion object {
        /** Label of a recording that has not been reviewed against the deviation register. */
        const val UNREVIEWED = "unreviewed"
    }
}

/**
 * Bounds on the time a server takes to answer a case that exercises response delays.
 * [recordedMillis] is the time observed when the case was recorded.
 */
data class ParityTiming(
    val atLeastMillis: Long = 0,
    val lessThanMillis: Long? = null,
    val recordedMillis: Long? = null,
) {
    fun allows(elapsedMillis: Long): Boolean = elapsedMillis >= atLeastMillis && (lessThanMillis == null || elapsedMillis < lessThanMillis)

    fun describe(): String = if (lessThanMillis == null) "at least $atLeastMillis ms" else "$atLeastMillis ms to under $lessThanMillis ms"
}

data class ParityRequest(
    val method: String,
    val target: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

data class ParityResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

data class ReplayResult(
    val caseName: String,
    val differences: List<String>,
) {
    val matches: Boolean
        get() = differences.isEmpty()
}
