package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.kuml.cli.reverse.ArxmlModelMerge
import dev.kuml.cli.reverse.ArxmlPackageDslPrinter
import dev.kuml.cli.reverse.UmlModelDslPrinter
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.codegen.reverse.registry.ReverseEngineRegistry
import dev.kuml.core.model.KumlModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `reverse` subcommand.
 *
 * Reverse-engineers source code or ARXML files into a UML model and emits it as a `*.kuml.kts`
 * script. V3.1.36 — added `--format arxml` for multi-file ARXML merge.
 *
 * Usage:
 * ```
 * kuml reverse src/main/java --lang java --output domain.kuml.kts
 * kuml reverse src/main/kotlin --lang kotlin
 * kuml reverse src/main/java --lang auto                     # auto-detect
 * kuml reverse --format arxml models/                        # reads all *.arxml in dir
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

    private val format by option("--format")
        .help("Reverse format: 'source' for source-language reverse (default), 'arxml' for ARXML import+merge")
        .default("source")

    private val listEngines by option("--list-engines")
        .flag()
        .help("List all available reverse engines and exit")

    private val verboseDiagnostics by option("--verbose-diagnostics")
        .flag()
        .help("Print every WARN/INFO diagnostic on stderr (default: summary only)")

    override fun help(context: Context): String = "Reverse-engineer source code (Java/Kotlin) or ARXML files into a kUML script."

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

        // ── ARXML reverse path — delegate before source-language engine path ─────
        if (format == "arxml") {
            val srcDir =
                sourceDir ?: run {
                    echo("Missing argument <source-dir>. Provide a directory containing *.arxml files.", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            if (!Files.isDirectory(srcDir)) {
                echo("Source directory does not exist or is not a directory: $srcDir", err = true)
                throw ProgramResult(ExitCodes.IO_ERROR)
            }
            reverseArxml(srcDir, output)
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

    /**
     * Reverses all `*.arxml` files found in [dir] into a merged kUML model and emits it as DSL.
     *
     * Uses reflection to load [dev.kuml.io.arxml.ArxmlClassicImporter] (JVM-only / Fat-JAR).
     * Multiple files with overlapping AR-PACKAGE trees are merged via [ArxmlModelMerge.merge]
     * which deduplicates packages by name recursively.
     */
    private fun reverseArxml(
        dir: Path,
        outputPath: String?,
    ) {
        val arxmlFiles =
            dir
                .toFile()
                .listFiles { f -> f.extension.equals("arxml", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()

        if (arxmlFiles.isEmpty()) {
            echo("No *.arxml files found in $dir", err = true)
            throw ProgramResult(ExitCodes.REVERSE_NO_SOURCES)
        }

        // Load ArxmlClassicImporter via reflection — JVM-only, must not link into native image.
        val importerClass =
            try {
                Class.forName("dev.kuml.io.arxml.ArxmlClassicImporter")
            } catch (_: ClassNotFoundException) {
                echo(
                    "ARXML reverse requires the kUML Fat-JAR distribution.\n" +
                        "Native Image binary does not include kuml-io-arxml (JVM-only, JDOM2).\n" +
                        "Download the Fat-JAR from https://kuml.dev/releases",
                    err = true,
                )
                throw ProgramResult(ExitCodes.FORMAT_NOT_AVAILABLE)
            }

        // Construct ArxmlClassicImporter with a single nullable ArxmlVersion? arg (null = auto-detect)
        val importer =
            try {
                val versionClass = Class.forName("dev.kuml.io.arxml.ArxmlVersion")
                importerClass
                    .getDeclaredConstructor(versionClass)
                    .newInstance(null)
            } catch (_: NoSuchMethodException) {
                importerClass.getDeclaredConstructor().newInstance()
            }

        val importMethod =
            importer.javaClass.getMethod(
                "import",
                File::class.java,
            )

        val models = mutableListOf<KumlModel>()
        for (file in arxmlFiles) {
            val importResult = importMethod.invoke(importer, file)
            // importResult is ImportResult — access its .model property via reflection
            val modelField = importResult.javaClass.getMethod("getModel")
            val kumlModel = modelField.invoke(importResult) as KumlModel
            models.add(kumlModel)
        }

        val merged = ArxmlModelMerge.merge(models)
        val dslText = ArxmlPackageDslPrinter.print(merged)

        if (outputPath == null) {
            echo(dslText)
        } else {
            File(outputPath).also { it.parentFile?.mkdirs() }.writeText(dslText)
            echo("Wrote ${dslText.lineSequence().count()} lines to $outputPath")
        }
        echo("Merged ${arxmlFiles.size} ARXML file(s) from $dir")
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
