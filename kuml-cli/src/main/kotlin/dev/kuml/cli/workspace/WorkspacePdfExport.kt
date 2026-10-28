package dev.kuml.cli.workspace

import dev.kuml.cli.KumlVersion
import dev.kuml.cli.RenderPipeline
import dev.kuml.cli.ScriptEvaluationException
import dev.kuml.core.config.KumlConfig
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.AiNotesProvider
import dev.kuml.workspace.pdf.DiagramArtifact
import dev.kuml.workspace.pdf.DiagramSource
import dev.kuml.workspace.pdf.ExportProgressListener
import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.PdfExportResult
import dev.kuml.workspace.pdf.PreparedExport
import dev.kuml.workspace.pdf.WorkspacePdfExporter
import java.io.File
import java.io.IOException

/**
 * The single code path behind `kuml workspace export --format pdf`, the Desktop export dialog and the MCP
 * tool: diagrams are rendered by the full [RenderPipeline] (all diagram families), then handed to the
 * script-free `kuml-workspace-pdf` module.
 *
 * Trust model: exactly like `workspace render`, this executes the Kotlin script of every ```` ```kuml ````
 * block. Only run it over workspaces the user trusts.
 */
public object WorkspacePdfExport {
    /** `kUML <version>`, written into the PDF producer field. */
    public val producer: String get() = "kUML ${KumlVersion.version}"

    /** A [DiagramSource] that renders blocks through [RenderPipeline] and extracts diagram facts. */
    public fun diagramSource(themeName: String? = null): DiagramSource =
        DiagramSource { doc, blockIndex, block ->
            val stem = WorkspaceBlockEvaluator.sanitizeStem(block.name ?: "${doc.file.nameWithoutExtension}-${blockIndex + 1}")
            try {
                WorkspaceBlockEvaluator.withTempScript(stem = stem, source = block.source) { script ->
                    val r = RenderPipeline.renderToSvg(input = script, themeName = themeName, config = KumlConfig.DEFAULT)
                    DiagramArtifact.Rendered(svg = r.svg, facts = DiagramFactsExtractor.from(r.extracted), diagramCount = r.diagramCount)
                }
            } catch (e: ScriptEvaluationException) {
                DiagramArtifact.Failed(reason = (e.message ?: "script error").take(MAX_REASON))
            } catch (e: IOException) {
                DiagramArtifact.Failed(reason = "I/O error: ${(e.message ?: "").take(MAX_REASON)}")
            } catch (e: IllegalArgumentException) {
                DiagramArtifact.Failed(reason = (e.message ?: "invalid block").take(MAX_REASON))
            }
        }

    /** Renders, lays out and assembles the PDF(s) in memory; writes nothing. */
    public fun prepare(
        workspace: OkfWorkspace,
        options: PdfExportOptions,
        themeName: String? = null,
        aiNotes: AiNotesProvider? = null,
        progress: ExportProgressListener = ExportProgressListener { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): PreparedExport =
        WorkspacePdfExporter.prepare(
            workspace = workspace,
            options = options.copy(producerVersion = producer),
            diagrams = diagramSource(themeName = themeName),
            aiNotes = aiNotes,
            progress = progress,
            cancelled = cancelled,
        )

    /** Saves a [PreparedExport]; see [WorkspacePdfExporter.write]. */
    public fun write(
        prepared: PreparedExport,
        target: File,
        overwrite: Boolean,
        restrictToRoot: File? = null,
    ): PdfExportResult =
        WorkspacePdfExporter.write(prepared = prepared, target = target, overwrite = overwrite, restrictToRoot = restrictToRoot)

    private const val MAX_REASON = 300
}
