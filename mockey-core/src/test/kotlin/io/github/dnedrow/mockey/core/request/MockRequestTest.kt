package io.github.dnedrow.mockey.core.request

import io.github.dnedrow.mockey.core.model.Header
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MockRequestTest {
    private val url = "http://mockey.test:8080/service/customer/123"

    private fun request(
        query: String? = null,
        contentType: String? = null,
        body: ByteArray = ByteArray(0),
    ) = MockRequest("POST", url, query, listOfNotNull(contentType?.let { Header("Content-Type", it) }), body)

    private fun parameters(query: String): List<Pair<String, String>> = request(query).parameters.map { it.name to it.value }

    @Test
    fun aRequestWithOnlyAMethodAndAUrlHasNothingElse() {
        val request = MockRequest("GET", url)

        assertEquals("GET", request.method)
        assertEquals(url, request.url)
        assertNull(request.query)
        assertEquals(emptyList(), request.headers)
        assertEquals(0, request.body.size)
        assertEquals("", request.bodyText)
        assertNull(request.contentType)
        assertEquals(emptyList(), request.parameters)
    }

    @Test
    fun theUrlOfARequestHoldsNoQueryString() {
        assertFailsWith<IllegalArgumentException> { MockRequest("GET", "$url?page=2") }
    }

    @Test
    fun headersKeepTheirOrderAndTheirRepeatedNames() {
        val headers = listOf(Header("Accept", "text/plain"), Header("X-Tier", "gold"), Header("accept", "application/json"))

        val request = MockRequest("GET", url, headers = headers)

        assertEquals(headers, request.headers)
        assertEquals(listOf("text/plain", "application/json"), request.headerValues("ACCEPT"))
        assertEquals("text/plain", request.header("accept"))
        assertEquals(emptyList(), request.headerValues("Cookie"))
        assertNull(request.header("Cookie"))
    }

    @Test
    fun aRequestKeepsTheHeadersThatItWasCreatedWith() {
        val headers = mutableListOf(Header("X-Tier", "gold"))
        val request = MockRequest("GET", url, headers = headers)

        headers += Header("X-Tier", "silver")

        assertEquals(listOf(Header("X-Tier", "gold")), request.headers)
    }

    @Test
    fun everyReadOfTheBodyGivesANewArray() {
        val request = request(body = byteArrayOf(1, 2, 3))

        request.body[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), request.body)
    }

    @Test
    fun theBodyIsReadAsUtf8WhenTheRequestDeclaresNoCharset() {
        val body = "caf\u00e9 \u20ac".toByteArray(Charsets.UTF_8)

        assertEquals("caf\u00e9 \u20ac", request(body = body).bodyText)
        assertEquals("caf\u00e9 \u20ac", request(contentType = "application/json", body = body).bodyText)
    }

    @Test
    fun theBodyIsReadInTheCharsetThatTheRequestDeclares() {
        val body = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)

        val declared =
            listOf(
                "text/plain; charset=ISO-8859-1",
                "text/plain;charset=iso-8859-1",
                "text/plain; format=flowed; Charset = \"ISO-8859-1\"",
            ).map { request(contentType = it, body = body).bodyText }

        assertEquals(listOf("caf\u00e9", "caf\u00e9", "caf\u00e9"), declared)
    }

    @Test
    fun aCharsetThatIsNotKnownIsReadAsUtf8() {
        val body = "caf\u00e9".toByteArray(Charsets.UTF_8)

        val read =
            listOf("text/plain; charset=klingon", "text/plain; charset=", "text/plain; charset", "text/plain; charset=\"").map {
                request(contentType = it, body = body).bodyText
            }

        assertEquals(List(4) { "caf\u00e9" }, read)
    }

    @Test
    fun bytesThatAreNoTextBecomeTheReplacementCharacter() {
        assertEquals("caf\uFFFD", request(body = "caf\u00e9".toByteArray(Charsets.ISO_8859_1)).bodyText)
    }

    @Test
    fun theContentTypeIsTheFirstHeaderOfThatNameInAnyLetterCase() {
        val request = MockRequest("POST", url, headers = listOf(Header("content-type", "text/plain"), Header("Content-Type", "text/html")))

        assertEquals("text/plain", request.contentType)
    }

    @Test
    fun theParametersOfTheQueryStringAreInTheOrderOfTheRequest() {
        assertEquals(listOf("b" to "2", "a" to "1", "b" to "3"), parameters("b=2&a=1&b=3"))
        assertEquals(listOf("2", "3"), request("b=2&a=1&b=3").parameterValues("b"))
        assertEquals(emptyList(), request("b=2").parameterValues("B"))
    }

    @Test
    fun namesAndValuesOfParametersAreDecoded() {
        assertEquals(listOf("full name" to "Jane Doe"), parameters("full+name=Jane%20Doe"))
        assertEquals(listOf("date" to "11/23/2026", "sum" to "1+1=2"), parameters("date=11%2F23%2F2026&sum=1%2B1%3D2"))
        assertEquals(listOf("city" to "K\u00f6ln"), parameters("city=K%C3%B6ln"))
        assertEquals(listOf("rate" to "100%", "odd" to "%zz"), parameters("rate=100%&odd=%zz"))
    }

    @Test
    fun onlyTheFirstEqualsSignSeparatesANameFromItsValue() {
        assertEquals(listOf("filter" to "a=b=c"), parameters("filter=a=b=c"))
    }

    @Test
    fun aParameterWithoutAnEqualsSignHasAnEmptyValue() {
        assertEquals(listOf("wsdl" to "", "empty" to "", "" to "5"), parameters("wsdl&empty=&=5"))
    }

    @Test
    fun nothingBetweenTwoSeparatorsIsNoParameter() {
        assertEquals(listOf("a" to "1", "b" to "2"), parameters("&a=1&&b=2&"))
        assertEquals(emptyList(), parameters(""))
    }

    @Test
    fun theFieldsOfAUrlEncodedFormFollowTheParametersOfTheQueryString() {
        val request = request("ticker=FB", "application/x-www-form-urlencoded", "ticker=GOOG&full+name=Jane%20Doe".toByteArray())

        val parameters = request.parameters.map { it.name to it.value }

        assertEquals(listOf("ticker" to "FB", "ticker" to "GOOG", "full name" to "Jane Doe"), parameters)
        assertEquals("ticker=GOOG&full+name=Jane%20Doe", request.bodyText)
    }

    @Test
    fun aFormIsKnownByItsMediaTypeInAnyLetterCaseAndWithParameters() {
        val body = "ticker=GOOG".toByteArray()

        val forms =
            listOf(
                "application/x-www-form-urlencoded",
                "Application/X-WWW-Form-Urlencoded; charset=UTF-8",
                " application/x-www-form-urlencoded ;x=y",
            ).map { request(contentType = it, body = body).parameters }

        assertEquals(List(3) { listOf(Parameter("ticker", "GOOG")) }, forms)
    }

    @Test
    fun aBodyThatIsNoFormHasNoParameters() {
        val body = "ticker=GOOG".toByteArray()

        assertEquals(emptyList(), request(body = body).parameters)
        assertEquals(emptyList(), request(contentType = "text/plain", body = body).parameters)
        assertEquals(emptyList(), request(contentType = "multipart/form-data; boundary=x", body = body).parameters)
    }

    @Test
    fun theTextOfARequestLeavesOutItsHeadersAndItsBody() {
        val request = MockRequest("POST", url, "token=t0p", listOf(Header("Authorization", "Bearer s3cret")), "pa55word".toByteArray())

        val text = request.toString()

        assertEquals("MockRequest(POST $url)", text)
        assertFalse("s3cret" in text || "pa55word" in text || "t0p" in text)
    }
}
