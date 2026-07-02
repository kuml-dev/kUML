package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.asciidoc.AsciidocOutputMode
import dev.kuml.asciidoc.AsciidocProcessor
import dev.kuml.core.script.ScriptEvaluationException
import java.io.File
import java.io.IOException

/**
 * The `asciidoc` subcommand.
 *
 * Pre-renders `[source,kuml]` listing blocks and `kuml::path[]` block macros in AsciiDoc
 * files to SVG/PNG, producing plain AsciiDoc that Antora's Node.js-based Asciidoctor
 * pipeline consumes without any further setup (Antora cannot load the JVM `kuml-asciidoc`
 * Asciidoctor extension directly — this CLI subcommand is the pre-render bridge).
 *
 * Two modes:
 *  - Single file: `--input page.adoc --output page.rendered.adoc`
 *  - Directory tree: `--input-dir docs/handbook --output-dir build/handbook-rendered`
 *    (mirrors the whole tree, rendering every `*.adoc` file found; non-`.adoc` files
 *    are copied through unchanged so Antora's module layout stays intact).
 *
 * Examples:
 * ```
 * kuml asciidoc --input guide.adoc --output guide.rendered.adoc --mode inline
 * kuml asciidoc --input-dir docs/handbook --output-dir build/handbook-rendered --mode inline
 * ```
 */
internal class AsciidocCommand : CliktCommand(name = "asciidoc") {
    private val input by option("-i", "--input", help = "AsciiDoc input file")
        .file(mustExist = true, canBeDir = false)

    private val output by option("-o", "--output", help = "Rendered AsciiDoc output file")
        .file()

    private val inputDir by option("--input-dir", help = "Directory tree to render recursively (Antora module tree)")
        .file(mustExist = true, canBeFile = false)

    private val outputDir by option("--output-dir", help = "Output directory mirroring --input-dir")
        .file(canBeFile = false)

    private val mode by option("--mode", help = "How to embed diagrams")
        .choice("inline", "linked-svg", "linked-png")
        .default("inline")

    private val assetsDir by option(
        "--assets-dir",
        help = "Directory where linked SVG/PNG files are written (default: <output-dir>/assets, single-file mode only)",
    ).file(canBeFile = false)

    private val width by option("-w", "--width", help = "Width in pixels (linked-png only)")
        .int()
        .default(1024)

    private val quiet by option("--quiet", help = "Suppress per-file progress output (directory mode)")
        .flag(default = false)

    override fun help(context: Context): String = "Render kuml blocks in AsciiDoc file(s) to SVG or PNG (Antora pre-render step)."

    override fun run() {
        val singleFileArgs = input != null || output != null
        val dirArgs = inputDir != null || outputDir != null
        if (singleFileArgs && dirArgs) {
            System.err.println("Use either --input/--output or --input-dir/--output-dir, not both.")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
        try {
            when {
                dirArgs -> runDirMode()
                else -> runSingleFileMode()
            }
        } catch (e: ScriptEvaluationException) {
            System.err.println("Script error: ${e.message}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        } catch (e: IOException) {
            System.err.println("I/O error: ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }

    private fun runSingleFileMode() {
        val inFile = input ?: error("--input is required in single-file mode")
        val outFile = output ?: error("--output is required in single-file mode")
        val outputMode = resolveOutputMode(outFile.parentFile ?: File("."))
        val processor = AsciidocProcessor(baseDir = inFile.parentFile ?: File("."))
        val result =
            processor.process(
                input = inFile.readText(Charsets.UTF_8),
                mode = outputMode,
                baseName = inFile.nameWithoutExtension,
            )
        outFile.parentFile?.mkdirs()
        outFile.writeText(result.output, Charsets.UTF_8)
        echo("Wrote ${outFile.path}")
        if (result.assets.isNotEmpty()) {
            echo("Assets:")
            result.assets.forEach { echo("  ${it.path}") }
        }
    }

    private fun runDirMode() {
        val inDir = inputDir ?: error("--input-dir is required in directory mode")
        val outDir = outputDir ?: error("--output-dir is required in directory mode")
        outDir.mkdirs()

        var renderedCount = 0
        var copiedCount = 0
        inDir.walkTopDown().filter { it.isFile }.forEach { srcFile ->
            val relative = srcFile.relativeTo(inDir)
            val destFile = File(outDir, relative.path)
            destFile.parentFile?.mkdirs()

            if (srcFile.extension == "adoc") {
                val processor = AsciidocProcessor(baseDir = srcFile.parentFile ?: inDir)
                // Default assets dir for linked-svg/linked-png in directory mode is the
                // Antora module's `images/` directory — a *sibling* of `pages/`, not a
                // child of it (Antora resolves `image::foo.svg[]` against
                // `modules/<module>/images/foo.svg`, regardless of how deeply the page
                // is nested under `pages/`). See docs/handbook/modules/tooling/pages/asciidoc.adoc.
                val defaultAssetsDir = File(resolveAntoraModuleRoot(destFile, outDir), "images")
                val outputMode =
                    when (mode) {
                        "inline" -> AsciidocOutputMode.InlineSvg
                        "linked-svg" -> AsciidocOutputMode.LinkedSvg(assetsDir ?: defaultAssetsDir)
                        "linked-png" -> AsciidocOutputMode.LinkedPng(assetsDir ?: defaultAssetsDir, width)
                        else -> error("Unsupported mode $mode")
                    }
                val result =
                    processor.process(
                        input = srcFile.readText(Charsets.UTF_8),
                        mode = outputMode,
                        baseName = srcFile.nameWithoutExtension,
                    )
                destFile.writeText(result.output, Charsets.UTF_8)
                renderedCount++
                if (!quiet && result.assets.isNotEmpty()) {
                    echo("Rendered ${relative.path} (${result.assets.size} diagram(s))")
                }
            } else {
                srcFile.copyTo(destFile, overwrite = true)
                copiedCount++
            }
        }
        echo("Processed $renderedCount .adoc file(s), copied $copiedCount other file(s) into ${outDir.path}")
    }

    private fun resolveOutputMode(outputParentDir: File): AsciidocOutputMode =
        when (mode) {
            "inline" -> AsciidocOutputMode.InlineSvg
            "linked-svg" -> AsciidocOutputMode.LinkedSvg(resolveAssetsDir(outputParentDir))
            "linked-png" -> AsciidocOutputMode.LinkedPng(resolveAssetsDir(outputParentDir), width)
            else -> error("Unsupported mode $mode")
        }

    private fun resolveAssetsDir(outputParentDir: File): File = assetsDir ?: File(outputParentDir, "assets")

    /**
     * Antora module layout: `modules/<module>/{pages,images,partials,...}/`. For a page at
     * `.../modules/<module>/pages/[sub/dir/]page.adoc`, the module root is the `pages`
     * directory's parent — `image::foo.svg[]` resolves against `<module root>/images/foo.svg`
     * no matter how deeply the page itself is nested under `pages/`.
     *
     * Falls back to the page's own parent directory when no `pages` ancestor is found
     * (e.g. a non-Antora AsciiDoc tree), matching the pre-V3.2.19 behaviour.
     */
    private fun resolveAntoraModuleRoot(
        destFile: File,
        outDir: File,
    ): File {
        var dir: File? = destFile.parentFile
        while (dir != null && dir.path.length >= outDir.path.length) {
            if (dir.name == "pages") return dir.parentFile ?: dir
            dir = dir.parentFile
        }
        return destFile.parentFile ?: outDir
    }
}
