package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.io.IOException

/**
 * The `fmt` subcommand.
 *
 * Formats `*.kuml.kts` scripts in place. Applies idempotent text normalisations:
 * trailing whitespace removal, tab-to-space conversion (4 spaces per tab),
 * consecutive blank-line collapsing (max 1), and a single trailing newline.
 *
 * Use `--check` in CI pipelines to verify that files are already formatted
 * without modifying them.
 *
 * Usage:
 * ```
 * kuml fmt order.kuml.kts
 * kuml fmt *.kuml.kts
 * kuml fmt --check order.kuml.kts
 * kuml fmt --canonical order.kuml.kts
 * kuml fmt --canonical --check order.kuml.kts
 * ```
 */
internal class FmtCommand : CliktCommand(name = "fmt") {
    private val inputs by argument(help = "*.kuml.kts files to format").multiple(required = true)

    private val check by option(
        "--check",
        help =
            "Check formatting without modifying files. " +
                "Exit ${ExitCodes.FMT_CHECK_FAILED} if any files need changes.",
    ).flag()

    private val canonical by option(
        "--canonical",
        help =
            "Apply the canonical normal form (removes all blank lines, unifies EOL) used for " +
                "deterministic model hashing (V3.0.1). Stricter than the default format. " +
                "Combine with --check for CI hash-stability verification.",
    ).flag()

    override fun help(context: Context): String =
        "Format kUML scripts in place. Use --check for CI verification, --canonical for hash-stable form."

    override fun run() {
        var needsChanges = false
        for (inputPath in inputs) {
            val file = File(inputPath)
            val original =
                try {
                    file.readText()
                } catch (e: IOException) {
                    System.err.println("I/O error reading '$inputPath': ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            val formatted = if (canonical) KumlFormatter.canonical(original) else KumlFormatter.format(original)
            when {
                original == formatted -> {
                    if (check) echo("$inputPath: ok")
                }
                check -> {
                    echo("$inputPath: needs formatting")
                    needsChanges = true
                }
                else -> {
                    try {
                        file.writeText(formatted)
                        echo("$inputPath: formatted")
                    } catch (e: IOException) {
                        System.err.println("I/O error writing '$inputPath': ${e.message}")
                        throw ProgramResult(ExitCodes.IO_ERROR)
                    }
                }
            }
        }
        if (check && needsChanges) throw ProgramResult(ExitCodes.FMT_CHECK_FAILED)
    }
}
