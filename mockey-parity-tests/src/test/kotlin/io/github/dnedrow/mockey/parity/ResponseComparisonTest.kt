package io.github.dnedrow.mockey.parity

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseComparisonTest {
    private val mock = ParityRequest("GET", "/service/customers")
    private val automation = ParityRequest("GET", "/definitions?serviceName=Customers")

    @Test
    fun aMockResponseIsComparedWhole() {
        val expected = response(200, "exact", "X-Trace" to "a", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2")

        assertEquals(
            emptyList(),
            differences(
                mock,
                expected,
                response(
                    200,
                    "exact",
                    "x-trace" to "a",
                    "set-cookie" to "a=1",
                    "set-cookie" to "b=2",
                ),
            ),
        )
        assertEquals(
            listOf(
                "status: expected 200, got 201",
                "header set-cookie: expected [a=1, b=2], got [b=2, a=1]",
                "header x-other: not expected, got [1]",
                "header x-trace: expected [a], got none",
                "body: expected 'exact' (5 bytes), got 'exact\n' (6 bytes)",
            ),
            differences(mock, expected, response(201, "exact\n", "Set-Cookie" to "b=2", "Set-Cookie" to "a=1", "X-Other" to "1")),
        )
    }

    @Test
    fun aMockResponseIsComparedWithoutTheHeadersOfTheConnection() {
        val legacy =
            response(
                200,
                "x",
                "Server" to "Jetty(2.0.102)",
                "Transfer-Encoding" to "chunked",
                "Date" to "Mon, 23 Nov 2026 05:18:23 GMT",
                "Last-Modified" to "Mon, 23 Nov 2026 05:18:23 GMT",
                "Content-Type" to "text/plain;charset=ISO-8859-1",
            )
        val other =
            response(
                200,
                "x",
                "Content-Length" to "1",
                "Connection" to "keep-alive",
                "Keep-Alive" to "timeout=5",
                "last-modified" to "Tue, 24 Nov 2026 00:00:00 GMT",
                "content-type" to "Text/Plain; Charset=iso-8859-1",
            )

        assertEquals(emptyList(), differences(mock, legacy, other))
        assertEquals(
            listOf("header content-type: expected [text/plain; charset=iso-8859-1], got [text/plain; charset=utf-8]"),
            differences(mock, legacy, response(200, "x", "Last-Modified" to "now", "Content-Type" to "text/plain; charset=UTF-8")),
        )
        assertEquals(
            listOf(
                "header content-type: expected [text/plain; charset=iso-8859-1], got none",
                "header last-modified: expected [<any>], got none",
            ),
            differences(mock, legacy, response(200, "x")),
        )
    }

    @Test
    fun aMockBodyThatIsJsonIsStillComparedByItsBytes() {
        assertEquals(
            listOf("body: expected '{\"ok\":true}' (11 bytes), got '{\"ok\": true}' (12 bytes)"),
            differences(mock, response(200, """{"ok":true}"""), response(200, """{"ok": true}""")),
        )
    }

    @Test
    fun anotherEndpointIsComparedByItsStatusAndItsJsonDocument() {
        val legacy = response(200, "{\"result\":{\"success\":\"updated\",\"hangTime\":250}}\n", "Access-Control-Allow-Origin" to "*")

        assertEquals(
            emptyList(),
            differences(
                automation,
                legacy,
                response(
                    200,
                    """{"result": {"hangTime": 250, "success": "updated"}}""",
                    "X-New" to "1",
                ),
            ),
        )
        assertEquals(
            listOf("status: expected 200, got 500", "body: $.result.hangTime: expected 250, got 0"),
            differences(automation, legacy, response(500, """{"result":{"success":"updated","hangTime":0}}""")),
        )
        assertEquals(
            listOf("body: expected a JSON document, got '<html>' (6 bytes)"),
            differences(automation, legacy, response(200, "<html>")),
        )
    }

    @Test
    fun anotherEndpointThatAnswersNoJsonIsComparedByItsBytes() {
        val legacy = response(500, "<html>Error 500</html>")

        assertEquals(emptyList(), differences(automation, legacy, response(500, "<html>Error 500</html>", "X-New" to "1")))
        assertEquals(
            listOf("body: expected '<html>Error 500</html>' (22 bytes), got '{}' (2 bytes)"),
            differences(automation, legacy, response(500, "{}")),
        )
    }

    @Test
    fun ofASnapshotOnlyTheStatusIsCompared() {
        val legacy = response(200, "{\"since\":\"11/23/2026 | 11:15:32\"}")

        assertEquals(emptyList(), ResponseComparison.differences(automation, legacy, response(200, "<html>"), snapshot = true))
        assertEquals(
            listOf("status: expected 200, got 404"),
            ResponseComparison.differences(automation, legacy, response(404, "<html>"), snapshot = true),
        )
    }

    @Test
    fun aStatedMockResponseIsComparedWhole() {
        val stated =
            NewResponse(
                status = 404,
                headers = mapOf("Content-Type" to listOf("text/plain"), "Last-Modified" to listOf(ANY_VALUE)),
                body = ExpectedBody.Exact("no match".toByteArray()),
            )

        assertEquals(
            emptyList(),
            ResponseComparison.differences(
                mock,
                stated,
                response(404, "no match", "content-type" to "text/plain", "last-modified" to "now"),
            ),
        )
        assertEquals(
            listOf(
                "status: expected 404, got 200",
                "header last-modified: expected [<any>], got none",
                "body: expected 'no match' (8 bytes), got 'no match\n' (9 bytes)",
            ),
            ResponseComparison.differences(mock, stated, response(200, "no match\n", "Content-Type" to "text/plain")),
        )
    }

    @Test
    fun aStatedHeaderValueCanLeaveItsEndOpen() {
        val stated = NewResponse(404, mapOf("Content-Type" to listOf("text/plain<any>"), "X-Exact" to listOf("1")))

        assertEquals(
            emptyList(),
            ResponseComparison.differences(
                mock,
                stated,
                response(404, "", "Content-Type" to "text/plain; charset=UTF-8", "X-Exact" to "1"),
            ),
        )
        assertEquals(
            emptyList(),
            ResponseComparison.differences(
                mock,
                stated,
                response(
                    404,
                    "",
                    "Content-Type" to "Text/Plain",
                    "X-Exact" to "1",
                ),
            ),
        )
        assertEquals(
            listOf("header content-type: expected [text/plain<any>], got [application/json]", "header x-exact: expected [1], got [12]"),
            ResponseComparison.differences(mock, stated, response(404, "", "Content-Type" to "application/json", "X-Exact" to "12")),
        )
        assertEquals(
            listOf("header content-type: expected [text/plain<any>], got [text/plain, text/plain]"),
            ResponseComparison.differences(
                mock,
                stated,
                response(
                    404,
                    "",
                    "Content-Type" to "text/plain",
                    "Content-Type" to "text/plain",
                    "X-Exact" to "1",
                ),
            ),
        )
    }

    @Test
    fun aStatedBodyIsComparedAsFarAsItIsStated() {
        val json = NewResponse(200, body = ExpectedBody.Json("""{"result":{"success":"updated","serviceId":"<any>"}}"""))
        val fragments = NewResponse(404, body = ExpectedBody.Contains(listOf("foo/bar", "no service")))
        val unspecified = NewResponse(404)

        assertEquals(
            emptyList(),
            ResponseComparison.differences(automation, json, response(200, """{"result":{"serviceId":"a1","success":"updated"}}""")),
        )
        assertEquals(
            listOf("body: $.result.success: expected \"updated\", got \"failed\""),
            ResponseComparison.differences(automation, json, response(200, """{"result":{"serviceId":"a1","success":"failed"}}""")),
        )
        assertEquals(
            listOf("body: expected a JSON document, got 'updated' (7 bytes)"),
            ResponseComparison.differences(automation, json, response(200, "updated")),
        )
        assertEquals(emptyList(), ResponseComparison.differences(automation, fragments, response(404, "There is no service for foo/bar.")))
        assertEquals(
            listOf("body: expected to contain 'foo/bar', got 'There is no service.' (20 bytes)"),
            ResponseComparison.differences(automation, fragments, response(404, "There is no service.")),
        )
        assertEquals(emptyList(), ResponseComparison.differences(automation, unspecified, response(404, "anything", "X-New" to "1")))
    }

    @Test
    fun aBinaryBodyIsShownAsHexadecimalDigits() {
        assertEquals(
            listOf("body: expected 00ff10 (3 bytes), got 00ff11 (3 bytes)"),
            ResponseComparison.differences(
                mock,
                ParityResponse(200, emptyMap(), byteArrayOf(0, -1, 16)),
                ParityResponse(200, emptyMap(), byteArrayOf(0, -1, 17)),
            ),
        )
    }

    private fun differences(
        request: ParityRequest,
        expected: ParityResponse,
        actual: ParityResponse,
    ) = ResponseComparison.differences(request, expected, actual)

    private fun response(
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ) = ParityResponse(status, headers.groupBy({ it.first }, { it.second }), body.toByteArray())
}
