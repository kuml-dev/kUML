package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.ai.AiPanelState
import dev.kuml.desktop.i18n.Strings

@Composable
fun ProviderModelPicker(
    state: AiPanelState,
    strings: Strings,
) {
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
                            // Prefer the provider's own configured default model (set via the
                            // "AI Providers" dialog, e.g. a free-text Ollama/Gonka model id)
                            // over the first entry of the static pricing.json list — that list
                            // is empty for Gonka and only a few suggestions for Ollama, so
                            // falling straight to firstOrNull() silently discards a validly
                            // configured model the moment the user opens this dropdown (V3.7.1
                            // review fix).
                            state.selectedModelId = state.aiSettings.defaultModels[p] ?: state.availableModels.firstOrNull() ?: ""
                            // P3 — switching TO Ollama kicks off a real /api/tags fetch (no-op
                            // for every other provider, see refreshOllamaModelsIfNeeded's guard).
                            state.refreshOllamaModelsIfNeeded()
                            pExpanded = false
                        },
                    )
                }
            }
        }
        // Model dropdown
        var mExpanded by remember { mutableStateOf(false) }
        val ollamaModelListState by state.ollamaModelListState.collectAsState()
        Box {
            OutlinedButton(onClick = { mExpanded = true }) {
                Text(state.selectedModelId.ifBlank { "Model" }, maxLines = 1)
            }
            DropdownMenu(expanded = mExpanded, onDismissRequest = { mExpanded = false }) {
                // P3 (design review — "kein stiller Rückfall"): for Ollama, the dropdown no
                // longer renders state.availableModels (pricing.json's static suggestion list,
                // which has nothing to do with what is actually pulled on the user's machine) —
                // it renders the REAL, live-fetched catalog instead, with an honest Loading/
                // Unavailable state rather than silently falling back to a wrong static list.
                if (state.selectedProviderId == "ollama") {
                    when (val ollamaState = ollamaModelListState) {
                        is AiPanelState.OllamaModelListState.Loading ->
                            DropdownMenuItem(text = { Text(strings.aiOllamaModelsLoading) }, enabled = false, onClick = {})
                        is AiPanelState.OllamaModelListState.Unavailable -> {
                            DropdownMenuItem(
                                text = { Text(strings.aiOllamaModelsUnavailable.format(ollamaState.reason)) },
                                enabled = false,
                                onClick = {},
                            )
                            DropdownMenuItem(text = { Text(strings.aiOllamaPullHint) }, enabled = false, onClick = {})
                        }
                        is AiPanelState.OllamaModelListState.Loaded ->
                            ollamaState.modelIds.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        state.selectedModelId = m
                                        mExpanded = false
                                    },
                                )
                            }
                    }
                } else {
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
}
