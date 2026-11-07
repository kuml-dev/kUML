package dev.kuml.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.preview.LatexMode
import dev.kuml.desktop.preview.latexDialogHint

/**
 * What the LaTeX export dialog shows: the mode that is preselected (the one confirmed last time) and, from the
 * first render, how many diagrams the script holds. Pure data so the hint logic needs no Compose.
 */
internal data class LatexDialogState(
    val initialMode: LatexMode,
    val diagramCount: Int,
    val firstDiagramName: String?,
)

/**
 * Modal choice between a complete document and the bare figure. There is deliberately no "do not ask again":
 * the remembered choice is preselected, and Enter confirms it. Cancel is on the left, "Export…" on the right and
 * the default button; Esc cancels. Own OS window like the other dialogs (a Compose popup would be hidden behind
 * the heavyweight editor and preview components).
 */
@Composable
internal fun LatexExportDialog(
    state: LatexDialogState,
    strings: Strings,
    onConfirm: (LatexMode) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember(state) { mutableStateOf(state.initialMode) }
    val hint = latexDialogHint(diagramCount = state.diagramCount, firstDiagramName = state.firstDiagramName, strings = strings)
    DialogWindow(
        onCloseRequest = onCancel,
        title = strings.latexExport.dialogTitle,
        state = rememberDialogState(width = 520.dp, height = if (hint == null) 290.dp else 360.dp),
        onKeyEvent = { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.Escape -> {
                    onCancel()
                    true
                }
                (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isCtrlPressed -> {
                    onConfirm(mode)
                    true
                }
                else -> false
            }
        },
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeRow(
                            selected = mode == LatexMode.STANDALONE,
                            label = strings.latexExport.standaloneLabel,
                            explanation = strings.latexExport.standaloneHint,
                            onSelect = { mode = LatexMode.STANDALONE },
                        )
                        ModeRow(
                            selected = mode == LatexMode.SNIPPET,
                            label = strings.latexExport.snippetLabel,
                            explanation = strings.latexExport.snippetHint,
                            onSelect = { mode = LatexMode.SNIPPET },
                        )
                        if (hint != null) {
                            Text(hint, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onCancel) { IconLabel(action = ButtonAction.CANCEL, text = strings.latexExport.cancel) }
                        Button(
                            onClick = { onConfirm(mode) },
                        ) { IconLabel(action = ButtonAction.CONFIRM, text = strings.latexExport.export) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    label: String,
    explanation: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
