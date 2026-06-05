package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.markdown.MarkdownOutputMode
import dev.kuml.markdown.MarkdownProcessor
import java.io.File
import java.io.IOException

/**
 * The `markdown` subcommand.
 *
 * Processes a Markdown (or AsciiDoc — fence-syntax compatible) file: every
 * ```` ```kuml ```` block is replaced by a rendered diagram according to
 * `--mode`.
 *
 * Examples:
 * ```
 * kuml markdown --input README.md --output README.rendered.md --mode inline
 * kuml markdown --input docs.md   --output docs.out.md         --mode linked-svg --assets-dir docs/assets
 * kuml markdown --input docs.md   --output docs.out.md         --mode linked-png --assets-dir docs/assets --width 1200
 * ```
 */
internal class MarkdownCommand : CliktCommand(name = "markdown") {
    private val input by option("-i", "--input", help = "Markdown input file")
        .file(mustExist = true, canBeDir = false)
        .required()

    private val output by option("-o", "--output", help = "Rendered Markdown output file")
        .file()
        .required()

    private val mode by option("--mode", help = "How to embed diagrams")
        .choice("inline", "linked-svg", "linked-png")
        .default("inline")

    private val assetsDir by option(
        "--assets-dir",
        help = "Directory where linked SVG/PNG files are written (default: <output-dir>/assets)",
    ).file(canBeFile = false)

    private val width by option("-w", "--width", help = "Width in pixels (linked-png only)")
        .int()
        .default(1024)

    override fun help(context: Context): String = "Render kuml code blocks in a Markdown file to SVG or PNG."

    override fun run() {
        try {
            val outputMode: MarkdownOutputMode =
                when (mode) {
                    "inline" -> MarkdownOutputMode.InlineSvg
                    "linked-svg" -> MarkdownOutputMode.LinkedSvg(resolveAssetsDir())
                    "linked-png" -> MarkdownOutputMode.LinkedPng(resolveAssetsDir(), width)
                    else -> error("Unsupported mode $mode")
                }
            val processor = MarkdownProcessor()
            val result =
                processor.process(
                    input = input.readText(Charsets.UTF_8),
                    mode = outputMode,
                    baseName = input.nameWithoutExtension,
                )
            output.parentFile?.mkdirs()
            output.writeText(result.output, Charsets.UTF_8)
            echo("Wrote ${output.path}")
            if (result.assets.isNotEmpty()) {
                echo("Assets:")
                result.assets.forEach { echo("  ${it.path}") }
            }
        } catch (e: ScriptEvaluationException) {
            System.err.println("Script error: ${e.message}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        } catch (e: IOException) {
            System.err.println("I/O error: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }

    private fun resolveAssetsDir(): File = assetsDir ?: File(output.parentFile ?: File("."), "assets")
}
