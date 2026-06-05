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
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
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

        val diagram =
            try {
                DiagramExtractor.extract(success.value.returnValue, input)
            } catch (e: ScriptEvaluationException) {
                System.err.println(e.message)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        if (CodeGenRegistry.names().isEmpty()) {
            CodeGenRegistry.loadFromClasspath()
        }
        val generator =
            CodeGenRegistry.get(plugin)
                ?: run {
                    System.err.println(
                        "Unknown codegen plugin: '$plugin'. " +
                            "Registered plugins: ${CodeGenRegistry.names()}",
                    )
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
                generator.generate(diagram, outputDir, options)
            } catch (e: IOException) {
                System.err.println("I/O error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        generated.forEach { file -> echo("Generated: ${file.path}") }
        echo("${generated.size} file(s) generated in ${outputDir.path}")
    }
}
