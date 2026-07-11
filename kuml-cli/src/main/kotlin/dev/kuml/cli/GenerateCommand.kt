package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.codegen.api.ErmCodeGenRegistry
import dev.kuml.codegen.sql.ErmSqlMigrationGenerator
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.erm.model.ErmModel
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
 *
 * ADR-0016 (deferred item): `--sql-migration` switches to a second mode that
 * takes two ERM scripts (`--from`/`--to`) and writes a single additive-only
 * SQL schema-diff Flyway migration via [ErmSqlMigrationGenerator], instead of
 * generating from one script via a registered plugin. Mutually exclusive with
 * `-i`/`--input` and the classic single-script mode.
 */
internal class GenerateCommand : CliktCommand(name = "generate") {
    private val input by option("-i", "--input", help = "Path to *.kuml.kts script (classic mode)")
        .file(mustExist = true, canBeDir = false)

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

    private val sqlMigration by option(
        "--sql-migration",
        help =
            "Generate an additive-only SQL schema-diff Flyway migration between two ERM scripts " +
                "(requires --from/--to/--version/--description; mutually exclusive with -i/--input)",
    ).flag()

    private val from by option("--from", help = "Path to the OLD *.kuml.kts ERM script (--sql-migration mode)")
        .file(mustExist = true, canBeDir = false)

    private val to by option("--to", help = "Path to the NEW *.kuml.kts ERM script (--sql-migration mode)")
        .file(mustExist = true, canBeDir = false)

    private val migrationVersion by option(
        "--version",
        help = "Flyway migration version, e.g. \"2\" (--sql-migration mode)",
    )

    private val migrationDescription by option(
        "--description",
        help = "Flyway migration description, e.g. \"add_orders\" (--sql-migration mode)",
    )

    override fun help(context: Context): String =
        "Generate code from a kUML script using the selected plugin, " +
            "or an additive-only SQL schema-diff migration with --sql-migration."

    override fun run() {
        if (sqlMigration) {
            runSqlMigration()
        } else {
            runClassicGenerate()
        }
    }

    // ── Classic single-script generate ──────────────────────────────────────

    private fun runClassicGenerate() {
        val inputFile =
            input ?: run {
                echo("generate: missing option \"-i\" / \"--input\" (or pass --sql-migration).", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }

        val evalResult = KumlScriptHost.eval(inputFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = errors.joinToString("\n") { it.message }
            echo("Script error: $msg", err = true)
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    echo("Script evaluation produced no result", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        val extracted =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, inputFile)
            } catch (e: ScriptEvaluationException) {
                echo(e.message, err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val options = parseOptions()

        val outputDir = output.toFile()
        val generated =
            try {
                generate(extracted, outputDir, options)
            } catch (e: IOException) {
                echo("I/O error: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.IO_ERROR)
            } catch (e: CodeGenerationException) {
                echo("Code generation error: ${e.message}", err = true)
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
                            echo(
                                "Unknown codegen plugin: '$plugin'. " +
                                    "Registered plugins: ${CodeGenRegistry.names()}",
                                err = true,
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
                            echo(
                                "Unknown ERM codegen plugin: '$plugin'. " +
                                    "Registered plugins: ${ErmCodeGenRegistry.names()}",
                                err = true,
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                generator.generate(extracted.model, outputDir, options)
            }
            else -> {
                echo(
                    "kuml generate currently supports UML class diagrams (`classDiagram { … }`) or " +
                        "ERM models (`ermModel(…) { … }`).",
                    err = true,
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }

    // ── --sql-migration: additive-only ERM schema-diff migration ───────────

    private fun runSqlMigration() {
        if (input != null) {
            echo("generate: --sql-migration is mutually exclusive with -i/--input.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }
        val fromFile =
            from ?: run {
                echo("generate: --sql-migration requires --from.", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }
        val toFile =
            to ?: run {
                echo("generate: --sql-migration requires --to.", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }
        val version =
            migrationVersion ?: run {
                echo("generate: --sql-migration requires --version.", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }
        val description =
            migrationDescription ?: run {
                echo("generate: --sql-migration requires --description.", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }

        val oldModel = loadErmModel(fromFile)
        val newModel = loadErmModel(toFile)
        val options = parseOptions()
        val outputDir = output.toFile()

        val migrationFile =
            try {
                ErmSqlMigrationGenerator().generate(oldModel, newModel, outputDir, version, description, options)
            } catch (e: IOException) {
                echo("I/O error: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.IO_ERROR)
            } catch (e: CodeGenerationException) {
                echo("Code generation error: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            } catch (e: IllegalArgumentException) {
                echo("generate: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }

        echo("Generated: ${migrationFile.path}")
        echo("1 file(s) generated in ${outputDir.path}")
    }

    /** Loads and evaluates [scriptFile], requiring it to be an ERM script (`ermModel(…) { … }`). */
    private fun loadErmModel(scriptFile: File): ErmModel {
        val evalResult = KumlScriptHost.eval(scriptFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = errors.joinToString("\n") { it.message }
            echo("Script error (${scriptFile.name}): $msg", err = true)
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    echo("Script evaluation produced no result: ${scriptFile.name}", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        val extracted =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, scriptFile)
            } catch (e: ScriptEvaluationException) {
                echo(e.message, err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        return (extracted as? ExtractedDiagram.Erm)?.model ?: run {
            echo(
                "generate --sql-migration requires both --from and --to to be ERM scripts " +
                    "(`ermModel(\"…\") { … }`); '${scriptFile.name}' is not.",
                err = true,
            )
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
    }

    private fun parseOptions(): Map<String, String> =
        buildMap {
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
}
