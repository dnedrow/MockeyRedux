package io.github.dnedrow.mockey.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 13.3: the names under which the UI answers, see [AllowedHosts]. A page of another site whose name is
 * made to stand for the address of the UI asks the UI under the name of that site, and gets nothing.
 *
 * A request of these tests names its host as the test writes it, so it goes over a socket, see
 * [Browser.raw].
 */
class UiHostsTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private fun fake(): FakeAdminApi = FakeAdminApi().also { fakes += it }

    private fun uiFor(
        api: FakeAdminApi,
        allowedHosts: List<String> = emptyList(),
    ): MockeyUi {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), allowedHosts = allowedHosts, logLevel = LogLevel.WARN)
        return MockeyUi.start(settings, areas = listOf(SampleArea)).also { uis += it }
    }

    /** The UI answers under an IP address, under `localhost`, and under the names that it is told, with any port. */
    @Test
    fun theUiAnswersUnderAnAddressUnderLocalhostAndUnderTheNamesThatItIsTold() {
        val ui = uiFor(fake(), listOf("mockey.corp", "ui.team.example"))
        val browser = Browser(ui)
        val port = ui.address.port
        val known =
            listOf(
                "127.0.0.1:$port",
                "127.0.0.1",
                "192.168.7.7:8082",
                "[::1]:$port",
                "[fe80::1]",
                "localhost:$port",
                "LOCALHOST",
                "localhost.",
                "app.localhost:$port",
                "mockey.corp",
                "Mockey.Corp:8443",
                "ui.team.example:$port",
            )

        val answers = known.associateWith { browser.raw(SampleArea.NEW, listOf("Host: $it")).status }

        assertEquals(known.associateWith { 200 }, answers)
    }

    /**
     * A request that names another host gets nothing of the UI: no page, no token of a form, and no word of
     * the server, which is asked nothing. That holds for a form with a token that the UI would take as well.
     */
    @Test
    fun aRequestThatNamesAnotherHostGetsNothingOfTheUi() {
        val api = fake()
        val ui = uiFor(api, listOf("mockey.corp"))
        val browser = Browser(ui)
        val port = ui.address.port
        val form = browser.get(SampleArea.NEW).form()
        val token = form.select("[name=_token]").attr("value")
        val cookie = "Cookie: ${browser.cookies.entries.single()}"
        val before = api.requests.size
        val others =
            listOf(
                "evil.example",
                "evil.example:$port",
                "127.0.0.1.evil.example",
                "localhost.evil.example:$port",
                "mockey.corp.evil.example",
                "notmockey.corp",
                "corp",
                "999.1.1.1",
                "1.2.3",
                "127.0.0.1:$port, evil.example",
                "127.0.0.1@evil.example",
                "evil.example/",
                "[evil.example]",
                "local_host",
                "",
            )

        val refused = others.map { browser.raw(SampleArea.NEW, listOf("Host: $it", cookie)) }
        val twice = browser.raw(SampleArea.NEW, listOf("Host: 127.0.0.1:$port", "Host: evil.example"))
        val without = browser.raw(SampleArea.NEW, emptyList())
        val fields = "_token=$token&name=Customers&mockUrl=customer"
        val ofAForm = listOf("Host: evil.example:$port", cookie, "Content-Type: application/x-www-form-urlencoded")
        val posted = browser.raw("/sample/services", ofAForm, "POST", fields)

        for (answer in refused + twice + without + posted) {
            assertEquals(403 to "text/html; charset=UTF-8", answer.status to answer.header("content-type"))
            assertEquals("The UI does not answer under this name - Mockey", answer.page.title())
            assertEquals(0, answer.page.select("nav, footer, form, input, a, link, script").size)
            val said = answer.page.select("body > p").text()
            assertTrue(said.startsWith("The request names "), answer.body)
            assertFalse(token in answer.body || api.url in answer.body, answer.body)
            assertNull(answer.headers["set-cookie"])
            assertEquals("no-store" to "nosniff", answer.header("cache-control") to answer.header("x-content-type-options"))
        }
        assertEquals(before, api.requests.size)
        val toANamedHost =
            "The request names the host evil.example, which the UI does not go by. The UI answers under an IP address, " +
                "under localhost, and under the names that it is started with, in --allowed-host or MOCKEY_UI_ALLOWED_HOST. " +
                "So no page of another site reads or changes what the UI manages. " +
                "A UI that is to answer under this name is started with --allowed-host evil.example."
        assertEquals(toANamedHost, refused[1].page.select("body > p").text())
        val toNoName = refused.filter { "The request names no host that the UI goes by." in it.body }
        assertEquals(others.count { AllowedHosts.hostOf(it) == null }, toNoName.size)
        assertEquals(emptyList(), toNoName.filter { "evil" in it.body || "A UI that is to answer under this name" in it.body })
    }

    /** The name that the UI is told to listen on is one under which it answers, like the names that it is told for that. */
    @Test
    fun theNameThatTheUiListensOnIsOneOfItsNames() {
        val hosts = AllowedHosts.of(UiSettings(host = "ui.internal", allowedHosts = listOf("mockey.corp")))
        val byDefault = AllowedHosts.of(UiSettings())

        val asked = listOf("ui.internal:8082", "MOCKEY.corp", "other.internal")
        assertEquals(listOf(true, true, false), asked.map { hosts.allows(listOf(it)) })
        val ofAnyUi = listOf("127.0.0.1:8082", "localhost:8082", "ui.internal")
        assertEquals(listOf(true, true, false), ofAnyUi.map { byDefault.allows(listOf(it)) })
        assertFalse(hosts.allows(emptyList()) || hosts.allows(listOf("ui.internal", "ui.internal")))
    }

    /** What a `Host` header names is a host with or without a port, in any letter case. */
    @Test
    fun aHostHeaderNamesAHostWithOrWithoutAPort() {
        val named =
            mapOf(
                "Mockey.Corp:8443" to "mockey.corp",
                " mockey.corp " to "mockey.corp",
                "mockey.corp." to "mockey.corp",
                "10.1.2.3:80" to "10.1.2.3",
                "[::1]:8082" to "[::1]",
                "[2001:DB8::7]" to "[2001:db8::7]",
                "a-b.c-d.example" to "a-b.c-d.example",
                "mockey.corp:" to null,
                "mockey.corp:port" to null,
                "mockey.corp:123456" to null,
                "-mockey.corp" to null,
                "mockey..corp" to null,
                "mockey.corp/path" to null,
                "http://mockey.corp" to null,
                "user@mockey.corp" to null,
                "m\u00f6ckey.corp" to null,
                "[::1" to null,
                "::1" to null,
                "256.1.1.1" to null,
                "a".repeat(LONGER_THAN_A_NAME) to null,
            )

        assertEquals(named, named.keys.associateWith(AllowedHosts::hostOf))
    }

    private companion object {
        const val LONGER_THAN_A_NAME = 254
    }
}
