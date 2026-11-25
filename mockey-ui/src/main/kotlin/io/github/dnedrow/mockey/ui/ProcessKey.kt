package io.github.dnedrow.mockey.ui

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key with which the UI marks what it gives to a browser and takes back from it: the tokens of its
 * forms, and its notices. Only a program with the key makes the mark of a text, so a mark that holds shows
 * that this UI made it.
 *
 * The key is made anew when the UI starts, and the UI stores it nowhere. So what a UI marked is no longer
 * taken after that UI has stopped, by the UI that is started in its place either.
 */
internal class ProcessKey {
    private val key = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), ALGORITHM)

    /**
     * Returns the mark of [text], for [purpose]. A mark for one purpose is none for another, so that what
     * the UI gives out for one use cannot be handed back for another one.
     */
    fun markOf(
        purpose: String,
        text: String,
    ): String {
        val mac = Mac.getInstance(ALGORITHM).also { it.init(key) }
        return encoded(mac.doFinal("$purpose\n$text".toByteArray(Charsets.UTF_8)))
    }

    /** Returns whether [mark] is the mark of [text] for [purpose]. How long the answer takes does not tell where a wrong mark differs. */
    fun isMarkOf(
        purpose: String,
        text: String,
        mark: String,
    ): Boolean = MessageDigest.isEqual(markOf(purpose, text).toByteArray(Charsets.UTF_8), mark.toByteArray(Charsets.UTF_8))

    internal companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_BYTES = 32

        /** Returns [bytes] as a text of letters, digits, hyphens, and underscores, which a cookie and a form field hold as it is. */
        fun encoded(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** Returns the bytes that [text] stands for, see [encoded], or `null` when it stands for none. */
        fun decoded(text: String): ByteArray? =
            try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
