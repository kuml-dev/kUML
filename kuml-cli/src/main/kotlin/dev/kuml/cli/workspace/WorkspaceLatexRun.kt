package dev.kuml.cli.workspace

import dev.kuml.cli.export.LatexFolderExport
import dev.kuml.cli.export.LatexFolderResult
import dev.kuml.cli.export.LatexOutputGuard
import dev.kuml.cli.export.PreparedLatexFolder
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.latex.LatexIssueLevel
import dev.kuml.workspace.latex.LatexKnowledgeOptions
import dev.kuml.workspace.latex.PreparedLatexKnowledge
import java.io.File

/** Which kind of LaTeX export a folder gets. */
public sealed interface LatexWorkspaceKind {
    public data class Knowledge(
        val workspace: OkfWorkspace,
    ) : LatexWorkspaceKind

    public data object Engineering : LatexWorkspaceKind
}

/** A rendered, not yet written LaTeX export of either kind. */
public sealed interface PreparedLatexRun {
    public val kind: LatexWorkspaceKind

    public data class Knowledge(
        val workspace: OkfWorkspace,
        val prepared: PreparedLatexKnowledge,
    ) : PreparedLatexRun {
        override val kind: LatexWorkspaceKind get() = LatexWorkspaceKind.Knowledge(workspace)
    }

    public data class Engineering(
        val prepared: PreparedLatexFolder,
    ) : PreparedLatexRun {
        override val kind: LatexWorkspaceKind get() = LatexWorkspaceKind.Engineering
    }

    /** True when there is no figure at all to write. Only an Engineering folder can be in this state. */
    public fun hasNothingToWrite(): Boolean = this is Engineering && prepared.figures.isEmpty()
}

/**
 * The code path shared by `kuml workspace export --format latex` and the Desktop dialog "Export Workspace > As
 * LaTeX...". Nothing here decides anything the CLI did not decide before; the CLI is a thin caller.
 *
 * Trust model: [prepare] executes the scripts of the workspace. Call it only for workspaces the user trusts.
 */
public object WorkspaceLatexRun {
    public const val DEFAULT_TARGET_RELATIVE: String = ".kuml-out/export/latex"

    public fun defaultBase(root: File): File = File(root, ".kuml-out/export")

    public fun defaultTarget(root: File): File = File(defaultBase(root), "latex")

    /** The target itself if it lies below [root]; an export must never read its own previous output. */
    private fun targetExcludes(
        root: File,
        target: File,
    ): Set<File> = buildSet { if (target.startsWith(root)) add(target) }

    /** The directories left out of the mode scan: the target (if below [root]) and the default export folder. */
    public fun excludes(
        root: File,
        target: File,
    ): Set<File> = targetExcludes(root = root, target = target) + defaultBase(root).absoluteFile.normalize()

    /**
     * Knowledge for a scanned Knowledge workspace, [LatexWorkspaceKind.Engineering] otherwise. Engineering wins as
     * soon as one `*.kuml.kts` exists, unless a `.kuml-workspace.toml` marker states a mode (the scanner's rule).
     * Does I/O; call it off the UI thread.
     */
    public fun detect(
        root: File,
        excludes: Set<File>,
    ): LatexWorkspaceKind {
        val hasMarker = File(root, ".kuml-workspace.toml").isFile
        if (!hasMarker && WorkspaceScanner.diagramScripts(root = root, excludeDirs = excludes).isNotEmpty()) {
            return LatexWorkspaceKind.Engineering
        }
        val ws = WorkspaceScanner.scan(root = root, excludeDirs = excludes)
        return if (ws.mode == WorkspaceMode.KNOWLEDGE) LatexWorkspaceKind.Knowledge(ws) else LatexWorkspaceKind.Engineering
    }

    /**
     * Renders everything and writes nothing. [root] must be canonical ([WorkspaceScanner.canonicalRoot]).
     * Engineering throws no error for "no figure"; check [PreparedLatexRun.hasNothingToWrite] after reporting the
     * issues, as the CLI does.
     */
    public fun prepare(
        root: File,
        target: File,
        themeName: String?,
        kind: LatexWorkspaceKind = detect(root = root, excludes = excludes(root = root, target = target)),
    ): PreparedLatexRun =
        when (kind) {
            is LatexWorkspaceKind.Knowledge ->
                PreparedLatexRun.Knowledge(
                    workspace = kind.workspace,
                    prepared =
                        WorkspaceLatexExport.prepare(
                            workspace = kind.workspace,
                            options = LatexKnowledgeOptions(generator = WorkspaceLatexExport.generator),
                            themeName = themeName,
                        ),
                )
            LatexWorkspaceKind.Engineering ->
                PreparedLatexRun.Engineering(
                    prepared =
                        LatexFolderExport.prepare(
                            root = root,
                            excludeDirs = targetExcludes(root = root, target = target),
                            themeName = themeName,
                        ),
                )
        }

    /** True when `--strict` must refuse: any failure, skip, exclusion or warning. A NOTE never blocks. */
    public fun blocksStrict(prepared: PreparedLatexRun): Boolean =
        when (prepared) {
            is PreparedLatexRun.Knowledge -> prepared.prepared.issues.any { it.level != LatexIssueLevel.NOTE }
            is PreparedLatexRun.Engineering ->
                prepared.prepared.let {
                    it.failures.isNotEmpty() || it.skipped.isNotEmpty() || it.figures.any { f -> f.excludedReason != null }
                }
        }

    /** The paths below [outDir] that [write] would refuse to replace without `overwrite`. */
    public fun existingTargets(
        prepared: PreparedLatexRun,
        outDir: File,
    ): List<String> =
        LatexOutputGuard.existing(
            outDir = outDir,
            relativePaths =
                when (prepared) {
                    is PreparedLatexRun.Knowledge -> prepared.prepared.files.map { it.path }
                    is PreparedLatexRun.Engineering -> LatexFolderExport.plannedPaths(prepared.prepared)
                },
        )

    /**
     * Writes through the one guarded path (no symbolic links, nothing outside [outDir], existing files only with
     * [overwrite]).
     *
     * @throws dev.kuml.cli.export.LatexFolderExportException on a refused or failed write.
     */
    public fun write(
        prepared: PreparedLatexRun,
        outDir: File,
        overwrite: Boolean,
    ): LatexFolderResult =
        when (prepared) {
            is PreparedLatexRun.Knowledge ->
                WorkspaceLatexExport.write(prepared = prepared.prepared, outDir = outDir, overwrite = overwrite)
            is PreparedLatexRun.Engineering ->
                LatexFolderExport.write(prepared = prepared.prepared, outDir = outDir, overwrite = overwrite)
        }
}

/** One line of the issue protocol; [text] is exactly what the CLI prints on stderr. */
public data class LatexReportLine(
    val level: String,
    val text: String,
)

/** Counts of a run. [warnings] is null for an Engineering folder, whose summary has no warnings figure. */
public data class LatexRunCounts(
    val failed: Int,
    val skipped: Int,
    val excluded: Int,
    val warnings: Int?,
)

/** Text of a LaTeX run, shared by the CLI and the Desktop dialog. */
public object LatexRunReport {
    /** Terminal-safe: control characters (ANSI escapes, line breaks) in paths and reasons become a space. */
    public fun shown(raw: String): String = raw.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")

    /** The issue protocol in the order the CLI prints it. */
    public fun issueLines(prepared: PreparedLatexRun): List<LatexReportLine> =
        when (prepared) {
            is PreparedLatexRun.Knowledge ->
                prepared.prepared.warnings.map { LatexReportLine(level = "NOTE", text = "NOTE ${shown(it)}") } +
                    prepared.prepared.issues.map {
                        LatexReportLine(
                            level = it.level.name,
                            text = "${it.level} ${shown(it.source)}: ${shown(it.message)}",
                        )
                    }
            is PreparedLatexRun.Engineering -> {
                val p = prepared.prepared
                p.notes.map { LatexReportLine(level = "NOTE", text = "NOTE ${shown(it)}") } +
                    p.skipped.map { LatexReportLine(level = "SKIP", text = "SKIP ${shown(it.source)}: ${shown(it.reason)}") } +
                    p.figures.filter { it.excludedReason != null }.map {
                        LatexReportLine(
                            level = "WARN",
                            text = "WARN ${shown(it.source)}: excluded from main.tex - ${shown(it.excludedReason.orEmpty())}",
                        )
                    } +
                    p.failures.map { LatexReportLine(level = "ERROR", text = "ERROR ${shown(it.source)}: ${shown(it.reason)}") }
            }
        }

    public fun counts(prepared: PreparedLatexRun): LatexRunCounts =
        when (prepared) {
            is PreparedLatexRun.Knowledge -> {
                val count = { level: LatexIssueLevel -> prepared.prepared.issues.count { it.level == level } }
                LatexRunCounts(
                    failed = count(LatexIssueLevel.ERROR),
                    skipped = count(LatexIssueLevel.SKIP),
                    excluded = prepared.prepared.excludedFigureCount,
                    warnings = count(LatexIssueLevel.WARN),
                )
            }
            is PreparedLatexRun.Engineering ->
                LatexRunCounts(
                    failed = prepared.prepared.failures.size,
                    skipped = prepared.prepared.skipped.size,
                    excluded = prepared.prepared.figures.count { it.excludedReason != null },
                    warnings = null,
                )
        }

    /** The two stdout lines after a successful write: "Wrote ..." and the counts. */
    public fun summaryLines(
        prepared: PreparedLatexRun,
        result: LatexFolderResult,
    ): List<String> {
        val c = counts(prepared)
        return when (prepared) {
            is PreparedLatexRun.Knowledge -> {
                val k = prepared.prepared
                listOf(
                    "Wrote main.tex, kuml-preamble.tex, ${k.figureCount} figure(s), ${k.imageCount} image(s) " +
                        "for ${k.documentCount} document(s): ${result.outDir.path}",
                    "${c.failed} failed, ${c.skipped} skipped, ${c.excluded} excluded from main.tex, ${c.warnings} warnings",
                )
            }
            is PreparedLatexRun.Engineering ->
                listOf(
                    "Wrote ${prepared.prepared.figures.size} figure(s) plus kuml-preamble.tex and main.tex: ${result.outDir.path}",
                    "${c.failed} failed, ${c.skipped} skipped, ${c.excluded} excluded from main.tex",
                )
        }
    }
}
