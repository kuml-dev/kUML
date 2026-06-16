package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.ai.AiPanelState

@Composable
fun ProviderModelPicker(state: AiPanelState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Provider dropdown
        var pExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { pExpanded = true }) {
                Text(state.selectedProviderId, maxLines = 1)
            }
            DropdownMenu(expanded = pExpanded, onDismissRequest = { pExpanded = false }) {
                state.availableProviders.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p) },
                        onClick = {
                            state.selectedProviderId = p
                            state.selectedModelId = state.availableModels.firstOrNull() ?: ""
                            pExpanded = false
                        },
                    )
                }
            }
        }
        // Model dropdown
        var mExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { mExpanded = true }) {
                Text(state.selectedModelId.ifBlank { "Model" }, maxLines = 1)
            }
            DropdownMenu(expanded = mExpanded, onDismissRequest = { mExpanded = false }) {
                state.availableModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            state.selectedModelId = m
                            mExpanded = false
                        },
                    )
                }
            }
        }
    }
}
