package dev.kuml.cli.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import dev.kuml.cli.ExitCodes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Single shared pretty-printing Json instance (see ValidateCommand's `kumlPrettyJson` for the
// same rationale: constructing a new Json {} per call is flagged by the serialization plugin).
private val okfPrettyJson = Json { prettyPrint = true }

/**
 * The `kuml workspace` subcommand group — inspect, validate and render OKF
 * knowledge workspaces (ADR-0011, FT-1 feasibility spike).
 *
 * Sub-subcommands:
 * - `info`     — scan a workspace and print its mode + document inventory.
 * - `validate` — run [OkfValidator]'s structural conformance checks.
 * - `render`   — render every ```kuml block through the full [dev.kuml.cli.RenderPipeline].
 */
internal class WorkspaceCommand : CliktCommand(name = "workspace") {
    init {
        subcommands(
            WorkspaceInfoCommand(),
            WorkspaceValidateCommand(),
            WorkspaceRenderCommand(),
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
        val ws = WorkspaceScanner.scan(dir)
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
                            root = dir.absolutePath,
                            mode = ws.mode.name.lowercase(),
                            markerFound = ws.markerFound,
                            documentCount = ws.documents.size,
                            byType = byType,
                        ),
                    ),
                )
            else -> {
                echo("Workspace: ${dir.absolutePath}")
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

    override fun help(context: Context): String = "Validate OKF conformance of a knowledge workspace (ADR-0011)."

    override fun run() {
        val ws = WorkspaceScanner.scan(dir)
        val findings = OkfValidator.validate(ws)
        val errorCount = findings.count { it.severity == OkfSeverity.ERROR }

        when (outputFormat) {
            "json" ->
                echo(
                    okfPrettyJson.encodeToString(
                        WorkspaceValidateJson(
                            valid = errorCount == 0,
                            findings = findings.map { it.toJson() },
                        ),
                    ),
                )
            else -> {
                if (findings.isEmpty()) {
                    echo("${dir.name}: valid — no findings.")
                } else {
                    echo("Validating ${dir.name}...\n")
                    for (f in findings) {
                        val tag = if (f.severity == OkfSeverity.ERROR) "ERROR" else "WARN "
                        echo("  $tag [${f.code}] ${f.file}:${f.line}: ${f.message}")
                        f.suggestion?.let { echo("        -> $it") }
                    }
                    echo("\n${findings.size} finding(s), $errorCount error(s).")
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
)

@Serializable
private data class OkfFindingJson(
    val code: String,
    val severity: String,
    val file: String,
    val line: Int,
    val message: String,
    val suggestion: String? = null,
)

private fun OkfFinding.toJson(): OkfFindingJson =
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

    private val outputDirOpt by option("-o", "--output", help = "Output directory (default: <root>/.kuml-out)")
        .file(canBeFile = false)

    private val format by option("-f", "--format", help = "Output format")
        .choice("svg", "png")
        .default("svg")

    private val width by option("-w", "--width", help = "Width in pixels (PNG only)")
        .int()
        .default(1024)

    private val mirror by option("--mirror", help = "Mirror every document with kuml blocks replaced by image links")
        .flag("--no-mirror", default = true)

    private val strict by option("--strict", help = "Exit with a non-zero code if any block failed to render")
        .flag(default = false)

    override fun help(context: Context): String = "Render every ```kuml block in an OKF workspace to SVG/PNG."

    override fun run() {
        val outputDir = outputDirOpt ?: File(dir, ".kuml-out")
        val ws = WorkspaceScanner.scan(dir, excludeDirs = setOf(outputDir))
        val report = WorkspaceRenderer.render(ws, outputDir = outputDir, format = format, width = width, mirror = mirror)

        echo("Rendered ${report.rendered.size}, skipped ${report.skipped.size}, failed ${report.failures.size}.")
        if (report.failures.isNotEmpty()) {
            echo("")
            for (f in report.failures) {
                echo("  ERROR [${f.code}] ${f.file}:${f.line}: ${f.message}", err = true)
            }
        }

        if (report.failures.isNotEmpty() && strict) {
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
    }
}
