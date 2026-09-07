package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderController
import dev.kuml.markdown.CodeBlockExtractor
import dev.kuml.workspace.FrontmatterParser
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.OkfWriteResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Three-column Knowledge-mode workspace layout (V3.6.4; editable in V-next):
 * document tree | editable Markdown document | live SVG preview.
 */
@OptIn(FlowPreview::class)
@Composable
fun KnowledgeWorkspaceScreen(
    state: WorkspaceState,
    themeName: String,
    strings: Strings,
    modifier: Modifier = Modifier,
    showWatermark: Boolean = false,
    /**
     * Wraps a tree/backlink navigation in the app's unsaved-changes guard (V-next) — shared
     * with the plain script editor's File-menu guard in `MainWindow`, so switching documents
     * with unsaved OKF changes prompts exactly like switching files does.
     */
    confirmUnsavedAndThen: (() -> Unit) -> Unit = { it() },
    onEditorReady: (EditorActions?) -> Unit = {},
    /**
     * Reports the outcome of the header bar's own "Speichern" button (bugfix, review
     * finding) — that button used to call [WorkspaceState.save] and drop the result on the
     * floor entirely, so a `Rejected` (e.g. a symlink swapped in from outside), `TooLarge`,
     * or `Failed` save was completely invisible: no dialog, no banner change, the dirty
     * marker just sat there while repeated clicks silently did nothing. `Written`/`Blocked`
     * still need no handling here — the tree's dirty marker and the findings banner already
     * reflect those (see [WorkspaceState.save]'s KDoc) — but the caller (`MainWindow`) is now
     * given every result so it can show the same rejection dialog Ctrl+S already shows via
     * `reportKnowledgeSaveResult`.
     */
    onSaveResult: (OkfWriteResult) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val linkHandler =
        remember(state) {
            DefaultWorkspaceLinkHandler(
                documents = { state.documents },
                currentDoc = { state.selected },
                onNavigate = { doc ->
                    confirmUnsavedAndThen {
                        scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
                    }
                },
            )
        }

    // Re-renders the CURRENTLY selected document (if any) whenever the theme or watermark
    // setting changes -- see the KDoc above. No-op when nothing is selected yet.
    LaunchedEffect(themeName, showWatermark) {
        if (state.selected != null) {
            state.renderCurrentBlock(themeName = themeName, strings = strings, watermark = showWatermark)
        }
    }

    // V-next — debounced re-validation of the in-memory buffer (never per keystroke
    // directly): mirrors the app's existing render debounce so typing in the title field or
    // the raw Markdown editor doesn't run OkfValidator on every character.
    LaunchedEffect(state) {
        snapshotFlow { state.buffer }
            .distinctUntilChanged()
            .debounce(DesktopRenderController.DEFAULT_DEBOUNCE_MS)
            .collect { state.revalidate() }
    }

    // V-next — debounced, BLOCK-bound re-render: only the extracted first ```kuml block's
    // source is watched, so typing prose (which never changes that string) never triggers a
    // script evaluation. Also reacts to a `type:` change (e.g. into/out of ErmDiagram) via
    // the same buffer projection `renderCurrentBlock` itself reads.
    LaunchedEffect(state) {
        snapshotFlow { state.buffer?.let { CodeBlockExtractor.extract(it).firstOrNull()?.source } }
            .distinctUntilChanged()
            .debounce(DesktopRenderController.DEFAULT_DEBOUNCE_MS)
            .collect { state.renderCurrentBlock(themeName = themeName, strings = strings, watermark = showWatermark) }
    }

    // Bugfix (review finding) — this used to be a plain `OkfType?`, which can't tell "no
    // buffer yet" apart from "buffer's `type:` doesn't resolve to a known OkfType"; both
    // collapsed onto `null` and made WorkspaceTreePane fall back to the document's stale,
    // last-saved badge for the second case too. See `PendingType`'s KDoc.
    val pendingTypeForSelected: PendingType =
        state.buffer?.let { PendingType.Override(type = OkfType.fromId(FrontmatterParser.parse(it).type)) }
            ?: PendingType.NoOverride

    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        WorkspaceTreePane(
            documents = state.documents,
            selected = state.selected,
            dirtyPath = state.selected?.relativePath?.takeIf { state.isDirty },
            pendingTypeForSelected = pendingTypeForSelected,
            onSelect = { doc ->
                confirmUnsavedAndThen {
                    scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
                }
            },
            strings = strings,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        DocumentEditorPane(
            doc = state.selected,
            buffer = state.buffer,
            editing = state.editing,
            isDirty = state.isDirty,
            findings = state.findings,
            blockingFindings = state.blockingFindings,
            onToggleEditing = { state.setEditing(it) },
            onBufferChange = { state.updateBuffer(it) },
            onTypeChange = { state.setFrontmatterField(key = "type", value = it) },
            onTitleChange = { state.setFrontmatterField(key = "title", value = it) },
            onSave = {
                scope.launch {
                    val result = state.save(themeName = themeName, strings = strings, watermark = showWatermark)
                    onSaveResult(result)
                }
            },
            linkHandler = linkHandler,
            backlinks = state.selected?.let { state.graphIndex.backlinks(it) }.orEmpty(),
            onNavigateBacklink = { doc ->
                confirmUnsavedAndThen {
                    scope.launch { state.select(doc = doc, themeName = themeName, strings = strings, watermark = showWatermark) }
                }
            },
            strings = strings,
            onEditorReady = onEditorReady,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        WorkspacePreviewPane(
            docSvg = state.docSvg,
            docError = state.docError,
            isRendering = state.isRendering,
            hasSelection = state.selected != null,
            strings = strings,
            modifier = Modifier.weight(2f).fillMaxHeight(),
        )
    }
}
