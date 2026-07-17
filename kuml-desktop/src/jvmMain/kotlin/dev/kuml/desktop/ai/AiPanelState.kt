package dev.kuml.desktop.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.ai.KumlAiException
import dev.kuml.ai.KumlAiExecutor
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
    private val settingsStore: KumlAiSettingsStore = KumlAiSettingsStore(),
    private val vault: ApiKeyVault,
    private val conversationStore: ConversationStore = ConversationStore.default(),
    private val pricingTable: PricingTable = PricingTable.loadFromResources(),
    /** V3.1.18: when true, routes through KumlAgentOrchestrator instead of single-turn. */
    val useOrchestration: Boolean = false,
) {
    var aiSettings by mutableStateOf(settingsStore.load())
        private set

    var selectedProviderId by mutableStateOf(aiSettings.defaultProvider)
    var selectedModelId by mutableStateOf(
        aiSettings.defaultModels[aiSettings.defaultProvider] ?: "llama3.2",
    )

    val availableProviders: List<String> get() = aiSettings.enabledProviders.toList().sorted()
    val availableModels: List<String> get() = pricingTable.modelsForProvider(selectedProviderId)

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

    private var editingContext: AgentEditingContext = AgentEditingContext(AnyKumlModel.emptyUml())
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
        appendMessage(ConversationMessage.User(uuid(), now(), userText.trim()))
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
            editingContext = AgentEditingContext(AnyKumlModel.emptyUml())
            patchEngine = createEngine()
        }
    }

    fun reloadSettings() {
        aiSettings = settingsStore.load()
        if (selectedProviderId !in aiSettings.enabledProviders) {
            selectedProviderId = aiSettings.defaultProvider
            selectedModelId = aiSettings.defaultModels[selectedProviderId] ?: "llama3.2"
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
        patchEngine.rejectOne(patchId)
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
                    val executor = KumlAiExecutor.fromSettings(aiSettings, vault)
                    val runner =
                        AgentRunner(
                            executor,
                            selectedProviderId,
                            selectedModelId,
                            editingContext,
                            patchEngine,
                            useOrchestration = useOrchestration,
                        )
                    runner.runConversation(_messages.value).collect { ev -> handleEvent(ev) }
                    persistCurrentSession()
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
                is AgentEvent.AssistantDelta -> appendOrUpdateStreaming(ev.delta, ev.providerId, ev.modelId)
                is AgentEvent.ToolCallStart ->
                    appendMessage(
                        ConversationMessage.ToolCall(ev.callId, now(), ev.tool, ev.argsJson),
                    )
                is AgentEvent.ToolCallEnd -> updateToolCallEnd(ev.callId, ev.resultJson, ev.isError)
                is AgentEvent.TokenUsage -> {
                    usageTracker.accumulate(ev.providerId, ev.modelId, ev.inTok, ev.outTok)
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
        } + ConversationMessage.ToolResult(uuid(), now(), callId, resultJson, isError)
    }

    private fun emitError(t: Throwable) {
        val (msg, cause) = mapError(t)
        appendMessage(ConversationMessage.ErrorMessage(uuid(), now(), msg, cause))
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
                    PendingPatchView(id, diff.elementChanges.firstOrNull()?.kind ?: "patch", diff)
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
