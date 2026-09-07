package dev.kuml.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.ai.AiPanel
import dev.kuml.desktop.ai.AiPanelState
import dev.kuml.desktop.ai.settings.AiProviderSettingsDialog
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.editor.EditorPane
import dev.kuml.desktop.editor.FindBar
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.AppSettingsStore
import dev.kuml.desktop.io.FileMenu
import dev.kuml.desktop.io.UnsavedChoice
import dev.kuml.desktop.io.UnsavedSaveTarget
import dev.kuml.desktop.io.unsavedSaveTargetFor
import dev.kuml.desktop.plugins.PluginManagerPane
import dev.kuml.desktop.preview.PreviewPane
import dev.kuml.desktop.render.DesktopRenderController
import dev.kuml.desktop.render.RenderInputs
import dev.kuml.desktop.simulation.SimulationSession
import dev.kuml.desktop.simulation.SimulationStartResult
import dev.kuml.desktop.simulation.SimulationStatus
import dev.kuml.desktop.state.rememberAppSettingsBinding
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.workspace.EngineeringFileScanner
import dev.kuml.desktop.workspace.EngineeringWorkspaceScreen
import dev.kuml.desktop.workspace.KnowledgeWorkspaceScreen
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.TrustDialog
import dev.kuml.desktop.workspace.WorkspaceModeChooserDialog
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.OkfWriteResult
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.WorkspaceWriteGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JOptionPane

/**
 * Pure decision of how long to debounce a render for the given [RenderInputs] transition
 * (V3.7.4, design review P6). Extracted out of the `LaunchedEffect(controller)` collector below
 * specifically so it is unit-testable without a Compose runtime (see the cross-cutting
 * pure-function-extraction note in the plan — `kuml-desktop` has no Compose UI test harness).
 *
 * Only an actual script EDIT (the user typing) gets the debounce — a theme switch, a watermark
 * toggle, or the very first emission (previousScript == null, i.e. app/document just opened)
 * all render immediately, since none of those originate from a keystroke stream that benefits
 * from coalescing.
 */
internal fun renderDelayFor(
    previousScript: String?,
    inputs: RenderInputs,
): Long =
    if (previousScript != null && previousScript != inputs.script) {
        DesktopRenderController.DEFAULT_DEBOUNCE_MS
    } else {
        0L
    }

/**
 * Pure mapping from a [WorkspaceWriteGuard.Rejection] reason to its user-facing message
 * (bugfix, review finding) — extracted out of `reportKnowledgeSaveResult`'s `when` so the
 * exact cause→message mapping is unit-testable without a Compose/Swing harness, same
 * reasoning as [renderDelayFor] above. Every rejection used to be reported with
 * [Strings.docSaveOutsideRoot] regardless of the actual [WorkspaceWriteGuard.Rejection]
 * value, actively misleading the diagnosis for e.g. a `SYMLINK` rejection.
 */
internal fun rejectionMessage(
    rejection: WorkspaceWriteGuard.Rejection,
    strings: Strings,
): String =
    when (rejection) {
        WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT -> strings.docSaveOutsideRoot
        WorkspaceWriteGuard.Rejection.SYMLINK -> strings.docSaveRejectedSymlink
        WorkspaceWriteGuard.Rejection.NOT_A_REGULAR_FILE -> strings.docSaveRejectedNotRegularFile
        WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT -> strings.docSaveRejectedUnknownDocument
        WorkspaceWriteGuard.Rejection.IO_ERROR -> strings.docSaveRejectedIoError
    }

/**
 * Top-Level-Composable für das kUML Desktop Hauptfenster.
 * Deklariert die native [MenuBar] und legt [EditorPane] + [PreviewPane] nebeneinander.
 */
@Composable
fun FrameWindowScope.MainWindow(
    state: AppState,
    store: AppSettingsStore,
    vault: ApiKeyVault,
    onQuit: () -> Unit = {},
) {
    val strings = Strings.forLanguage(state.language)
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { DesktopRenderController(state = state, scope = scope) }
    val aiState = remember { AiPanelState(appState = state, scope = scope, vault = vault) }
    var showPluginManager by remember { mutableStateOf(false) }
    // V3.7.1 — AI provider settings dialog
    var showAiProviderSettings by remember { mutableStateOf(false) }
    // P6, design review — About dialog (the strings already existed, the dialog didn't).
    var showAboutDialog by remember { mutableStateOf(false) }
    // P2, design review — handle to the currently-mounted EditorPane's undo/redo, so the
    // Edit menu can wire real Undo/Redo instead of the previous `/* V3.0.11 */` no-ops.
    var editorActions by remember { mutableStateOf<EditorActions?>(null) }

    // V3.6.4 — Knowledge Workspace viewer: pending dialogs gate opening a workspace.
    var pendingTrustWorkspace by remember { mutableStateOf<OkfWorkspace?>(null) }
    var pendingUnknownWorkspace by remember { mutableStateOf<OkfWorkspace?>(null) }

    rememberAppSettingsBinding(state = state, store = store)

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }

    // V3.7.4 (design review P6) — the ONE render trigger, replacing the previous single
    // call site inside EditorPane's DocumentListener (which meant a theme/watermark change,
    // or ViewMode.DIAGRAM where EditorPane isn't even composed, never re-rendered anything
    // until the next keystroke). RenderInputs bundles every input a render actually depends
    // on; distinctUntilChanged() skips a redundant re-render on an unrelated recomposition
    // that happens not to change any of the three fields.
    //
    // snapshotFlow emits once immediately on collection start, which also fixes the
    // "welcome script never rendered" gap: state.script == the initial script at mount time,
    // so the very first emission already renders it, rather than waiting for a first edit
    // that (for the placeholder welcome script) may never happen.
    //
    // Keyed on `controller` (not Unit) — this effect owns `controller`'s only render calls,
    // so if the controller instance were ever replaced, the effect must restart against the
    // new one rather than silently keep driving a stale controller for the rest of the
    // composition's lifetime.
    LaunchedEffect(controller) {
        var previousScript: String? = null
        snapshotFlow { RenderInputs(script = state.script, themeName = state.theme, watermark = state.showWatermark) }
            .distinctUntilChanged()
            .collect { inputs ->
                val delayMs = renderDelayFor(previousScript = previousScript, inputs = inputs)
                previousScript = inputs.script
                controller.scheduleRender(inputs = inputs, delayMs = delayMs)
            }
    }

    val windowHandle: java.awt.Window? = window

    // V3.6.4 — Knowledge Workspace viewer: mode dispatch + trust gate.
    //
    // WorkspaceScanner only reads/parses Markdown (no script evaluation), so scanning
    // ahead of the trust decision is safe — it's needed to know KNOWLEDGE/ENGINEERING/
    // UNKNOWN before deciding what to show. The trust gate itself runs strictly before
    // any document is selected/rendered, i.e. before any `kuml` block is evaluated.
    fun dispatchWorkspace(workspace: OkfWorkspace) {
        when (workspace.mode) {
            WorkspaceMode.KNOWLEDGE -> state.openWorkspace = OpenWorkspace.Knowledge(WorkspaceState(workspace))
            WorkspaceMode.ENGINEERING -> {
                val files = EngineeringFileScanner.scan(workspace.root)
                state.openWorkspace = OpenWorkspace.Engineering(root = workspace.root, scriptFiles = files)
            }
            WorkspaceMode.UNKNOWN -> pendingUnknownWorkspace = workspace
        }
    }

    fun openWorkspaceDirectory(dir: File) {
        scope.launch {
            val workspace = withContext(Dispatchers.IO) { WorkspaceScanner.scan(root = dir) }
            if (WorkspaceTrust.isTrusted(trustedPaths = state.trustedWorkspaces, root = dir)) {
                dispatchWorkspace(workspace)
            } else {
                pendingTrustWorkspace = workspace
            }
        }
    }

    fun saveCurrentFile(): Boolean {
        val file = state.currentFile
        return if (file != null) {
            FileMenu.writeScript(file = file, content = state.script)
            state.markSaved(file)
            true
        } else {
            val chosen =
                FileMenu.chooseSave(
                    parent = windowHandle,
                    initialDir = state.lastDir?.let { File(it) },
                    suggestedName = "diagram.kuml.kts",
                    strings = strings,
                )
            if (chosen != null) {
                FileMenu.writeScript(file = chosen, content = state.script)
                state.markSaved(chosen)
                true
            } else {
                false
            }
        }
    }

    // V-next — editable Knowledge Workspace documents: surfaces a save outcome the
    // banner/tree dirty-marker can't show on their own (a refused write, an I/O failure, or
    // the too-large cap) as a modal dialog. `Written`/`Blocked` need no dialog here — the
    // banner and the tree's dirty marker already reflect them (see `WorkspaceState.save`).
    fun reportKnowledgeSaveResult(result: OkfWriteResult) {
        val message =
            when (result) {
                is OkfWriteResult.Written, is OkfWriteResult.Blocked -> null
                is OkfWriteResult.Rejected -> rejectionMessage(rejection = result.rejection, strings = strings)
                is OkfWriteResult.TooLarge -> strings.docSaveIoError.format("${result.bytes} > ${result.limit} bytes")
                is OkfWriteResult.Failed -> strings.docSaveIoError.format(result.cause.message ?: result.cause.javaClass.simpleName)
            }
        if (message != null) {
            JOptionPane.showMessageDialog(windowHandle, message)
        }
    }

    // V-next — saves the currently open Knowledge Workspace document, if any. Unlike
    // `saveCurrentFile` this is inherently asynchronous (`WorkspaceState.save` is `suspend`
    // — it validates and writes on `Dispatchers.IO`), so callers that need to gate a
    // follow-up action on success (`confirmUnsavedAndThen`) do so inside the `scope.launch`
    // block themselves rather than through this function's return value.
    fun saveKnowledgeDoc(knowledgeState: WorkspaceState) {
        scope.launch {
            val result = knowledgeState.save(themeName = state.theme, strings = strings, watermark = state.showWatermark)
            reportKnowledgeSaveResult(result)
        }
    }

    fun confirmUnsavedAndThen(action: () -> Unit) {
        val knowledgeState = (state.openWorkspace as? OpenWorkspace.Knowledge)?.state
        val knowledgeDirtyNow = knowledgeState?.isDirty == true
        // Bugfix (review finding) — this used to branch on "is a Knowledge Workspace open at
        // all" (`knowledgeState != null`), not on whether IT is actually dirty; see
        // `unsavedSaveTargetFor`'s KDoc for the failure this caused.
        val saveTarget = unsavedSaveTargetFor(scriptDirty = state.isDirty, knowledgeDirty = knowledgeDirtyNow)
        if (saveTarget == UnsavedSaveTarget.NONE) {
            action()
            return
        }
        val choice = FileMenu.confirmUnsaved(parent = windowHandle, strings = strings)
        val needsKnowledgeSave = saveTarget == UnsavedSaveTarget.KNOWLEDGE || saveTarget == UnsavedSaveTarget.BOTH
        if (choice == UnsavedChoice.SAVE && needsKnowledgeSave && knowledgeState != null) {
            // Async save path: the follow-up `action` only runs once the write(s) have
            // actually succeeded — a `Blocked`/`Rejected`/`Failed` result must NOT proceed
            // with whatever the user was about to do (switch documents, close the workspace,
            // quit). When BOTH buffers are dirty, the single-file script is saved right after
            // the Knowledge document, in sequence, before `action` runs.
            scope.launch {
                val result = knowledgeState.save(themeName = state.theme, strings = strings, watermark = state.showWatermark)
                reportKnowledgeSaveResult(result)
                if (result is OkfWriteResult.Written) {
                    val scriptSaveOk = saveTarget != UnsavedSaveTarget.BOTH || saveCurrentFile()
                    if (scriptSaveOk) action()
                }
            }
            return
        }
        val saveSucceeded = choice == UnsavedChoice.SAVE && saveCurrentFile()
        // Bugfix (review finding) — DISCARD used to proceed with `action()` while leaving
        // whichever buffer(s) `saveTarget` identified as dirty untouched: the plain script's
        // `state.isDirty` stayed `true` forever (nothing here ever reset it outside of a
        // fresh load), and an open Knowledge document's derived `isDirty` stayed `true`
        // because its `buffer` was never reverted. Either one re-armed this very dialog on
        // the very next guarded action (e.g. the next tree click), so "Verwerfen" never
        // actually let the user proceed past it.
        //
        // Bugfix (review finding, round 3) — `state.isDirty = false` alone cleared the FLAG
        // but left `state.script` holding the discarded edits: an action that doesn't
        // overwrite `script` itself (Workspace öffnen/schließen, Beenden) then carried those
        // edits forward with no dirty marker to warn about them — silently reappearing if the
        // user came back to the plain editor, or silently dropped with no further prompt on
        // quit. `discardScriptChanges()` reverts `script` to its last saved/loaded baseline
        // AND clears the flag, mirroring `knowledgeState.discardChanges()` on the line below.
        if (choice == UnsavedChoice.DISCARD) {
            if (saveTarget == UnsavedSaveTarget.SCRIPT || saveTarget == UnsavedSaveTarget.BOTH) {
                state.discardScriptChanges()
            }
            if ((saveTarget == UnsavedSaveTarget.KNOWLEDGE || saveTarget == UnsavedSaveTarget.BOTH) && knowledgeState != null) {
                knowledgeState.discardChanges()
            }
        }
        if (FileMenu.shouldProceedAfterUnsavedChoice(choice = choice, saveSucceeded = saveSucceeded)) {
            action()
        }
    }

    // V3.x — Live-Simulation von Zustandsautomaten im Editor (Werkzeuge ▸ Simulieren, Ctrl+R).
    fun stopSimulation() {
        state.simulation?.close()
        state.simulation = null
    }

    fun startSimulation() {
        // Review fix — re-entrancy guard. The menu item's own `enabled` check below already
        // keeps a second Ctrl+R/click from firing while a start is in flight, but this is a
        // second, independent line of defense (belt-and-braces, not "either/or"): if this
        // function is ever invoked a second time before the first `scope.launch` below has
        // resolved — e.g. a future caller that doesn't go through the menu item at all — bail
        // out instead of racing two SimulationSession.start() calls, each spinning up its own
        // TimeLimitedGuardEvaluator thread pool, for `state.simulation` to overwrite one with
        // the other and leak the loser's pool for the JVM's lifetime.
        if (state.simulationStarting) return
        stopSimulation()
        // Spez. A4 — a simulation needs the diagram visible; SOURCE-only leaves nothing to
        // highlight, so switch to SPLIT (never DIAGRAM — the user might still want the editor).
        if (state.viewMode == AppState.ViewMode.SOURCE) state.viewMode = AppState.ViewMode.SPLIT
        state.simulationStarting = true
        scope.launch {
            try {
                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            SimulationSession.start(script = state.script, themeName = state.theme)
                        }
                ) {
                    is SimulationStartResult.Started -> state.simulation = result.session
                    is SimulationStartResult.Unsupported -> state.lastError = strings.simUnsupportedDiagram
                    is SimulationStartResult.Failed -> state.lastError = strings.simStartFailed.format(result.message)
                }
            } finally {
                state.simulationStarting = false
            }
        }
    }

    // V3.x — every session that ends without close() leaks a kuml-sandbox-guard-* thread pool.
    DisposableEffect(Unit) {
        onDispose {
            state.simulation?.close()
            state.simulation = null
        }
    }

    // P2, design review — `showsEditor` gates the plain single-file script editor's own
    // View-menu concepts (the ViewMode submenu, the status bar's segmented control): the
    // Knowledge Workspace's three-column layout never reads `state.viewMode` at all,
    // editing or not, so this stays exactly "not a Knowledge workspace" (unchanged since
    // V3.6.4 — see its remaining usages below).
    val showsEditor = state.openWorkspace !is OpenWorkspace.Knowledge

    // V-next — editable Knowledge Workspace documents: a second, INDEPENDENT editor
    // (`DocumentEditorPane`'s `SyntaxTextEditor`) is now mounted while a Knowledge document
    // is open AND its Read/Edit toggle is set to Edit — `editorActions` (Undo/Redo/Find)
    // wires to whichever of the two editors is actually mounted (never both at once: the
    // Knowledge screen replaces the single-file editor entirely — see `MainWindow`'s Row
    // below).
    val knowledgeEditing = (state.openWorkspace as? OpenWorkspace.Knowledge)?.state?.editing == true

    // Review fix — `showsEditor` alone doesn't cover ViewMode.DIAGRAM: EditorPane (and FindBar)
    // aren't composed at all there either (see the `if (state.viewMode != DIAGRAM)` gate around
    // EditorPane below), so `editorActions` is null in that mode too. Undo/Redo already fall
    // through safely because their `enabled` reads `editorActions?.canUndo?.value ?: false`,
    // which is false once null — but Find's onClick unconditionally set
    // `state.findBarOpen = true` regardless of whether an editor existed to show it against,
    // so pressing Ctrl+F in Diagram mode silently armed a find bar that popped up unannounced
    // the next time the user switched back to Split/Source.
    //
    // V-next: widened with `|| knowledgeEditing` so Undo/Redo/Find (which now also reads
    // `showsEditorPane`, not just `showsEditor` — see the Undo/Redo `Item`s below) become
    // available the moment a Knowledge document's Read/Edit toggle switches to Edit, exactly
    // like they would for the plain script editor.
    val showsEditorPane = (showsEditor && state.viewMode != AppState.ViewMode.DIAGRAM) || knowledgeEditing

    MenuBar {
        Menu(strings.menuFile) {
            Item(strings.menuFileNew, onClick = {
                confirmUnsavedAndThen { state.newScript() }
            })
            Item(strings.menuFileOpen, onClick = {
                confirmUnsavedAndThen {
                    val file =
                        FileMenu.chooseOpen(
                            parent = windowHandle,
                            initialDir = state.lastDir?.let { File(it) },
                            strings = strings,
                        )
                    if (file != null) {
                        state.loadFrom(file = file, content = FileMenu.readScript(file))
                        state.isDirty = false
                    }
                }
            })
            Item(strings.menuFileOpenWorkspace, onClick = {
                confirmUnsavedAndThen {
                    val dir =
                        FileMenu.chooseOpenDirectory(
                            parent = windowHandle,
                            initialDir = state.lastDir?.let { File(it) },
                            strings = strings,
                        )
                    if (dir != null) openWorkspaceDirectory(dir)
                }
            })
            if (state.openWorkspace != null) {
                // V-next — closing a workspace with unsaved OKF-document changes now goes
                // through the same guard as everything else (previously ungated: a
                // still-dirty knowledge document was silently discarded).
                Item(strings.menuFileCloseWorkspace, onClick = { confirmUnsavedAndThen { state.openWorkspace = null } })
            }
            // V-next — Ctrl+S now also saves the open Knowledge Workspace document, if any;
            // `menuFileSave` never had a shortcut before this welle.
            Item(
                strings.menuFileSave,
                shortcut = KeyShortcut(key = Key.S, ctrl = true),
                onClick = {
                    val knowledgeState = (state.openWorkspace as? OpenWorkspace.Knowledge)?.state
                    // Bugfix (review finding) — this used to route on "is a Knowledge
                    // Workspace open at all" (`knowledgeState != null`), ignoring which of
                    // the two independent dirty buffers (plain script vs. Knowledge
                    // document) actually needs saving; see `unsavedSaveTargetFor`'s KDoc for
                    // the identical bug already fixed in `confirmUnsavedAndThen`. A dirty
                    // script with a workspace merely open (nothing selected, or a clean
                    // document selected) used to route here and fail with "No document
                    // selected" instead of ever reaching `saveCurrentFile()`. When NEITHER
                    // buffer is dirty, fall back to the same choice this made before the fix
                    // (still harmless either way — both saves are idempotent) rather than
                    // forcing a Save-As dialog onto a workspace with no plain script file.
                    when (unsavedSaveTargetFor(scriptDirty = state.isDirty, knowledgeDirty = knowledgeState?.isDirty == true)) {
                        UnsavedSaveTarget.KNOWLEDGE -> knowledgeState?.let { saveKnowledgeDoc(it) }
                        UnsavedSaveTarget.SCRIPT -> saveCurrentFile()
                        UnsavedSaveTarget.BOTH -> {
                            knowledgeState?.let { saveKnowledgeDoc(it) }
                            saveCurrentFile()
                        }
                        UnsavedSaveTarget.NONE ->
                            if (knowledgeState != null) saveKnowledgeDoc(knowledgeState) else saveCurrentFile()
                    }
                },
            )
            Item(strings.menuFileSaveAs, onClick = {
                val chosen =
                    FileMenu.chooseSave(
                        parent = windowHandle,
                        initialDir = state.lastDir?.let { File(it) },
                        suggestedName = state.currentFile?.name ?: "diagram.kuml.kts",
                        strings = strings,
                    )
                if (chosen != null) {
                    FileMenu.writeScript(file = chosen, content = state.script)
                    state.markSaved(chosen)
                }
            })
            Separator()
            // P3, design review — Export the rendered diagram. Disabled until something has
            // actually been rendered; SVG export is zero-dependency (state.lastSvg is already
            // an in-memory SVG string), PNG goes through the existing kuml-io-png renderer.
            Item(
                strings.menuFileExportSvg,
                enabled = state.lastSvg.isNotBlank(),
                onClick = {
                    val chosen =
                        FileMenu.chooseExport(
                            parent = windowHandle,
                            initialDir = state.lastDir?.let { File(it) },
                            suggestedName = "${FileMenu.exportBaseName(state.currentFile)}.svg",
                            description = "SVG image (*.svg)",
                            extension = "svg",
                            strings = strings,
                        )
                    if (chosen != null) {
                        FileMenu.writeScript(file = chosen, content = state.lastSvg)
                    }
                },
            )
            Item(
                strings.menuFileExportPng,
                enabled = state.lastSvg.isNotBlank(),
                onClick = {
                    val chosen =
                        FileMenu.chooseExport(
                            parent = windowHandle,
                            initialDir = state.lastDir?.let { File(it) },
                            suggestedName = "${FileMenu.exportBaseName(state.currentFile)}.png",
                            description = "PNG image (*.png)",
                            extension = "png",
                            strings = strings,
                        )
                    if (chosen != null) {
                        FileMenu.writeBytes(file = chosen, bytes = KumlPngRenderer.toPng(svg = state.lastSvg))
                    }
                },
            )
            Separator()
            Menu(strings.menuFileRecent) {
                if (state.recentFiles.isEmpty()) {
                    Item(strings.menuFileRecentEmpty, enabled = false, onClick = {})
                } else {
                    state.recentFiles.toList().forEach { path ->
                        Item(File(path).name, onClick = {
                            val file = File(path)
                            if (file.exists()) {
                                state.loadFrom(file = file, content = FileMenu.readScript(file))
                                state.isDirty = false
                            }
                        })
                    }
                    Separator()
                    Item(strings.menuFileRecentClear, onClick = { state.recentFiles.clear() })
                }
            }
            Separator()
            Item(strings.menuFileQuit, onClick = {
                confirmUnsavedAndThen {
                    stopSimulation()
                    store.save(state.toSettings())
                    onQuit()
                }
            })
        }
        Menu(strings.menuEdit) {
            // P2, design review — wired to RSyntaxTextArea's built-in undo manager
            // (EditorPane.EditorActions) instead of the previous no-op placeholders.
            Item(
                strings.menuEditUndo,
                // V-next: `showsEditorPane` (not bare `showsEditor`) so this also enables
                // while editing a Knowledge document — see `showsEditorPane`'s KDoc above.
                enabled = showsEditorPane && (editorActions?.canUndo?.value ?: false),
                shortcut = KeyShortcut(key = Key.Z, ctrl = true),
                onClick = { editorActions?.undo?.invoke() },
            )
            Item(
                strings.menuEditRedo,
                enabled = showsEditorPane && (editorActions?.canRedo?.value ?: false),
                shortcut = KeyShortcut(key = Key.Z, ctrl = true, shift = true),
                onClick = { editorActions?.redo?.invoke() },
            )
            Separator()
            // V3.7.4 (design review P8) — inline incremental find bar (see FindBar.kt).
            // V3.7.5 (review fix): gated on `showsEditorPane`, not just `showsEditor` — see its
            // definition above for why ViewMode.DIAGRAM needs the same guard as Undo/Redo. The
            // onClick body is also guarded directly (not just via `enabled`) so a shortcut that
            // somehow still fires while disabled can never latch `findBarOpen = true` with no
            // editor mounted to show it against.
            Item(
                strings.menuEditFind,
                enabled = showsEditorPane,
                shortcut = KeyShortcut(key = Key.F, ctrl = true),
                onClick = {
                    if (showsEditorPane) {
                        editorActions?.beginFind?.invoke()
                        state.findBarOpen = true
                    }
                },
            )
        }
        Menu(strings.menuView) {
            // V3.7.4 (design review P6): the old fallback list `listOf("plain", "dark",
            // "blueprint")` named two themes ("dark", "blueprint") that DesktopRenderPipeline's
            // theme lookup silently falls back to "kuml" for — selecting them visibly changed
            // nothing. That fallback was only ever reachable because ThemeRegistry.names() used
            // to be empty at MenuBar-build time (DesktopEngineInit.ensure() ran too late — see
            // Main.main()); now that init runs first, this ifEmpty is pure defensive fallback for
            // a genuinely empty registry, so it lists only the one theme that is guaranteed to
            // actually exist.
            val themeNames = remember { ThemeRegistry.names().ifEmpty { listOf("kuml") } }
            Menu(strings.menuViewTheme) {
                themeNames.forEach { name ->
                    // RadioButtonItem (not Item) so the active theme is visibly marked -- same
                    // reasoning as the language submenu right below and the view-mode submenu.
                    RadioButtonItem(
                        name.replaceFirstChar { it.uppercase() },
                        selected = state.theme == name,
                        onClick = { state.theme = name },
                    )
                }
            }
            Menu(strings.menuViewLanguage) {
                // V3.7.4 (design review P7): RadioButtonItem instead of Item, so the currently
                // active language is visibly marked -- same pattern as menuViewMode below.
                // Labels intentionally stay in their own language (not localized via `strings`),
                // matching the existing convention.
                RadioButtonItem("Deutsch", selected = state.language == "de", onClick = { state.language = "de" })
                RadioButtonItem("English", selected = state.language == "en", onClick = { state.language = "en" })
            }
            Separator()
            // V3.7.4 (design review P9): opt-in "Powered by kUML" watermark, default off (parity
            // with the CLI's `kuml render` default -- see AppSettings.showWatermark's KDoc).
            // Placed here, before the Separator/view-mode submenu below -- toggling it re-renders
            // immediately via DesktopRenderController's RenderInputs (see P6's derivation).
            CheckboxItem(
                text = strings.menuViewWatermark,
                checked = state.showWatermark,
                onCheckedChange = { state.showWatermark = it },
            )
            Separator()
            // P5 — view-mode submenu, mirrors the segmented control in the status bar.
            // Shortcuts follow this repo's existing convention (Undo/Redo above use
            // `ctrl = true` for BOTH Windows/Linux and macOS — Compose Desktop maps `ctrl`
            // to the platform-native modifier itself, no separate `meta` flag anywhere in
            // this codebase yet), so `ctrl = true` here too rather than introducing `meta`.
            //
            // Review fix — the Knowledge workspace viewer (KnowledgeWorkspaceScreen) ignores
            // state.viewMode entirely and always renders its fixed tree|editor|SVG
            // three-column layout (editing or not — see `showsEditor`'s KDoc above), so the
            // submenu (and its Ctrl+1/2/3 shortcuts) is hidden rather than shown-but-inert
            // while a Knowledge workspace is open. EngineeringWorkspaceScreen DOES read
            // state.viewMode (see its editorWeight/previewWeight), so the submenu stays
            // available there — `showsEditor` is exactly `openWorkspace !is Knowledge`.
            if (showsEditor) {
                Menu(strings.menuViewMode) {
                    RadioButtonItem(
                        strings.viewModeSource,
                        selected = state.viewMode == AppState.ViewMode.SOURCE,
                        shortcut = KeyShortcut(key = Key.One, ctrl = true),
                        onClick = { state.viewMode = AppState.ViewMode.SOURCE },
                    )
                    RadioButtonItem(
                        strings.viewModeSplit,
                        selected = state.viewMode == AppState.ViewMode.SPLIT,
                        shortcut = KeyShortcut(key = Key.Two, ctrl = true),
                        onClick = { state.viewMode = AppState.ViewMode.SPLIT },
                    )
                    RadioButtonItem(
                        strings.viewModeDiagram,
                        selected = state.viewMode == AppState.ViewMode.DIAGRAM,
                        shortcut = KeyShortcut(key = Key.Three, ctrl = true),
                        onClick = { state.viewMode = AppState.ViewMode.DIAGRAM },
                    )
                }
            }
        }
        Menu(strings.menuHelp) {
            // P6, design review — actually opens the docs site instead of doing nothing.
            Item(
                strings.menuHelpDocs,
                onClick = {
                    runCatching {
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop
                                .getDesktop()
                                .browse(java.net.URI("https://kuml.dev/docs"))
                        }
                    }
                },
            )
            // P6, design review — the About text already existed in Strings; only the
            // dialog to show it was missing.
            Item(strings.menuHelpAbout, onClick = { showAboutDialog = true })
        }
        // V3.0.24 — AI panel menu
        Menu(strings.menuAi) {
            // V3.7.1 — provider/model/key configuration, first item so it's found where a
            // user reporting a crash on "no api key configured" actually looked for it.
            Item(strings.menuAiProviderSettings, onClick = { showAiProviderSettings = true })
            Separator()
            CheckboxItem(
                text = strings.aiPanelTitle,
                checked = state.aiPanelOpen,
                onCheckedChange = { state.aiPanelOpen = it },
            )
            Item(strings.aiNewSession, onClick = { aiState.newSession() })
        }
        // V3.0.13 — Tools menu
        Menu(strings.menuTools) {
            // V3.x — Live-Simulation von Zustandsautomaten im Editor. Additive to Spez. A5's
            // Esc + close-icon: the editor's RSyntaxTextArea is a heavyweight Swing component
            // that can swallow Esc before Window(onKeyEvent) ever sees it (see Main.kt), so a
            // menu item is the one route that always works.
            Item(
                strings.menuToolsSimulate,
                // Review fix — also gated on `!state.simulationStarting`: `state.simulation`
                // isn't assigned until the async start (script eval + ELK layout, 1-3s) actually
                // finishes, so without this the item stayed enabled for that whole window and a
                // second Ctrl+R started a second SimulationSession (see `startSimulation()`'s
                // KDoc-level comment and `AppState.simulationStarting`'s KDoc).
                enabled = state.simulation == null && !state.simulationStarting && state.lastDiagramSimulatable,
                shortcut = KeyShortcut(key = Key.R, ctrl = true),
                onClick = { startSimulation() },
            )
            Item(
                strings.menuToolsSimulateStop,
                enabled = state.simulation != null,
                onClick = { stopSimulation() },
            )
            Separator()
            Item(strings.menuToolsPluginManager, onClick = { showPluginManager = true })
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Hauptbereich: (Editor | Vorschau) ODER Workspace-Ansicht | AI-Panel (optional)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val ws = state.openWorkspace) {
                        is OpenWorkspace.Knowledge ->
                            KnowledgeWorkspaceScreen(
                                state = ws.state,
                                themeName = state.theme,
                                strings = strings,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                showWatermark = state.showWatermark,
                                confirmUnsavedAndThen = { action -> confirmUnsavedAndThen(action) },
                                onEditorReady = { editorActions = it },
                                onSaveResult = { result -> reportKnowledgeSaveResult(result) },
                            )
                        is OpenWorkspace.Engineering ->
                            EngineeringWorkspaceScreen(
                                state = state,
                                strings = strings,
                                scriptFiles = ws.scriptFiles,
                                confirmUnsavedAndThen = { action -> confirmUnsavedAndThen(action) },
                                onEditorReady = { editorActions = it },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        null -> {
                            // P5 — Ansichtsmodus: der ausgeblendete Bereich wird NICHT komponiert
                            // (echtes if, keine Nullbreite). Das setzt bei jedem Umschalten den
                            // JSVGCanvas/SwingPanel-State von PreviewPane zurück — das ist KEIN
                            // neuer Fehler, sondern konsistent mit dem bestehenden Verhalten:
                            // PreviewPane setzt bei jeder Änderung von state.lastSvg ohnehin ein
                            // frisches SVGDocument, der Zoom wird also heute schon bei jedem
                            // Rendern zurückgesetzt (design review, Bill Atkinson/Alan Kay).
                            if (state.viewMode != AppState.ViewMode.DIAGRAM) {
                                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    EditorPane(
                                        state = state,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onEditorReady = { editorActions = it },
                                    )
                                    // V3.7.4 (design review P8) — below the editor, never an
                                    // overlay on top of it (SwingPanel is heavyweight, see
                                    // FindBar's own KDoc).
                                    if (state.findBarOpen) {
                                        FindBar(
                                            actions = editorActions,
                                            strings = strings,
                                            onClose = { state.findBarOpen = false },
                                        )
                                    }
                                }
                            }
                            if (state.viewMode == AppState.ViewMode.SPLIT) {
                                HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                            }
                            if (state.viewMode != AppState.ViewMode.SOURCE) {
                                PreviewPane(
                                    state = state,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                    // V3.0.24 — AI panel (conditionally visible)
                    if (state.aiPanelOpen) {
                        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                        AiPanel(
                            state = aiState,
                            strings = strings,
                            onOpenProviderSettings = { showAiProviderSettings = true },
                            modifier = Modifier.width(state.aiPanelWidthPx.dp).fillMaxHeight(),
                        )
                    }
                }
                // StatusBar — reines Compose, KEIN SwingPanel → immer sichtbar
                StatusBar(state = state, showsEditor = showsEditor)
            }
        }
    }

    // V3.0.13 — Plugin Manager Dialog (conditionally visible)
    if (showPluginManager) {
        PluginManagerPane(strings = strings, onClose = { showPluginManager = false })
    }

    // V3.7.1 — AI provider settings dialog (conditionally visible)
    if (showAiProviderSettings) {
        AiProviderSettingsDialog(
            settingsStore = aiState.settingsStore,
            vault = vault,
            strings = strings,
            onClose = {
                showAiProviderSettings = false
                // ZWINGEND — sonst sieht das Panel (Header-Privacy-Badge, Provider-Auswahl)
                // die im Dialog vorgenommenen Änderungen nicht (siehe AiPanel.kt LaunchedEffect,
                // das nur beim ersten Mount feuert).
                aiState.reloadSettings()
            },
        )
    }

    // P6, design review — About dialog.
    if (showAboutDialog) {
        AboutDialog(strings = strings, onClose = { showAboutDialog = false })
    }

    // V3.6.4 — Knowledge Workspace viewer: trust gate, shown BEFORE any document
    // in the workspace is selected/rendered (i.e. before any kuml-block eval).
    pendingTrustWorkspace?.let { workspace ->
        TrustDialog(
            root = workspace.root,
            strings = strings,
            onTrust = {
                state.trustedWorkspaces.add(WorkspaceTrust.canonicalPath(workspace.root))
                scope.launch(Dispatchers.IO) { store.save(state.toSettings()) }
                pendingTrustWorkspace = null
                dispatchWorkspace(workspace)
            },
            onDecline = { pendingTrustWorkspace = null },
        )
    }

    // V3.6.4 — WorkspaceMode.UNKNOWN fallback: let the user force a mode, or cancel.
    pendingUnknownWorkspace?.let { workspace ->
        WorkspaceModeChooserDialog(
            strings = strings,
            onChooseKnowledge = {
                pendingUnknownWorkspace = null
                state.openWorkspace = OpenWorkspace.Knowledge(WorkspaceState(workspace))
            },
            onChooseEngineering = {
                pendingUnknownWorkspace = null
                val files = EngineeringFileScanner.scan(workspace.root)
                state.openWorkspace = OpenWorkspace.Engineering(root = workspace.root, scriptFiles = files)
            },
            onCancel = { pendingUnknownWorkspace = null },
        )
    }
}

/**
 * Statusleiste am unteren Rand.
 *
 * Zeigt Fehler (rot) oder Render-Status (grau). Liegt AUSSERHALB jedes SwingPanel,
 * sodass sie nie von AWT-Heavyweight-Komponenten verdeckt wird.
 */
@Composable
private fun StatusBar(
    state: AppState,
    showsEditor: Boolean,
) {
    val strings = Strings.forLanguage(state.language)
    val session = state.simulation

    // V3.x — a short-lived DiscardedSteps notice (Spez. E23) auto-dismisses back to Idle so it
    // doesn't linger and get mistaken for a persistent warning.
    LaunchedEffect(session, session?.status) {
        if (session != null && session.status is SimulationStatus.DiscardedSteps) {
            delay(4_000)
            session.clearDiscardedStepsNotice()
        }
    }

    val simLine = session?.let { simulationStatusLine(status = it.status, strings = strings) }
    val (text, color) =
        when {
            simLine != null -> simLine
            state.lastError != null -> state.lastError!! to Color(0xFFCC0000)
            state.isRendering -> strings.statusRendering to Color.Gray
            // V3.x — Nortons "deaktiviertes Element ohne Begründung ist eine Beleidigung":
            // explain why Werkzeuge ▸ Simulieren is greyed out for the current diagram.
            session == null && !state.lastDiagramSimulatable && state.lastSvg.isNotBlank() ->
                strings.simUnsupportedDiagram to Color.Gray
            state.lastSvg.isNotBlank() -> strings.statusReady to Color(0xFF228822)
            else -> strings.statusNoDiagram to Color.Gray
        }
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // P5 — view-mode segmented control, only meaningful in single-file mode and the
        // Engineering workspace (EngineeringWorkspaceScreen reads the same state.viewMode
        // for its own editor/preview weights). Review fix — the Knowledge workspace viewer
        // (KnowledgeWorkspaceScreen) never reads state.viewMode at all and always renders
        // its fixed tree|editor|SVG three-column layout, so the control is hidden rather
        // than shown-but-inert while a Knowledge workspace is open. Same `showsEditor` gate
        // used for the View ▸ Ansichtsmodus submenu above (Undo/Redo/Find use the wider
        // `showsEditorPane`, which also covers editing a Knowledge document — see its KDoc).
        if (showsEditor) {
            ViewModeSegmentedControl(state = state, strings = strings)
        }
    }
}

/**
 * Translates a [SimulationStatus] into a status-bar (text, color) pair — `null` for
 * [SimulationStatus.Idle], which falls through to the regular render-status line above it.
 */
private fun simulationStatusLine(
    status: SimulationStatus,
    strings: Strings,
): Pair<String, Color>? =
    when (status) {
        SimulationStatus.Idle -> null
        is SimulationStatus.Stayed -> strings.simStayed.format(status.reason) to Color.Gray
        is SimulationStatus.GuardFailed ->
            strings.simGuardFailed.format(status.transitionId, status.message) to Color(0xFFCC0000)
        is SimulationStatus.GuardTimeout ->
            strings.simGuardTimeout.format(status.transitionId, status.message) to Color(0xFFCC0000)
        is SimulationStatus.Error -> strings.simError.format(status.message) to Color(0xFFCC0000)
        SimulationStatus.Terminated -> strings.simTerminated to Color.Gray
        is SimulationStatus.AutoStopped -> strings.simAutoStopped.format(status.reason.name) to Color.Gray
        is SimulationStatus.DiscardedSteps -> strings.simDiscardedSteps.format(status.count) to Color.Gray
    }

/**
 * Compact three-way segmented control for [AppState.ViewMode] (P5, design review). Uses
 * [IconTooltipButton] (same tooltip styling as [dev.kuml.desktop.preview.PreviewPane]'s
 * zoom/fit strip) so all three icon-only affordances in the app share one visual language.
 */
@Composable
private fun ViewModeSegmentedControl(
    state: AppState,
    strings: Strings,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ViewModeSegment(
            mode = AppState.ViewMode.SOURCE,
            icon = KumlIcons.ViewSource,
            description = strings.viewModeSource,
            state = state,
        )
        ViewModeSegment(
            mode = AppState.ViewMode.SPLIT,
            icon = KumlIcons.ViewSplit,
            description = strings.viewModeSplit,
            state = state,
        )
        ViewModeSegment(
            mode = AppState.ViewMode.DIAGRAM,
            icon = KumlIcons.ViewDiagram,
            description = strings.viewModeDiagram,
            state = state,
        )
    }
}

// V3.7.4 (design review P5) — IconTooltipButton's tooltipPlacement parameter carries the
// experimental TooltipPlacement type in its own public signature, so every caller must opt in,
// even a call site (like this one) that never names TooltipPlacement explicitly.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewModeSegment(
    mode: AppState.ViewMode,
    icon: ImageVector,
    description: String,
    state: AppState,
) {
    val isActive = state.viewMode == mode
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
    ) {
        IconTooltipButton(
            icon = icon,
            description = description,
            onClick = { state.viewMode = mode },
        )
    }
}

/**
 * About dialog (P6, design review) — `strings.aboutTitle`/`aboutBody` already existed;
 * `Help → About` previously did nothing (`/* V3.0.12: About-Dialog */`) because no dialog
 * was ever built to show them.
 */
@Composable
private fun AboutDialog(
    strings: Strings,
    onClose: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = strings.aboutTitle,
        state = rememberDialogState(width = 420.dp, height = 220.dp),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(strings.aboutBody)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onClose) { Text(strings.dialogClose) }
                    }
                }
            }
        }
    }
}
