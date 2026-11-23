package io.github.dnedrow.mockey.parity

data class ParityCase(
    val label: String,
    val fixtureXml: String,
    val request: ParityRequest,
    val expectedResponse: ParityResponse? = null,
)

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
