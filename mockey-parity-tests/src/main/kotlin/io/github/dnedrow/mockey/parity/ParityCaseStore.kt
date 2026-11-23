package io.github.dnedrow.mockey.parity

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
        Files.writeString(directory.resolve("label.txt"), case.label, StandardCharsets.UTF_8)
        Files.writeString(directory.resolve("fixture.xml"), case.fixtureXml, StandardCharsets.UTF_8)
        writeRequest(case.request, directory.resolve("request.properties"))
        writeResponse(case.expectedResponse, directory.resolve("expected-response.properties"))
        writeTiming(case.timing, directory.resolve("timing.properties"))
    }

    fun read(directory: Path): ParityCase {
        val label = Files.readString(directory.resolve("label.txt"), StandardCharsets.UTF_8).trim()
        val fixture = Files.readString(directory.resolve("fixture.xml"), StandardCharsets.UTF_8)
        val request = readRequest(directory.resolve("request.properties"))
        val response = readResponse(directory.resolve("expected-response.properties"))
        val timing = readTiming(directory.resolve("timing.properties"))
        return ParityCase(label, fixture, request, response, timing)
    }

    private fun writeRequest(
        request: ParityRequest,
        path: Path,
    ) {
        val properties = Properties()
        properties["method"] = request.method
        properties["target"] = request.target
        properties["body"] = encode(request.body)
        writeHeaders(request.headers, properties)
        store(properties, path, "Mockey parity request")
    }

    private fun readRequest(path: Path): ParityRequest {
        val properties = load(path)
        return ParityRequest(
            method = properties.required("method"),
            target = properties.required("target"),
            headers = readHeaders(properties),
            body = decode(properties.required("body")),
        )
    }

    private fun writeResponse(
        response: ParityResponse,
        path: Path,
    ) {
        val properties = Properties()
        properties["status"] = response.status.toString()
        properties["body"] = encode(response.body)
        writeHeaders(response.headers, properties)
        store(properties, path, "Mockey parity response")
    }

    private fun readResponse(path: Path): ParityResponse {
        val properties = load(path)
        return ParityResponse(
            status = properties.required("status").toInt(),
            headers = readHeaders(properties),
            body = decode(properties.required("body")),
        )
    }

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

    private fun store(
        properties: Properties,
        path: Path,
        comment: String,
    ) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { properties.store(it, comment) }
    }

    private fun load(path: Path): Properties =
        Properties().also { properties ->
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun Properties.required(key: String): String = getProperty(key) ?: error("Missing '$key' in parity recording.")
}
