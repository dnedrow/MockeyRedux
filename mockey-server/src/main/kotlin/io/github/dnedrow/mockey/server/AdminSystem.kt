package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.ktor.http.HttpMethod
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The operations of the admin API on the server as a whole.
 *
 * [engine] is the engine of the server. [since] is the time at which the server started, [definitions] is
 * its definitions location, and [mock] is the address and the port on which its mock listener accepts
 * connections.
 */
internal class AdminSystem(
    private val engine: MockeyEngine,
    private val since: Instant,
    private val definitions: Path,
    private val mock: InetSocketAddress,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "system") { AdminResponse.json(status(host)) },
        )

    /**
     * Returns the status of the server for a client that reached the admin listener by [host]: the version
     * of the server, the time at which it started, its definitions location, whether it is in read-only
     * mode, its tag filter, the twist configuration that is enabled, the base URL of its mock endpoint, see
     * [mockBaseUrl], and whether the legacy shim is enabled.
     *
     * The time is stated in ISO 8601 and to the millisecond, as `/status` states it. The server has no
     * legacy shim, so none is enabled.
     */
    private fun status(host: String): SystemStatusDto {
        val current = engine.definitions
        val enabledTwist = current.settings.enabledTwistId?.let { id -> current.twists.firstOrNull { it.id == id } }
        return SystemStatusDto(
            version = ServerVersion.text,
            startTime = since.truncatedTo(ChronoUnit.MILLIS).toString(),
            definitionsLocation = definitions.toAbsolutePath().normalize().toString(),
            readOnly = engine.readOnly,
            tagFilter = engine.tagFilter.toList(),
            enabledTwist = enabledTwist?.toAdminDto(),
            mockBaseUrl = mockBaseUrl(host),
            legacyShimEnabled = false,
        )
    }

    /**
     * Returns the URL of the mock listener without a path, such as `http://mockey.test:8080`. The mock URL
     * of a service follows `/service/` after it.
     *
     * A mock listener on one address has that address in its URL. A mock listener on all addresses of the
     * machine, which is the default, has no address that a client could write. Its URL names the [host] by
     * which the client reached the admin listener, because the mock listener accepts connections there as
     * well.
     */
    private fun mockBaseUrl(host: String): String =
        if (mock.address.isAnyLocalAddress) "http://$host:${mock.port}" else "http://${mock.text()}"
}
