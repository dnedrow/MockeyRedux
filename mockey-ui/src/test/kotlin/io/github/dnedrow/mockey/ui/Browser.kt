package io.github.dnedrow.mockey.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A browser without scripting, as far as the tests of the UI need one. It sends one request and returns the
 * answer: it follows no redirect, and it keeps nothing from one request to the next, so that a test sees
 * each answer of the UI as it is.
 */
internal class Browser(
    private val ui: MockeyUi,
) {
    /** The answer of the UI to a request: its [status], its [headers] by their names in lower case, and its [body]. */
    class Response(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        private val address: String,
    ) {
        /** The body as the page that a browser makes of it. */
        val page: Document by lazy { Jsoup.parse(body, address) }

        /** Returns the one value of the header [name], or `null` when the answer has no such header, or several. */
        fun header(name: String): String? = headers[name.lowercase()]?.singleOrNull()

        /** Everything of the answer that reaches a browser, as one text: the status, the headers, and the body. */
        val everything: String
            get() = (listOf("$status") + headers.flatMap { (name, values) -> values.map { "$name: $it" } } + body).joinToString("\n")
    }

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    /** The address of the UI, as a browser writes it. */
    val address: String = "http://${ui.address.text()}"

    /** Sends a request with [method] for [path], which starts with a slash and has its query string, and returns the answer. */
    fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val request = HttpRequest.newBuilder(URI.create(address + path)).method(method, HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val byName = response.headers().map()
        val received = byName.entries.associate { (name, values) -> name.lowercase() to values.toList() }
        return Response(response.statusCode(), received, response.body(), address + path)
    }

    /**
     * Sends a request for [target] as it is written, and returns the answer. A browser sends no address that
     * is no URL, and neither does the HTTP client of the tests, so the request goes over a socket.
     */
    fun raw(target: String): Response {
        Socket(ui.address.address, ui.address.port).use { socket ->
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: ${ui.address.text()}\r\nConnection: close\r\n\r\n".toByteArray())
            val answer = String(socket.getInputStream().readAllBytes(), Charsets.UTF_8)
            val head = answer.substringBefore("\r\n\r\n").split("\r\n")
            val headers = head.drop(1).groupBy({ it.substringBefore(':').lowercase() }, { it.substringAfter(':').trim() })
            return Response(head.first().split(' ')[1].toInt(), headers, answer.substringAfter("\r\n\r\n"), address + target)
        }
    }

    /** Asks for the page at [path] and returns the answer. */
    fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Response = request("GET", path, headers)
}
