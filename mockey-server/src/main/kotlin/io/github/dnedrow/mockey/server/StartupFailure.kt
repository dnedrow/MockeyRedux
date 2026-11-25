package io.github.dnedrow.mockey.server

/**
 * A reason for which the server does not start, and which the one who starts it can do something about. The
 * message says what is wrong and how to go on. The program writes it to the standard error and ends with a
 * status other than 0.
 */
internal class StartupFailure(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
