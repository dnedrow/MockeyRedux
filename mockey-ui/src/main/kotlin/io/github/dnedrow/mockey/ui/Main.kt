package io.github.dnedrow.mockey.ui

import kotlin.system.exitProcess

/** Runs the UI program with the command-line arguments [args] and ends the process with its exit status. */
fun main(args: Array<String>) {
    exitProcess(UiCommand().execute(args.asList()))
}
