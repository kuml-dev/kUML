package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.cli.reverse.UmlModelDslPrinter
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.codegen.reverse.registry.ReverseEngineRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * The `reverse` subcommand.
 *
 * Reverse-engineers source code into a UML model and emits it as a `*.kuml.kts`
 * script. V3.0.9 — user-facing entry point for Track B (reverse engineering).
 *
 * Usage:
 * ```
 * kuml reverse src/main/java --lang java --output domain.kuml.kts
 * kuml reverse src/main/kotlin --lang kotlin
 * kuml reverse src/main/java --lang auto                     # auto-detect
 * kuml reverse --list-engines
 * ```
 */
internal class ReverseCommand : CliktCommand(name = "reverse") {
    private val sourceDir by argument(
        "source-dir",
        help = "Path to the source root directory containing .java/.kt files",
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .optional()

    private val lang by option("--lang")
        .help("Source language: java, kotlin, or auto (default: auto)")

    private val output by option("--output", "-o")
        .help("Path to write the generated *.kuml.kts; if omitted, prints to stdout")

    private val includeGlobs by option("--include")
        .multiple()
        .help("File include glob (repeatable). Defaults per --lang")

    private val excludeGlobs by option("--exclude")
        .multiple()
        .help("File exclude glob (repeatable)")

    private val modelName by option("--model-name")
        .help("Name for the resulting model (default: ReverseEngineered)")

    private val listEngines by option("--list-engines")
        .flag()
        .help("List all available reverse engines and exit")

    private val verboseDiagnostics by option("--verbose-diagnostics")
        .flag()
        .help("Print every WARN/INFO diagnostic on stderr (default: summary only)")

    override fun help(context: Context): String = "Reverse-engineer source code (Java/Kotlin) into a UML kUML script."

    override fun run() {
        if (listEngines) {
            val engines = ReverseEngineRegistry.all()
            if (engines.isEmpty()) {
                echo("No reverse engines registered on the classpath.")
                return
            }
            echo("Available reverse engines:")
            engines.sortedBy { it.id }.forEach { e ->
                echo("  ${e.id}: ${e.description}")
            }
            return
        }

        val srcDir =
            sourceDir ?: run {
                echo("Missing argument <source-dir>. Use --list-engines to list reverse engines.", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        if (!Files.isDirectory(srcDir)) {
            echo("Source directory does not exist or is not a directory: $srcDir", err = true)
            throw ProgramResult(ExitCodes.IO_ERROR)
        }

        val resolvedLang =
            when (val explicit = lang) {
                null, "auto" ->
                    ReverseEngineRegistry.detectLanguage(listOf(srcDir)) ?: run {
                        echo(
                            "No source files (.java/.kt) found in $srcDir, or no clear language majority detected.",
                            err = true,
                        )
                        throw ProgramResult(ExitCodes.REVERSE_NO_SOURCES)
                    }
                else -> explicit
            }

        val engine =
            ReverseEngineRegistry.byId(resolvedLang) ?: run {
                val knownIds = ReverseEngineRegistry.all().map { it.id }
                echo(
                    "Unknown reverse engine '$resolvedLang'. Available engines: ${knownIds.joinToString(", ")}",
                    err = true,
                )
                throw ProgramResult(ExitCodes.REVERSE_ENGINE_NOT_FOUND)
            }

        val effectiveIncludes =
            when {
                includeGlobs.isNotEmpty() -> includeGlobs
                resolvedLang == "java" -> listOf("**/*.java")
                resolvedLang == "kotlin" -> listOf("**/*.kt")
                else -> emptyList()
            }

        val request =
            ReverseRequest(
                sourceRoots = listOf(srcDir),
                includeGlobs = effectiveIncludes,
                excludeGlobs = excludeGlobs,
                targetModelName = modelName ?: "ReverseEngineered",
            )

        val result = runBlocking { engine.analyze(request) }

        when (result) {
            is ReverseResult.Failure -> {
                echo("Reverse analysis failed with ${result.errors.size} error(s):", err = true)
                result.errors.forEach { d ->
                    echo("  [${d.code}] ${d.message}${formatLocation(d)}", err = true)
                }
                throw ProgramResult(ExitCodes.REVERSE_ANALYSIS_FAILED)
            }
            is ReverseResult.Success -> {
                emitDiagnosticsSummary(result.diagnostics)
                val dslText = UmlModelDslPrinter.print(result.model)
                val outPath = output
                if (outPath == null) {
                    echo(dslText)
                } else {
                    File(outPath).also { it.parentFile?.mkdirs() }.writeText(dslText)
                    echo("Wrote ${dslText.lineSequence().count()} lines to $outPath")
                }
                echo("Analysed ${result.filesAnalysed} file(s) in ${result.elapsedMs} ms via '${engine.id}' engine.")
            }
        }
    }

    private fun emitDiagnosticsSummary(diagnostics: List<ReverseDiagnostic>) {
        if (diagnostics.isEmpty()) return
        if (verboseDiagnostics) {
            diagnostics.forEach { d ->
                echo("  [${d.severity}/${d.code}] ${d.message}${formatLocation(d)}", err = true)
            }
        } else {
            val parts =
                diagnostics
                    .groupingBy { it.severity }
                    .eachCount()
                    .entries
                    .sortedBy { it.key.ordinal }
                    .joinToString(", ") { "${it.value} ${it.key}" }
            echo("Reverse diagnostics: $parts (use --verbose-diagnostics to see all)", err = true)
        }
    }

    private fun formatLocation(d: ReverseDiagnostic): String {
        if (d.file == null) return ""
        return if (d.line != null) " (at ${d.file}:${d.line})" else " (at ${d.file})"
    }
}
