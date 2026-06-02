package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Root command for the kUML CLI.
 *
 * Registers all subcommands and delegates execution to them.
 * Additional subcommands (`validate`, `watch`, `fmt`) will be added in follow-up tickets.
 */
internal class KumlCli : CliktCommand(name = "kuml") {
    init {
        subcommands(RenderCommand())
    }

    override fun help(context: Context): String = "Compiles kUML scripts to UML/C4 diagrams."

    override fun run() = Unit
}
