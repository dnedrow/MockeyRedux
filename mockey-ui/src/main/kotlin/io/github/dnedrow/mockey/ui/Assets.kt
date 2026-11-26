package io.github.dnedrow.mockey.ui

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.security.MessageDigest

/**
 * The files that the pages of the UI refer to: the stylesheet, the icon, and the script. The UI serves each
 * of them itself, from what the program holds, so that a browser asks no other place for anything.
 *
 * A file is served under the path of [path]. A browser may keep it, and asks whether it still holds: every
 * file has a tag that is made of its bytes, and a request that names the tag gets the status 304.
 */
internal object Assets {
    /** The path under which the files are served. */
    const val PREFIX = "/assets"

    private class Asset(
        val bytes: ByteArray,
        val contentType: ContentType,
    ) {
        /** The tag of the file, which another file does not have. */
        val tag: String = digestOf(bytes).take(TAG_BYTES).joinToString("", "\"", "\"") { "%02x".format(it) }
    }

    private const val TAG_BYTES = 12

    private fun digestOf(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private val files: Map<String, Asset> =
        mapOf(
            "mockey.css" to asset("mockey.css", ContentType.Text.CSS.withCharset(Charsets.UTF_8)),
            "favicon.svg" to asset("favicon.svg", ContentType.Image.SVG),
            Enhancement.SCRIPT to asset(Enhancement.SCRIPT, ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)),
        )

    /** The names of the files. */
    val names: Set<String>
        get() = files.keys

    /** Returns the path at which the file [name] is served, which a page refers to. */
    fun path(name: String): String {
        check(name in files) { "The UI has no file $name." }
        return "$PREFIX/$name"
    }

    /**
     * Answers the request of [call] with the file [name]. A request for a name that is none of the files
     * gets `false`, and no answer: no other file of the program is served, whatever the name is.
     */
    suspend fun respond(
        call: ApplicationCall,
        name: String,
    ): Boolean {
        val asset = files[name] ?: return false
        call.response.header(HttpHeaders.ETag, asset.tag)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.tag) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondBytes(asset.bytes, asset.contentType)
        }
        return true
    }

    private fun asset(
        name: String,
        contentType: ContentType,
    ): Asset {
        val resource = checkNotNull(Assets::class.java.getResourceAsStream("assets/$name")) { "The program has no resource assets/$name." }
        return Asset(resource.use { it.readBytes() }, contentType)
    }
}
