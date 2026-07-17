package dev.kuml.desktop.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.ai.components.AiFooter
import dev.kuml.desktop.ai.components.ConversationPane
import dev.kuml.desktop.ai.components.InputPane
import dev.kuml.desktop.ai.components.ProviderModelPicker
import kotlinx.coroutines.launch

@Composable
fun AiPanel(
    state: AiPanelState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { state.reloadSettings() }
    val messages by state.messages.collectAsState()
    val pendingPatches by state.pendingPatches.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ProviderModelPicker(state)
            IconButton(onClick = { state.newSession() }) { Text("✚") }
        }
        Spacer(Modifier.height(6.dp))
        ConversationPane(
            messages = messages,
            // weight(1f) instead of fillMaxHeight avoids macOS Stutter
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(6.dp))
        InputPane(
            isRunning = state.isRunning,
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
    PatchPreviewDialog(
        pendingPatches = pendingPatches,
        isVisible = state.showPatchDialog,
        isApplying = state.isApplying,
        onAcceptOne = { id -> scope.launch { state.acceptOne(id) } },
        onRejectOne = { id -> scope.launch { state.rejectOne(id) } },
        onAcceptAll = { scope.launch { state.acceptAll() } },
        onRejectAll = { scope.launch { state.rejectAll() } },
        onDismiss = { state.dismissPatchDialog() },
    )
}
