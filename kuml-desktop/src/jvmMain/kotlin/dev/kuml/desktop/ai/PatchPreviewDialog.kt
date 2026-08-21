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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.desktop.preview.parseSvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.swing.JSVGCanvas
import javax.swing.SwingUtilities

/** Fixed height of the turn-mode SVG preview strip above the patch list (design review, 3.4). */
private val TURN_PREVIEW_HEIGHT = 240.dp

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
    /**
     * V3.2.x — turn-based confirmation (design review 3.4): when non-null, this dialog is
     * showing the ONE real diagram change the AI made this turn (direct tool-calling path,
     * see [AgentRunner]) rather than a list of individually accept/reject-able legacy
     * [dev.kuml.ai.tools.context.ModelPatch] guesses. Renders as a diagram preview above the
     * patch list, and [PatchPreviewCard] hides its per-item Accept/Reject buttons — only the
     * footer's "Alle übernehmen" / "Alle ablehnen" apply.
     */
    previewSvg: String? = null,
    /**
     * Review fix: whether the dialog is in turn-confirmation mode — decides whether
     * per-item Accept/Reject buttons are hidden, and MUST be independent of whether
     * [previewSvg] actually rendered. `previewSvg == null` used to double as this signal, so a
     * turn whose model mutated for real but then FAILED to render (see
     * [AiPanelState.checkForTurnPatches]) fell back to showing per-item buttons wired to the
     * legacy [dev.kuml.ai.tools.patch.PatchApplyEngine] buffer — which turn-mode patches were
     * never buffered into, so accepting/rejecting one silently no-opped while closing the
     * dialog, losing the confirmation entirely. Callers pass `turnPatches.isNotEmpty()`.
     */
    isTurnMode: Boolean = previewSvg != null,
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
                    if (previewSvg != null) {
                        TurnPreviewSvg(svg = previewSvg, modifier = Modifier.fillMaxWidth().height(TURN_PREVIEW_HEIGHT))
                        Divider()
                    } else if (isTurnMode) {
                        // Review fix: the turn's tool calls mutated the model for real even
                        // though rendering the preview failed — say so instead of silently
                        // showing an empty strip, since the confirmation below still applies
                        // to a real change the user cannot currently see rendered.
                        Surface(Modifier.fillMaxWidth()) {
                            Text(
                                "Vorschau nicht verfügbar — das Modell wurde dennoch geändert. " +
                                    "„Alle übernehmen“/„Alle ablehnen“ entscheiden über diese Änderung.",
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Divider()
                    }
                    // Patch list
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(pendingPatches, key = { it.patchId }) { view ->
                            PatchPreviewCard(
                                view = view,
                                isApplying = isApplying,
                                allowPerItemActions = !isTurnMode,
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

/**
 * Renders [svg] into a fixed-height Batik canvas — the turn-mode preview shown above the
 * patch list in [PatchPreviewDialog]. Lightweight sibling of [dev.kuml.desktop.preview.PreviewPane]:
 * no zoom/fit toolbar, since this is a read-only confirmation preview, not an editing surface.
 */
@Composable
private fun TurnPreviewSvg(
    svg: String,
    modifier: Modifier,
) {
    val canvas = remember { JSVGCanvas() }
    LaunchedEffect(svg) {
        val doc = withContext(Dispatchers.IO) { parseSvg(svg) }
        if (doc != null) {
            SwingUtilities.invokeLater { canvas.setSVGDocument(doc) }
        }
    }
    SwingPanel(factory = { canvas }, modifier = modifier)
}

@Composable
private fun PatchPreviewCard(
    view: AiPanelState.PendingPatchView,
    isApplying: Boolean,
    allowPerItemActions: Boolean = true,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colors.primaryVariant, shape = MaterialTheme.shapes.small) {
                    Text(
                        view.kind,
                        color = Color.White,
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
            // Before / After — skipped in turn mode: the diagram preview above the list
            // already shows the "Nachher" state, and there is no per-patch "Vorher" to show
            // (the whole turn's tool calls already landed in editingContext together).
            if (allowPerItemActions) {
                Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiffBox(label = "Vorher", text = view.diff.before.text, modifier = Modifier.weight(1f))
                    DiffBox(label = "Nachher", text = view.diff.after.text, modifier = Modifier.weight(1f))
                }
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
            // Actions — hidden in turn mode: only the dialog footer's "Alle übernehmen" /
            // "Alle ablehnen" apply (design review, 3.4 — one confirmation per AI turn, not
            // per individual change within it).
            if (allowPerItemActions) {
                Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun DiffBox(
    label: String,
    text: String,
    modifier: Modifier,
) {
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
internal fun kindPrefix(kind: String): String =
    when (kind) {
        "added" -> "[+]"
        "removed" -> "[-]"
        else -> "[~]"
    }

/** Maps a change kind to its display color. Used by [PatchPreviewCard] and tests. */
internal fun kindColor(kind: String): Color =
    when (kind) {
        "added" -> Color(0xFF2e7d32)
        "removed" -> Color(0xFFc62828)
        else -> Color(0xFF1565c0)
    }

/** Footer warning text — defined as a val for testability. */
internal val FOOTER_WARNING_TEXT: String =
    "Hinweis: „Alle ablehnen“ rollt auch bereits übernommene Patches zurück."
