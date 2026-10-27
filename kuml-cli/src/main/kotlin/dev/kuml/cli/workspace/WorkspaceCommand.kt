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
 */
internal class WorkspaceCommand : CliktCommand(name = "workspace") {
    init {
        subcommands(
            WorkspaceInitCommand(),
            WorkspaceInfoCommand(),
            WorkspaceValidateCommand(),
            WorkspaceRenderCommand(),
            WorkspaceConvertCommand(),
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
                "produce several diagrams (OKF-W-008) or fail (OKF-E-007)",
    ).flag(default = false)

    private val strict by option(
        "--strict",
        help = "With --evaluate: treat OKF-W-008 (several diagrams in one block) as an ERROR. Has no effect without --evaluate",
    ).flag(default = false)

    override fun help(context: Context): String = "Validate OKF conformance of a knowledge workspace (ADR-0011)."

    override fun run() {
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
            findings += WorkspaceBlockEvaluator.evaluateDocuments(documents = docs, strict = strict)
        }
        val errorCount = findings.count { it.severity == OkfSeverity.ERROR }
        val label = if (scoped) docs.joinToString(", ") { it.relativePath.replace('\\', '/') } else root.name

        when (outputFormat) {
            "json" ->
                echo(
                    okfPrettyJson.encodeToString(
                        WorkspaceValidateJson(
                            valid = errorCount == 0,
                            findings = findings.map { it.toJson() },
                            scope = if (scoped) "document" else "workspace",
                            documents = if (scoped) docs.map { it.relativePath.replace('\\', '/') } else null,
                        ),
                    ),
                )
            else -> {
                if (findings.isEmpty()) {
                    echo("$label: valid — no findings.")
                } else {
                    echo("Validating $label${if (scoped) " in ${root.name}" else ""}...\n")
                    for (f in findings) {
                        val tag = if (f.severity == OkfSeverity.ERROR) "ERROR" else "WARN "
                        echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}")
                        f.suggestion?.let { echo("        -> $it") }
                    }
                    echo("\n${findings.size} finding(s), $errorCount error(s).")
                }
                if (!evaluate && docs.any { it.kumlBlocks.isNotEmpty() }) {
                    echo(
                        "Note: diagram counts per kuml block are only checked with --evaluate (executes block code).",
                        err = true,
                    )
                }
            }
        }

        if (errorCount > 0) throw ProgramResult(ExitCodes.VALIDATION_VIOLATIONS)
    }
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
)

internal fun OkfFinding.toJson(): OkfFindingJson =
    OkfFindingJson(
        code = code,
        severity = if (severity == OkfSeverity.ERROR) "error" else "warning",
        file = file,
        line = line,
        message = message,
        suggestion = suggestion,
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
            val tag = if (f.severity == OkfSeverity.ERROR) "ERROR" else "WARN"
            echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}", err = true)
        }

        val hasStrictViolation = report.warnings.any { it.severity == OkfSeverity.ERROR }
        if ((report.failures.isNotEmpty() && strict) || hasStrictViolation) {
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
    }
}
