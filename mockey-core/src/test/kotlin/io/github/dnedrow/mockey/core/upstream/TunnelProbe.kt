package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A program that sends one request for an `https` URL through a stub proxy that wants Basic credentials, and
 * prints what comes of it: the status that it gets as `status=200`, or the message of the failure as
 * `failure=...`, and then the value that `jdk.http.auth.tunneling.disabledSchemes` has afterwards as
 * `property=...`, with `unset` for a property that is not set.
 *
 * The first argument says who sends the request: `jdk` for the JDK HTTP client as it is, and `adapter` for the
 * upstream client of the library.
 *
 * The JDK reads the property once in the life of a JVM. A test therefore runs this program in a JVM of its own
 * to see what a value of that property does, see [run].
 */
internal object TunnelProbe {
    private const val SECONDS = 120L
    private const val PROPERTY = "jdk.http.auth.tunneling.disabledSchemes"

    @JvmStatic
    fun main(args: Array<String>) {
        StubUpstream(StubTls.server).use { upstream ->
            StubProxy("user:secret").use { proxy ->
                val url = "${upstream.origin}/probe"
                println(if (args.firstOrNull() == "adapter") throughAdapter(url, proxy) else throughJdk(url, proxy))
                println("property=${System.getProperty(PROPERTY) ?: "unset"}")
            }
        }
    }

    private fun throughJdk(
        url: String,
        proxy: StubProxy,
    ): String {
        val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", proxy.port)))
                .sslContext(StubTls.trusting)
                .build()
        val request = HttpRequest.newBuilder(URI(url)).header("Proxy-Authorization", proxy.authorization).build()
        return "status=${client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()}"
    }

    private fun throughAdapter(
        url: String,
        proxy: StubProxy,
    ): String {
        val outboundProxy = OutboundProxy(proxy.url, enabled = true, credentials = ProxyCredentials("user", "secret"))
        return JdkUpstreamClient(sslContext = StubTls.trusting).use { client ->
            try {
                "status=${runBlocking { client.send(UpstreamRequest("GET", url, outboundProxy = outboundProxy)) }.status}"
            } catch (e: UpstreamException) {
                "failure=${e.message}"
            }
        }
    }

    /**
     * Runs the program in a new JVM with the class path of this one, and returns what it printed: the text
     * after `status=`, `failure=`, and `property=`, under these names. [disabledSchemes] is the value of
     * `jdk.http.auth.tunneling.disabledSchemes` for that JVM, or `null` to leave the property unset. [sender]
     * is the first argument of the program.
     */
    fun run(
        disabledSchemes: String?,
        sender: String = "jdk",
    ): Map<String, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val property = listOfNotNull(disabledSchemes?.let { "-D$PROPERTY=$it" })
        val command = listOf(java) + property + listOf("-cp", System.getProperty("java.class.path"), TunnelProbe::class.java.name, sender)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "The probe failed: $output" }
        return output
            .lineSequence()
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
