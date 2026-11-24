package io.github.dnedrow.mockey.server

import kotlin.system.exitProcess

/** Runs the server program with the command-line arguments [args] and ends the process with its exit status. */
fun main(args: Array<String>) {
    exitProcess(ServerCommand().execute(args.asList()))
}
