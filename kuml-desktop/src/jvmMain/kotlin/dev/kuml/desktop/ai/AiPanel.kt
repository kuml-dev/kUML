package dev.kuml.desktop.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.ai.tools.patch.ElementChange
import dev.kuml.ai.tools.patch.PatchDiff
import dev.kuml.ai.tools.patch.apply.ModelSnippet
import dev.kuml.desktop.ai.components.AiFooter
import dev.kuml.desktop.ai.components.ConversationPane
import dev.kuml.desktop.ai.components.InputPane
import dev.kuml.desktop.ai.components.ProviderModelPicker
import dev.kuml.desktop.i18n.Strings
import kotlinx.coroutines.launch

@Composable
fun AiPanel(
    state: AiPanelState,
    strings: Strings,
    onOpenProviderSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { state.reloadSettings() }
    val messages by state.messages.collectAsState()
    val pendingPatches by state.pendingPatches.collectAsState()
    val turnPatches by state.turnPatches.collectAsState()
    val turnPreviewSvg by state.turnPreviewSvg.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(8.dp)) {
        // V3.7.1 — two-line header (a single line has no room for provider+model picker,
        // a privacy badge, "Providers…" AND "New session" in the 420dp default panel width).
        Column(Modifier.fillMaxWidth()) {
            // Line 1 — selection + state indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderModelPicker(state)
                Spacer(Modifier.weight(1f))
                if (state.aiSettings.privacyMode) {
                    PrivacyBadge(label = strings.aiPrivacyBadge)
                }
            }
            Spacer(Modifier.height(4.dp))
            // Line 2 — actions, spatially separated from the selection row above (its own
            // row + divider below, not just adjacency) so it doesn't read as part of the picker.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenProviderSettings) { Text(strings.aiManageProviders) }
                Spacer(Modifier.weight(1f))
                // Only shown once there's something to start over from — an empty chat has
                // nothing for "new session" to reset.
                if (messages.isNotEmpty()) {
                    TextButton(onClick = { state.newSession() }) { Text(strings.aiNewSession) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        ConversationPane(
            messages = messages,
            // weight(1f) instead of fillMaxHeight avoids macOS Stutter
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(6.dp))
        InputPane(
            isRunning = state.isRunning,
            strings = strings,
            onSend = { state.send(it) },
            onStop = { state.stop() },
        )
        AiFooter(
            tokensIn = state.tokensIn,
            tokensOut = state.tokensOut,
            costUsd = state.estimatedCostUsd,
            budgetUsd = state.aiSettings.costBudgetUsd,
        )
    }

    // V3.0.25 — Patch preview dialog (rendered outside the Column so it floats above everything)
    // V3.2.x: when the AI turn actually mutated the model via real tool calls (see
    // AgentRunner), turnPatches/turnPreviewSvg are non-empty and take over the dialog in
    // turn-confirmation mode (design review, 3.4) — the legacy per-patch pendingPatches view
    // is shown only for the (currently never-activated) orchestrator path.
    PatchPreviewDialog(
        pendingPatches = if (turnPatches.isNotEmpty()) turnPatches.mapIndexed { i, s -> s.toPendingPatchView(i) } else pendingPatches,
        previewSvg = turnPreviewSvg,
        // Review fix: turn-mode is decided by whether this turn had real tool-call mutations
        // (turnPatches non-empty), never by whether the preview SVG happened to render — see
        // PatchPreviewDialog's isTurnMode KDoc.
        isTurnMode = turnPatches.isNotEmpty(),
        isVisible = state.showPatchDialog,
        isApplying = state.isApplying,
        onAcceptOne = { id -> scope.launch { state.acceptOne(id) } },
        onRejectOne = { id -> scope.launch { state.rejectOne(id) } },
        onAcceptAll = { scope.launch { state.acceptAll() } },
        onRejectAll = { scope.launch { state.rejectAll() } },
        onDismiss = { state.dismissPatchDialog() },
    )
}

/**
 * Maps a [AiPanelState.TurnPatchSummary] to a [AiPanelState.PendingPatchView] so
 * [PatchPreviewDialog] can render it through the same list — with `previewSvg` non-null,
 * the dialog itself skips the Vorher/Nachher boxes and per-item Accept/Reject buttons, so
 * only [PatchDiff.elementChanges] (used for the summary line) actually matters here.
 */
private fun AiPanelState.TurnPatchSummary.toPendingPatchView(index: Int): AiPanelState.PendingPatchView {
    val patchId = "turn-patch-$index"
    return AiPanelState.PendingPatchView(
        patchId = patchId,
        kind = kind,
        diff =
            PatchDiff(
                patchId = patchId,
                before = ModelSnippet(elementIds = emptyList(), text = ""),
                after = ModelSnippet(elementIds = emptyList(), text = ""),
                elementChanges = listOf(ElementChange(elementId = description, kind = kind)),
            ),
    )
}

/** Small badge chip shown while privacy mode is on — style analogous to PluginManagerPane's update badge. */
@Composable
private fun PrivacyBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
