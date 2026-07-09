package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.codegen.api.ErmCodeGenRegistry
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import java.io.File
import java.io.IOException
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `generate` subcommand.
 *
 * Evaluates a `*.kuml.kts` script and generates code using the selected plugin.
 *
 * Usage:
 * ```
 * kuml generate [OPTIONS]
 * ```
 *
 * V3.4.7: dispatches on the script's diagram kind — a UML class diagram
 * (`classDiagram { … }`) resolves the plugin from [CodeGenRegistry]
 * ([dev.kuml.codegen.api.KumlCodeGenerator]); an ERM model
 * (`ermModel(…) { … }`) resolves it from the separate [ErmCodeGenRegistry]
 * ([dev.kuml.codegen.api.ErmCodeGenerator]) instead — the two id-namespaces
 * are independent (e.g. both `kuml-gen-sql` entry points are registered as
 * `"sql"`, one per registry).
 */
internal class GenerateCommand : CliktCommand(name = "generate") {
    private val input by option("-i", "--input", help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)
        .required()

    private val output by option("-o", "--output", help = "Output directory for generated files")
        .path()
        .required()

    private val plugin by option("--plugin", help = "Code generator plugin (e.g. kotlin, java, sql)")
        .default("kotlin")

    private val packageName by option("--package", help = "Kotlin package name (e.g. com.example.domain)")

    private val rawOptions by option(
        "--options",
        help = "Plugin-specific options as comma-separated key=value pairs (e.g. java-style=records,sql-dialect=mysql)",
    )

    override fun help(context: Context): String = "Generate code from a kUML script using the selected plugin."

    override fun run() {
        val evalResult = KumlScriptHost.eval(input)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = errors.joinToString("\n") { it.message }
            System.err.println("Script error: $msg")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    System.err.println("Script evaluation produced no result")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        val extracted =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, input)
            } catch (e: ScriptEvaluationException) {
                System.err.println(e.message)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val options =
            buildMap<String, String> {
                packageName?.let { put("package", it) }
                rawOptions?.split(",")?.forEach { pair ->
                    val trimmed = pair.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val (k, v) =
                        trimmed
                            .split("=", limit = 2)
                            .let { if (it.size == 2) it[0].trim() to it[1].trim() else it[0].trim() to "" }
                    put(k, v)
                }
            }

        val outputDir = output.toFile()
        val generated =
            try {
                generate(extracted, outputDir, options)
            } catch (e: IOException) {
                System.err.println("I/O error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            } catch (e: CodeGenerationException) {
                System.err.println("Code generation error: ${e.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        generated.forEach { file -> echo("Generated: ${file.path}") }
        echo("${generated.size} file(s) generated in ${outputDir.path}")
    }

    private fun generate(
        extracted: ExtractedDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                if (CodeGenRegistry.names().isEmpty()) CodeGenRegistry.loadFromClasspath()
                val generator =
                    CodeGenRegistry.get(plugin)
                        ?: run {
                            System.err.println(
                                "Unknown codegen plugin: '$plugin'. " +
                                    "Registered plugins: ${CodeGenRegistry.names()}",
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                generator.generate(extracted.diagram, outputDir, options)
            }
            is ExtractedDiagram.Erm -> {
                if (ErmCodeGenRegistry.names().isEmpty()) ErmCodeGenRegistry.loadFromClasspath()
                val generator =
                    ErmCodeGenRegistry.get(plugin)
                        ?: run {
                            System.err.println(
                                "Unknown ERM codegen plugin: '$plugin'. " +
                                    "Registered plugins: ${ErmCodeGenRegistry.names()}",
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                generator.generate(extracted.model, outputDir, options)
            }
            else -> {
                System.err.println(
                    "kuml generate currently supports UML class diagrams (`classDiagram { … }`) or " +
                        "ERM models (`ermModel(…) { … }`).",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }
}
