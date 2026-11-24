package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Tags
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The settings of the server program as the log states them, and the admin token, which nothing states. */
class ServerSettingsTest {
    @Test
    fun theSummaryStatesEverySettingUnderTheNameOfItsOption() {
        val settings =
            ServerSettings(
                mockHost = "192.0.2.1",
                mockPort = 9100,
                adminHost = "192.0.2.2",
                adminPort = 9200,
                adminToken = AdminToken("tk-71c0"),
                allowUnprotectedAdmin = true,
                definitions = Path("/var/lib/mockey/../mockey/definitions"),
                readOnly = false,
                initialDefinitions = "legacy/mock_service_definitions.xml",
                tagFilter = Tags.of("smoke", "regression"),
                historyLimit = 20,
                bodyCaptureLimit = 2048,
                upstreamConnectTimeout = 500.milliseconds,
                upstreamResponseTimeout = 2.minutes,
                maxRequestBody = 2_097_152,
                forwardUnknownTargets = false,
                logLevel = LogLevel.DEBUG,
                shutdownGracePeriod = 3.seconds,
            )

        val expected =
            listOf(
                "mock-host" to "192.0.2.1",
                "mock-port" to "9100",
                "admin-host" to "192.0.2.2",
                "admin-port" to "9200",
                "admin-token" to "set",
                "allow-unprotected-admin" to "true",
                "definitions" to "/var/lib/mockey/definitions",
                "read-only" to "false",
                "initial-definitions" to "legacy/mock_service_definitions.xml",
                "tag-filter" to "regression smoke",
                "history-limit" to "20",
                "body-capture-limit" to "2048",
                "upstream-connect-timeout" to "500ms",
                "upstream-response-timeout" to "2m",
                "max-request-body" to "2097152",
                "forward-unknown-targets" to "false",
                "log-level" to "debug",
                "shutdown-grace-period" to "3s",
            )
        assertEquals(expected, settings.summary().toList())
    }

    @Test
    fun theSummaryOfTheDefaultsNamesWhatIsNotSet() {
        val summary = ServerSettings().summary()

        assertEquals("none", summary["admin-token"])
        assertEquals("none", summary["initial-definitions"])
        assertEquals("none", summary["tag-filter"])
        assertEquals(Path("mockey-definitions").toAbsolutePath().toString(), summary["definitions"])
    }

    @Test
    fun theSummaryHasAnEntryForEveryOptionOfTheCommand() {
        val options = ServerCommand().registeredOptions().filterNot { it.eager }

        assertEquals(options.map { it.names.single().removePrefix("--") }, ServerSettings().summary().keys.toList())
    }

    @Test
    fun aUrlLosesItsUserInformationAndItsQueryForTheLog() {
        assertEquals("https://example.org/definitions.json", withoutSecrets("https://example.org/definitions.json"))
        assertEquals("https://***@example.org/definitions.json", withoutSecrets("https://reader:pw-83a1@example.org/definitions.json"))
        assertEquals("https://***@example.org:8443/a/b.xml", withoutSecrets("https://pw-83a1@example.org:8443/a/b.xml"))
        assertEquals("http://example.org/export?***", withoutSecrets("http://example.org/export?key=pw-83a1&format=json"))
        assertEquals("http://***@example.org/export?***#top", withoutSecrets("http://a:b@example.org/export?key=c#top"))
        assertEquals("http://example.org/a@b", withoutSecrets("http://example.org/a@b"))
        assertEquals("http://***@example.org", withoutSecrets("http://a:b@c@example.org"))
    }

    @Test
    fun aPathStaysAsItIsForTheLog() {
        for (path in listOf(
            "definitions.json",
            "/srv/mockey/legacy",
            "legacy dir/mock_service_definitions.xml",
            """C:\mockey\export.xml""",
            "a?b",
            "user@host",
        )) {
            assertEquals(path, withoutSecrets(path))
        }
    }

    @Test
    fun theSummaryHoldsNoSecretOfAUrl() {
        val summary = ServerSettings(initialDefinitions = "https://reader:pw-83a1@example.org/export?key=k-55f2").summary()

        assertEquals("https://***@example.org/export?***", summary["initial-definitions"])
    }

    @Test
    fun anAdminTokenIsMatchedByItsOwnTextOnly() {
        val token = AdminToken("tk-71c0")

        assertTrue(token.matches("tk-71c0"))
        for (other in listOf("tk-71c", "tk-71c00", "TK-71C0", " tk-71c0", "tk-71c0 ", "", "Bearer tk-71c0")) {
            assertFalse(token.matches(other), other)
        }
    }

    @Test
    fun anAdminTokenIsTakenAsItIsWritten() {
        assertTrue(AdminToken(" spaced out ").matches(" spaced out "))
        assertFalse(AdminToken(" spaced out ").matches("spaced out"))
        assertTrue(AdminToken("schlüssel-鍵").matches("schlüssel-鍵"))
    }

    @Test
    fun anAdminTokenDoesNotShowItsValue() {
        val token = AdminToken("tk-71c0")

        assertEquals("AdminToken(******)", token.toString())
        assertFalse("tk-71c0" in "$token")
        assertFalse("tk-71c0" in ServerSettings(adminToken = token).toString())
        assertEquals(AdminToken("another token").hashCode(), token.hashCode())
    }

    @Test
    fun adminTokensAreEqualWhenTheirTextsAre() {
        assertEquals(AdminToken("tk-71c0"), AdminToken("tk-71c0"))
        assertEquals(AdminToken("tk-71c0").hashCode(), AdminToken("tk-71c0").hashCode())
        assertNotEquals(AdminToken("tk-71c0"), AdminToken("tk-71c1"))
        assertEquals(ServerSettings(adminToken = AdminToken("tk-71c0")), ServerSettings(adminToken = AdminToken("tk-71c0")))
    }

    @Test
    fun aBlankAdminTokenIsNoToken() {
        for (blank in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { AdminToken(blank) }
        }
    }
}
