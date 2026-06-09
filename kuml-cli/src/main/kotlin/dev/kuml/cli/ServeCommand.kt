package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.web.KumlWebServer

/**
 * The `serve` subcommand.
 *
 * Starts the kUML web server with a live SVG preview UI.
 *
 * Usage:
 * ```
 * kuml serve [--port N] [--host H]
 * ```
 */
internal class ServeCommand : CliktCommand(name = "serve") {
    private val port by option("--port", help = "HTTP port to listen on").int().default(8080)
    private val host by option("--host", help = "Host to bind to").default("0.0.0.0")

    override fun help(context: Context): String = "Start the kUML web UI (live SVG preview)."

    override fun run() {
        KumlWebServer.start(host, port)
    }
}
