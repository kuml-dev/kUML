package dev.kuml.cli.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.cli.ExitCodes
import dev.kuml.workspace.OkfAliasRewriteResult
import dev.kuml.workspace.OkfAliasRewriter
import dev.kuml.workspace.OkfAliasSkipReason
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.WorkspaceDocumentResolver
import dev.kuml.workspace.WorkspaceScanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Single shared pretty-printing Json instance (see ValidateCommand's `kumlPrettyJson` for the
// same rationale: constructing a new Json {} per call is flagged by the serialization plugin).
// Internal (not private) so WorkspaceConvertCommand.kt can reuse the same instance + JSON DTOs
// instead of duplicating them.
internal val okfPrettyJson = Json { prettyPrint = true }

/**
 * Loads the documents selected by repeated `--document` options (paths relative to [root]) —
 * without scanning the rest of the workspace. Any rejected path ends the command with
 * [ExitCodes.USAGE] and a message that names only the requested path, never file content.
 * Duplicates are collapsed, order of first appearance is kept.
 */
internal fun CliktCommand.loadSelectedDocuments(
    root: File,
    requested: List<String>,
    excludeDirs: Set<File> = emptySet(),
): List<OkfDocument> {
    val docs = LinkedHashMap<String, OkfDocument>()
    for (path in requested) {
        val result = WorkspaceScanner.scanDocument(root = root, relativePath = path, excludeDirs = excludeDirs)
        val doc =
            result.getOrElse { e ->
                val msg =
                    when (e) {
                        is WorkspaceDocumentResolver.DocumentResolutionException -> e.message
                        else -> "Cannot read document: ${e.message}"
                    }
                echo("Error: $msg", err = true)
                throw ProgramResult(ExitCodes.USAGE)
            }
        docs.putIfAbsent(doc.file.path, doc)
    }
    return docs.values.toList()
}

/**
 * The `kuml workspace` subcommand group — inspect, validate, render and convert
 * OKF knowledge workspaces (ADR-0011, FT-1 feasibility spike).
 *
 * Sub-subcommands:
 * - `init`     — scaffold a new OKF knowledge workspace (V3.6.2, FT-4).
 * - `info`     — scan a workspace and print its mode + document inventory.
 * - `validate` — run [OkfValidator]'s structural conformance checks.
 * - `render`   — render every ```kuml block through the full [dev.kuml.cli.RenderPipeline].
 * - `convert`  — convert between OKF notes and bare `.kuml.kts` scripts (FT-7).
 * - `export`   — export to PDF: handbook, per-document or diagram folio (wave F).
 */
internal class WorkspaceCommand : CliktCommand(name = "workspace") {
    init {
        subcommands(
            WorkspaceInitCommand(),
            WorkspaceInfoCommand(),
            WorkspaceValidateCommand(),
            WorkspaceRenderCommand(),
            WorkspaceConvertCommand(),
            WorkspaceExportCommand(),
        )
    }

    override fun help(context: Context): String = "Inspect, validate and render OKF knowledge workspaces (ADR-0011)."

    override fun run() = Unit
}

// ── `kuml workspace info` ─────────────────────────────────────────────────────

internal class WorkspaceInfoCommand : CliktCommand(name = "info") {
    private val dir by argument(help = "Path to the OKF workspace root")
        .file(mustExist = true, canBeFile = false)

    private val outputFormat by option("-o", "--output", help = "Output format")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String = "Show an OKF workspace's mode and document inventory."

    override fun run() {
        val root = WorkspaceScanner.canonicalRoot(dir)
        val ws = WorkspaceScanner.scan(root = root)
        val byType =
            ws.documents
                .groupBy { it.type?.id ?: (it.rawType ?: "(no type)") }
                .mapValues { it.value.size }
                .toSortedMap()

        when (outputFormat) {
            "json" ->
                echo(
                    okfPrettyJson.encodeToString(
                        WorkspaceInfoJson(
                            root = root.absolutePath,
                            mode = ws.mode.name.lowercase(),
                            markerFound = ws.markerFound,
                            documentCount = ws.documents.size,
                            byType = byType,
                        ),
                    ),
                )
            else -> {
                echo("Workspace: ${root.absolutePath}")
                echo("Mode:      ${ws.mode.name.lowercase()}${if (ws.markerFound) " (declared)" else " (inferred)"}")
                echo("Documents: ${ws.documents.size}")
                if (byType.isNotEmpty()) {
                    echo("")
                    echo("By type:")
                    for ((type, count) in byType) {
                        echo("  $type: $count")
                    }
                }
            }
        }
    }
}

@Serializable
private data class WorkspaceInfoJson(
    val root: String,
    val mode: String,
    val markerFound: Boolean,
    val documentCount: Int,
    val byType: Map<String, Int>,
)

// ── `kuml workspace validate` ─────────────────────────────────────────────────

internal class WorkspaceValidateCommand : CliktCommand(name = "validate") {
    private val dir by argument(help = "Path to the OKF workspace root")
        .file(mustExist = true, canBeFile = false)

    private val outputFormat by option("-o", "--output", help = "Output format")
        .choice("text", "json")
        .default("text")

    private val strictVocabulary by option(
        "--strict-vocabulary",
        help = "Treat an unrecognised 'type:' value (OKF-W-002) as an ERROR instead of a WARNING",
    ).flag(default = false)

    private val documents by option(
        "--document",
        help = "Validate only this document (path relative to the workspace root). Repeatable.",
    ).multiple()

    private val evaluate by option(
        "--evaluate",
        help =
            "Also evaluate every kuml block (EXECUTES block code) to report blocks that " +
                "produce several diagrams (OKF-W-008), fail (OKF-E-007) or declare the same reqId with " +
                "different text in several documents (OKF-W-009)",
    ).flag(default = false)

    private val strict by option(
        "--strict",
        help = "With --evaluate: treat OKF-W-008 (several diagrams in one block) as an ERROR. Has no effect without --evaluate",
    ).flag(default = false)

    private val fixAliases by option(
        "--fix-aliases",
        help =
            "Rewrite alias 'type:' ids (OKF-I-001) to their canonical ids in the workspace's .md files. " +
                "Without this flag validate never writes.",
    ).flag(default = false)

    private val dryRun by option(
        "--dry-run",
        help = "With --fix-aliases: list the files that would change, write nothing",
    ).flag(default = false)

    override fun help(context: Context): String =
        "Validate OKF conformance of a knowledge workspace (ADR-0011). With --fix-aliases, also rewrites alias " +
            "'type:' ids to canonical ids (exit 5 on validation errors, else 4 if a file was skipped, else 0); " +
            "the printed findings describe the state before the rewrite."

    override fun run() {
        if (dryRun && !fixAliases) {
            echo("Error: --dry-run requires --fix-aliases", err = true)
            throw ProgramResult(ExitCodes.USAGE)
        }
        val root = WorkspaceScanner.canonicalRoot(dir)
        val scoped = documents.isNotEmpty()
        val docs: List<OkfDocument>
        val findings = mutableListOf<OkfFinding>()
        if (scoped) {
            docs = loadSelectedDocuments(root = root, requested = documents)
            for (doc in docs) {
                findings += OkfValidator.validateDocument(root = root, doc = doc, strictVocabulary = strictVocabulary)
            }
        } else {
            val ws = WorkspaceScanner.scan(root = root)
            docs = ws.documents
            findings += OkfValidator.validate(ws = ws, strictVocabulary = strictVocabulary)
        }
        if (evaluate) {
            val prefixKinds = WorkspaceScanner.readMarker(root)?.notePrefixKinds ?: dev.kuml.core.model.note.NotePrefixKinds.EMPTY
            findings += WorkspaceBlockEvaluator.evaluateDocuments(documents = docs, strict = strict, prefixKinds = prefixKinds)
        }
        val errorCount = findings.count { it.severity == OkfSeverity.ERROR }
        val label = if (scoped) docs.joinToString(", ") { it.relativePath.replace('\\', '/') } else root.name

        // The findings above describe the state BEFORE the rewrite.
        val fixResults: List<OkfAliasRewriteResult>? =
            if (fixAliases) {
                val aliasFiles = findings.filter { it.code == "OKF-I-001" }.map { it.file }.toSet()
                OkfAliasRewriter.rewrite(
                    root = root,
                    targets = docs.filter { it.relativePath in aliasFiles },
                    knownDocuments = docs.map { it.file },
                    dryRun = dryRun,
                )
            } else {
                null
            }

        when (outputFormat) {
            "json" ->
                echo(
                    okfPrettyJson.encodeToString(
                        WorkspaceValidateJson(
                            valid = errorCount == 0,
                            findings = findings.map { it.toJson() },
                            scope = if (scoped) "document" else "workspace",
                            documents = if (scoped) docs.map { it.relativePath.replace('\\', '/') } else null,
                            fixes = fixResults?.map { it.toJson() },
                            dryRun = if (fixAliases) dryRun else null,
                        ),
                    ),
                )
            else -> {
                if (findings.isEmpty()) {
                    echo("$label: valid — no findings.")
                } else {
                    echo("Validating $label${if (scoped) " in ${root.name}" else ""}...\n")
                    for (f in findings) {
                        val tag = f.severity.tag5()
                        echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}")
                        f.suggestion?.let { echo("        -> $it") }
                    }
                    echo("\n${findings.size} finding(s), $errorCount error(s).")
                }
                if (!evaluate && docs.any { it.kumlBlocks.isNotEmpty() }) {
                    echo(
                        "Note: diagram counts per kuml block and reqId conflicts across documents (OKF-W-009) " +
                            "are only checked with --evaluate (executes block code).",
                        err = true,
                    )
                }
                if (fixResults != null) printFixResults(results = fixResults)
            }
        }

        if (errorCount > 0) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
        if (fixResults?.any { it is OkfAliasRewriteResult.Skipped } == true) throw ProgramResult(ExitCodes.IO_ERROR)
    }

    private fun printFixResults(results: List<OkfAliasRewriteResult>) {
        if (results.isEmpty()) {
            echo("\nNo alias type ids to rewrite.")
            return
        }
        echo("")
        var changed = 0
        var skipped = 0
        for (r in results) {
            val path = r.relativePath.replace('\\', '/').sanitiseForTerminal()
            when (r) {
                is OkfAliasRewriteResult.Rewritten -> {
                    changed++
                    echo("  fixed      $path: ${r.alias} -> ${r.canonical}")
                }
                is OkfAliasRewriteResult.WouldRewrite -> {
                    changed++
                    echo("  would fix  $path: ${r.alias} -> ${r.canonical}")
                }
                is OkfAliasRewriteResult.Unchanged -> echo("  unchanged  $path: no alias any more")
                is OkfAliasRewriteResult.Skipped -> {
                    skipped++
                    echo("  skipped    $path: ${r.reason.plainEnglish()}", err = true)
                }
            }
        }
        if (dryRun) {
            echo("Dry run: would rewrite $changed file(s), skipped $skipped; nothing written.")
        } else {
            echo("Rewrote $changed file(s), skipped $skipped.")
        }
    }
}

// File names are user-controlled: never echo control characters (terminal escape sequences) back.
private fun String.sanitiseForTerminal(): String = map { if (it.isISOControl()) '?' else it }.joinToString("")

private fun OkfAliasSkipReason.plainEnglish(): String =
    when (this) {
        OkfAliasSkipReason.OUTSIDE_ROOT -> "outside the workspace root"
        OkfAliasSkipReason.SYMLINK -> "is a symbolic link"
        OkfAliasSkipReason.NOT_A_REGULAR_FILE -> "is not a regular file"
        OkfAliasSkipReason.UNKNOWN_DOCUMENT -> "not a known workspace document"
        OkfAliasSkipReason.NOT_MARKDOWN -> "is not a .md file"
        OkfAliasSkipReason.TOO_LARGE -> "file is too large"
        OkfAliasSkipReason.NOT_UTF8 -> "not valid UTF-8"
        OkfAliasSkipReason.CHANGED_ON_DISK -> "changed on disk while being rewritten"
        OkfAliasSkipReason.IO_ERROR -> "could not be rewritten (I/O error)"
    }

@Serializable
private data class OkfAliasFixJson(
    val file: String,
    val status: String,
    val alias: String? = null,
    val canonical: String? = null,
    val reason: String? = null,
)

private fun OkfAliasRewriteResult.toJson(): OkfAliasFixJson =
    when (this) {
        is OkfAliasRewriteResult.Rewritten ->
            OkfAliasFixJson(file = relativePath, status = "rewritten", alias = alias, canonical = canonical)
        is OkfAliasRewriteResult.WouldRewrite ->
            OkfAliasFixJson(file = relativePath, status = "would-rewrite", alias = alias, canonical = canonical)
        is OkfAliasRewriteResult.Unchanged -> OkfAliasFixJson(file = relativePath, status = "unchanged")
        is OkfAliasRewriteResult.Skipped ->
            OkfAliasFixJson(file = relativePath, status = "skipped", reason = reason.name.lowercase().replace('_', '-'))
    }

@Serializable
private data class WorkspaceValidateJson(
    val valid: Boolean,
    val findings: List<OkfFindingJson>,
    // No default: kotlinx.serialization omits default-valued properties, and consumers
    // should always see the scope.
    val scope: String,
    // Only present for --document runs (null default => omitted otherwise).
    val documents: List<String>? = null,
    // Only present with --fix-aliases (null default => omitted otherwise).
    val fixes: List<OkfAliasFixJson>? = null,
    val dryRun: Boolean? = null,
)

// Internal (not private): reused by WorkspaceConvertCommand.kt's JSON report shape.
@Serializable
internal data class OkfFindingJson(
    val code: String,
    val severity: String,
    val file: String,
    val line: Int,
    val message: String,
    val suggestion: String? = null,
    // Only present for OKF-I-001 (null default => omitted otherwise).
    val canonicalId: String? = null,
)

private fun OkfSeverity.tag5(): String =
    when (this) {
        OkfSeverity.ERROR -> "ERROR"
        OkfSeverity.WARNING -> "WARN "
        OkfSeverity.INFO -> "INFO "
    }

internal fun OkfFinding.toJson(): OkfFindingJson =
    OkfFindingJson(
        code = code,
        severity =
            when (severity) {
                OkfSeverity.ERROR -> "error"
                OkfSeverity.WARNING -> "warning"
                OkfSeverity.INFO -> "info"
            },
        file = file,
        line = line,
        message = message,
        suggestion = suggestion,
        canonicalId = canonicalId,
    )

// ── `kuml workspace render` ───────────────────────────────────────────────────

internal class WorkspaceRenderCommand : CliktCommand(name = "render") {
    private val dir by argument(help = "Path to the OKF workspace root")
        .file(mustExist = true, canBeFile = false)

    private val outputDirOpt by option(
        "-o",
        "--output",
        help = "Output directory (default: <root>/.kuml-out — inside the workspace, hidden and never scanned)",
    ).file(canBeFile = false)

    private val format by option("-f", "--format", help = "Output format")
        .choice("svg", "png")
        .default("svg")

    private val width by option("-w", "--width", help = "Width in pixels (PNG only)")
        .int()
        .default(1024)

    private val mirror by option("--mirror", help = "Mirror every document with kuml blocks replaced by image links")
        .flag("--no-mirror", default = true)

    private val documents by option(
        "--document",
        help = "Render only this document (path relative to the workspace root). Repeatable.",
    ).multiple()

    private val strict by option(
        "--strict",
        help = "Exit with a non-zero code if any block failed to render or produced several diagrams (OKF-W-008)",
    ).flag(default = false)

    override fun help(context: Context): String = "Render every ```kuml block in an OKF workspace to SVG/PNG."

    override fun run() {
        val root = WorkspaceScanner.canonicalRoot(dir)
        val usedDefault = outputDirOpt == null
        val outputDir = (outputDirOpt ?: File(root, ".kuml-out")).absoluteFile.normalize()

        if (mirror && !WorkspaceRenderer.isSafeOutputDir(root = root, outputDir = outputDir)) {
            echo(
                "Error: refusing to mirror into '${outputDir.path}': it is the workspace root or one of its " +
                    "ancestors, so mirroring would overwrite the source documents. Choose another -o directory " +
                    "or pass --no-mirror.",
                err = true,
            )
            throw ProgramResult(ExitCodes.USAGE)
        }

        val excludes = setOf(outputDir)
        val docs =
            if (documents.isEmpty()) {
                WorkspaceScanner.scan(root = root, excludeDirs = excludes).documents
            } else {
                loadSelectedDocuments(root = root, requested = documents, excludeDirs = excludes)
            }
        if (mirror) {
            val collisions = WorkspaceRenderer.mirrorCollisions(root = root, outputDir = outputDir, documents = docs)
            if (collisions.isNotEmpty()) {
                echo(
                    "Error: refusing to mirror into '${outputDir.path}': it already contains files that would be " +
                        "overwritten and that look like source documents (${collisions.joinToString(", ")}). " +
                        "Choose an empty -o directory, delete the old output, create an empty " +
                        ".kuml-render-output file in it if it is an earlier render output, or pass --no-mirror.",
                    err = true,
                )
                throw ProgramResult(ExitCodes.USAGE)
            }
        }
        val report =
            WorkspaceRenderer.render(
                documents = docs,
                outputDir = outputDir,
                format = format,
                width = width,
                mirror = mirror,
                strict = strict,
                prefixKinds = WorkspaceScanner.readMarker(root)?.notePrefixKinds ?: dev.kuml.core.model.note.NotePrefixKinds.EMPTY,
            )

        echo(
            "Rendered ${report.rendered.size}, skipped ${report.skipped.size}, " +
                "failed ${report.failures.size}, warnings ${report.warnings.size}.",
        )
        echo("Output: ${outputDir.path}")
        if (usedDefault && outputDir.toPath().startsWith(root.toPath())) {
            echo(
                "Note: output is inside the workspace; it is excluded from scans. " +
                    "Add '.kuml-out/' to .gitignore or pass -o <dir>.",
            )
        }
        for (f in report.failures + report.warnings) {
            val tag = f.severity.tag5().trim()
            echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}", err = true)
        }

        val hasStrictViolation = report.warnings.any { it.severity == OkfSeverity.ERROR }
        if ((report.failures.isNotEmpty() && strict) || hasStrictViolation) {
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
    }
}
