package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.core.config.KumlConfig
import java.io.IOException

/**
 * The `render` subcommand.
 *
 * Evaluates a `*.kuml.kts` script, runs the layout engine, and writes the
 * rendered diagram to an SVG or PNG file.
 *
 * Usage:
 * ```
 * kuml render [OPTIONS] INPUT
 * ```
 */
internal class RenderCommand : CliktCommand(name = "render") {
    private val input by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val output by option("-o", "--output", help = "Output file path")
        .path()

    private val format by option("-f", "--format", help = "Output format")
        .choice("svg", "png", "latex", "tex")

    private val width by option("-w", "--width", help = "Width in pixels (PNG only)")
        .int()
        .default(1024)

    private val themeName by option("--theme", help = "Theme name (e.g. plain, kuml); overrides config file")

    private val configFile by option("--config", help = "Path to kuml.config.kts")
        .file(mustExist = true, canBeDir = false)

    override fun help(context: Context): String = "Render a kUML script (UML or C4) to SVG, PNG, or LaTeX/TikZ source."

    override fun run() {
        try {
            val resolvedFormat = FormatResolver.resolve(format, output, input)
            val resolvedOutput = output ?: FormatResolver.defaultOutput(input, resolvedFormat)
            val config: KumlConfig = ConfigLoader.load(configFile)
            RenderPipeline.run(input, resolvedOutput, resolvedFormat, width, themeName, config)
            echo("Wrote $resolvedOutput")
        } catch (e: ScriptEvaluationException) {
            System.err.println("Script error: ${e.message}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        } catch (e: IOException) {
            System.err.println("I/O error: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }
}
