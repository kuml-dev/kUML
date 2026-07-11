package dev.kuml.desktop.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.OkfWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds an opened Knowledge-mode [OkfWorkspace] plus the currently selected
 * document and its render output (V3.6.4).
 *
 * Deliberately decoupled from Compose UI beyond the `mutableStateOf` properties
 * themselves — [select] is a plain `suspend` function with no internal coroutine
 * launch/cancel bookkeeping, so it is directly unit-testable (`WorkspaceStateTest`)
 * via `runTest { state.select(...) }` without needing a `CoroutineScope`/Job or
 * Compose test harness. The caller (a Composable with `rememberCoroutineScope`)
 * is responsible for launching `select` in response to a tree click.
 */
class WorkspaceState(
    val workspace: OkfWorkspace,
) {
    /** Documents sorted by relativePath (matches [dev.kuml.workspace.WorkspaceScanner]'s order). */
    val documents: List<OkfDocument> = workspace.documents.sortedBy { it.relativePath }

    var selected by mutableStateOf<OkfDocument?>(null)
        private set

    /** Rendered SVG for the selected document's first kuml block; null = no diagram / error / prose. */
    var docSvg by mutableStateOf<String?>(null)
        private set

    /** Render/validation error for the selected document; null = no error. */
    var docError by mutableStateOf<String?>(null)
        private set

    var isRendering by mutableStateOf(false)
        private set

    /** Monotonic guard so a slow render from a superseded selection can't clobber a newer one. */
    private var selectionToken = 0

    /**
     * Selects [doc] and renders its first ` ```kuml ` block (if any) via
     * [DesktopRenderPipeline] on `Dispatchers.IO`.
     *
     * - No kuml block (prose document) → [docSvg] / [docError] both `null`
     *   (the UI shows [Strings.previewProseEmpty]).
     * - ERM diagram type → short-circuits to [docError] = [Strings.previewErmUnsupported]
     *   **without** evaluating the script (the desktop render pipeline can't render
     *   ERM yet; no reason to run untrusted code for a diagram we'd reject anyway).
     * - Otherwise → runs the block through the normal single-file render pipeline.
     */
    suspend fun select(
        doc: OkfDocument,
        themeName: String,
        strings: Strings,
    ) {
        val token = ++selectionToken
        selected = doc
        docSvg = null
        docError = null

        val block = doc.kumlBlocks.firstOrNull() ?: return

        if (doc.type == OkfType.ERM_DIAGRAM) {
            docError = strings.previewErmUnsupported
            return
        }

        isRendering = true
        try {
            val result =
                withContext(Dispatchers.IO) {
                    DesktopRenderPipeline.render(block.source, themeName)
                }
            if (token != selectionToken) return // superseded by a newer selection
            when (result) {
                is DesktopRenderResult.Svg -> docSvg = result.svg
                is DesktopRenderResult.Error -> docError = result.message
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token == selectionToken) {
                docError = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
        } finally {
            if (token == selectionToken) isRendering = false
        }
    }
}
