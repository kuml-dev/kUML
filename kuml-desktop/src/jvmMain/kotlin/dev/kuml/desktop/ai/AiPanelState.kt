package dev.kuml.desktop.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.ai.KumlAiException
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.patch.PatchDiff
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AiPanelState(
    private val appState: AppState,
    private val scope: CoroutineScope,
    // V3.7.1 — internal (not private) so AiProviderSettingsDialog can be handed the exact
    // same KumlAiSettingsStore instance/path as this panel (see MainWindow.kt wiring).
    internal val settingsStore: KumlAiSettingsStore = KumlAiSettingsStore(),
    private val vault: ApiKeyVault,
    private val conversationStore: ConversationStore = ConversationStore.default(),
    private val pricingTable: PricingTable = PricingTable.loadFromResources(),
    /** V3.1.18: when true, routes through KumlAgentOrchestrator instead of single-turn. */
    val useOrchestration: Boolean = false,
) {
    // V3.7.1 review fix: [KumlAiSettingsStore.load] throws KumlAiException.SettingsCorrupted on
    // unparsable JSON or an unknown schema version. This initializer runs from MainWindow's
    // `remember { AiPanelState(...) }` — an uncaught throw here would crash composition on
    // every app start until the file is fixed or deleted by hand. Falling back to defaults
    // keeps the app usable; AiProviderSettingsState.load() is what actually self-heals the file
    // on disk once the "AI Providers…" dialog is opened (see its KDoc).
    var aiSettings by mutableStateOf(runCatching { settingsStore.load() }.getOrDefault(KumlAiSettings()))
        private set

    var selectedProviderId by mutableStateOf(aiSettings.defaultProvider)
    var selectedModelId by mutableStateOf(
        aiSettings.defaultModels[aiSettings.defaultProvider] ?: "llama3.2",
    )

    val availableProviders: List<String> get() = aiSettings.enabledProviders.toList().sorted()

    /**
     * Model ids offered in the panel's model dropdown ([ProviderModelPicker]) for the currently
     * selected provider.
     *
     * V3.7.1 review fix: this used to be JUST [PricingTable.modelsForProvider]'s static list.
     * For a dynamic-catalog provider (Ollama, Gonka) the user's actual configured default model
     * — set via a free-text field in the "AI Providers" dialog — is very often NOT one of
     * pricing.json's few suggested entries (Gonka has no pricing.json entry at all, so its list
     * is empty). [ProviderModelPicker]'s provider-switch handler falls back to
     * `availableModels.firstOrNull()` whenever [KumlAiSettings.defaultModels] has no entry for
     * the newly selected provider yet — with only the static list, that fallback would silently
     * overwrite a validly configured free-text model with "" (Gonka) or a wrong hard-coded
     * suggestion (Ollama). Prepending the configured default (when it isn't already in the
     * static list) keeps it selectable and keeps it the fallback value.
     */
    val availableModels: List<String>
        get() {
            val fromPricing = pricingTable.modelsForProvider(selectedProviderId)
            val configuredDefault = aiSettings.defaultModels[selectedProviderId]
            return if (!configuredDefault.isNullOrBlank() && configuredDefault !in fromPricing) {
                listOf(configuredDefault) + fromPricing
            } else {
                fromPricing
            }
        }

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    var sessionId by mutableStateOf(UUID.randomUUID().toString())
        private set
    private val sessionCreatedAt = System.currentTimeMillis()

    var isRunning by mutableStateOf(false)
        private set
    private var currentJob: Job? = null

    private val usageTracker = TokenUsageTracker(pricingTable)
    var tokensIn by mutableStateOf(0)
        private set
    var tokensOut by mutableStateOf(0)
        private set
    var estimatedCostUsd by mutableStateOf(0.0)
        private set

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // ── V3.0.25: Patch Preview ────────────────────────────────────────────────

    /** A single pending patch ready to preview in [PatchPreviewDialog]. */
    data class PendingPatchView(
        val patchId: String,
        val kind: String,
        val diff: PatchDiff,
    )

    private var editingContext: AgentEditingContext = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
    private var patchEngine: PatchApplyEngine = createEngine()

    private val _pendingPatches = MutableStateFlow<List<PendingPatchView>>(emptyList())
    val pendingPatches: StateFlow<List<PendingPatchView>> = _pendingPatches.asStateFlow()

    var showPatchDialog by mutableStateOf(false)
        private set
    var isApplying by mutableStateOf(false)
        private set

    private fun createEngine(): PatchApplyEngine = PatchApplyEngine(context = editingContext, traceSink = AppStateAiTraceSink())

    // ── Conversation ──────────────────────────────────────────────────────────

    fun send(userText: String) {
        if (isRunning || userText.isBlank()) return
        appendMessage(ConversationMessage.User(id = uuid(), timestamp = now(), text = userText.trim()))
        runAgent()
    }

    fun stop() {
        currentJob?.cancel()
        isRunning = false
    }

    fun newSession() {
        stop()
        persistCurrentSession()
        sessionId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        _pendingPatches.value = emptyList()
        showPatchDialog = false
        usageTracker.reset()
        tokensIn = 0
        tokensOut = 0
        estimatedCostUsd = 0.0
        scope.launch(Dispatchers.IO) {
            editingContext = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            patchEngine = createEngine()
        }
    }

    fun reloadSettings() {
        // V3.7.1 review fix: called from MainWindow's AiProviderSettingsDialog onClose callback
        // and from AiPanel's `LaunchedEffect(Unit)` — neither has an exception handler of its
        // own, so an uncaught KumlAiException.SettingsCorrupted (e.g. the user hand-edited
        // ai-settings.json and left it unparsable) would crash straight out of a Compose click
        // handler / recomposition. Falling back to the settings already held keeps the panel
        // usable; AiProviderSettingsState.load() is what actually self-heals the file on disk.
        aiSettings = runCatching { settingsStore.load() }.getOrDefault(aiSettings)
        if (selectedProviderId !in aiSettings.enabledProviders) {
            // Currently selected provider was disabled (or never enabled) — fall back to the
            // configured default provider/model pair.
            selectedProviderId = aiSettings.defaultProvider
            selectedModelId = aiSettings.defaultModels[selectedProviderId] ?: "llama3.2"
        } else {
            // Provider is still enabled, but its default model may have changed in the dialog
            // (e.g. a Freitext model entry for Ollama). Pick that up so the panel's dropdown
            // reflects the change without requiring an app restart.
            aiSettings.defaultModels[selectedProviderId]?.let { updatedModel ->
                if (updatedModel != selectedModelId) {
                    selectedModelId = updatedModel
                }
            }
        }
    }

    // ── Patch Preview Actions ─────────────────────────────────────────────────

    fun dismissPatchDialog() {
        showPatchDialog = false
    }

    suspend fun acceptOne(patchId: String) {
        if (isApplying) return
        isApplying = true
        try {
            patchEngine.applyOne(patchId)
            updateScriptFromModel()
            refreshPendingPatches()
            if (_pendingPatches.value.isEmpty()) withContext(Dispatchers.Main) { showPatchDialog = false }
        } finally {
            isApplying = false
        }
    }

    suspend fun rejectOne(patchId: String) {
        patchEngine.rejectOne(patchId = patchId)
        refreshPendingPatches()
        if (_pendingPatches.value.isEmpty()) withContext(Dispatchers.Main) { showPatchDialog = false }
    }

    suspend fun acceptAll() {
        if (isApplying) return
        isApplying = true
        try {
            val ids = patchEngine.pendingPatchIds()
            ids.forEach { patchEngine.applyOne(it) }
            updateScriptFromModel()
            withContext(Dispatchers.Main) {
                _pendingPatches.value = emptyList()
                showPatchDialog = false
            }
        } finally {
            isApplying = false
        }
    }

    suspend fun rejectAll() {
        patchEngine.rejectAll()
        updateScriptFromModel() // Script shows pre-session snapshot
        withContext(Dispatchers.Main) {
            _pendingPatches.value = emptyList()
            showPatchDialog = false
        }
    }

    // ── Agent execution ───────────────────────────────────────────────────────

    private fun runAgent() {
        currentJob?.cancel()
        currentJob =
            scope.launch(Dispatchers.IO) {
                isRunning = true
                try {
                    val executor = KumlAiExecutor.fromSettings(settings = aiSettings, vault = vault)
                    executor.use {
                        val runner =
                            AgentRunner(
                                executor = executor,
                                providerId = selectedProviderId,
                                modelId = selectedModelId,
                                editingContext = editingContext,
                                patchEngine = patchEngine,
                                useOrchestration = useOrchestration,
                            )
                        runner.runConversation(_messages.value).collect { ev -> handleEvent(ev) }
                        persistCurrentSession()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // user-initiated stop — do not emit error
                } catch (e: Throwable) {
                    emitError(e)
                } finally {
                    withContext(Dispatchers.Main) { isRunning = false }
                }
            }
    }

    private suspend fun handleEvent(ev: AgentEvent) =
        withContext(Dispatchers.Main) {
            when (ev) {
                is AgentEvent.AssistantDelta -> appendOrUpdateStreaming(delta = ev.delta, providerId = ev.providerId, modelId = ev.modelId)
                is AgentEvent.ToolCallStart ->
                    appendMessage(
                        ConversationMessage.ToolCall(id = ev.callId, timestamp = now(), toolName = ev.tool, argsJson = ev.argsJson),
                    )
                is AgentEvent.ToolCallEnd -> updateToolCallEnd(callId = ev.callId, resultJson = ev.resultJson, isError = ev.isError)
                is AgentEvent.TokenUsage -> {
                    usageTracker.accumulate(providerId = ev.providerId, modelId = ev.modelId, inTok = ev.inTok, outTok = ev.outTok)
                    tokensIn = usageTracker.tokensIn
                    tokensOut = usageTracker.tokensOut
                    estimatedCostUsd = usageTracker.costUsd
                }
                is AgentEvent.Done -> finalizeStreaming()
                is AgentEvent.Error -> emitError(ev.throwable)
                is AgentEvent.PatchBuffered -> {
                    scope.launch(Dispatchers.IO) {
                        refreshPendingPatches()
                        withContext(Dispatchers.Main) { showPatchDialog = true }
                    }
                }
                // V3.1.18: orchestration trace events — append a lightweight info message
                is AgentEvent.OrchestratorRouted ->
                    appendMessage(
                        ConversationMessage.Assistant(
                            id = uuid(),
                            timestamp = now(),
                            text = "[Orchestrator] Routing to ${ev.domain} specialist — ${ev.reason}",
                            isStreaming = false,
                            providerId = "orchestrator",
                            modelId = selectedModelId,
                        ),
                    )
                is AgentEvent.SpecialistStarted ->
                    appendMessage(
                        ConversationMessage.Assistant(
                            id = uuid(),
                            timestamp = now(),
                            text = "[Orchestrator] ${ev.domain.uppercase()} specialist started.",
                            isStreaming = false,
                            providerId = "orchestrator",
                            modelId = selectedModelId,
                        ),
                    )
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun appendMessage(msg: ConversationMessage) {
        _messages.value = _messages.value + msg
    }

    private fun appendOrUpdateStreaming(
        delta: String,
        providerId: String,
        modelId: String,
    ) {
        val last = _messages.value.lastOrNull()
        if (last is ConversationMessage.Assistant && last.isStreaming) {
            _messages.value = _messages.value.dropLast(1) +
                last.copy(text = last.text + delta)
        } else {
            appendMessage(
                ConversationMessage.Assistant(
                    id = uuid(),
                    timestamp = now(),
                    text = delta,
                    isStreaming = true,
                    providerId = providerId,
                    modelId = modelId,
                ),
            )
        }
    }

    private fun finalizeStreaming() {
        val last = _messages.value.lastOrNull()
        if (last is ConversationMessage.Assistant && last.isStreaming) {
            _messages.value = _messages.value.dropLast(1) + last.copy(isStreaming = false)
        }
    }

    private fun updateToolCallEnd(
        callId: String,
        resultJson: String,
        isError: Boolean,
    ) {
        _messages.value = _messages.value.map { msg ->
            if (msg is ConversationMessage.ToolCall && msg.id == callId) {
                msg.copy(state = if (isError) ToolCallState.FAILED else ToolCallState.SUCCESS)
            } else {
                msg
            }
        } + ConversationMessage.ToolResult(id = uuid(), timestamp = now(), toolCallId = callId, resultJson = resultJson, isError = isError)
    }

    private fun emitError(t: Throwable) {
        val (msg, cause) = mapError(t)
        appendMessage(ConversationMessage.ErrorMessage(id = uuid(), timestamp = now(), message = msg, cause = cause))
        scope.launch { _toasts.emit(msg) }
    }

    internal fun mapError(t: Throwable): Pair<String, String?> =
        when (t) {
            is KumlAiException.PrivacyModeViolation ->
                "Privacy-Modus: Cloud-Anbieter blockiert" to "PrivacyModeViolation"
            is KumlAiException.MissingApiKey ->
                "API-Key fehlt für $selectedProviderId" to "MissingApiKey"
            is KumlAiException.BudgetExceeded -> {
                val msg = "Kostenbudget erreicht — spent ${"%.4f".format(t.spentUsd)} of ${"%.2f".format(t.budgetUsd)} limit"
                msg to "BudgetExceeded"
            }
            else ->
                when {
                    t.message?.contains("timeout", ignoreCase = true) == true ->
                        "Zeitüberschreitung beim KI-Provider" to "Timeout"
                    t.message?.contains("rate", ignoreCase = true) == true ->
                        "Rate-Limit erreicht — bitte kurz warten" to "RateLimit"
                    else -> (t.message ?: "Unbekannter Fehler") to t.javaClass.simpleName
                }
        }

    private suspend fun refreshPendingPatches() {
        val ids = patchEngine.pendingPatchIds()
        val views =
            ids.mapNotNull { id ->
                runCatching { patchEngine.diff(id) }.getOrNull()?.let { diff ->
                    PendingPatchView(patchId = id, kind = diff.elementChanges.firstOrNull()?.kind ?: "patch", diff = diff)
                }
            }
        withContext(Dispatchers.Main) { _pendingPatches.value = views }
    }

    private suspend fun updateScriptFromModel() {
        val model = editingContext.resolveModel()
        val dsl = ScriptSerializer.toDsl(model)
        withContext(Dispatchers.Main) {
            appState.script = dsl
            appState.isDirty = true
        }
    }

    private fun persistCurrentSession() {
        val conv =
            Conversation(
                sessionId = sessionId,
                createdAt = sessionCreatedAt,
                updatedAt = now(),
                providerId = selectedProviderId,
                modelId = selectedModelId,
                messages = _messages.value,
                totalTokensIn = tokensIn,
                totalTokensOut = tokensOut,
                totalCostUsd = estimatedCostUsd,
            )
        scope.launch(Dispatchers.IO) { conversationStore.save(conv) }
    }

    companion object {
        fun uuid(): String = UUID.randomUUID().toString()

        fun now(): Long = System.currentTimeMillis()
    }
}
