package dev.kuml.cli.trace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.trace.otlp.OtlpExporter
import java.io.IOException

/**
 * `kuml trace export [--format otlp-json] [--output file] [--service-name name] <trace.json>`
 *
 * Exports a recorded trace file to an OpenTelemetry-compatible format.
 *
 * Exit codes:
 * - 0 — export successful
 * - 3 — I/O error (IO_ERROR)
 */
internal class TraceExportCommand : CliktCommand(name = "export") {
    private val traceFile by argument(help = "Path to the recorded trace JSON file")
        .file(mustExist = true, canBeDir = false)

    private val format by option(
        "--format",
        help = "Export format (default: otlp-json)",
    ).choice("otlp-json").default("otlp-json")

    private val output by option(
        "--output",
        "-o",
        help = "Path to write the exported file (default: stdout)",
    ).file()

    private val serviceName by option(
        "--service-name",
        help = "OpenTelemetry service.name resource attribute (default: kuml.runtime)",
    ).default("kuml.runtime")

    override fun help(context: Context): String = "Export a recorded trace to an OpenTelemetry-compatible format (e.g. OTLP JSON)."

    override fun run() {
        val traceData =
            try {
                loadTrace(traceFile)
            } catch (e: Exception) {
                System.err.println("Failed to load trace: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        @Suppress("UNUSED_VARIABLE") // only one format supported; guard for future formats
        val exportedJson =
            when (format) {
                "otlp-json" -> OtlpExporter(serviceName = serviceName).exportToJson(traceData)
                else -> {
                    System.err.println("Unknown format: $format")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            }

        try {
            if (output != null) {
                output!!.parentFile?.mkdirs()
                output!!.writeText(exportedJson)
                echo("Exported OTLP JSON to ${output!!.absolutePath}")
            } else {
                echo(exportedJson, trailingNewline = false)
            }
        } catch (e: IOException) {
            System.err.println("I/O error writing export: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }
}
