package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.ai.KumlAiException
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.BuiltInProviders
import dev.kuml.ai.provider.OllamaModelListResult
import dev.kuml.ai.provider.fetchOllamaModelIds
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.Snapshot
import dev.kuml.ai.tools.context.fromKumlDiagram
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.patch.PatchDiff
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
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
import java.io.File
import java.util.UUID
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

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
    /**
     * Renders a turn's resulting model to SVG for the confirmation dialog preview.
     * Defaults to the real [DesktopRenderPipeline]; injectable so tests can exercise
     * [checkForTurnPatches]'s success AND render-failure branches deterministically,
     * without paying for a full ELK/theme render pipeline per test case.
     */
    private val renderFn: (String, String) -> DesktopRenderResult = { script, themeName ->
        // V3.7.4 (design review P9) — the confirmation-dialog preview should match the main
        // preview/export WYSIWYG-wise: if the user has the watermark toggle on, a patch
        // preview rendered without it would look subtly different from what gets applied.
        // The seam signature itself stays (String, String) -> DesktopRenderResult (unchanged)
        // so AiPanelStateTest/AiPanelStatePatchTest's existing test doubles need no changes —
        // only this default implementation closes over `appState`.
        DesktopRenderPipeline.render(script = script, themeName = themeName, watermark = appState.showWatermark)
    },
    /**
     * Test-only seam (analogous to [renderFn]): overrides the execution function passed to
     * [AgentRunner], letting a regression test drive a full [send] → [handleEvent] → [messages]
     * round-trip against a stubbed model response instead of only exercising [AgentRunner] in
     * isolation. Null (the default) means [AgentRunner] uses [KumlAiExecutor.execute] as usual.
     */
    internal val agentExecutorFn: (suspend (Prompt, LLModel) -> Message.Assistant)? = null,
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

    // ── P3 — real Ollama model list (replaces the static pricing.json suggestion list) ──────

    /** State of the Ollama-only model list fetched live from `/api/tags`. See [ollamaModelListState]. */
    sealed class OllamaModelListState {
        data object Loading : OllamaModelListState()

        data class Loaded(
            val modelIds: List<String>,
        ) : OllamaModelListState()

        data class Unavailable(
            val reason: String,
        ) : OllamaModelListState()
    }

    private val _ollamaModelListState = MutableStateFlow<OllamaModelListState>(OllamaModelListState.Loading)

    /**
     * Read by [dev.kuml.desktop.ai.components.ProviderModelPicker] whenever [selectedProviderId]
     * is `"ollama"`: replaces the previous silent fallback to [availableModels] (which for
     * Ollama is just pricing.json's static suggestion list, not what is actually pulled on the
     * user's machine) with a real, live-fetched catalog — see [refreshOllamaModelsIfNeeded].
     */
    val ollamaModelListState: StateFlow<OllamaModelListState> = _ollamaModelListState.asStateFlow()

    /** Cached across calls within one provider-selection so re-opening the dropdown doesn't re-fetch every time. */
    private var ollamaModelsCache: List<String>? = null

    /**
     * Test-only seam (analogous to [renderFn]/[agentExecutorFn]): overrides the network call
     * [refreshOllamaModelsIfNeeded] makes, so a test can drive Loading/Loaded/Unavailable without
     * a real local Ollama process. Defaults to the real [BuiltInProviders.fetchOllamaModelIds].
     */
    internal var ollamaModelFetcher: suspend () -> OllamaModelListResult = { BuiltInProviders.fetchOllamaModelIds() }

    /**
     * Fetches the real, currently-pulled Ollama model ids in the background. A no-op when
     * [selectedProviderId] isn't `"ollama"` — switching to any other provider must not fire a
     * network call. `force = true` bypasses [ollamaModelsCache] (used by a manual refresh
     * affordance, if one is ever added — not wired to any UI control yet).
     */
    fun refreshOllamaModelsIfNeeded(force: Boolean = false) {
        if (selectedProviderId != "ollama") return
        val cached = ollamaModelsCache
        if (cached != null && !force) {
            _ollamaModelListState.value = OllamaModelListState.Loaded(cached)
            return
        }
        _ollamaModelListState.value = OllamaModelListState.Loading
        scope.launch(Dispatchers.IO) {
            when (val result = ollamaModelFetcher()) {
                is OllamaModelListResult.Success -> {
                    ollamaModelsCache = result.modelIds
                    withContext(Dispatchers.Main) { _ollamaModelListState.value = OllamaModelListState.Loaded(result.modelIds) }
                }
                is OllamaModelListResult.Failure ->
                    withContext(Dispatchers.Main) { _ollamaModelListState.value = OllamaModelListState.Unavailable(result.reason) }
            }
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

    // internal (not private): AiPanelStatePatchTest exercises the turn-confirmation flow
    // directly against this field — see seedEditingContextFromScript/checkForTurnPatches KDoc.
    internal var editingContext: AgentEditingContext = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
    private var patchEngine: PatchApplyEngine = createEngine()

    private val _pendingPatches = MutableStateFlow<List<PendingPatchView>>(emptyList())
    val pendingPatches: StateFlow<List<PendingPatchView>> = _pendingPatches.asStateFlow()

    var showPatchDialog by mutableStateOf(false)
        private set
    var isApplying by mutableStateOf(false)
        private set

    private fun createEngine(): PatchApplyEngine = PatchApplyEngine(context = editingContext, traceSink = AppStateAiTraceSink())

    // ── V3.2.x — Real tool-calling: turn-based confirmation ───────────────────
    // (Fund 4, design review: the legacy PatchBuffered/patchEngine buffer above stays
    // wired for the — currently never-activated — orchestrator path only. The direct
    // AgentRunner path mutates editingContext for real inside each @Tool call, so its
    // pending-change surface is read straight from editingContext.patches() instead.)

    /** A single mutation the AI applied for real during the just-finished turn. */
    data class TurnPatchSummary(
        val kind: String,
        val description: String,
    )

    /**
     * Snapshot taken right before dispatching the current turn — used to undo it on reject.
     * internal (not private): set up directly by [AiPanelStatePatchTest] to test rejectAll's
     * turn-mode rollback without needing a full agent run.
     */
    internal var lastTurnSnapshot: Snapshot? = null

    /**
     * Whether [updateScriptFromModel] is allowed to overwrite [appState].script this turn.
     * False when [seedEditingContextFromScript] could not parse the currently open script —
     * writing back in that case would silently clobber content the AI never actually saw.
     * internal (not private): asserted directly by [AiPanelStatePatchTest].
     */
    internal var canWriteScript: Boolean = true

    /**
     * Size of [AgentEditingContext.patches] captured right before the current turn started.
     * internal (not private): set up directly by [AiPanelStatePatchTest] to drive
     * [checkForTurnPatches] without a full agent run.
     */
    internal var patchCountBeforeTurn: Int = 0

    private val _turnPatches = MutableStateFlow<List<TurnPatchSummary>>(emptyList())
    val turnPatches: StateFlow<List<TurnPatchSummary>> = _turnPatches.asStateFlow()

    private val _turnPreviewSvg = MutableStateFlow<String?>(null)
    val turnPreviewSvg: StateFlow<String?> = _turnPreviewSvg.asStateFlow()

    // ── Conversation ──────────────────────────────────────────────────────────

    fun send(userText: String) {
        // Review fix (race condition): a pending PatchPreviewDialog confirmation — turn mode
        // OR legacy buffered-patch mode — MUST block a new turn. Without this guard, starting
        // turn 2 while turn 1's (non-modal) dialog is still open re-seeds editingContext from
        // the still-unmodified script (see seedEditingContextFromScript), silently detaching
        // the dialog from the state it is showing: confirming it afterwards would then act on
        // turn 2's context instead, discarding turn 1's real mutations without any warning.
        if (isRunning || showPatchDialog || userText.isBlank()) return
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
        // V3.2.x: editingContext is no longer seeded here — it is re-derived from the
        // currently open script at the start of every turn (see seedEditingContextFromScript()),
        // so newSession() has nothing useful to seed in advance. Fund 1 fix: the previous
        // seed-once-per-session behaviour is what let an accepted/rejected AI turn silently
        // diverge from the script the user was actually looking at.
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

    /**
     * Confirms the pending changes shown in [PatchPreviewDialog].
     *
     * Turn mode (`_turnPatches` non-empty — the direct AgentRunner tool-loop already
     * mutated [editingContext] for real): writes the resulting model back to the script
     * and re-baselines [patchEngine] so a later [rejectAll] cannot undo an already-confirmed
     * turn. Legacy mode (orchestrator path, `_turnPatches` empty): unchanged pre-V3.2
     * behaviour — applies every buffered [ModelPatch] via [patchEngine].
     */
    suspend fun acceptAll() {
        if (isApplying) return
        isApplying = true
        try {
            if (_turnPatches.value.isNotEmpty()) {
                updateScriptFromModel()
                patchEngine = createEngine()
                withContext(Dispatchers.Main) {
                    _turnPatches.value = emptyList()
                    _turnPreviewSvg.value = null
                    showPatchDialog = false
                }
            } else {
                val ids = patchEngine.pendingPatchIds()
                ids.forEach { patchEngine.applyOne(it) }
                updateScriptFromModel()
                withContext(Dispatchers.Main) {
                    _pendingPatches.value = emptyList()
                    showPatchDialog = false
                }
            }
        } finally {
            isApplying = false
        }
    }

    /**
     * Discards the pending changes shown in [PatchPreviewDialog].
     *
     * Turn mode: rolls [editingContext] back to [lastTurnSnapshot] — undoing only this
     * turn's direct `ctx.applyPatch()` mutations, not the whole session. Legacy mode:
     * unchanged pre-V3.2 behaviour (rolls back to the pre-session snapshot via
     * [PatchApplyEngine.rejectAll]).
     *
     * Fund 1 fix: this no longer calls [updateScriptFromModel] — rejecting must never
     * write anything back to [appState].script.
     */
    suspend fun rejectAll() {
        if (_turnPatches.value.isNotEmpty()) {
            lastTurnSnapshot?.let { editingContext.resetTo(it) }
            withContext(Dispatchers.Main) {
                _turnPatches.value = emptyList()
                _turnPreviewSvg.value = null
                showPatchDialog = false
            }
        } else {
            patchEngine.rejectAll()
            withContext(Dispatchers.Main) {
                _pendingPatches.value = emptyList()
                showPatchDialog = false
            }
        }
    }

    // ── Agent execution ───────────────────────────────────────────────────────

    private fun runAgent() {
        currentJob?.cancel()
        currentJob =
            scope.launch(Dispatchers.IO) {
                isRunning = true
                try {
                    seedEditingContextFromScript()
                    lastTurnSnapshot = editingContext.snapshot()
                    patchCountBeforeTurn = editingContext.patches().size
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
                                executorFn = agentExecutorFn,
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

    /**
     * Re-seeds [editingContext] from the currently open editor script before every agent
     * turn (Kay/Atkinson, design review: "das Skript ist die Wahrheit, das Modell ist eine
     * Projektion, jede Projektion wird vor Gebrauch neu abgeleitet"). On an unparsable/empty
     * script, falls back to an empty UML model but sets [canWriteScript] = false so
     * [updateScriptFromModel] cannot clobber a script the AI never actually understood.
     *
     * internal (not private): called directly by [AiPanelStatePatchTest] to test both the
     * happy path and the unparsable-script fallback without going through a full agent run.
     */
    internal suspend fun seedEditingContextFromScript() {
        val script = appState.script
        val seeded =
            if (script.isBlank()) {
                canWriteScript = true
                AnyKumlModel.emptyUml()
            } else {
                runCatching {
                    val evalResult = KumlScriptHost.eval(code = script)
                    val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                    val success =
                        (evalResult as? ResultWithDiagnostics.Success)
                            ?.takeIf { errors.isEmpty() }
                            ?: error("script evaluation failed: ${errors.joinToString("; ") { it.message }}")
                    val extracted =
                        DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File("inline.kuml.kts"))
                    extracted as? ExtractedDiagram.Uml ?: error("not a UML class diagram")
                }.map { AnyKumlModel.Uml.fromKumlDiagram(it.diagram) }
                    .onSuccess { canWriteScript = true }
                    .onFailure {
                        // `runCatching` above catches Throwable, which includes
                        // CancellationException — never swallow that here: a cancelled agent
                        // turn (see runAgent()'s `catch (e: CancellationException)`) must
                        // propagate and stop cleanly instead of falling through to an empty
                        // model and continuing on to call the external LLM provider.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                    }.getOrElse {
                        canWriteScript = false
                        AnyKumlModel.emptyUml()
                    }
            }
        editingContext = AgentEditingContext(initialModel = seeded)
        patchEngine = createEngine()
    }

    private suspend fun handleEvent(ev: AgentEvent) =
        withContext(Dispatchers.Main) {
            when (ev) {
                is AgentEvent.AssistantDelta -> appendOrUpdateStreaming(delta = ev.delta, providerId = ev.providerId, modelId = ev.modelId)
                is AgentEvent.ToolCallStart ->
                    appendMessage(
                        ConversationMessage.ToolCall(
                            id = ev.callId,
                            timestamp = now(),
                            toolName = ev.tool,
                            argsJson = ev.argsJson,
                            providerCallId = ev.providerCallId,
                        ),
                    )
                is AgentEvent.ToolCallEnd -> updateToolCallEnd(callId = ev.callId, resultJson = ev.resultJson, isError = ev.isError)
                is AgentEvent.TokenUsage -> {
                    usageTracker.accumulate(providerId = ev.providerId, modelId = ev.modelId, inTok = ev.inTok, outTok = ev.outTok)
                    tokensIn = usageTracker.tokensIn
                    tokensOut = usageTracker.tokensOut
                    estimatedCostUsd = usageTracker.costUsd
                }
                is AgentEvent.Done -> {
                    finalizeStreaming()
                    // Review fix (race condition): this used to launch checkForTurnPatches in a
                    // coroutine detached from currentJob (scope.launch(Dispatchers.IO) { ... }),
                    // which meant currentJob?.cancel() could not stop it AND isRunning flipped to
                    // false (in runAgent()'s finally) before it finished — the exact window that
                    // let a second turn start while turn 1's patch preview was still being
                    // prepared. Calling it directly here keeps it a structured child of
                    // currentJob: cancellable, and isRunning stays true until the dialog state
                    // (_turnPatches/_turnPreviewSvg/showPatchDialog) is fully settled.
                    checkForTurnPatches(patchCountBeforeTurn)
                }
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
        if (!canWriteScript) return
        val model = editingContext.resolveModel()
        val dsl = ScriptSerializer.toDsl(model)
        withContext(Dispatchers.Main) {
            appState.script = dsl
            appState.isDirty = true
        }
    }

    /**
     * Checks whether the just-finished turn made any real [ModelPatch] mutations against
     * [editingContext] (the direct AgentRunner tool-loop applies tools for real — see
     * [dev.kuml.ai.tools.uml.UmlEditingTools] — so [AgentEditingContext.patches] is the
     * source of truth, not the legacy [patchEngine] buffer). If so, renders a preview SVG
     * of the resulting model and opens [PatchPreviewDialog] in turn-confirmation mode.
     *
     * A no-op when [countBefore] equals the current patch count — that happens for plain
     * chat turns and for the (currently never-activated) orchestrator path, which still
     * goes through the legacy `patchEngine`/`AgentEvent.PatchBuffered` mechanism instead.
     *
     * Review fix: a failed [renderFn] call (`renderResult` is [DesktopRenderResult.Error],
     * not [DesktopRenderResult.Svg]) still opens the dialog with [_turnPatches] populated and
     * [_turnPreviewSvg] left null — the turn's mutations are real regardless of whether the
     * preview rendered, so the confirmation must still happen. Whether the dialog offers
     * per-item Accept/Reject in that case is decided by turn-mode (`turnPatches.isNotEmpty()`
     * in [AiPanel]), never by nullness of the preview SVG — see [PatchPreviewDialog]'s
     * `isTurnMode` parameter.
     *
     * internal (not private): called directly by [AiPanelStatePatchTest] to test the
     * empty-delta no-op, the successful-render path, and the render-failure path without
     * needing a full agent run.
     */
    internal suspend fun checkForTurnPatches(countBefore: Int) {
        val all = editingContext.patches()
        val delta = all.drop(countBefore)
        if (delta.isEmpty()) return

        val model = editingContext.resolveModel()
        val dsl = ScriptSerializer.toDsl(model)
        val renderResult =
            withContext(Dispatchers.Default) {
                renderFn(dsl, appState.theme)
            }
        withContext(Dispatchers.Main) {
            _turnPatches.value = delta.map { it.toSummary() }
            _turnPreviewSvg.value = (renderResult as? DesktopRenderResult.Svg)?.svg
            showPatchDialog = true
        }
    }

    private fun ModelPatch.toSummary(): TurnPatchSummary =
        when (this) {
            is ModelPatch.AddElement -> TurnPatchSummary(kind = "added", description = "$elementKind '$name'")
            is ModelPatch.RemoveElement -> TurnPatchSummary(kind = "removed", description = elementId)
            is ModelPatch.UpdateAttribute -> TurnPatchSummary(kind = "modified", description = "$ownerId.$field")
            is ModelPatch.RenameElement -> TurnPatchSummary(kind = "modified", description = "$oldName → $newName")
            is ModelPatch.AddRelationship ->
                TurnPatchSummary(kind = "added", description = "$relationshipKind ($sourceId → $targetId)")
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
