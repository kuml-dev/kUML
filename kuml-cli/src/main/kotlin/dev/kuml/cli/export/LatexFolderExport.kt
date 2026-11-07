package dev.kuml.cli.export

import dev.kuml.cli.KumlVersion
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexDocumentAssembler
import dev.kuml.io.latex.LatexExcludedFigure
import dev.kuml.io.latex.LatexFamily
import dev.kuml.io.latex.LatexFigure
import dev.kuml.io.latex.LatexSourceCheck
import dev.kuml.workspace.WorkspaceScanner
import java.io.File
import java.nio.file.Files

/** One exported script. [target] is the relative `.tex` path with `/` separators. */
public data class LatexFolderFigure(
    val source: String,
    val target: String,
    val tex: String,
    val family: LatexFamily,
    val diagramName: String?,
    val diagramCount: Int,
    val excludedReason: String?,
)

public data class LatexFolderIssue(
    val source: String,
    val reason: String,
)

public data class PreparedLatexFolder(
    val figures: List<LatexFolderFigure>,
    val failures: List<LatexFolderIssue>,
    val skipped: List<LatexFolderIssue>,
    val notes: List<String>,
    val preamble: String,
    val main: String,
)

public data class LatexFolderResult(
    val outDir: File,
    val written: List<File>,
)

public class LatexFolderExportException(
    public val code: Code,
    message: String,
) : Exception(message) {
    public enum class Code { NO_SCRIPTS, OUTPUT_EXISTS, OUTPUT_INVALID, PATH_OUTSIDE_OUTPUT, IO }
}

/**
 * Exports every `*.kuml.kts` below a folder as TikZ snippets that share one preamble, plus a `main.tex` that
 * collects them. The export is split into [prepare] (renders everything, writes nothing) and [write], so `--strict`
 * can refuse without leaving files behind.
 *
 * Scripts are run one after another: the render pipeline keeps per-thread state. Each script is evaluated, which
 * is the same trust model as `kuml workspace render`.
 */
public object LatexFolderExport {
    private const val SCRIPT_SUFFIX = ".kuml.kts"
    private val RESERVED_ROOT_NAMES = setOf("kuml-preamble", "main")

    public fun prepare(
        root: File,
        excludeDirs: Set<File>,
        themeName: String?,
    ): PreparedLatexFolder {
        val scanRoot = WorkspaceScanner.canonicalRoot(root)
        val scripts = WorkspaceScanner.diagramScripts(root = scanRoot, excludeDirs = excludeDirs)
        if (scripts.isEmpty()) {
            throw LatexFolderExportException(
                code = LatexFolderExportException.Code.NO_SCRIPTS,
                message = "No *.kuml.kts scripts found below $scanRoot (a Knowledge workspace needs an index.md plus Markdown documents).",
            )
        }
        val figures = mutableListOf<LatexFolderFigure>()
        val failures = mutableListOf<LatexFolderIssue>()
        val skipped = mutableListOf<LatexFolderIssue>()
        val notes = mutableListOf<String>()
        val used = mutableSetOf<String>()
        for (script in scripts) {
            val source = script.relativeTo(scanRoot).invariantSeparatorsPath
            if (Files.isSymbolicLink(script.toPath())) {
                skipped += LatexFolderIssue(source = source, reason = "symbolic link")
                continue
            }
            when (val r = LatexExportService.exportFile(input = script, themeName = themeName, sharedPreamble = true)) {
                is LatexFileExportResult.Skipped -> skipped += LatexFolderIssue(source = source, reason = r.reason)
                is LatexFileExportResult.Failed -> failures += LatexFolderIssue(source = source, reason = r.reason)
                is LatexFileExportResult.Rendered -> {
                    val target = uniqueTarget(source = source, used = used, notes = notes)
                    if (r.diagramCount > 1) {
                        notes += "$source: ${r.diagramCount} diagrams, exported the first (\"${r.diagramName ?: "unnamed"}\")"
                    }
                    val dangling = LatexSourceCheck.undefinedNodeReferences(r.tex)
                    figures +=
                        LatexFolderFigure(
                            source = source,
                            target = target,
                            tex = r.tex,
                            family = r.family,
                            diagramName = r.diagramName,
                            diagramCount = r.diagramCount,
                            excludedReason =
                                dangling.takeIf { it.isNotEmpty() }?.let {
                                    "undefined TikZ node reference(s): " + it.sorted().take(MAX_LISTED_NAMES).joinToString(", ")
                                },
                        )
                }
            }
        }
        val included = figures.filter { it.excludedReason == null }
        val main =
            LatexDocumentAssembler.mainDocument(
                figures =
                    included.map {
                        LatexFigure(
                            input = it.target.removeSuffix(".tex"),
                            caption =
                                it.diagramName ?: it.source.removeSuffix(SCRIPT_SUFFIX),
                        )
                    },
                excluded =
                    figures
                        .filter { it.excludedReason != null }
                        .map { LatexExcludedFigure(input = it.target.removeSuffix(".tex"), reason = it.excludedReason.orEmpty()) },
                generator = "kUML ${KumlVersion.version} - kuml workspace export --format latex",
            )
        return PreparedLatexFolder(
            figures = figures,
            failures = failures,
            skipped = skipped,
            notes = notes,
            preamble = KumlLatexRenderer.sharedPreamble(figures.map { it.family }.toSet()),
            main = main,
        )
    }

    /** The relative paths [write] produces, in write order. */
    public fun plannedPaths(prepared: PreparedLatexFolder): List<String> =
        prepared.figures.map { it.target } + KumlLatexRenderer.SHARED_PREAMBLE_FILE + MAIN_FILE

    /**
     * Writes the figures, then `kuml-preamble.tex`, then `main.tex`. Every file is replaced atomically on its own;
     * the set is not atomic, so an I/O error midway leaves earlier files in place. Files of earlier runs that are
     * no longer produced are left alone.
     */
    public fun write(
        prepared: PreparedLatexFolder,
        outDir: File,
        overwrite: Boolean,
    ): LatexFolderResult {
        if (prepared.figures.isEmpty()) {
            throw LatexFolderExportException(
                code = LatexFolderExportException.Code.NO_SCRIPTS,
                message = "No diagram could be exported.",
            )
        }
        val files = LinkedHashMap<String, String>()
        prepared.figures.forEach { files[it.target] = it.tex }
        files[KumlLatexRenderer.SHARED_PREAMBLE_FILE] = prepared.preamble
        files[MAIN_FILE] = prepared.main
        val written = LatexOutputGuard.writeAll(outDir = outDir, files = files, overwrite = overwrite)
        return LatexFolderResult(outDir = outDir.absoluteFile.normalize(), written = written)
    }

    private const val MAX_LISTED_NAMES = 5
    private const val MAIN_FILE = "main.tex"

    /** Produces only names that [LatexDocumentAssembler] accepts: start with a letter or digit, no `..`. */
    private fun sanitizeSegment(raw: String): String {
        val cleaned =
            raw
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
                .replace(Regex("-{2,}"), "-")
                .replace(Regex("\\.{2,}"), ".")
                .trimStart { !it.isLetterOrDigit() || it.code > 127 }
        return cleaned.ifEmpty { "diagram" }
    }

    private fun uniqueTarget(
        source: String,
        used: MutableSet<String>,
        notes: MutableList<String>,
    ): String {
        val segments =
            source
                .removeSuffix(SCRIPT_SUFFIX)
                .split('/')
                .map(::sanitizeSegment)
                .toMutableList()
        // A directory must not share its path with an already written figure file ("foo.tex").
        for (i in 0 until segments.size - 1) {
            var k = 1
            val original = segments[i]
            while (segments.take(i + 1).joinToString("/").lowercase() in used) {
                k++
                segments[i] = "$original-$k"
            }
        }
        val base = segments.joinToString("/")
        var candidate = base
        var n = 1
        while (isTaken(candidate = candidate, depth = segments.size, used = used)) {
            n++
            candidate = "$base-$n"
        }
        // Files are stored as "<path>.tex", directories as "<path>/", so a file and a directory
        // with the same path can be told apart and checked against each other.
        used += "${candidate.lowercase()}.tex"
        val parts = candidate.lowercase().split('/')
        for (i in 1 until parts.size) used += parts.take(i).joinToString("/") + "/"
        if (n > 1) notes += "$source: written as $candidate.tex (name collision)"
        return "$candidate.tex"
    }

    private fun isTaken(
        candidate: String,
        depth: Int,
        used: Set<String>,
    ): Boolean {
        val lower = candidate.lowercase()
        if (depth == 1 && lower in RESERVED_ROOT_NAMES) return true
        return "$lower.tex" in used || "$lower.tex/" in used
    }
}
