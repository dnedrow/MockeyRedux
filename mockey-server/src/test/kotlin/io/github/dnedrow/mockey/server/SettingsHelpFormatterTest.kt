package io.github.dnedrow.mockey.server

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** The usage line of the help for the kinds of commands, of which the server program has two. */
class SettingsHelpFormatterTest {
    /** A command that has a command of its own or has none, and that runs without one or does not. */
    private class Tool(
        override val invokeWithoutSubcommand: Boolean,
        hasCommand: Boolean,
    ) : CoreCliktCommand(name = "tool") {
        init {
            context { helpFormatter = { SettingsHelpFormatter(it) } }
            if (hasCommand) subcommands(Part())
        }

        override fun run() = Unit
    }

    private class Part : CoreCliktCommand(name = "part") {
        override fun run() = Unit
    }

    private fun usageOf(command: CoreCliktCommand): String =
        command
            .getFormattedHelp()
            .orEmpty()
            .lines()
            .first()

    @Test
    fun theUsageShowsTheCommandsOfACommandAsSomethingThatCanBeLeftOutWhenTheCommandRunsWithoutOne() {
        assertEquals("Usage: tool [<options>] [<command> [<args>]...]", usageOf(Tool(invokeWithoutSubcommand = true, hasCommand = true)))
        assertEquals("Usage: tool [<options>] <command> [<args>]...", usageOf(Tool(invokeWithoutSubcommand = false, hasCommand = true)))
    }

    @Test
    fun theUsageOfACommandWithoutCommandsShowsNone() {
        assertEquals("Usage: tool [<options>]", usageOf(Tool(invokeWithoutSubcommand = true, hasCommand = false)))
        assertEquals("Usage: tool [<options>]", usageOf(Tool(invokeWithoutSubcommand = false, hasCommand = false)))
    }
}
