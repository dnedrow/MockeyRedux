package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.ResponseModeDto

/** The response modes of a service: what the pages call each, and how a form and the admin API name it. */
internal object Modes {
    /** Returns what the pages call [mode]. */
    fun labelOf(mode: ResponseModeDto): String =
        when (mode) {
            ResponseModeDto.PROXY -> "Proxy"
            ResponseModeDto.STATIC -> "Static"
            ResponseModeDto.DYNAMIC -> "Dynamic"
        }

    /** Returns the name of [mode] in a form and in a request to the admin API, which is the one that the API states it with. */
    fun nameOf(mode: ResponseModeDto): String = ResponseModeDto.serializer().descriptor.getElementName(mode.ordinal)

    /** Returns the mode with the name [name], or `null` when that is the name of none. */
    fun named(name: String): ResponseModeDto? = ResponseModeDto.entries.firstOrNull { nameOf(it) == name }
}
