package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.scaffold.WorkspaceScaffolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "New Workspace…" modal (V3.x, FT-Desktop-New-Workspace — UI/UX design-team review
 * 2026-09-08). Lets the user scaffold a fresh OKF workspace (Knowledge or Engineering mode)
 * from directly inside kUML Desktop, without dropping to a terminal for
 * `kuml workspace init`. Once created, [onCreated] hands the new directory straight to the
 * caller's existing "open a workspace directory" path (trust gate + mode dispatch included) —
 * this dialog does not special-case what happens after scaffolding succeeds.
 *
 * [chooseParentDir] is a callback rather than a direct [dev.kuml.desktop.io.FileMenu] call so
 * this composable stays free of the `java.awt.Window` parent handle `FileMenu`'s Swing dialogs
 * need — that coupling lives in the caller (`MainWindow`), which already owns `windowHandle`.
 *
 * The name field turns its error state on only once the user has typed into it and then left
 * it blank (`nameTouched` flips on the first `onValueChange`, not on true focus-loss) — a
 * deliberate simplification: Compose Desktop's `AlertDialog` content has no established
 * focus-loss pattern elsewhere in this module, and this reads correctly for the dominant case
 * (freshly opened dialog stays neutral; type-then-clear turns red), differing from a strict
 * "visited and blank" definition only for the rare "click in, click out without typing" path.
 */
@Composable
fun NewWorkspaceDialog(
    strings: Strings,
    initialParentDir: File,
    chooseParentDir: (File) -> File?,
    onCreated: (File) -> Unit,
    onCancel: () -> Unit,
) {
    var form by remember { mutableStateOf(NewWorkspaceFormState(parentDir = initialParentDir)) }
    var isCreating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun create() {
        form = form.copy(runtimeError = null)
        isCreating = true
        scope.launch {
            val spec = form.toSpec()
            val dir = form.targetDir
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        WorkspaceScaffolder.scaffold(spec = spec, targetDir = dir, force = false, echo = {})
                        dir
                    }
                }
            isCreating = false
            result
                .onSuccess { onCreated(it) }
                .onFailure { e -> form = form.copy(runtimeError = e.message ?: e.javaClass.simpleName) }
        }
    }

    AlertDialog(
        modifier = Modifier.width(560.dp),
        onDismissRequest = onCancel,
        title = { Text(strings.newWorkspaceTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it, nameTouched = true, runtimeError = null) },
                    label = { Text(strings.newWorkspaceNameLabel) },
                    supportingText = {
                        val message =
                            if (form.nameTouched && form.error == NewWorkspaceError.NAME_EMPTY) {
                                strings.newWorkspaceNameEmpty
                            } else {
                                strings.newWorkspaceNameHint
                            }
                        Text(message)
                    },
                    isError = form.nameTouched && form.error == NewWorkspaceError.NAME_EMPTY,
                    singleLine = true,
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    strings.newWorkspaceModeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ModeOption(
                    selected = form.mode == NewWorkspaceMode.KNOWLEDGE,
                    title = strings.newWorkspaceModeKnowledge,
                    hint = strings.newWorkspaceModeKnowledgeHint,
                    enabled = !isCreating,
                    onClick = { form = form.copy(mode = NewWorkspaceMode.KNOWLEDGE, runtimeError = null) },
                )
                ModeOption(
                    selected = form.mode == NewWorkspaceMode.ENGINEERING,
                    title = strings.newWorkspaceModeEngineering,
                    hint = strings.newWorkspaceModeEngineeringHint,
                    enabled = !isCreating,
                    onClick = { form = form.copy(mode = NewWorkspaceMode.ENGINEERING, runtimeError = null) },
                )

                Text(
                    strings.newWorkspaceTargetLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = form.targetDir.absolutePath,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { chooseParentDir(form.parentDir)?.let { form = form.copy(parentDir = it, runtimeError = null) } },
                        enabled = !isCreating,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(strings.newWorkspaceBrowse) }
                }

                val errorMessage =
                    when (form.error) {
                        NewWorkspaceError.TARGET_NOT_EMPTY -> strings.newWorkspaceTargetNotEmpty
                        NewWorkspaceError.WRITE_FAILED -> strings.newWorkspaceFailed.format(form.runtimeError)
                        NewWorkspaceError.NAME_EMPTY, null -> null
                    }
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = ::create, enabled = form.canCreate && !isCreating) { Text(strings.newWorkspaceCreate) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isCreating) { Text(strings.newWorkspaceCancel) }
        },
    )
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    hint: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Column {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
