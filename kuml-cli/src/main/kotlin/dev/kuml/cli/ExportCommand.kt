package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.structurizr.StructurizrEmitter
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import java.io.File
import java.io.IOException
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `export` subcommand — V1.1.
 *
 * Writes a kUML script in an external format. V1.1 supports Structurizr DSL
 * (`--format structurizr`). The input must be a `*.kuml.kts` script that
 * produces a C4 model — UML scripts are rejected with a clear error.
 *
 * Usage:
 *   kuml export --format structurizr <script> [-o output.dsl]
 *
 * Exit codes:
 *   0  success
 *   2  script error or no C4 model in script ([ExitCodes.SCRIPT_ERROR])
 *   3  I/O error reading or writing files ([ExitCodes.IO_ERROR])
 */
internal class ExportCommand : CliktCommand(name = "export") {
    private val input by argument(help = "Path to a *.kuml.kts script producing a C4 model")
        .file(mustExist = true, canBeDir = false)

    private val format by option("--format", help = "Target format")
        .choice("structurizr")
        .default("structurizr")

    private val output by option(
        "-o",
        "--output",
        help = "Output file (default: input filename with .dsl extension)",
    ).file()

    override fun help(context: Context): String = "Export a kUML C4 model to another format (V1.1: Structurizr DSL)."

    override fun run() {
        val scriptFile = input
        val outputFile = output ?: deriveOutputFile(scriptFile, format)

        val evalResult = KumlScriptHost.eval(scriptFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success ?: run {
                System.err.println("Script evaluation produced no result")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        val extracted = DiagramExtractor.extractAny(success.value.returnValue, scriptFile)
        val text =
            when (extracted) {
                is ExtractedDiagram.C4 ->
                    when (format) {
                        "structurizr" -> StructurizrEmitter.emit(extracted.model)
                        else -> {
                            System.err.println("Unknown format: $format")
                            throw ProgramResult(1)
                        }
                    }
                is ExtractedDiagram.Uml -> {
                    System.err.println(
                        "kuml export --format structurizr requires a C4 model — " +
                            "the script '${scriptFile.name}' produces a UML diagram.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                is ExtractedDiagram.Sysml2 -> {
                    // V2.0.4: `kuml export` ist Structurizr-only. SysML 2 hat seinen
                    // eigenen Render-Pfad (`kuml render --format svg|tex`) — Structurizr
                    // ist semantisch nicht das richtige Export-Ziel für ein BDD.
                    System.err.println(
                        "kuml export --format structurizr requires a C4 model — " +
                            "the script '${scriptFile.name}' produces a SysML 2 model. " +
                            "Use `kuml render --format svg|tex` for SysML 2 BDDs.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        try {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(text)
        } catch (e: IOException) {
            System.err.println("I/O error writing '${outputFile.path}': ${e.message}")
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        echo("Exported: ${scriptFile.name} → ${outputFile.path}")
    }

    private fun deriveOutputFile(
        inputFile: File,
        format: String,
    ): File {
        // Strip a trailing ".kuml.kts" suffix in addition to plain extensions.
        var base = inputFile.name
        if (base.endsWith(".kuml.kts")) {
            base = base.removeSuffix(".kuml.kts")
        } else if (base.contains('.')) {
            base = base.substringBeforeLast('.')
        }
        val ext =
            when (format) {
                "structurizr" -> "dsl"
                else -> "txt"
            }
        return File(inputFile.parentFile, "$base.$ext")
    }
}
