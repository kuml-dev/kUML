package dev.kuml.cli.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.ExitCodes
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.workspace.ConvertReport
import dev.kuml.workspace.OkfConverter
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.WorkspaceScanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * `kuml workspace convert` — converts between OKF knowledge-workspace notes and
 * bare `.kuml.kts` engineering scripts (FT-7).
 *
 * The two formats share identical DSL text: an OKF ` ```kuml ` block's source is
 * byte-for-byte a valid standalone `.kuml.kts` body (the same fact
 * `WorkspaceRenderer` relies on to render a block by writing it to a temp
 * script and running it through [dev.kuml.cli.RenderPipeline]). "Convert" is
 * therefore a text wrap/unwrap plus frontmatter synthesis/stripping operation
 * — not a model re-serialization.
 *
 * Directionality of losslessness (documented here because it is easy to get
 * backwards): `--to okf` then `--to kts` on the result recovers the original
 * DSL text exactly (modulo trailing-newline normalisation, see
 * [OkfConverter.wrapAsOkf]'s KDoc). The reverse round trip is **not**
 * lossless: `--to kts` then `--to okf` drops the original prose, `title:`/
 * `tags:`/other frontmatter, and non-diagram documents (`Concept`, `Article`,
 * `Glossary`, …) entirely — only the diagram DSL survives.
 *
 * Trust model: the `--to okf` direction evaluates every `.kuml.kts` script (to
 * determine its concrete diagram kind for `type:` mapping) — the same
 * arbitrary-code-execution trust model as `kuml render` / `kuml workspace
 * render`. The `--to kts` direction is pure text extraction and never
 * evaluates anything.
 */
internal class WorkspaceConvertCommand : CliktCommand(name = "convert") {
    private val src: File by argument(help = "Source file or directory to convert")
        .file(mustExist = true)

    private val to: String by option(
        "--to",
        help =
            "Conversion direction: 'okf' wraps .kuml.kts scripts as OKF notes; " +
                "'kts' extracts ```kuml blocks as bare scripts",
    ).choice("okf", "kts").required()

    // Deliberately NOT "-o" — that short flag is used below for --report-format,
    // matching `workspace info`/`validate`'s established `-o text|json` convention.
    private val outputDirOpt: File? by option(
        "--output",
        help =
            "Output directory (default: sibling '<src>-okf'/'<src>-kts' for a directory " +
                "source, or the current directory for a single-file source)",
    ).file(canBeFile = false)

    private val force: Boolean by option(
        "--force",
        help = "Overwrite an output file that already exists",
    ).flag(default = false)

    private val strict: Boolean by option(
        "--strict",
        help =
            "Escalate warnings (multi-block split, un-mappable diagram type) to errors " +
                "and exit with a non-zero code if any finding was reported",
    ).flag(default = false)

    private val reportFormat: String by option("-o", "--report-format", help = "Report output format")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String =
        "Convert between OKF knowledge-workspace notes and bare .kuml.kts scripts (ADR-0011, FT-7). " +
            "Lossless kts->okf->kts; lossy okf->kts->okf (prose/frontmatter/non-diagram docs are dropped)."

    override fun run() {
        if (to == "kts" && src.isFile && src.extension != "md") {
            echo("Error: --to kts expects a Markdown (.md) source file or directory, got '${src.name}'.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }
        if (to == "okf" && src.isFile && !src.name.endsWith(".kuml.kts")) {
            echo("Error: --to okf expects a .kuml.kts source file or directory, got '${src.name}'.", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }

        val outputDir = outputDirOpt ?: defaultOutputDir(src, to)
        outputDir.mkdirs()

        val report =
            when (to) {
                "okf" -> convertToOkf(src, outputDir, strict, force)
                "kts" -> convertToKts(src, outputDir, strict, force)
                else -> error("Unreachable: Clikt already restricted --to to 'okf'/'kts'")
            }

        when (reportFormat) {
            "json" -> echo(okfPrettyJson.encodeToString(report.toJson()))
            else -> {
                echo(
                    "Converted ${report.converted.size}, skipped ${report.skipped.size}, " +
                        "${report.findings.size} finding(s).",
                )
                if (report.findings.isNotEmpty()) {
                    echo("")
                    for (f in report.findings) {
                        val tag = if (f.severity == OkfSeverity.ERROR) "ERROR" else "WARN "
                        echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}")
                        f.suggestion?.let { echo("        -> $it") }
                    }
                }
            }
        }

        if (report.findings.isNotEmpty() && strict) {
            throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        }
    }

    private fun defaultOutputDir(
        src: File,
        to: String,
    ): File {
        val suffix = if (to == "okf") "okf" else "kts"
        return if (src.isDirectory) {
            File(src.absoluteFile.parentFile ?: File("."), "${src.name}-$suffix")
        } else {
            File(".")
        }
    }

    // ── --to okf: engineering (.kuml.kts) → knowledge (OKF Markdown notes) ──────

    private fun convertToOkf(
        src: File,
        outputDir: File,
        strict: Boolean,
        force: Boolean,
    ): ConvertReport {
        val converted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val findings = mutableListOf<OkfFinding>()
        val scanRoot = if (src.isDirectory) src else (src.absoluteFile.parentFile ?: File("."))
        val scripts = if (src.isDirectory) findKumlScripts(src, excludeDirs = setOf(outputDir)) else listOf(src)

        for (script in scripts) {
            val relativePath = script.relativeTo(scanRoot).path.replace(File.separatorChar, '/')
            val stem = OkfConverter.sanitizeStem(script.kumlScriptStem())
            val relDir = File(relativePath).parent
            val targetDir = if (relDir != null) File(outputDir, relDir) else outputDir
            targetDir.mkdirs()

            try {
                val extracted = evalAndExtract(script)
                val mappedTypeId = OkfTypeMapper.toOkfTypeId(extracted)
                val typeId = mappedTypeId ?: OkfTypeMapper.customTypeIdFallback(extracted)
                if (mappedTypeId == null) {
                    findings +=
                        OkfFinding(
                            code = "OKF-C-003",
                            severity = if (strict) OkfSeverity.ERROR else OkfSeverity.WARNING,
                            file = relativePath,
                            line = 1,
                            message = "Diagram kind has no OKF vocabulary entry; wrote a custom 'type: $typeId'.",
                            suggestion = "Add a vocabulary entry for this diagram kind, or accept the custom type.",
                        )
                }
                val title = OkfTypeMapper.titleOf(extracted, fallback = stem)
                val note = OkfConverter.wrapAsOkf(dslSource = script.readText(Charsets.UTF_8), typeId = typeId, title = title)
                val outFile = resolveWithin(targetDir, "$stem.md")
                if (!outFile.exists() || force) {
                    outFile.writeText(note, Charsets.UTF_8)
                    converted += outFile.relativeTo(outputDir).path.replace(File.separatorChar, '/')
                } else {
                    skipped += relativePath
                }
            } catch (e: ScriptEvaluationException) {
                findings += evalFailureFinding(relativePath, "Failed to evaluate script: ${e.message}")
            } catch (e: IOException) {
                findings += evalFailureFinding(relativePath, "I/O error while converting script: ${e.message}")
            } catch (e: IllegalArgumentException) {
                findings += evalFailureFinding(relativePath, "Refusing to write converted note: ${e.message}")
            }
        }

        return ConvertReport(converted = converted, skipped = skipped, findings = findings)
    }

    /**
     * Evaluates [script] and extracts its diagram — the same idiom as
     * [dev.kuml.cli.RenderPipeline.run]'s steps 1-2, kept local here since
     * `RenderPipeline` does not expose eval+extract as a standalone call.
     */
    private fun evalAndExtract(script: File): ExtractedDiagram {
        val evalResult = KumlScriptHost.eval(script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            throw ScriptEvaluationException(
                "Script evaluation failed:\n${errors.joinToString("\n") { it.message }}",
            )
        }
        val successResult =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation did not produce a result")
        return DiagramExtractor.extractAny(successResult.value.returnValue, script)
    }

    /** `hello.kuml.kts` → `"hello"` (plain `nameWithoutExtension` would only strip `.kts`). */
    private fun File.kumlScriptStem(): String = if (name.endsWith(".kuml.kts")) name.removeSuffix(".kuml.kts") else nameWithoutExtension

    /**
     * Walks [root] for `*.kuml.kts` files, applying the same traversal safety as
     * [WorkspaceScanner.scan]: hidden directories, [excludeDirs] (the convert
     * output directory), and symlinked directories are never entered.
     */
    private fun findKumlScripts(
        root: File,
        excludeDirs: Set<File>,
    ): List<File> {
        val excludeCanonical = excludeDirs.map { it.absoluteFile.normalize() }.toSet()
        return root
            .walkTopDown()
            .onEnter { dir ->
                !dir.name.startsWith(".") &&
                    dir.absoluteFile.normalize() !in excludeCanonical &&
                    !Files.isSymbolicLink(dir.toPath())
            }.filter { it.isFile && it.name.endsWith(".kuml.kts") }
            .toList()
    }

    // ── --to kts: knowledge (OKF Markdown notes) → engineering (.kuml.kts) ──────

    private fun convertToKts(
        src: File,
        outputDir: File,
        strict: Boolean,
        force: Boolean,
    ): ConvertReport {
        val converted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val findings = mutableListOf<OkfFinding>()
        val scanRoot = if (src.isDirectory) src else (src.absoluteFile.parentFile ?: File("."))
        val ws = WorkspaceScanner.scan(scanRoot, excludeDirs = setOf(outputDir))
        val documents: List<OkfDocument> =
            if (src.isDirectory) ws.documents else ws.documents.filter { it.file.absoluteFile == src.absoluteFile }

        for (doc in documents) {
            val scripts = OkfConverter.extractBlocks(doc)
            if (scripts.isEmpty()) {
                skipped += doc.relativePath
                continue
            }
            if (scripts.size > 1) {
                findings +=
                    OkfFinding(
                        code = "OKF-C-002",
                        severity = if (strict) OkfSeverity.ERROR else OkfSeverity.WARNING,
                        file = doc.relativePath,
                        line = doc.kumlBlocks.getOrNull(1)?.startLine ?: 1,
                        message = "Document contains ${scripts.size} ```kuml blocks; split into ${scripts.size} scripts.",
                        suggestion = "Split additional diagrams into their own Markdown files upstream.",
                    )
            }

            val docRelDir = File(doc.relativePath).parent
            val targetDir = if (docRelDir != null) File(outputDir, docRelDir) else outputDir
            targetDir.mkdirs()

            for (script in scripts) {
                try {
                    val outFile = resolveWithin(targetDir, "${script.stem}.kuml.kts")
                    if (!outFile.exists() || force) {
                        outFile.writeText(script.dslSource, Charsets.UTF_8)
                        converted += outFile.relativeTo(outputDir).path.replace(File.separatorChar, '/')
                    } else {
                        skipped += doc.relativePath
                    }
                } catch (e: IOException) {
                    findings += evalFailureFinding(doc.relativePath, "I/O error while writing extracted script: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    findings += evalFailureFinding(doc.relativePath, "Refusing to write extracted script: ${e.message}")
                }
            }
        }

        return ConvertReport(converted = converted, skipped = skipped, findings = findings)
    }

    // ── Shared finding helpers ───────────────────────────────────────────────────

    private fun evalFailureFinding(
        relativePath: String,
        message: String,
    ): OkfFinding =
        OkfFinding(
            code = "OKF-C-004",
            severity = OkfSeverity.ERROR,
            file = relativePath,
            line = 1,
            message = message,
        )

    /**
     * Resolves [childName] under [base] and asserts the result stays inside
     * [base] after normalization — a defence-in-depth backstop, identical
     * contract to `WorkspaceRenderer.resolveWithin`, in case
     * [OkfConverter.sanitizeStem] is ever bypassed.
     */
    private fun resolveWithin(
        base: File,
        childName: String,
    ): File {
        val resolved = File(base, childName)
        val normalizedResolved = resolved.toPath().normalize()
        val normalizedBase = base.toPath().normalize()
        require(normalizedResolved.startsWith(normalizedBase)) {
            "Refusing to write outside of $normalizedBase: $normalizedResolved"
        }
        return resolved
    }
}

@Serializable
internal data class WorkspaceConvertJson(
    val converted: List<String>,
    val skipped: List<String>,
    val findings: List<OkfFindingJson>,
)

private fun ConvertReport.toJson(): WorkspaceConvertJson =
    WorkspaceConvertJson(
        converted = converted,
        skipped = skipped,
        findings = findings.map { it.toJson() },
    )
