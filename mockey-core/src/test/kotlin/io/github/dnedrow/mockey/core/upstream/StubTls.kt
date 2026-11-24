package io.github.dnedrow.mockey.core.upstream

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * The key material of the stubs that speak TLS: one certificate for `127.0.0.1` and `localhost` that the stub
 * signed itself. The `keytool` of the JDK that runs the tests makes it once for a test run, so that no key is
 * kept in the repository. No trust store of a runtime trusts the certificate.
 */
internal object StubTls {
    private const val PASSWORD = "stub-password"
    private const val ALIAS = "stub"
    private const val KEYTOOL_SECONDS = 60L

    private val keyStore: KeyStore by lazy {
        val directory = Files.createTempDirectory("mockey-stub-tls")
        val file = directory.resolve("stub.p12")
        try {
            runKeytool(file)
            Files.newInputStream(file).use { stored -> KeyStore.getInstance("PKCS12").apply { load(stored, PASSWORD.toCharArray()) } }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    /** What a stub that speaks TLS presents itself with. */
    val server: SSLContext by lazy {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    /** What a client needs to trust the certificate of the stubs, and nothing but that certificate. */
    val trusting: SSLContext by lazy {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.setCertificateEntry(ALIAS, keyStore.getCertificate(ALIAS))
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    private fun runKeytool(file: Path) {
        val home = Path.of(System.getProperty("java.home"), "bin")
        val keytool = listOf("keytool", "keytool.exe").map(home::resolve).firstOrNull(Files::exists) ?: error("No keytool in $home")
        val command =
            listOf(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                ALIAS,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-validity",
                "2",
                "-dname",
                "CN=Mockey test stub",
                "-ext",
                "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                file.toString(),
                "-storepass",
                PASSWORD,
            )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
    }
}
