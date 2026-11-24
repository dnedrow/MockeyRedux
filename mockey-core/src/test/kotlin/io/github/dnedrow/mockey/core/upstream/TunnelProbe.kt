package io.github.dnedrow.mockey.core.upstream

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A program that sends one request for an `https` URL through a stub proxy that wants Basic credentials, with
 * the JDK HTTP client, and prints the status that it gets as `status=200`.
 *
 * The JDK reads `jdk.http.auth.tunneling.disabledSchemes` once in the life of a JVM. A test therefore runs this
 * program in a JVM of its own to see what a value of that property does, see [run].
 */
internal object TunnelProbe {
    private const val SECONDS = 120L

    @JvmStatic
    fun main(args: Array<String>) {
        StubUpstream(StubTls.server).use { upstream ->
            StubProxy("user:secret").use { proxy ->
                val client =
                    HttpClient
                        .newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))
                        .sslContext(StubTls.trusting)
                        .build()
                val request =
                    HttpRequest
                        .newBuilder(
                            URI("${upstream.origin}/probe"),
                        ).header("Proxy-Authorization", proxy.authorization)
                        .build()
                val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                println("status=$status")
            }
        }
    }

    /**
     * Runs the program in a new JVM with the class path of this one, and returns the status that it printed.
     * [disabledSchemes] is the value of `jdk.http.auth.tunneling.disabledSchemes` for that JVM, or `null` to
     * leave the property unset.
     */
    fun run(disabledSchemes: String?): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val property = listOfNotNull(disabledSchemes?.let { "-Djdk.http.auth.tunneling.disabledSchemes=$it" })
        val command = listOf(java) + property + listOf("-cp", System.getProperty("java.class.path"), TunnelProbe::class.java.name)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "The probe failed: $output" }
        return output
            .lineSequence()
            .first { it.startsWith("status=") }
            .removePrefix("status=")
            .toInt()
    }
}
