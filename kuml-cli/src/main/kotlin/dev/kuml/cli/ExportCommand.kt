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
import dev.kuml.core.model.KumlModel
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import java.io.File
import java.io.IOException
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `export` subcommand — V3.0.17.
 *
 * Writes a kUML script in an external format.
 * - `--format structurizr` (V1.1): exports C4 models to Structurizr DSL.
 * - `--format xmi` (V3.0.17): exports UML models to XMI via EMF (Fat-JAR only).
 *
 * Usage:
 *   kuml export --format structurizr <script> [-o output.dsl]
 *   kuml export --format xmi <script> [-o output.xmi]
 *
 * Exit codes:
 *   0  success
 *   3  script error or incompatible model ([ExitCodes.SCRIPT_ERROR])
 *   4  I/O error reading or writing files ([ExitCodes.IO_ERROR])
 *   24 format requires Fat-JAR distribution ([ExitCodes.FORMAT_NOT_AVAILABLE])
 */
internal class ExportCommand : CliktCommand(name = "export") {
    private val input by argument(help = "Path to a *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val format by option("--format", help = "Target format")
        .choice("structurizr", "xmi")
        .default("structurizr")

    private val output by option(
        "-o",
        "--output",
        help = "Output file (default: input filename with format-appropriate extension)",
    ).file()

    override fun help(context: Context): String = "Export a kUML model to another format (Structurizr DSL, XMI)."

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

        when (format) {
            "structurizr" -> exportStructurizr(extracted, scriptFile, outputFile)
            "xmi" -> exportXmi(extracted, scriptFile, outputFile)
            else -> {
                System.err.println("Unknown format: $format")
                throw ProgramResult(1)
            }
        }
    }

    private fun exportStructurizr(
        extracted: ExtractedDiagram,
        scriptFile: File,
        outputFile: File,
    ) {
        val text =
            when (extracted) {
                is ExtractedDiagram.C4 ->
                    StructurizrEmitter.emit(extracted.model)
                is ExtractedDiagram.Uml -> {
                    System.err.println(
                        "kuml export --format structurizr requires a C4 model — " +
                            "the script '${scriptFile.name}' produces a UML diagram.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                is ExtractedDiagram.Sysml2 -> {
                    System.err.println(
                        "kuml export --format structurizr requires a C4 model — " +
                            "the script '${scriptFile.name}' produces a SysML 2 model. " +
                            "Use `kuml render --format svg|tex` for SysML 2 BDDs.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                is ExtractedDiagram.Bpmn -> {
                    System.err.println(
                        "kuml export --format structurizr requires a C4 model — " +
                            "the script '${scriptFile.name}' produces a BPMN model. " +
                            "Use `kuml render --format svg|png` for BPMN diagrams.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        writeOutput(outputFile, text)
        echo("Exported: ${scriptFile.name} → ${outputFile.path}")
    }

    private fun exportXmi(
        extracted: ExtractedDiagram,
        scriptFile: File,
        outputFile: File,
    ) {
        val kumlModel =
            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    KumlModel(
                        root = extracted.diagram,
                        language = dev.kuml.core.model.ModelingLanguage.UML,
                        level = dev.kuml.core.model.ModelLevel.PIM,
                        name = scriptFile.nameWithoutExtension,
                    )
                }
                is ExtractedDiagram.C4 -> {
                    System.err.println(
                        "kuml export --format xmi supports UML models only — " +
                            "the script '${scriptFile.name}' produces a C4 model. " +
                            "Use `kuml export --format structurizr` for C4 models.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                is ExtractedDiagram.Sysml2 -> {
                    System.err.println(
                        "kuml export --format xmi supports UML models only — " +
                            "the script '${scriptFile.name}' produces a SysML 2 model.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                is ExtractedDiagram.Bpmn -> {
                    System.err.println(
                        "kuml export --format xmi supports UML models only — " +
                            "the script '${scriptFile.name}' produces a BPMN model. " +
                            "Use `kuml render --format svg|png` for BPMN diagrams.",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }

        // Load XmiExporter via Reflection — kuml-io-emf is JVM-only and not bundled
        // in the Native Image binary. The Fat-JAR includes it at runtime.
        val exporter =
            try {
                Class
                    .forName("dev.kuml.io.emf.XmiExporter")
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

        outputFile.parentFile?.mkdirs()
        val exportMethod =
            exporter.javaClass.getMethod(
                "export",
                KumlModel::class.java,
                File::class.java,
            )
        exportMethod.invoke(exporter, kumlModel, outputFile)
        echo("Exported XMI: ${scriptFile.name} → ${outputFile.path}")
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
                "xmi" -> "xmi"
                else -> "txt"
            }
        return File(inputFile.parentFile, "$base.$ext")
    }
}
