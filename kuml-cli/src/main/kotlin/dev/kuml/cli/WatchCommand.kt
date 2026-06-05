package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.io.IOException
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The `watch` subcommand.
 *
 * Polls the input script for modifications and re-renders on every change.
 * Renders once immediately on start. Runs until Ctrl+C (thread interrupt).
 *
 * Script and I/O errors are printed to stderr with a timestamp; the watch
 * loop continues — it never exits with code 2 or 3.
 *
 * Usage:
 * ```
 * kuml watch [OPTIONS] INPUT
 * ```
 */
internal class WatchCommand : CliktCommand(name = "watch") {
    private val input by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val output by option("-o", "--output", help = "Output file path")
        .path()

    private val format by option("-f", "--format", help = "Output format")
        .choice("svg", "png")

    private val width by option("-w", "--width", help = "Width in pixels (PNG only)")
        .int()
        .default(1024)

    private val themeName by option("--theme", help = "Theme name")
        .choice("plain")
        .default("plain")

    override fun help(context: Context): String = "Watch a kUML script and re-render on every change. Exit: Ctrl+C."

    override fun run() {
        val resolvedFormat = FormatResolver.resolve(format, output, input)
        val resolvedOutput = output ?: FormatResolver.defaultOutput(input, resolvedFormat)

        echo("Watching ${input.name} — press Ctrl+C to stop")

        // Initial render before entering the watch loop
        renderSafe(resolvedFormat, resolvedOutput)

        // Poll loop — blocks until thread is interrupted (Ctrl+C / SIGINT)
        WatchLoop.watch(input) {
            renderSafe(resolvedFormat, resolvedOutput)
        }
    }

    private fun renderSafe(
        format: String,
        output: Path,
    ) {
        val ts = LocalTime.now().format(TS_FORMAT)
        try {
            RenderPipeline.run(input, output, format, width, themeName)
            echo("[$ts] Wrote $output")
        } catch (e: ScriptEvaluationException) {
            System.err.println("[$ts] Script error: ${e.message}")
            // Continue watching — do NOT rethrow
        } catch (e: IOException) {
            System.err.println("[$ts] I/O error: ${e.message}")
            // Continue watching — do NOT rethrow
        }
    }

    private companion object {
        val TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
