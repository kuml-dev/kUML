package dev.kuml.desktop.preview

import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.cli.export.LatexExportResult
import dev.kuml.cli.export.LatexExportService
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.FileMenu
import java.awt.Window
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// LaTeX (TikZ) export of the diagram in the editor. Deliberately its own small type next to the image exports:
// it is generated from the SCRIPT TEXT (through the CLI's own render path, see LatexExportService), never from the
// SVG on screen, so a running simulation (frozen base render, highlight rings) can never leak into the .tex.

/** What the LaTeX file contains. */
internal enum class LatexMode { STANDALONE, SNIPPET }

/** `true` for the stored `AppSettings.latexStandalone` value of [mode]. */
internal fun LatexMode.isStandalone(): Boolean = this == LatexMode.STANDALONE

internal fun latexModeOf(standalone: Boolean): LatexMode = if (standalone) LatexMode.STANDALONE else LatexMode.SNIPPET

/**
 * Whether the CLI's LaTeX path can draw [extracted]: UML, C4, SysML 2 (not the package diagram), BPMN process and
 * collaboration diagrams, and Blueprint. ERM and the remaining BPMN kinds are refused by the pipeline.
 */
internal fun isLatexCapable(extracted: ExtractedDiagram): Boolean =
    when (extracted) {
        is ExtractedDiagram.Uml -> true
        is ExtractedDiagram.C4 -> true
        is ExtractedDiagram.Sysml2 -> extracted.diagram !is dev.kuml.sysml2.Sysml2PackageDiagram
        is ExtractedDiagram.Bpmn -> extracted.diagram is ProcessDiagram || extracted.diagram is CollaborationDiagram
        is ExtractedDiagram.Blueprint -> true
        is ExtractedDiagram.Erm -> false
    }

/** Why the LaTeX export is unavailable, or `null` when it is available. One cause per answer, in this order. */
internal enum class LatexUnavailable { NOTHING_RENDERED, NOT_A_SCRIPT, UNSUPPORTED_DIAGRAM }

/**
 * Pure decision behind the menu item and the toolbar button.
 *
 * @param hasRenderedDiagram a diagram is shown (the same test the SVG/PNG export uses)
 * @param renderFailed the last render ended in an error (the script does not compile right now)
 * @param latexCapable the rendered diagram's family is supported
 * @param sourceIsScript the editor buffer is a `.kuml.kts` script (single file or Engineering workspace); a
 *   Knowledge-Workspace Markdown document is not
 */
internal fun latexUnavailableReason(
    hasRenderedDiagram: Boolean,
    renderFailed: Boolean,
    latexCapable: Boolean,
    sourceIsScript: Boolean,
): LatexUnavailable? =
    when {
        !sourceIsScript -> LatexUnavailable.NOT_A_SCRIPT
        !hasRenderedDiagram || renderFailed -> LatexUnavailable.NOTHING_RENDERED
        !latexCapable -> LatexUnavailable.UNSUPPORTED_DIAGRAM
        else -> null
    }

/** The tooltip / explanation for a disabled export; [LatexUnavailable.NOTHING_RENDERED] reuses the image exports' text. */
internal fun latexUnavailableText(
    reason: LatexUnavailable,
    strings: Strings,
): String =
    when (reason) {
        LatexUnavailable.NOTHING_RENDERED -> strings.previewExport.nothingToSave
        LatexUnavailable.NOT_A_SCRIPT -> strings.latexExport.needsScript
        LatexUnavailable.UNSUPPORTED_DIAGRAM -> strings.latexExport.unsupportedDiagram
    }

/** Suggested file name: `<base>.tex`, with the same cleaning as the image exports. */
internal fun latexSuggestedFileName(baseName: String?): String =
    checkNotNull(exportTarget(format = ImageFormat.SVG, svg = "x", baseName = baseName)).suggestedFileName.removeSuffix(".svg") + ".tex"

internal data class LatexExportRequest(
    val script: String,
    val themeName: String,
    val standalone: Boolean,
)

internal sealed interface LatexRenderOutcome {
    data class Ready(
        val tex: String,
        val diagramCount: Int,
        val firstDiagramName: String?,
    ) : LatexRenderOutcome

    data class Failed(
        val message: String,
    ) : LatexRenderOutcome
}

/** Blocking: evaluates and renders. Call off the UI thread. Never throws; every problem is a [LatexRenderOutcome.Failed]. */
internal fun renderLatex(
    request: LatexExportRequest,
    service: (script: String, themeName: String?, standalone: Boolean) -> LatexExportResult = LatexExportService::export,
): LatexRenderOutcome =
    try {
        when (val result = service(request.script, request.themeName, request.standalone)) {
            is LatexExportResult.Success ->
                LatexRenderOutcome.Ready(
                    tex = result.tex,
                    diagramCount = result.diagramCount,
                    firstDiagramName = result.firstDiagramName,
                )
            is LatexExportResult.Failure -> LatexRenderOutcome.Failed(result.message)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LatexRenderOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }

/**
 * Hint line of the dialog, shown only when the script holds more than one diagram (only the first is exported,
 * which the user must see before saving), else `null`.
 */
internal fun latexDialogHint(
    diagramCount: Int,
    firstDiagramName: String?,
    strings: Strings,
): String? =
    if (diagramCount > 1) {
        strings.latexExport.multiDiagramHint.format(diagramCount, firstDiagramName?.takeIf { it.isNotBlank() } ?: "-")
    } else {
        null
    }

/** Atomic UTF-8 write: a temp file next to the target, then a move; no half-written `.tex` is ever left behind. */
internal fun saveLatex(
    file: File,
    tex: String,
): ExportSaveResult =
    try {
        val dir = (file.absoluteFile.parentFile ?: File(System.getProperty("user.home"))).toPath()
        val tmp = Files.createTempFile(dir, "${file.name}.", ".tmp")
        try {
            Files.writeString(tmp, tex, Charsets.UTF_8)
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        ExportSaveResult.Saved(file)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ExportSaveResult.Failed(e.message ?: e.javaClass.simpleName)
    }

/** File chooser for the `.tex` target; `null` when the user cancels. */
internal fun chooseLatexFile(
    parent: Window?,
    initialDir: File?,
    suggestedName: String,
    strings: Strings,
): File? =
    FileMenu.chooseExport(
        parent = parent,
        initialDir = initialDir,
        suggestedName = suggestedName,
        description = strings.latexExport.fileDescription,
        extension = "tex",
        strings = strings,
    )
