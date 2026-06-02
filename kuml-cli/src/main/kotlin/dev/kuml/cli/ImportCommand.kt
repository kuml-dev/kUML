package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.structurizr.KumlDslGenerator
import dev.kuml.cli.structurizr.StructurizrDslParser
import java.io.File
import java.io.IOException

/**
 * The `import` subcommand.
 *
 * Parses a diagram file in an external format and generates a `.kuml.kts` script.
 * V1 supports Structurizr DSL only.
 *
 * Exit codes:
 * - 0: success
 * - 1: unknown format (via Clikt choice validation)
 * - 3: I/O error reading or writing files (see [ExitCodes.IO_ERROR])
 */
internal class ImportCommand : CliktCommand(name = "import") {
    private val input by argument(help = "Path to the input file (e.g. workspace.dsl)")
        .file(mustExist = true, canBeDir = false)

    private val format by option("--format", help = "Source format to import from")
        .choice("structurizr")
        .default("structurizr")

    private val output by option("-o", "--output", help = "Output .kuml.kts file (default: input filename with .kuml.kts extension)")
        .file()

    override fun help(context: Context): String = "Import a diagram from another format (V1: Structurizr DSL)."

    override fun run() {
        val inputFile = input
        val outputFile = output ?: deriveOutputFile(inputFile)

        val source =
            try {
                inputFile.readText()
            } catch (e: IOException) {
                System.err.println("I/O error reading '${inputFile.path}': ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        val kumlScript =
            when (format) {
                "structurizr" -> {
                    val workspace = StructurizrDslParser.parse(source)
                    KumlDslGenerator.generate(workspace, inputFile.name)
                }
                else -> {
                    // Clikt choice() already validates the value, but keep for safety
                    System.err.println("Unknown format: $format")
                    throw ProgramResult(1)
                }
            }

        try {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(kumlScript)
        } catch (e: IOException) {
            System.err.println("I/O error writing '${outputFile.path}': ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        echo("Imported: ${inputFile.name} → ${outputFile.path}")
    }

    private fun deriveOutputFile(inputFile: File): File {
        val baseName = inputFile.nameWithoutExtension
        return File(inputFile.parentFile, "$baseName.kuml.kts")
    }
}
