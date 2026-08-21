package dev.kuml.desktop.ai.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.i18n.Strings
import kotlinx.coroutines.launch

/**
 * V3.7.1 — "AI Providers" settings dialog.
 *
 * Every row change (enable/disable, default provider/model, API key save/change/delete,
 * privacy mode) persists immediately through [AiProviderSettingsState] — there is no
 * separate Apply/OK step, only [Strings.dialogClose] at the bottom.
 *
 * [settingsStore] must be the SAME instance the calling [dev.kuml.desktop.ai.AiPanelState]
 * uses (see `MainWindow.kt` wiring) so both sides always agree on the persisted file.
 */
@Composable
internal fun AiProviderSettingsDialog(
    settingsStore: KumlAiSettingsStore,
    vault: ApiKeyVault,
    strings: Strings,
    onClose: () -> Unit,
) {
    val state = remember { AiProviderSettingsState(settingsStore = settingsStore, vault = vault) }
    // Purely for orchestrating the close sequence below — every actual settings mutation goes
    // through state.launchTracked() on the state's OWN scope (see its KDoc), never through this
    // one, so an in-flight persist is never cancelled just because the dialog is closing.
    val closeScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { state.load() }

    // V3.7.2 review fix ("Privacy-Kontrolle wirkungslos, Race"): `onClose` triggers
    // AiPanelState.reloadSettings() in MainWindow.kt, which reads ai-settings.json straight back
    // in. If that happens while a just-toggled privacy mode (or any other row change) is still
    // being written, the panel reloads a stale pre-write snapshot — the dialog showed "privacy
    // on", the panel silently keeps a cloud provider enabled. Waiting for
    // state.awaitPendingWrites() first guarantees every write this dialog kicked off has actually
    // landed on disk before the caller ever finds out the dialog closed.
    //
    // V3.7.3 review fix ("awaitPendingWrites snapshot race"): state.beginClosing() runs
    // synchronously, BEFORE the first suspension point, so every row control below is already
    // disabled (state.isClosing) by the time this function returns — no new state.launchTracked()
    // call can be started by a click that lands after this point. Combined with
    // awaitPendingWrites()'s own drain loop, this closes both ends of the race where a
    // late-arriving job would otherwise be silently cancelled by state.dispose() instead of ever
    // reaching disk. The isClosing guard also makes requestClose() itself idempotent — a second
    // click (e.g. the window-close request firing right after the Close button) is a no-op.
    fun requestClose() {
        if (state.isClosing) return
        state.beginClosing()
        closeScope.launch {
            state.awaitPendingWrites()
            onClose()
        }
    }

    // Cancels the state's background scope once the dialog actually leaves composition. Safe by
    // construction: onDispose only fires from the recomposition triggered by requestClose()'s
    // onClose() call (which flips `showAiProviderSettings` to false in MainWindow.kt) — and by
    // then awaitPendingWrites() above has already returned, so nothing is left in flight to cut
    // off. Fixes the resource leak where this scope was previously never cancelled at all.
    DisposableEffect(state) { onDispose { state.dispose() } }

    DialogWindow(
        onCloseRequest = ::requestClose,
        title = strings.aiProviderSettingsHeadline,
        state = rememberDialogState(width = 720.dp, height = 560.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(text = strings.aiProviderSettingsHeadline, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))

                    // ── Privacy frame (a frame around the whole dialog, not a sixth list row) ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = state.privacyMode,
                            enabled = !state.isClosing,
                            onCheckedChange = { checked -> state.requestPrivacyMode(enabled = checked) },
                        )
                        Text(strings.aiPrivacyModeLabel)
                    }
                    Text(
                        text = strings.aiPrivacyModeHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.rows, key = { it.id }) { row ->
                            ProviderSettingsRow(row = row, state = state, strings = strings)
                            HorizontalDivider()
                        }
                    }

                    if (state.vaultIsFallback) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = strings.aiVaultPlainWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(enabled = !state.isClosing, onClick = ::requestClose) { Text(strings.dialogClose) }
                    }
                }
            }
        }
    }

    // ── One-time confirmation before turning privacy mode OFF ──────────────────────
    // Turning it back ON never asks — see AiProviderSettingsState.requestPrivacyMode.
    if (state.privacyConfirmPending) {
        AlertDialog(
            onDismissRequest = { state.cancelPrivacyDisable() },
            title = { Text(strings.aiPrivacyConfirmTitle) },
            text = { Text(strings.aiPrivacyConfirmBody) },
            confirmButton = {
                TextButton(
                    enabled = !state.isClosing,
                    onClick = { state.launchTracked { state.confirmPrivacyDisable() } },
                ) {
                    Text(strings.aiPrivacyConfirmAccept)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.cancelPrivacyDisable() }) {
                    Text(strings.aiPrivacyConfirmCancel)
                }
            },
        )
    }
}

@Composable
private fun ProviderSettingsRow(
    row: ProviderRow,
    state: AiProviderSettingsState,
    strings: Strings,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = row.isEnabled,
                enabled = row.checkboxEnabled && !state.isClosing,
                onCheckedChange = { checked -> state.launchTracked { state.setEnabled(providerId = row.id, enabled = checked) } },
            )
            Text(row.displayName, modifier = Modifier.weight(1f))
            ProviderBadge(text = if (row.isLocal) strings.aiProviderLocal else strings.aiProviderCloud)
            RadioButton(
                selected = row.isDefault,
                enabled = row.isEnabled && row.checkboxEnabled && !state.isClosing,
                onClick = { state.launchTracked { state.setDefaultProvider(providerId = row.id) } },
            )
            Text(strings.aiDefaultProvider, style = MaterialTheme.typography.bodySmall)

            if (row.hasDynamicCatalog) {
                var draftModel by remember(row.id) { mutableStateOf(row.selectedModel) }
                OutlinedTextField(
                    value = draftModel,
                    onValueChange = { draftModel = it },
                    label = { Text(strings.aiDefaultModel) },
                    singleLine = true,
                    enabled = !state.isClosing,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { state.launchTracked { state.setDefaultModel(providerId = row.id, modelId = draftModel) } },
                        ),
                    modifier =
                        Modifier.width(160.dp).onFocusChanged { focusState ->
                            if (!focusState.isFocused && draftModel != row.selectedModel) {
                                state.launchTracked { state.setDefaultModel(providerId = row.id, modelId = draftModel) }
                            }
                        },
                )
            } else {
                var modelMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(enabled = !state.isClosing, onClick = { modelMenuExpanded = true }) {
                        Text(row.selectedModel.ifBlank { strings.aiDefaultModel }, maxLines = 1)
                    }
                    DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                        row.models.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                enabled = !state.isClosing,
                                onClick = {
                                    state.launchTracked { state.setDefaultModel(providerId = row.id, modelId = modelId) }
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // A custom-SPI provider (row.needsKey = !isLocal, so this can be true for one) always
        // has koogProvider == null (sealed LLMProvider — see ProviderRegistry's KDoc), which
        // makes AiProviderSettingsState.saveApiKey() a silent no-op. Showing the key field for
        // such a NOT_EXECUTABLE row would let the user type a key, click Save, watch the field
        // clear — and nothing gets persisted, with no feedback that it didn't. Not reachable
        // with today's shipped providers (all five built-ins are executable), but guards the
        // moment a future non-local custom SPI provider is registered.
        if (row.needsKey && row.lockReason != ProviderLockReason.NOT_EXECUTABLE) {
            ApiKeyRow(row = row, state = state, strings = strings)
        }

        if (row.lockReason != ProviderLockReason.NONE) {
            Text(
                text = lockReasonMessage(reason = row.lockReason, strings = strings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApiKeyRow(
    row: ProviderRow,
    state: AiProviderSettingsState,
    strings: Strings,
) {
    // Editing state resets per provider id, not on every recomposition — remember(row.id)
    // means a key already typed mid-edit survives unrelated row recompositions but a
    // fresh id (a different provider's row instance) always starts from row.hasKey.
    var editingKey by remember(row.id) { mutableStateOf(!row.hasKey) }
    // The typed key lives ONLY here, reset to "" the instant it's handed to saveApiKey —
    // never stored on AiProviderSettingsState/ProviderRow, never logged, never thrown in
    // an exception message (see the V3.7.1 plan's security checklist).
    var keyDraft by remember(row.id) { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row.hasKey && !editingKey) {
            Text("••••••••", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !state.isClosing, onClick = { editingKey = true }) { Text(strings.aiKeyChange) }
            TextButton(
                enabled = !state.isClosing,
                onClick = { state.launchTracked { state.deleteApiKey(providerId = row.id) } },
            ) {
                Text(strings.aiKeyDelete)
            }
        } else {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                placeholder = { Text(strings.aiKeyPlaceholder) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.isClosing,
                modifier = Modifier.width(220.dp),
            )
            Button(
                enabled = !state.isClosing,
                onClick = {
                    val toSave = keyDraft
                    keyDraft = ""
                    editingKey = false
                    state.launchTracked { state.saveApiKey(providerId = row.id, apiKey = toSave) }
                },
            ) { Text(strings.aiKeySave) }
        }
    }
}

private fun lockReasonMessage(
    reason: ProviderLockReason,
    strings: Strings,
): String =
    when (reason) {
        ProviderLockReason.NEEDS_KEY -> strings.aiProviderNeedsKey
        ProviderLockReason.BLOCKED_BY_PRIVACY -> strings.aiProviderBlockedByPrivacy
        ProviderLockReason.NOT_EXECUTABLE -> strings.aiProviderNoModels
        ProviderLockReason.NEEDS_MODEL -> strings.aiProviderNeedsModel
        ProviderLockReason.NONE -> ""
    }

/** Small badge chip — style analogous to the update-badge in PluginManagerPane.kt. */
@Composable
private fun ProviderBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
