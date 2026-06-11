package dev.kuml.cli.sandbox

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * `kuml sandbox` — root command for sandbox policy validation and simulation.
 *
 * Subcommands:
 *  - `kuml sandbox validate <model.kuml.kts> [--strict] [--guard-timeout-ms N]`
 *
 * V2.0.40 — Sandbox-Garantien.
 */
internal class SandboxCommand : CliktCommand(name = "sandbox") {
    init {
        subcommands(SandboxValidateCommand())
    }

    override fun help(context: Context): String = "Sandbox policy commands — validate guards/actions against the sandbox policy (V2.0.40)."

    override fun run() = Unit
}
