package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.core.config.KumlConfig
import dev.kuml.io.anim.AnimEncoderException
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
        .choice("svg", "png", "latex", "tex", "apng", "webp")

    private val width by option("-w", "--width", help = "Width in pixels (PNG only)")
        .int()
        .default(1024)

    private val themeName by option("--theme", help = "Theme name (e.g. kuml, plain); default is 'kuml'. Overrides config file")

    private val layoutEngine by
        option(
            "--layout",
            help =
                "Layout engine override: 'auto' (default), 'elk' (elk.layered), 'grid' (kuml.grid, experimental). " +
                    "Default is 'auto', which uses ELK for all diagram types. " +
                    "Use '--layout=grid' to opt in to the grid layout engine.",
        ).default("auto")

    private val configFile by option("--config", help = "Path to kuml.config.kts")
        .file(mustExist = true, canBeDir = false)

    private val animated by option(
        "--animated",
        help =
            "Emit an animated SMIL SVG (SVG output only). Works with STATE (UML state machine) " +
                "and ACTIVITY diagrams. Optionally supply --trace for trace-driven animation; " +
                "without --trace a demo StateEntered/TokenPlaced sequence is synthesised. " +
                "Not valid with --format=png or --format=latex.",
    ).flag()

    private val traceFile by option(
        "--trace",
        help = "Path to a kuml.trace.v1 JSON file for trace-driven animation (requires --animated).",
    ).file(mustExist = true, canBeDir = false)

    private val speed by option(
        "--speed",
        help = "Playback speed multiplier for animation (default 1.0). Must be > 0. Requires --animated.",
    ).double()
        .default(1.0)

    private val latexStandalone by option(
        "--latex-standalone",
        help =
            "Emit a complete LaTeX document (\\documentclass{standalone} + preamble) " +
                "instead of a bare tikzpicture snippet. Only valid with --format=latex.",
    ).flag()

    override fun help(context: Context): String = "Render a kUML script (UML or C4) to SVG, PNG, or LaTeX/TikZ source."

    override fun run() {
        try {
            val resolvedFormat = FormatResolver.resolve(format, output, input)
            // --animated is required for apng/webp, and only valid for svg/apng/webp
            val animFormats = setOf("svg", "apng", "webp")
            if (animated && resolvedFormat !in animFormats) {
                throw UsageError("--animated is only valid with --format=svg|apng|webp (current format: $resolvedFormat)")
            }
            if (resolvedFormat in setOf("apng", "webp") && !animated) {
                throw UsageError("--format=$resolvedFormat requires --animated")
            }
            // Warn when webp and no encoder binary available
            if (resolvedFormat == "webp") {
                val binAvailable =
                    try {
                        dev.kuml.io.anim.EncoderBinaryLocator
                            .isWebpAvailable()
                    } catch (_: Exception) {
                        false
                    }
                if (!binAvailable) {
                    System.err.println(
                        "[kuml] WARNING: --format=webp selected but no WebP encoder found on PATH. " +
                            "Install libwebp (provides img2webp) via 'brew install webp' on macOS, " +
                            "'apt-get install webp' on Debian/Ubuntu, or ensure ffmpeg is on PATH. " +
                            "The export will fail unless an encoder is installed.",
                    )
                }
            }
            // --speed must be positive
            if (speed <= 0.0) {
                throw UsageError("--speed must be greater than 0, got: $speed")
            }
            // --latex-standalone is only meaningful for latex output
            if (latexStandalone && resolvedFormat != "latex" && resolvedFormat != "tex") {
                throw UsageError("--latex-standalone is only valid with --format=latex (current format: $resolvedFormat)")
            }
            val resolvedOutput = output ?: FormatResolver.defaultOutput(input, resolvedFormat)
            val config: KumlConfig = ConfigLoader.load(configFile)
            val layoutOverride = layoutEngine.takeUnless { it == "auto" }
            RenderPipeline.run(
                input,
                resolvedOutput,
                resolvedFormat,
                width,
                themeName,
                config,
                layoutOverride,
                latexStandalone,
                animated = animated,
                traceFile = traceFile,
                speed = speed,
            )
            echo("Wrote $resolvedOutput")
        } catch (e: ScriptEvaluationException) {
            System.err.println("Script error: ${e.message}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        } catch (e: AnimEncoderException) {
            System.err.println("Animated export error: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        } catch (e: IOException) {
            System.err.println("I/O error: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }
}
