package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.cli.workspace.LatexWorkspaceKind
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

private const val PHASE_RENDERING = 0
private const val PHASE_WRITING = 1
private const val PHASE_CANCELLED = 2

/**
 * "Export Workspace > As LaTeX…" dialog. One form (output folder, strict, theme), one fixed status area above the
 * buttons. The work is done by [runWorkspaceLatexExportSafely] on a background thread; this composable holds state and
 * layout only. Rendering holds the shared evaluation gate, so it never runs concurrently with an MCP render. Cancelling
 * is possible while the diagrams are rendered (the result is then discarded), not while files are written.
 */
@Composable
fun WorkspaceLatexExportDialog(
    strings: Strings,
    workspaceRoot: File,
    trusted: Boolean,
    themeName: String,
    themeNames: List<String>,
    evalGate: Semaphore,
    initialDir: File?,
    chooseSaveDirectory: (initialDir: File?) -> File?,
    canOpenFolder: Boolean,
    openFolder: (File) -> Unit,
    copyToClipboard: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Whether the selected document has unsaved edits; the export reads the files on disk. */
    documentDirty: Boolean = false,
) {
    val s = strings.workspaceLatexExport
    var form by remember { mutableStateOf(WorkspaceLatexFormState.initial(root = workspaceRoot, themeName = themeName)) }
    var ui by remember { mutableStateOf<LatexUiState>(LatexUiState.Idle) }
    var pending by remember { mutableStateOf<LatexPendingWrite?>(null) }
    var kind by remember { mutableStateOf<LatexWorkspaceKind?>(null) }
    var kindKnown by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    // A run only applies its result while it is still the current one and the dialog is open.
    val runId = remember { AtomicInteger(0) }
    // 0 = rendering, 1 = writing, 2 = cancelled. One compare-and-set decides between "write" and "cancel".
    val phase = remember { AtomicInteger(PHASE_RENDERING) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { runId.incrementAndGet() } }
    LaunchedEffect(workspaceRoot) {
        kind = withContext(Dispatchers.IO) { detectLatexWorkspaceKind(workspaceRoot) }
        kindKnown = true
    }

    val view = latexStatusView(ui)
    val problem = form.problem(workspaceRoot)

    fun startExport() {
        pending = null
        val id = runId.incrementAndGet()
        val current = form
        phase.set(PHASE_RENDERING)
        ui = LatexUiState.Preparing
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    runWorkspaceLatexExportSafely(
                        root = workspaceRoot,
                        form = current,
                        evalGate = evalGate,
                        isCancelled = { runId.get() != id },
                        onWriting = {
                            val mayWrite = runId.get() == id && phase.compareAndSet(PHASE_RENDERING, PHASE_WRITING)
                            if (mayWrite) ui = LatexUiState.Writing
                            mayWrite
                        },
                    )
                }
            if (runId.get() == id) {
                pending = outcome.pending
                ui = outcome.state
            }
        }
    }

    fun overwrite() {
        val p = pending ?: return
        pending = null
        val id = runId.incrementAndGet()
        ui = LatexUiState.Writing
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { overwriteWorkspaceLatexExportSafely(p) }
            if (runId.get() == id) {
                pending = outcome.pending
                ui = outcome.state
            }
        }
    }

    WindowAlertDialog(
        title = s.title,
        onDismissRequest = onDismiss,
        width = 680.dp,
        height = 600.dp,
        content = {
            Column {
                if (!trusted) {
                    Text(strings.pdfUntrustedReason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    text =
                        when {
                            !kindKnown -> s.modeDetecting
                            kind is LatexWorkspaceKind.Knowledge -> s.modeKnowledge
                            else -> s.modeEngineering
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (documentDirty && kind is LatexWorkspaceKind.Knowledge) {
                    Text(s.unsavedHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = form.targetPath,
                        onValueChange = { form = form.copy(targetPath = it) },
                        label = { Text(s.targetLabel) },
                        isError = problem != null,
                        singleLine = true,
                        enabled = !view.blocksPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { chooseSaveDirectory(initialDir)?.let { form = form.copy(targetPath = it.path) } },
                        enabled = !view.blocksPrimary(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { IconLabel(action = ButtonAction.BROWSE, text = s.browse) }
                }
                when (problem) {
                    LatexTargetProblem.EMPTY_OR_INVALID ->
                        Text(s.targetInvalid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    LatexTargetProblem.IS_WORKSPACE_ROOT ->
                        Text(s.targetIsRoot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(
                        checked = form.strict,
                        onCheckedChange = { form = form.copy(strict = it) },
                        enabled = !view.blocksPrimary(),
                    )
                    Text(s.strictLabel, modifier = Modifier.weight(1f))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(s.themeLabel)
                    Box {
                        // no-icon: MENU_TRIGGER
                        OutlinedButton(onClick = { themeMenu = true }, enabled = !view.blocksPrimary()) {
                            Text(form.themeName.replaceFirstChar { it.uppercase() } + " ▾")
                        }
                        DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                            themeNames.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name.replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        form = form.copy(themeName = name)
                                        themeMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        status = {
            WorkspaceLatexStatusArea(
                view = view,
                strings = strings,
                canOpenFolder = canOpenFolder,
                onShowFolder = { (ui as? LatexUiState.Done)?.let { ui = showLatexFolder(done = it, openFolder = openFolder) } },
                onCopyReport = { (ui as? LatexUiState.Done)?.let { copyToClipboard(latexReportText(it)) } },
                onOverwrite = { overwrite() },
                onKeepExisting = {
                    pending = null
                    ui = LatexUiState.Idle
                },
            )
        },
        confirmButton = {
            Button(
                onClick = { startExport() },
                enabled = trusted && problem == null && !view.blocksPrimary(),
            ) {
                IconLabel(action = ButtonAction.EXPORT_LATEX, text = s.exportButton)
            }
        },
        dismissButton = {
            // While rendering this cancels the run (its result is discarded); while writing it is disabled.
            TextButton(
                onClick = {
                    if (view.canCancel() && phase.compareAndSet(PHASE_RENDERING, PHASE_CANCELLED)) {
                        runId.incrementAndGet()
                        ui = LatexUiState.Cancelled
                    } else if (!view.canCancel()) {
                        onDismiss()
                    }
                },
                enabled = !(view is LatexStatusView.Working && !view.canCancel()),
            ) {
                IconLabel(
                    action = if (view is LatexStatusView.Done) ButtonAction.CLOSE else ButtonAction.CANCEL,
                    text = if (view is LatexStatusView.Done) s.close else s.cancel,
                )
            }
        },
    )
}
