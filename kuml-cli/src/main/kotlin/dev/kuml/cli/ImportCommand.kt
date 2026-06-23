package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.reverse.UmlModelDslPrinter
import dev.kuml.cli.structurizr.KumlDslGenerator
import dev.kuml.cli.structurizr.StructurizrDslParser
import dev.kuml.core.model.KumlModel
import java.io.File
import java.io.IOException

/**
 * The `import` subcommand.
 *
 * Parses a diagram file in an external format and generates a `.kuml.kts` script.
 * V1 supports Structurizr DSL only.
 * V3.0.17 adds XMI import via reflection (EMF is JVM-only, not in Native Image).
 *
 * Exit codes:
 * - 0: success
 * - 1: unknown format (via Clikt choice validation)
 * - 4: I/O error reading or writing files (see [ExitCodes.IO_ERROR])
 * - 24: format requires Fat-JAR distribution (see [ExitCodes.FORMAT_NOT_AVAILABLE])
 */
internal class ImportCommand : CliktCommand(name = "import") {
    private val input by argument(help = "Path to the input file (e.g. workspace.dsl, model.xmi)")
        .file(mustExist = true, canBeDir = false)

    private val format by option("--format", help = "Source format to import from")
        .choice("structurizr", "xmi")
        .default("structurizr")

    private val output by option("-o", "--output", help = "Output .kuml.kts file (default: input filename with .kuml.kts extension)")
        .file()

    override fun help(context: Context): String = "Import a diagram from another format (Structurizr DSL, XMI)."

    override fun run() {
        val inputFile = input
        val outputFile = output ?: deriveOutputFile(inputFile)

        when (format) {
            "structurizr" -> importStructurizr(inputFile, outputFile)
            "xmi" -> importXmi(inputFile, outputFile)
            else -> {
                // Clikt choice() already validates the value, but keep for safety
                System.err.println("Unknown format: $format")
                throw ProgramResult(1)
            }
        }
    }

    private fun importStructurizr(
        inputFile: File,
        outputFile: File,
    ) {
        val source =
            try {
                inputFile.readText()
            } catch (e: IOException) {
                System.err.println("I/O error reading '${inputFile.path}': ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        val kumlScript =
            try {
                val workspace = StructurizrDslParser.parse(source)
                KumlDslGenerator.generate(workspace, inputFile.name)
            } catch (e: Exception) {
                System.err.println("Structurizr parse error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        writeOutput(outputFile, kumlScript)
        echo("Imported: ${inputFile.name} → ${outputFile.path}")
    }

    private fun importXmi(
        inputFile: File,
        outputFile: File,
    ) {
        // Load XmiImporter via Reflection — kuml-io-emf is JVM-only and not bundled
        // in the Native Image binary. The Fat-JAR includes it at runtime.
        val importer =
            try {
                Class
                    .forName("dev.kuml.io.emf.XmiImporter")
                    .getDeclaredConstructor()
                    .newInstance()
            } catch (_: ClassNotFoundException) {
                System.err.println(
                    "XMI format requires the kUML Fat-JAR distribution.\n" +
                        "Native Image binary does not include EMF (JVM-only).\n" +
                        "Download the Fat-JAR from https://kuml.dev/releases",
                )
                throw ProgramResult(ExitCodes.FORMAT_NOT_AVAILABLE)
            }

        val importMethod = importer.javaClass.getMethod("import", File::class.java)
        val kumlModel = importMethod.invoke(importer, inputFile) as KumlModel

        val script =
            try {
                UmlModelDslPrinter.print(kumlModel)
            } catch (_: Exception) {
                "// XMI import: model '${kumlModel.name}'\n" +
                    "// Use `kuml export --format xmi` for roundtrip.\n"
            }

        writeOutput(outputFile, script)
        echo("Imported XMI: ${inputFile.name} → ${outputFile.path}")
    }

    private fun writeOutput(
        outputFile: File,
        content: String,
    ) {
        try {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(content)
        } catch (e: IOException) {
            System.err.println("I/O error writing '${outputFile.path}': ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }

    private fun deriveOutputFile(inputFile: File): File {
        val baseName = inputFile.nameWithoutExtension
        return File(inputFile.parentFile, "$baseName.kuml.kts")
    }
}
