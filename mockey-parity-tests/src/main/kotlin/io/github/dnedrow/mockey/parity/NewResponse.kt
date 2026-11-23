package io.github.dnedrow.mockey.parity

/**
 * The response that the specifications require of the new server where it deviates from legacy.
 *
 * The [headers] are stated for a response of the mock endpoint only, see [ResponseComparison]. A header value
 * that ends in [ANY_VALUE] says only how the value starts: `text/plain<any>` leaves the parameters of a content
 * type open, and `<any>` alone asks for no more than the header. A null [timing] means that the timing bounds of
 * the case apply to the new server too.
 */
data class NewResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ExpectedBody = ExpectedBody.Unspecified,
    val timing: ParityTiming? = null,
)

/** Stands for a value that the specifications do not determine: in a header, or in a JSON document. */
const val ANY_VALUE = "<any>"

/** What a [NewResponse] says about the body, as far as the specifications define it. */
sealed interface ExpectedBody {
    /** The body is exactly [bytes]. */
    class Exact(
        val bytes: ByteArray,
    ) : ExpectedBody {
        override fun equals(other: Any?): Boolean = other is Exact && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Exact(${bytes.size} bytes)"
    }

    /**
     * The body is a JSON document equal to [template]. The order of the members of an object and the whitespace
     * between tokens do not count. The string [ANY_VALUE] in the template stands for any value.
     */
    data class Json(
        val template: String,
    ) : ExpectedBody

    /** The body, read as UTF-8, holds every one of [fragments]. The specifications leave its wording open. */
    data class Contains(
        val fragments: List<String>,
    ) : ExpectedBody

    /** The specifications say nothing about the body. */
    data object Unspecified : ExpectedBody
}
