package io.github.dnedrow.mockey.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A browser, as far as the tests of the UI need one. It sends one request and returns the answer: it
 * follows no redirect by itself, so that a test sees each answer of the UI as it is.
 *
 * It keeps the cookies that the UI gives it and sends them with its requests, as a browser does for the
 * requests of a site's own pages. It sends a form of a page as a browser sends it, see [submit]. Two
 * browsers know nothing of each other.
 *
 * It runs no script. A test that needs what the script of the UI does sends the request of the script
 * itself, see [submitWithScript] and [getWithScript], and puts the answer into the page with [Swap].
 */
internal class Browser(
    /** The UI that the browser talks to. A second browser for the same UI stands for another person's browser. */
    val ui: MockeyUi,
) {
    /** The answer of the UI to a request: its [status], its [headers] by their names in lower case, and its [body]. */
    class Response(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        private val address: String,
        /** The page that the browser shows after the script of the UI has put the answer into it, when it did. */
        private val shown: Document? = null,
    ) {
        /**
         * The body, parsed into a page the way a browser would do it. Before a browser parses a page, it turns
         * every carriage return into a single line feed, whether or not a line feed follows it.
         *
         * Where the script of the UI put the answer into a page, this is that page.
         */
        val page: Document by lazy { shown ?: Jsoup.parse(asRead, address) }

        /** The body as a browser reads it, with a single line feed for every line break. */
        val asRead: String
            get() = body.replace("\r\n", "\n").replace('\r', '\n')

        /** Returns the one value of the header [name], or `null` when the answer has no such header, or several. */
        fun header(name: String): String? = headers[name.lowercase()]?.singleOrNull()

        /** Everything of the answer that reaches a browser, as one text: the status, the headers, and the body. */
        val everything: String
            get() = (listOf("$status") + headers.flatMap { (name, values) -> values.map { "$name: $it" } } + body).joinToString("\n")

        /** Returns the form of the page that [selector] finds. */
        fun form(selector: String = "main form"): FormElement = page.select(selector).single() as FormElement

        /** What the page says of a form that the UI has done, or `null` when it says nothing. */
        val notice: String?
            get() {
                val said = page.select("main .notice").singleOrNull()?.text()
                return said?.takeIf { it.isNotEmpty() }
            }

        /** The messages at the fields of the form of the page, by the names of the fields. */
        val fieldProblems: Map<String, List<String>>
            get() =
                page.select("main .field").filter { it.select(".field-problem").isNotEmpty() }.associate { field ->
                    val control = field.select("input, textarea, select").first()!!
                    control.attr("name") to field.select(".field-problem p").map { it.text() }
                }
    }

    /** A file that a test chooses in a file field of a form: its name and its bytes. */
    class Chosen(
        val fileName: String,
        val bytes: ByteArray,
    )

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    /** The cookies that the browser keeps, by their names. */
    val cookies: MutableMap<String, String> = LinkedHashMap()

    /** The address of the UI, as a browser writes it. */
    val address: String = "http://${ui.address.text()}"

    /**
     * Sends a request with [method] for [path], which starts with a slash and has its query string, and
     * returns the answer. The request has [body], which it declares as [contentType], when there is one.
     */
    fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): Response {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)
        val request = HttpRequest.newBuilder(URI.create(address + path)).method(method, publisher)
        val kept = cookies.map { (name, value) -> "$name=$value" }
        if (kept.isNotEmpty() && "Cookie" !in headers) request.header("Cookie", kept.joinToString("; "))
        if (contentType != null) request.header("Content-Type", contentType)
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val byName = response.headers().map()
        val received = byName.entries.associate { (name, values) -> name.lowercase() to values.toList() }
        received["set-cookie"].orEmpty().forEach(::keep)
        return Response(response.statusCode(), received, response.body(), address + path)
    }

    /** Keeps or drops a cookie as a `Set-Cookie` header with the value [header] says. */
    private fun keep(header: String) {
        val (name, value) = header.substringBefore(';').split('=', limit = 2)
        val dropped = header.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) }
        if (dropped) cookies.remove(name) else cookies[name] = value
    }

    /**
     * Sends a request for [target] as it is written, with [headers] as they are written, and returns the
     * answer. A browser sends no address that is no URL and names the host that it asks, and so does the
     * HTTP client of the tests, so a request that does otherwise goes over a socket. The request states the
     * length of its [body] unless it says in its headers how the body ends.
     */
    fun raw(
        target: String,
        headers: List<String> = listOf("Host: ${ui.address.text()}"),
        method: String = "GET",
        body: String = "",
        statesLength: Boolean = true,
    ): Response {
        Socket(ui.address.address, ui.address.port).use { socket ->
            socket.soTimeout = RAW_TIMEOUT_MILLIS
            val length = if (body.isEmpty() || !statesLength) emptyList() else listOf("Content-Length: ${body.toByteArray().size}")
            val head = (listOf("$method $target HTTP/1.1") + headers + length + "Connection: close").joinToString("\r\n")
            socket.getOutputStream().write("$head\r\n\r\n$body".toByteArray())
            val answer = String(socket.getInputStream().readAllBytes(), Charsets.UTF_8)
            val lines = answer.substringBefore("\r\n\r\n").split("\r\n")
            val received = lines.drop(1).groupBy({ it.substringBefore(':').lowercase() }, { it.substringAfter(':').trim() })
            return Response(lines.first().split(' ')[1].toInt(), received, answer.substringAfter("\r\n\r\n"), address + target)
        }
    }

    /** Asks for the page at [path] and returns the answer. */
    fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Response = request("GET", path, headers)

    /**
     * Asks for the page that [answer] sends the browser to, and returns the answer. What follows a `#` in
     * the address is the place of the page that a browser shows, which it does not send.
     */
    fun follow(answer: Response): Response = get(answer.header("location")!!.substringBefore('#'))

    /** Sends [fields] to [path] as a form of fields, with the method POST, and returns the answer. */
    fun post(
        path: String,
        fields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): Response = request("POST", path, headers, encoded(fields).toByteArray(), "application/x-www-form-urlencoded")

    /**
     * Sends [form], which is a form of a page, as a browser sends it when its button is pressed: to the
     * address of the form, with the fields that the form has and their values, of which [edits] replace the
     * ones that a person changed. A field with the value `null` is not sent, as a check box that is not
     * checked. A field of several lines is sent with a carriage return before every line feed. A one-line
     * text field is sent without the line breaks of its value, because browsers strip them. [files] are
     * the files that were chosen in the file fields. A file field without a chosen file is sent as a file
     * without a name and without bytes, which is what browsers do.
     *
     * [pressed] is the button of the form that is pressed, which is its first one unless a test presses
     * another. Of the buttons of a form, a browser sends the one that was pressed, when it has a name.
     *
     * [rows] are the values of the fields that the form has more than once under one name, such as the rows
     * of a list, in the order of the form. They take the place of the values that the page gave those fields.
     */
    fun submit(
        form: FormElement,
        edits: Map<String, String?> = emptyMap(),
        files: Map<String, Chosen> = emptyMap(),
        pressed: Element? = form.select(BUTTONS).firstOrNull(),
        rows: Map<String, List<String>> = emptyMap(),
    ): Response {
        val fields = held(form, edits, pressed, rows).map { (name, value) -> name to asSent(value) }
        val action = form.attr("action")
        if (!form.attr("method").equals("post", ignoreCase = true)) return get("$action?${encoded(fields)}")
        if (form.attr("enctype") != "multipart/form-data") return post(action, fields)
        val boundary = "----browser${System.nanoTime()}"
        val sent = fileFieldsOf(form).associateWith { files[it] ?: Chosen("", ByteArray(0)) }
        return request("POST", action, body = parts(fields, sent, boundary), contentType = "multipart/form-data; boundary=$boundary")
    }

    /**
     * Sends [form] as the script of the UI sends a form that is marked for it, see [Enhancement]: to the
     * address that the mark names, with the header of the script, and with the fields as [submit] reads
     * them from the page and from [edits], [files], [pressed], and [rows].
     *
     * The script differs from a browser in two ways. It sends a field of several lines as the page holds
     * it, with line feeds alone, unless the form holds files: the browser then writes the form for the
     * script and puts a carriage return before every line feed. And it leaves out a file field for which
     * no file was chosen.
     */
    fun submitWithScript(
        form: FormElement,
        edits: Map<String, String?> = emptyMap(),
        files: Map<String, Chosen> = emptyMap(),
        pressed: Element? = form.select(BUTTONS).firstOrNull(),
        rows: Map<String, List<String>> = emptyMap(),
    ): Response {
        val address = form.attr("hx-post")
        check(address.isNotEmpty()) { "The form for ${form.attr("action")} is not marked for the script." }
        val fields = held(form, edits, pressed, rows)
        if (form.attr("hx-encoding") != "multipart/form-data") {
            return request("POST", address, SCRIPT_HEADERS, writtenByScript(fields).toByteArray(), "application/x-www-form-urlencoded")
        }
        val boundary = "----script${System.nanoTime()}"
        val sent = fileFieldsOf(form).filter { it in files }.associateWith { files.getValue(it) }
        val body = parts(fields.map { (name, value) -> name to asSent(value) }, sent, boundary)
        return request("POST", address, SCRIPT_HEADERS, body, "multipart/form-data; boundary=$boundary")
    }

    /** Asks for [path] as the script of the UI asks for it when it brings a part of a page up to date. */
    fun getWithScript(path: String): Response = get(path, SCRIPT_HEADERS)

    /**
     * Returns the fields that [form] holds when its button is pressed, each with the value that the page
     * gave it or that a person entered, before anything is done to the line breaks of a field of several lines.
     */
    private fun held(
        form: FormElement,
        edits: Map<String, String?>,
        pressed: Element?,
        rows: Map<String, List<String>>,
    ): List<Pair<String, String>> {
        val areas = form.select("textarea").associate { it.attr("name") to it.wholeText() }
        val data = form.formData().map { it.key() to it.value() }.toMutableList()
        form.select(BUTTONS).forEach { data.remove(it.attr("name") to it.attr("value")) }
        if (pressed != null && pressed.hasAttr("name")) data += pressed.attr("name") to pressed.attr("value")
        val fileFields = fileFieldsOf(form)
        val ofThePage = data.filter { (name, _) -> name !in fileFields }.map { (name, value) -> name to (areas[name] ?: value) }
        val kept = ofThePage.filter { (name, _) -> name !in edits && name !in rows }
        val edited = edits.mapNotNull { (name, value) -> value?.let { name to it } }
        val ofRows = rows.flatMap { (name, values) -> values.map { name to it } }
        val oneLine = form.select(ONE_LINE_FIELDS).map { it.attr("name") }.toSet()
        return (kept + edited + ofRows).map { (name, value) -> name to if (name in oneLine) withoutLineBreaks(value) else value }
    }

    private fun fileFieldsOf(form: FormElement): List<String> = form.select("input[type=file]").map { it.attr("name") }

    private fun withoutLineBreaks(value: String): String = value.filterNot { it == '\r' || it == '\n' }

    private fun asSent(value: String): String = withLineFeeds(value).replace("\n", "\r\n")

    companion object {
        /** The header that the script of the UI adds to every request that it sends, as htmx names it. */
        val SCRIPT_HEADERS = mapOf("HX-Request" to "true")

        /**
         * Returns [fields] as the script of the UI writes a form of fields: every line break of a value is a
         * line feed alone. A browser that sends such a form itself writes a carriage return before it.
         */
        fun writtenByScript(fields: List<Pair<String, String>>): String =
            encoded(fields.map { (name, value) -> name to withLineFeeds(value) })

        private fun withLineFeeds(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

        private fun encoded(fields: List<Pair<String, String>>): String =
            fields.joinToString("&") { (name, value) -> "${escaped(name)}=${escaped(value)}" }

        private fun escaped(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

        /** For how long a request over a socket waits for its answer. An answer that does not come fails the test. */
        private const val RAW_TIMEOUT_MILLIS = 10_000

        /** What finds the buttons that send the form that they are in. */
        private const val BUTTONS = "button:not([type=button]):not([type=reset]), input[type=submit]"

        /** The selector of the one-line text fields of a form. */
        private const val ONE_LINE_FIELDS = "input[type=text], input:not([type])"
    }

    private fun parts(
        fields: List<Pair<String, String>>,
        files: Map<String, Chosen>,
        boundary: String,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for ((name, value) in fields) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        for ((name, file) in files) {
            // A browser writes a line break and a quote in a file name as their escapes, so the header stays one line.
            val oneLine = file.fileName.replace("\r", "%0D").replace("\n", "%0A")
            val disposition = "Content-Disposition: form-data; name=\"$name\"; filename=\"${oneLine.replace("\"", "%22")}\""
            body.write("--$boundary\r\n$disposition\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            body.write(file.bytes)
            body.write("\r\n".toByteArray())
        }
        body.write("--$boundary--\r\n".toByteArray())
        return body.toByteArray()
    }
}
