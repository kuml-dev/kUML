package dev.kuml.cli.trace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * `kuml trace` — root command for trace inspection, replay, and export.
 *
 * Subcommands:
 * - `kuml trace replay <trace.json> <model.kuml.kts> [--verbose]`
 * - `kuml trace export [--format otlp-json] [--output file] <trace.json>`
 */
internal class TraceCommand : CliktCommand(name = "trace") {
    init {
        subcommands(TraceReplayCommand(), TraceExportCommand())
    }

    override fun help(context: Context): String = "Inspect, replay, and export behaviour traces (V2.0.39)."

    override fun run() = Unit
}
