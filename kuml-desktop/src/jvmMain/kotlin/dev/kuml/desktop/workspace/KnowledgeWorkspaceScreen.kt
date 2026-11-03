package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.preview.ImageFormat
import dev.kuml.desktop.render.DesktopRenderController
import dev.kuml.desktop.ui.ResizableSplit
import dev.kuml.desktop.ui.SPLIT_DIVIDER_WIDTH
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
    /** Share of the width taken by the document tree (draggable divider, see [WorkspaceColumns]). */
    navFraction: Double = WORKSPACE_NAV_DEFAULT_FRACTION,
    onNavFractionChange: (Double) -> Unit = {},
    onNavFractionCommit: (Double) -> Unit = {},
    /** Share of the editor within the area right of the tree (second draggable divider). */
    editorFraction: Double = WORKSPACE_EDITOR_DEFAULT_FRACTION,
    onEditorFractionChange: (Double) -> Unit = {},
    onEditorFractionCommit: (Double) -> Unit = {},
    /** Opens the PDF export dialog for the document with the given workspace-relative path. */
    onExportPdf: (String) -> Unit = {},
    /** Saves the shown diagram (the selected document's) as an image. */
    onExportImage: ((ImageFormat) -> Unit)? = null,
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

    // Picks up files created, changed or deleted outside the app (editor, git, sync tool).
    WorkspaceChangeEffect(root = state.workspace.root) {
        state.refreshFromDisk(themeName = themeName, strings = strings, watermark = showWatermark)
    }

    // Bugfix (review finding) — this used to be a plain `OkfType?`, which can't tell "no
    // buffer yet" apart from "buffer's `type:` doesn't resolve to a known OkfType"; both
    // collapsed onto `null` and made WorkspaceTreePane fall back to the document's stale,
    // last-saved badge for the second case too. See `PendingType`'s KDoc.
    val pendingTypeForSelected: PendingType =
        state.buffer?.let { PendingType.Override(type = OkfType.fromId(FrontmatterParser.parse(it).type)) }
            ?: PendingType.NoOverride

    WorkspaceColumns(
        navFraction = navFraction,
        onNavFractionChange = onNavFractionChange,
        onNavFractionCommit = onNavFractionCommit,
        editorFraction = editorFraction,
        onEditorFractionChange = onEditorFractionChange,
        onEditorFractionCommit = onEditorFractionCommit,
        strings = strings,
        modifier = modifier.fillMaxWidth().fillMaxHeight(),
        tree = {
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
                modifier = Modifier.fillMaxSize(),
            )
        },
        editor = {
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
                diskConflict = state.diskConflict,
                onResolveDiskConflict = { keepMine ->
                    scope.launch {
                        state.resolveDiskConflict(
                            keepMine = keepMine,
                            themeName = themeName,
                            strings = strings,
                            watermark = showWatermark,
                        )
                    }
                },
                onEditorReady = onEditorReady,
                onExportPdf = { state.selected?.let { onExportPdf(it.relativePath) } },
                modifier = Modifier.fillMaxSize(),
            )
        },
        preview = {
            WorkspacePreviewPane(
                docSvg = state.docSvg,
                docError = state.docError,
                isRendering = state.isRendering,
                hasSelection = state.selected != null,
                strings = strings,
                modifier = Modifier.fillMaxSize(),
                onExport = onExportImage,
            )
        },
    )
}

internal const val WORKSPACE_NAV_DEFAULT_FRACTION: Double = 0.2
internal const val WORKSPACE_NAV_MAX_FRACTION: Double = 0.40
internal const val WORKSPACE_EDITOR_DEFAULT_FRACTION: Double = 0.5
internal const val WORKSPACE_EDITOR_MAX_FRACTION: Double = 0.85
internal const val WORKSPACE_NAV_DIVIDER_TAG = "kuml-workspace-nav-divider"
internal const val WORKSPACE_EDITOR_DIVIDER_TAG = "kuml-workspace-editor-divider"

/** Minimum width of every workspace column. */
private val WORKSPACE_MIN_COLUMN = 200.dp

/**
 * The right-hand area (editor | divider | preview) must keep two minimum columns plus its own
 * divider, so the outer split reserves that much for its end pane.
 */
private val WORKSPACE_NAV_END_MIN = WORKSPACE_MIN_COLUMN * 2 + SPLIT_DIVIDER_WIDTH

/**
 * Three columns as two nested [ResizableSplit]s: tree | (editor | preview). The inner share is a
 * share of the remainder, so dragging the outer divider scales editor and preview proportionally
 * and never changes the inner wish. Defaults 20 / 40 / 40.
 */
@Composable
internal fun WorkspaceColumns(
    navFraction: Double,
    onNavFractionChange: (Double) -> Unit,
    onNavFractionCommit: (Double) -> Unit,
    editorFraction: Double,
    onEditorFractionChange: (Double) -> Unit,
    onEditorFractionCommit: (Double) -> Unit,
    strings: Strings,
    modifier: Modifier = Modifier,
    tree: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    ResizableSplit(
        fraction = navFraction,
        onFractionChange = onNavFractionChange,
        onFractionCommit = onNavFractionCommit,
        dividerDescription = strings.workspaceLayout.navDividerDescription,
        modifier = modifier,
        defaultFraction = WORKSPACE_NAV_DEFAULT_FRACTION,
        startMinPane = WORKSPACE_MIN_COLUMN,
        endMinPane = WORKSPACE_NAV_END_MIN,
        maxFraction = WORKSPACE_NAV_MAX_FRACTION,
        testTag = WORKSPACE_NAV_DIVIDER_TAG,
        start = tree,
        end = {
            ResizableSplit(
                fraction = editorFraction,
                onFractionChange = onEditorFractionChange,
                onFractionCommit = onEditorFractionCommit,
                dividerDescription = strings.workspaceLayout.editorDividerDescription,
                modifier = Modifier.fillMaxSize(),
                defaultFraction = WORKSPACE_EDITOR_DEFAULT_FRACTION,
                startMinPane = WORKSPACE_MIN_COLUMN,
                endMinPane = WORKSPACE_MIN_COLUMN,
                maxFraction = WORKSPACE_EDITOR_MAX_FRACTION,
                testTag = WORKSPACE_EDITOR_DIVIDER_TAG,
                start = editor,
                end = preview,
            )
        },
    )
}
