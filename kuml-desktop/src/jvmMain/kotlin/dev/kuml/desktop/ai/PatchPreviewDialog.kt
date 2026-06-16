package dev.kuml.desktop.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.ai.tools.patch.PatchDiff

@Composable
fun PatchPreviewDialog(
    pendingPatches: List<AiPanelState.PendingPatchView>,
    isVisible: Boolean,
    isApplying: Boolean,
    onAcceptOne: (String) -> Unit,
    onRejectOne: (String) -> Unit,
    onAcceptAll: () -> Unit,
    onRejectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "KI-Vorschläge prüfen (${pendingPatches.size} Patch${if (pendingPatches.size != 1) "es" else ""})",
        state = rememberDialogState(width = 800.dp, height = 620.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // Patch list
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(pendingPatches, key = { it.patchId }) { view ->
                            PatchPreviewCard(
                                view = view,
                                isApplying = isApplying,
                                onAccept = { onAcceptOne(view.patchId) },
                                onReject = { onRejectOne(view.patchId) },
                            )
                        }
                    }
                    Divider()
                    // Footer
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            FOOTER_WARNING_TEXT,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRejectAll, enabled = !isApplying) {
                                Text("Alle ablehnen")
                            }
                            Button(onClick = onAcceptAll, enabled = !isApplying) {
                                Text("Alle übernehmen")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("Schließen") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchPreviewCard(
    view: AiPanelState.PendingPatchView,
    isApplying: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colors.primaryVariant, shape = MaterialTheme.shapes.small) {
                    Text(
                        view.kind, color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    view.patchId.take(12) + "…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.Gray,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Before / After
            Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiffBox("Vorher", view.diff.before.text, Modifier.weight(1f))
                DiffBox("Nachher", view.diff.after.text, Modifier.weight(1f))
            }
            // Element changes
            if (view.diff.elementChanges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column {
                    view.diff.elementChanges.forEach { change ->
                        val prefix = kindPrefix(change.kind)
                        val color = kindColor(change.kind)
                        val detail = listOfNotNull(change.before, change.after).joinToString(" → ")
                        Text(
                            "$prefix ${change.elementId}" + (if (detail.isNotEmpty()) ": $detail" else ""),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, enabled = !isApplying, modifier = Modifier.height(32.dp)) {
                    Text("Übernehmen", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onReject, enabled = !isApplying, modifier = Modifier.height(32.dp)) {
                    Text("Ablehnen", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DiffBox(label: String, text: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.caption, color = Color.Gray)
        Surface(
            color = Color(0xFFF5F5F5),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxSize(),
        ) {
            val scroll = rememberScrollState()
            SelectionContainer {
                Text(
                    text.ifBlank { "(leer)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxSize().padding(6.dp).verticalScroll(scroll),
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

// ── Pure logic helpers (testable without Compose) ─────────────────────────────

/** Maps a change kind to its prefix string. Used by [PatchPreviewCard] and tests. */
internal fun kindPrefix(kind: String): String = when (kind) {
    "added" -> "[+]"
    "removed" -> "[-]"
    else -> "[~]"
}

/** Maps a change kind to its display color. Used by [PatchPreviewCard] and tests. */
internal fun kindColor(kind: String): Color = when (kind) {
    "added" -> Color(0xFF2e7d32)
    "removed" -> Color(0xFFc62828)
    else -> Color(0xFF1565c0)
}

/** Footer warning text — defined as a val for testability. */
internal val FOOTER_WARNING_TEXT: String =
    "Hinweis: „Alle ablehnen“ rollt auch bereits übernommene Patches zurück."
