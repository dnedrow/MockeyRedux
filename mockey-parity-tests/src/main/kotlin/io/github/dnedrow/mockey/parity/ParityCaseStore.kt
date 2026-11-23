package io.github.dnedrow.mockey.parity

import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

class ParityCaseStore {
    fun write(
        case: ParityCase,
        directory: Path,
    ) {
        require(case.expectedResponse != null) { "A recording must include an expected response." }
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(LABEL), case.label, StandardCharsets.UTF_8)
        Files.writeString(directory.resolve("fixture.xml"), case.fixtureXml, StandardCharsets.UTF_8)
        writeRequest(case.request, directory.resolve("request.properties"))
        writeBefore(case.before, directory)
        writeResponse(case.expectedResponse, case.snapshot, directory.resolve(LEGACY_RESPONSE))
        writeTiming(case.timing, directory.resolve("timing.properties"))
        writeNewResponse(case.newResponse, directory.resolve(NEW_RESPONSE))
    }

    fun read(directory: Path): ParityCase {
        val response = load(directory.resolve(LEGACY_RESPONSE))
        return ParityCase(
            label = Files.readString(directory.resolve(LABEL), StandardCharsets.UTF_8).trim(),
            fixtureXml = Files.readString(directory.resolve("fixture.xml"), StandardCharsets.UTF_8),
            request = readRequest(directory.resolve("request.properties")),
            expectedResponse = readResponse(response),
            timing = readTiming(directory.resolve("timing.properties")),
            before = readBefore(directory),
            newResponse = readNewResponse(directory.resolve(NEW_RESPONSE)),
            snapshot = response.getProperty(SNAPSHOT).toBoolean(),
        )
    }

    /** The recording in [directory], or null when it holds none that can be read. */
    fun readOrNull(directory: Path): ParityCase? = runCatching { read(directory) }.getOrNull()

    /** Writes the requests that precede the recorded one as before-1.request.properties, before-2, and so on. */
    private fun writeBefore(
        before: List<ParityRequest>,
        directory: Path,
    ) {
        beforeFiles(directory).forEach(Files::delete)
        before.forEachIndexed { index, request -> writeRequest(request, directory.resolve(beforeFileName(index + 1))) }
    }

    private fun readBefore(directory: Path): List<ParityRequest> = beforeFiles(directory).map(::readRequest)

    private fun beforeFiles(directory: Path): List<Path> =
        generateSequence(1) { it + 1 }
            .map { directory.resolve(beforeFileName(it)) }
            .takeWhile(Files::exists)
            .toList()

    private fun beforeFileName(position: Int) = "before-$position.request.properties"

    private fun writeRequest(
        request: ParityRequest,
        path: Path,
    ) {
        val properties = Properties()
        properties["method"] = request.method
        properties["target"] = request.target
        properties[BODY] = encode(request.body)
        writeHeaders(request.headers, properties)
        store(properties, path, "Mockey parity request")
    }

    private fun readRequest(path: Path): ParityRequest {
        val properties = load(path)
        return ParityRequest(
            method = properties.required("method"),
            target = properties.required("target"),
            headers = readHeaders(properties),
            body = decode(properties.required(BODY)),
        )
    }

    private fun writeResponse(
        response: ParityResponse,
        snapshot: Boolean,
        path: Path,
    ) {
        val properties = Properties()
        properties[STATUS] = response.status.toString()
        properties[BODY] = encode(response.body)
        if (snapshot) properties[SNAPSHOT] = "true"
        writeHeaders(response.headers, properties)
        store(properties, path, "Mockey parity response")
    }

    private fun readResponse(properties: Properties): ParityResponse =
        ParityResponse(
            status = properties.required(STATUS).toInt(),
            headers = readHeaders(properties),
            body = decode(properties.required(BODY)),
        )

    private fun writeTiming(
        timing: ParityTiming?,
        path: Path,
    ) {
        if (timing == null) {
            Files.deleteIfExists(path)
            return
        }
        val properties = Properties()
        properties["atLeastMillis"] = timing.atLeastMillis.toString()
        timing.lessThanMillis?.let { properties["lessThanMillis"] = it.toString() }
        timing.recordedMillis?.let { properties["recordedMillis"] = it.toString() }
        store(properties, path, "Mockey parity timing")
    }

    private fun readTiming(path: Path): ParityTiming? {
        if (!Files.exists(path)) return null
        val properties = load(path)
        return ParityTiming(
            atLeastMillis = properties.required("atLeastMillis").toLong(),
            lessThanMillis = properties.getProperty("lessThanMillis")?.toLong(),
            recordedMillis = properties.getProperty("recordedMillis")?.toLong(),
        )
    }

    private fun writeNewResponse(
        response: NewResponse?,
        path: Path,
    ) {
        if (response == null) {
            Files.deleteIfExists(path)
            return
        }
        val properties = Properties()
        properties[STATUS] = response.status.toString()
        writeHeaders(response.headers, properties)
        when (val body = response.body) {
            is ExpectedBody.Exact -> writeExactBody(body.bytes, properties)
            is ExpectedBody.Json -> properties[BODY_JSON] = body.template
            is ExpectedBody.Contains -> body.fragments.forEachIndexed { index, fragment -> properties["$BODY_CONTAINS.$index"] = fragment }
            ExpectedBody.Unspecified -> properties[BODY_UNSPECIFIED] = "true"
        }
        response.timing?.let { timing ->
            properties[TIMING_AT_LEAST] = timing.atLeastMillis.toString()
            timing.lessThanMillis?.let { properties[TIMING_LESS_THAN] = it.toString() }
        }
        store(properties, path, "Mockey parity response of the new server")
    }

    private fun writeExactBody(
        bytes: ByteArray,
        properties: Properties,
    ) {
        val text = textOf(bytes)
        if (text == null) properties[BODY] = encode(bytes) else properties[BODY_TEXT] = text
    }

    private fun readNewResponse(path: Path): NewResponse? {
        if (!Files.exists(path)) return null
        val properties = load(path)
        return NewResponse(
            status = properties.required(STATUS).toInt(),
            headers = readHeaders(properties),
            body = readExpectedBody(properties, path),
            timing =
                properties.getProperty(TIMING_AT_LEAST)?.let { atLeast ->
                    ParityTiming(atLeast.toLong(), properties.getProperty(TIMING_LESS_THAN)?.toLong())
                },
        )
    }

    /** A stated response says exactly one thing about its body. */
    private fun readExpectedBody(
        properties: Properties,
        path: Path,
    ): ExpectedBody {
        val fragments =
            generateSequence(0) { it + 1 }
                .map { properties.getProperty("$BODY_CONTAINS.$it") }
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
        val statements =
            listOfNotNull(
                properties.getProperty(BODY)?.let { ExpectedBody.Exact(decode(it)) },
                properties.getProperty(BODY_TEXT)?.let { ExpectedBody.Exact(it.toByteArray(StandardCharsets.UTF_8)) },
                properties.getProperty(BODY_JSON)?.let(ExpectedBody::Json),
                fragments.takeIf { it.isNotEmpty() }?.let(ExpectedBody::Contains),
                ExpectedBody.Unspecified.takeIf { properties.getProperty(BODY_UNSPECIFIED).toBoolean() },
            )
        return statements.singleOrNull()
            ?: error("$path must state the body in one way: $BODY, $BODY_TEXT, $BODY_JSON, $BODY_CONTAINS.0, or $BODY_UNSPECIFIED=true.")
    }

    /**
     * [bytes] as text, when they are UTF-8 text that a person can read in the file. A stated body is written by
     * a reviewer, who should not have to encode it. Any other body is stored as Base64.
     */
    private fun textOf(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
        return text.takeIf { it.none { character -> character.isISOControl() && character !in "\r\n\t" } }
    }

    private fun writeHeaders(
        headers: Map<String, List<String>>,
        properties: Properties,
    ) {
        headers.entries
            .sortedBy { it.key.lowercase() }
            .flatMap { (name, values) -> values.map { value -> name to value } }
            .forEachIndexed { index, (name, value) ->
                properties["header.$index.name"] = name
                properties["header.$index.value"] = value
            }
    }

    private fun readHeaders(properties: Properties): Map<String, List<String>> {
        val headers = linkedMapOf<String, MutableList<String>>()
        var index = 0
        while (properties.containsKey("header.$index.name")) {
            val name = properties.required("header.$index.name")
            headers.getOrPut(name) { mutableListOf() }.add(properties.required("header.$index.value"))
            index++
        }
        return headers
    }

    /**
     * Writes [properties] in the order of their keys and without the date that [Properties.store] adds, so that
     * a file changes only when its content does.
     */
    private fun store(
        properties: Properties,
        path: Path,
        comment: String,
    ) {
        val entries =
            StringWriter()
                .also { properties.store(it, null) }
                .toString()
                .lineSequence()
                .filterNot { it.isEmpty() || it.startsWith("#") }
                .sorted()
        Files.writeString(path, (sequenceOf("# $comment") + entries).joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }

    private fun load(path: Path): Properties =
        Properties().also { properties ->
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun Properties.required(key: String): String = getProperty(key) ?: error("Missing '$key' in parity recording.")

    private companion object {
        const val LABEL = "label.txt"
        const val LEGACY_RESPONSE = "expected-response.properties"
        const val NEW_RESPONSE = "expected-new-response.properties"
        const val STATUS = "status"
        const val BODY = "body"
        const val BODY_TEXT = "body.text"
        const val BODY_JSON = "body.json"
        const val BODY_CONTAINS = "body.contains"
        const val BODY_UNSPECIFIED = "body.unspecified"
        const val SNAPSHOT = "snapshot"
        const val TIMING_AT_LEAST = "timing.atLeastMillis"
        const val TIMING_LESS_THAN = "timing.lessThanMillis"
    }
}
