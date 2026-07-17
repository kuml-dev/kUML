package dev.kuml.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.ai.AiPanel
import dev.kuml.desktop.ai.AiPanelState
import dev.kuml.desktop.editor.EditorPane
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.AppSettingsStore
import dev.kuml.desktop.io.FileMenu
import dev.kuml.desktop.io.UnsavedChoice
import dev.kuml.desktop.plugins.PluginManagerPane
import dev.kuml.desktop.preview.PreviewPane
import dev.kuml.desktop.render.DesktopRenderController
import dev.kuml.desktop.state.rememberAppSettingsBinding
import dev.kuml.desktop.workspace.EngineeringFileScanner
import dev.kuml.desktop.workspace.EngineeringWorkspaceScreen
import dev.kuml.desktop.workspace.KnowledgeWorkspaceScreen
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.TrustDialog
import dev.kuml.desktop.workspace.WorkspaceModeChooserDialog
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val controller = remember(scope) { DesktopRenderController(state, scope) }
    val aiState = remember { AiPanelState(appState = state, scope = scope, vault = vault) }
    var showPluginManager by remember { mutableStateOf(false) }

    // V3.6.4 — Knowledge Workspace viewer: pending dialogs gate opening a workspace.
    var pendingTrustWorkspace by remember { mutableStateOf<OkfWorkspace?>(null) }
    var pendingUnknownWorkspace by remember { mutableStateOf<OkfWorkspace?>(null) }

    rememberAppSettingsBinding(state = state, store = store)

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
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
                state.openWorkspace = OpenWorkspace.Engineering(workspace.root, files)
            }
            WorkspaceMode.UNKNOWN -> pendingUnknownWorkspace = workspace
        }
    }

    fun openWorkspaceDirectory(dir: File) {
        scope.launch {
            val workspace = withContext(Dispatchers.IO) { WorkspaceScanner.scan(dir) }
            if (WorkspaceTrust.isTrusted(state.trustedWorkspaces, dir)) {
                dispatchWorkspace(workspace)
            } else {
                pendingTrustWorkspace = workspace
            }
        }
    }

    fun saveCurrentFile(): Boolean {
        val file = state.currentFile
        return if (file != null) {
            FileMenu.writeScript(file, state.script)
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
                FileMenu.writeScript(chosen, state.script)
                state.markSaved(chosen)
                true
            } else {
                false
            }
        }
    }

    fun confirmUnsavedAndThen(action: () -> Unit) {
        if (!state.isDirty) {
            action()
            return
        }
        val choice = FileMenu.confirmUnsaved(parent = windowHandle, strings = strings)
        when (choice) {
            UnsavedChoice.SAVE -> {
                if (saveCurrentFile()) action()
            }
            UnsavedChoice.DISCARD -> action()
            UnsavedChoice.CANCEL -> { /* do nothing */ }
        }
    }

    MenuBar {
        Menu(strings.menuFile) {
            Item(strings.menuFileNew, onClick = {
                confirmUnsavedAndThen {
                    state.script = ""
                    state.currentFile = null
                    state.isDirty = false
                }
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
                        state.loadFrom(file, FileMenu.readScript(file))
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
                Item(strings.menuFileCloseWorkspace, onClick = { state.openWorkspace = null })
            }
            Item(strings.menuFileSave, onClick = { saveCurrentFile() })
            Item(strings.menuFileSaveAs, onClick = {
                val chosen =
                    FileMenu.chooseSave(
                        parent = windowHandle,
                        initialDir = state.lastDir?.let { File(it) },
                        suggestedName = state.currentFile?.name ?: "diagram.kuml.kts",
                        strings = strings,
                    )
                if (chosen != null) {
                    FileMenu.writeScript(chosen, state.script)
                    state.markSaved(chosen)
                }
            })
            Separator()
            Menu(strings.menuFileRecent) {
                if (state.recentFiles.isEmpty()) {
                    Item(strings.menuFileRecentEmpty, enabled = false, onClick = {})
                } else {
                    state.recentFiles.toList().forEach { path ->
                        Item(File(path).name, onClick = {
                            val file = File(path)
                            if (file.exists()) {
                                state.loadFrom(file, FileMenu.readScript(file))
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
                    store.save(state.toSettings())
                    onQuit()
                }
            })
        }
        Menu(strings.menuEdit) {
            Item(strings.menuEditUndo, onClick = { /* V3.0.11 */ })
            Item(strings.menuEditRedo, onClick = { /* V3.0.11 */ })
            Separator()
            Item(strings.menuEditFind, onClick = { /* V3.0.11 */ })
        }
        Menu(strings.menuView) {
            val themeNames = remember { ThemeRegistry.names().ifEmpty { listOf("plain", "dark", "blueprint") } }
            Menu(strings.menuViewTheme) {
                themeNames.forEach { name ->
                    Item(name.replaceFirstChar { it.uppercase() }, onClick = { state.theme = name })
                }
            }
            Menu(strings.menuViewLanguage) {
                Item("Deutsch", onClick = { state.language = "de" })
                Item("English", onClick = { state.language = "en" })
            }
        }
        Menu(strings.menuHelp) {
            Item(strings.menuHelpDocs, onClick = { /* open https://kuml.dev/docs */ })
            Item(strings.menuHelpAbout, onClick = { /* V3.0.12: About-Dialog */ })
        }
        // V3.0.24 — AI panel menu
        Menu(strings.menuAi) {
            CheckboxItem(
                text = strings.aiPanelTitle,
                checked = state.aiPanelOpen,
                onCheckedChange = { state.aiPanelOpen = it },
            )
            Item(strings.aiNewSession, onClick = { aiState.newSession() })
        }
        // V3.0.13 — Tools menu
        Menu(strings.menuTools) {
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
                            )
                        is OpenWorkspace.Engineering ->
                            EngineeringWorkspaceScreen(
                                state = state,
                                controller = controller,
                                scriptFiles = ws.scriptFiles,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        null -> {
                            EditorPane(
                                state = state,
                                controller = controller,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                            PreviewPane(
                                state = state,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    // V3.0.24 — AI panel (conditionally visible)
                    if (state.aiPanelOpen) {
                        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                        AiPanel(
                            state = aiState,
                            modifier = Modifier.width(state.aiPanelWidthPx.dp).fillMaxHeight(),
                        )
                    }
                }
                // StatusBar — reines Compose, KEIN SwingPanel → immer sichtbar
                StatusBar(state = state)
            }
        }
    }

    // V3.0.13 — Plugin Manager Dialog (conditionally visible)
    if (showPluginManager) {
        PluginManagerPane(onClose = { showPluginManager = false })
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
                state.openWorkspace = OpenWorkspace.Engineering(workspace.root, files)
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
private fun StatusBar(state: AppState) {
    val strings = Strings.forLanguage(state.language)
    val (text, color) =
        when {
            state.lastError != null -> state.lastError!! to Color(0xFFCC0000)
            state.isRendering -> strings.statusRendering to Color.Gray
            state.lastSvg.isNotBlank() -> strings.statusReady to Color(0xFF228822)
            else -> strings.statusNoDiagram to Color.Gray
        }
    HorizontalDivider()
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
