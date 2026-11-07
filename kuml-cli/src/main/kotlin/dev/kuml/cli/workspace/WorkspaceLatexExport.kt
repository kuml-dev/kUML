package dev.kuml.cli.workspace

import dev.kuml.cli.KumlVersion
import dev.kuml.cli.ScriptEvaluationException
import dev.kuml.cli.export.LatexExportService
import dev.kuml.cli.export.LatexFileExportResult
import dev.kuml.cli.export.LatexFolderExportException
import dev.kuml.cli.export.LatexFolderResult
import dev.kuml.cli.export.LatexOutputGuard
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.latex.LatexDiagramOutcome
import dev.kuml.workspace.latex.LatexDiagramSource
import dev.kuml.workspace.latex.LatexKnowledgeExporter
import dev.kuml.workspace.latex.LatexKnowledgeOptions
import dev.kuml.workspace.latex.LatexOutputFile
import dev.kuml.workspace.latex.PreparedLatexKnowledge
import java.io.File
import java.io.IOException

/**
 * The single code path behind `kuml workspace export --format latex` for Knowledge workspaces (and, in a later
 * wave, the Desktop export dialog): diagrams are exported by [LatexExportService], the script-free
 * `kuml-workspace-latex` module writes the document.
 *
 * Trust model: exactly like `workspace render`, this executes the Kotlin script of every ```` ```kuml ```` block.
 * Only run it over workspaces the user trusts.
 */
public object WorkspaceLatexExport {
    /** Text of the header comment of `main.tex`. */
    public val generator: String get() = "kUML ${KumlVersion.version} - kuml workspace export --format latex"

    /** A [LatexDiagramSource] that exports blocks through the CLI's own LaTeX path. */
    public fun diagramSource(themeName: String? = null): LatexDiagramSource =
        LatexDiagramSource { doc, blockIndex, block ->
            val stem = WorkspaceBlockEvaluator.sanitizeStem(block.name ?: "${doc.file.nameWithoutExtension}-${blockIndex + 1}")
            try {
                WorkspaceBlockEvaluator.withTempScript(stem = stem, source = block.source) { script ->
                    when (val r = LatexExportService.exportFile(input = script, themeName = themeName, sharedPreamble = true)) {
                        is LatexFileExportResult.Rendered ->
                            LatexDiagramOutcome.Rendered(
                                tex = r.tex,
                                family = r.family,
                                diagramCount = r.diagramCount,
                                diagramName = r.diagramName,
                            )
                        is LatexFileExportResult.Skipped -> LatexDiagramOutcome.Skipped(reason = r.reason.take(MAX_REASON))
                        is LatexFileExportResult.Failed -> LatexDiagramOutcome.Failed(reason = r.reason.take(MAX_REASON))
                    }
                }
            } catch (e: ScriptEvaluationException) {
                LatexDiagramOutcome.Failed(reason = (e.message ?: "script error").take(MAX_REASON))
            } catch (e: IOException) {
                LatexDiagramOutcome.Failed(reason = "I/O error: ${(e.message ?: "").take(MAX_REASON)}")
            } catch (e: IllegalArgumentException) {
                LatexDiagramOutcome.Failed(reason = (e.message ?: "invalid block").take(MAX_REASON))
            }
        }

    /** Renders the diagrams and builds all files in memory; writes nothing. */
    public fun prepare(
        workspace: OkfWorkspace,
        options: LatexKnowledgeOptions,
        themeName: String? = null,
    ): PreparedLatexKnowledge =
        LatexKnowledgeExporter.prepare(workspace = workspace, options = options, diagrams = diagramSource(themeName = themeName))

    /**
     * Writes the files below [outDir] with the same guard as the folder export: no symbolic links, nothing outside
     * the directory, existing files only with [overwrite]; all targets are checked before the first byte is written.
     *
     * @throws LatexFolderExportException on a refused or failed write.
     */
    public fun write(
        prepared: PreparedLatexKnowledge,
        outDir: File,
        overwrite: Boolean,
    ): LatexFolderResult {
        val files = LinkedHashMap<String, ByteArray>()
        for (f in prepared.files) {
            files[f.path] =
                when (f) {
                    is LatexOutputFile.Text -> f.content.toByteArray(Charsets.UTF_8)
                    is LatexOutputFile.Binary -> f.bytes
                }
        }
        val written = LatexOutputGuard.writeAllBytes(outDir = outDir, files = files, overwrite = overwrite)
        return LatexFolderResult(outDir = outDir.absoluteFile.normalize(), written = written)
    }

    private const val MAX_REASON = 300
}
