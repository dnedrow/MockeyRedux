package io.github.dnedrow.mockey.parity

/**
 * One recorded exchange. The [before] requests are sent first, in order, to bring the server into the state
 * that [request] needs, such as an upstream cookie or a changed setting. Only the response to [request] is
 * recorded and compared.
 *
 * [expectedResponse] is the response of legacy Mockey. The [label] tells whether the new server answers the
 * same, see [ParityLabel]. A case that deviates states in [newResponse] what the new server answers instead.
 * A [snapshot] is a legacy response whose body holds times or session IDs, so that only its status can be
 * compared with a later response.
 */
data class ParityCase(
    val label: String,
    val fixtureXml: String,
    val request: ParityRequest,
    val expectedResponse: ParityResponse? = null,
    val timing: ParityTiming? = null,
    val before: List<ParityRequest> = emptyList(),
    val newResponse: NewResponse? = null,
    val snapshot: Boolean = false,
) {
    companion object {
        /** Label of a recording that has not been reviewed against the deviation register. */
        const val UNREVIEWED = "unreviewed"

        /** Label of a recording that the new server must reproduce. */
        const val SAME = "same"
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
) {
    /** Whether [other] is the same request. A data class compares the body by identity, which is no use here. */
    fun sameAs(other: ParityRequest): Boolean =
        method == other.method && target == other.target && headers == other.headers && body.contentEquals(other.body)
}

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
