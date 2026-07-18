package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings

/**
 * Shown when [dev.kuml.workspace.WorkspaceMode] resolves to `UNKNOWN` — neither
 * `*.kuml.kts` files nor an `index.md` + other Markdown files were found (V3.6.4,
 * ADR-0011 fallback rule). Lets the user force-open the directory as either mode,
 * or cancel and stay in the single-file view.
 *
 * ADR-0011 makes Knowledge Workspace the deliberate "hero mode" for kUML
 * Desktop — a strategic call the button styling didn't previously reflect
 * (both choices rendered as identical plain [TextButton]s). Knowledge now
 * gets the filled primary [Button]; Engineering, still fully supported, gets
 * an [OutlinedButton] one visual step down.
 */
@Composable
fun WorkspaceModeChooserDialog(
    strings: Strings,
    onChooseKnowledge: () -> Unit,
    onChooseEngineering: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(strings.workspaceUnknownTitle) },
        text = { Text(strings.workspaceUnknownMessage) },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onChooseKnowledge) { Text(strings.workspaceOpenAsKnowledge) }
                OutlinedButton(
                    onClick = onChooseEngineering,
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(strings.workspaceOpenAsEngineering) }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(strings.workspaceOpenCancel) }
        },
    )
}
