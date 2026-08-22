package dev.kuml.desktop.ai.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.ai.provider.KumlLlmProvider
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.ai.PricingTable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/** Why a provider row's enable-checkbox is locked. NONE = clickable. */
internal enum class ProviderLockReason { NONE, NEEDS_KEY, BLOCKED_BY_PRIVACY, NOT_EXECUTABLE, NEEDS_MODEL }

/** Hard cap on API key input length — defence against pathological input reaching the vault's shell-out backends. */
internal const val MAX_API_KEY_LENGTH: Int = 4096

/** Synthetic model id used only to probe a provider's dynamic-catalog capability — never sent to any provider. */
internal const val MODEL_PROBE_ID: String = "kuml-catalog-probe"

/**
 * One rendered provider row. Deliberately carries NO API key — only [hasKey].
 * Never add a key field: this object is held in Compose state and would end up
 * in recomposition traces and any toString()/logging.
 */
internal data class ProviderRow(
    val id: String,
    val displayName: String,
    val isLocal: Boolean,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    val hasKey: Boolean,
    /** True for cloud providers — only these get a key field. */
    val needsKey: Boolean,
    /** Declared/suggested model ids (ModelCatalog for built-ins, pricing.json fallback for the picker). */
    val models: List<String>,
    /** Provider accepts arbitrary model ids (Ollama, Gonka) — free-text model field instead of a dropdown. */
    val hasDynamicCatalog: Boolean,
    val selectedModel: String,
    val checkboxEnabled: Boolean,
    val lockReason: ProviderLockReason,
)

/**
 * Enforces every invariant [dev.kuml.ai.KumlAiExecutor.fromSettings] implicitly requires:
 *  - every enabled provider is known AND (local OR has an API key)
 *  - enabledProviders is never empty
 *  - defaultProvider is always inside enabledProviders
 *  - every enabled provider has a defaultModels entry
 *  - NO cloud provider is ever left enabled while [KumlAiSettings.privacyMode] is on — this is
 *    the settings-level mirror of the dialog's own checkbox lock (see [computeLockReason]'s
 *    BLOCKED_BY_PRIVACY case), so the invariant holds even for a hand-edited settings file or a
 *    provider that was enabled BEFORE privacy mode was switched on (turning privacy mode on does
 *    not, by itself, un-check any already-enabled cloud provider's checkbox unless this function
 *    is the one doing it)
 * defaultModels entries of DISABLED (including privacy-blocked) providers are deliberately kept,
 * so re-enabling a provider restores the user's earlier model choice.
 *
 * Rule 4 (dropping providers with no resolvable default model) runs BEFORE rule 3 picks the
 * final [KumlAiSettings.defaultProvider] — otherwise a default provider chosen against the
 * pre-pruning set could end up outside the post-pruning one, violating the very invariant
 * this function exists to enforce.
 *
 * [hasKey] returns `null` when the vault access itself failed (e.g. a transient Keychain/
 * secret-tool error) — distinct from a definite "no key configured" (`false`). A `null` is
 * treated conservatively: an already-enabled cloud provider is left enabled rather than
 * destructively stripped out on nothing more than a transient vault hiccup (see the review
 * finding on [AiProviderSettingsState]'s vault-error handling — a fail-open/fail-destructive
 * bug where opening the dialog during a transient vault error would permanently drop every
 * cloud provider from `enabledProviders`, with no rollback and no user-visible warning).
 */
internal fun sanitizeSettings(
    settings: KumlAiSettings,
    isKnown: (String) -> Boolean,
    isLocal: (String) -> Boolean,
    hasKey: (String) -> Boolean?,
    fallbackModelFor: (String) -> String?,
): KumlAiSettings {
    // 1. Every enabled provider must be known AND (local OR (has a key AND privacy mode is off)).
    //    A null from hasKey() (vault error, not "no key") keeps the provider's current enabled
    //    state instead of dropping it — see this function's KDoc.
    var enabled =
        settings.enabledProviders.filterTo(LinkedHashSet()) { id ->
            when {
                !isKnown(id) -> false
                isLocal(id) -> true
                settings.privacyMode -> false
                else -> hasKey(id) ?: true
            }
        }

    // 2. Never leave enabledProviders empty — Ollama is always known and local.
    if (enabled.isEmpty()) enabled = linkedSetOf("ollama")

    // 4. Every surviving provider needs a resolvable default model, or it gets dropped —
    // never leave a provider enabled for which resolveDefaultModel() would error(...).
    val defaultModels = settings.defaultModels.toMutableMap()
    val survivors = LinkedHashSet<String>()
    for (id in enabled) {
        val existing = defaultModels[id]
        if (!existing.isNullOrBlank()) {
            survivors += id
            continue
        }
        val fallback = fallbackModelFor(id) ?: "llama3.2".takeIf { id == "ollama" }
        if (fallback != null) {
            defaultModels[id] = fallback
            survivors += id
        }
    }
    val finalEnabled =
        survivors.ifEmpty {
            defaultModels.putIfAbsent("ollama", "llama3.2")
            linkedSetOf("ollama")
        }

    // 3. defaultProvider must land inside the FINAL (post-pruning) enabled set.
    val defaultProvider =
        settings.defaultProvider.takeIf { it in finalEnabled }
            ?: "ollama".takeIf { it in finalEnabled }
            ?: finalEnabled.first()

    return settings.copy(
        enabledProviders = finalEnabled,
        defaultProvider = defaultProvider,
        defaultModels = defaultModels,
    )
}

/**
 * Pure lock-reason predicate, extracted so the custom-SPI-provider case (isSelectable=false)
 * is directly unit-testable without registering a real ServiceLoader provider (which
 * [AiProviderSettingsState] cannot reach from kuml-desktop's test module — ProviderRegistry's
 * `discoverFrom` test seam is `internal` to kuml-ai-core).
 *
 * Priority (only one reason is ever shown per row):
 *  1. not selectable at all (no model can ever be resolved) → NOT_EXECUTABLE
 *  2. cloud provider while privacy mode is on → BLOCKED_BY_PRIVACY
 *  3. cloud provider without a stored API key → NEEDS_KEY
 *  4. dynamic-catalog provider (Ollama, Gonka) with no default model chosen yet → NEEDS_MODEL
 *  5. otherwise → NONE (clickable)
 *
 * [hasDynamicCatalog] + [hasDefaultModel] exist for providers like Gonka whose model catalog
 * is network-hosted and dynamic — kUML has no safe built-in default for them (see
 * `AiBenchCommand`'s identical reasoning for `--model`), so [sanitizeSettings] rule 4 would
 * otherwise silently drop the provider the instant its checkbox is checked, with no visible
 * feedback. Locking the checkbox until a model is typed into the free-text field (which is
 * always reachable, checkbox state notwithstanding — see `AiProviderSettingsDialog`) surfaces
 * the requirement instead of hiding it.
 */
internal fun computeLockReason(
    isSelectable: Boolean,
    isLocal: Boolean,
    hasKey: Boolean,
    privacyMode: Boolean,
    hasDynamicCatalog: Boolean = false,
    hasDefaultModel: Boolean = true,
): ProviderLockReason =
    when {
        !isSelectable -> ProviderLockReason.NOT_EXECUTABLE
        !isLocal && privacyMode -> ProviderLockReason.BLOCKED_BY_PRIVACY
        !isLocal && !hasKey -> ProviderLockReason.NEEDS_KEY
        hasDynamicCatalog && !hasDefaultModel -> ProviderLockReason.NEEDS_MODEL
        else -> ProviderLockReason.NONE
    }

/**
 * Backing state for [AiProviderSettingsDialog]. Uses `androidx.compose.runtime.mutableStateOf`
 * directly — no UI-toolkit test harness required, exactly like [dev.kuml.desktop.ai.AiPanelState].
 *
 * Every settings mutation is persisted immediately via [sanitizeSettings] + [KumlAiSettingsStore.save]
 * — there is no separate "apply"/"cancel" step (see the dialog's single Close button).
 */
internal class AiProviderSettingsState(
    private val settingsStore: KumlAiSettingsStore,
    private val vault: ApiKeyVault,
    private val registry: ProviderRegistry = ProviderRegistry.discover(),
    private val pricingTable: PricingTable = PricingTable.loadFromResources(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Fire-and-forget background work triggered from the non-suspend requestPrivacyMode()
    // callback (Compose's Switch.onCheckedChange is not suspend). All actual work inside runs
    // on [ioDispatcher]; SupervisorJob so one failed persist never cancels a later one.
    //
    // Owned by THIS state instance, not by the dialog's `rememberCoroutineScope()` — a Compose
    // scope tied to the dialog composable is cancelled the instant the dialog leaves composition
    // (Close click), which would silently cut off an in-flight persist before it reaches disk.
    // Every row action must launch through [launchTracked] on this scope instead; [dispose] only
    // cancels it once the caller has confirmed (via [awaitPendingWrites]) nothing is in flight
    // anymore — see the review finding on the privacy-toggle Close race.
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Jobs launched via [launchTracked], so [awaitPendingWrites] can wait for all of them to
    // actually finish (persist included) before the caller proceeds — e.g. before
    // [dev.kuml.desktop.ai.AiPanelState.reloadSettings] reads the settings file back in.
    private val pendingWrites = ConcurrentLinkedQueue<Job>()

    // V3.7.1 review fix: every read-transform-sanitize-save-writeback of [settings] must be
    // atomic — Compose dialog callbacks (checkbox toggle, radio button, key save, model field
    // blur) each fire their own `scope.launch { ... }` and genuinely run concurrently. Without
    // this mutex, two overlapping mutate() calls can both read the same pre-mutation `settings`
    // snapshot (the second read happening while the first is still awaiting the Keychain-backed
    // `hasKeyBlocking()`/`rebuildRows()` round trip inside `withContext(ioDispatcher)`), so
    // whichever call's `settingsStore.save()` + `settings = updated` writeback lands last wins —
    // silently discarding the other call's change both in memory and on disk.
    private val mutationMutex = Mutex()

    private var settings: KumlAiSettings = KumlAiSettings()

    var privacyMode by mutableStateOf(true)
        private set
    var rows by mutableStateOf<List<ProviderRow>>(emptyList())
        private set
    var vaultIsFallback by mutableStateOf(false)
        private set

    /**
     * True when a leftover shared-service Keychain item from before V3.7.4 is still present on
     * this machine (macOS only — always `false` on every other OS/backend) AND the user has not
     * already dismissed the notice via [dismissLegacyKeychainNotice] (V3.7.5, review fix — the
     * underlying Keychain item is never deleted, see
     * [ApiKeyVault.hasLegacySharedKeychainItem]'s KDoc, so presence alone would otherwise show
     * this notice forever). Read-only from the dialog's perspective — only [dismissLegacyKeychainNotice]
     * changes it.
     */
    var legacyKeychainItemPresent by mutableStateOf(false)
        private set

    /** True while the one-time "disable privacy mode" confirmation is open. */
    var privacyConfirmPending by mutableStateOf(false)
        private set

    /** Set once per dialog instance after the user confirmed — no repeat prompts. */
    private var privacyDisableConfirmed = false

    /**
     * Reads settings + vault presence. Call from LaunchedEffect.
     *
     * Runs the loaded settings through [sanitizeSettings] (via the identity-transform [mutate]
     * call below) and persists the result if anything changed — so a stale or hand-edited
     * `ai-settings.json` (e.g. a cloud provider left enabled from before privacy mode was turned
     * on) self-heals the moment this dialog opens, not only on the next explicit mutation.
     *
     * [KumlAiSettingsStore.load] itself throws [dev.kuml.ai.KumlAiException.SettingsCorrupted]
     * on unparsable JSON or an unknown schema version. Left uncaught, that would escape the
     * `LaunchedEffect(Unit)` this is called from (see `AiProviderSettingsDialog`) and crash the
     * composition instead of opening the dialog. Falling back to [KumlAiSettings] defaults here
     * — followed by the identity-[mutate] below, which sanitizes AND persists — means a hand-
     * corrupted file is overwritten with a fresh valid one the moment the dialog opens, matching
     * this function's own "self-heals" promise for the stale-but-parsable case above.
     *
     * The identity-[mutate] call passes `forceSave = true` when the load above actually failed.
     * Without it, [mutate]'s own no-op-write optimization would defeat this exact self-heal:
     * `sanitizeSettings(KumlAiSettings())` is content-equal to `KumlAiSettings()` (defaults are
     * already sanitized), so the write would be silently skipped and the corrupted file would
     * stay on disk — `settingsStore.load()` would keep throwing on every next call (panel
     * mount, dialog re-open, `AiPanelState.reloadSettings()`), i.e. exactly the crash this
     * function claims to prevent, just deferred to the next caller that isn't wrapped in
     * `runCatching`.
     */
    suspend fun load() {
        val loadResult = withContext(ioDispatcher) { runCatching { settingsStore.load() } }
        settings = loadResult.getOrDefault(KumlAiSettings())
        vaultIsFallback = vault.isFallback
        val legacyItemPresent =
            withContext(ioDispatcher) { runCatching { vault.hasLegacySharedKeychainItem() }.getOrDefault(false) }
        legacyKeychainItemPresent = legacyItemPresent && !settings.legacyKeychainNoticeDismissed
        mutate(forceSave = loadResult.isFailure) { it }
    }

    /**
     * Persists that the user has acknowledged the leftover-shared-Keychain-item notice, so it
     * stops reappearing on every future dialog open (V3.7.5, review fix — see
     * [legacyKeychainItemPresent]'s KDoc for why presence alone can never clear it).
     */
    fun dismissLegacyKeychainNotice() {
        legacyKeychainItemPresent = false
        launchTracked {
            mutate { it.copy(legacyKeychainNoticeDismissed = true) }
        }
    }

    suspend fun setEnabled(
        providerId: String,
        enabled: Boolean,
    ) {
        mutate { current ->
            current.copy(
                enabledProviders =
                    if (enabled) current.enabledProviders + providerId else current.enabledProviders - providerId,
            )
        }
    }

    suspend fun setDefaultProvider(providerId: String) {
        mutate { it.copy(defaultProvider = providerId) }
    }

    suspend fun setDefaultModel(
        providerId: String,
        modelId: String,
    ) {
        if (modelId.isBlank()) return
        mutate { it.copy(defaultModels = it.defaultModels + (providerId to modelId)) }
    }

    /** Trims, rejects blank/oversized input, writes to the vault, unlocks the row. */
    suspend fun saveApiKey(
        providerId: String,
        apiKey: String,
    ) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_API_KEY_LENGTH) return
        val koog = registry.get(providerId)?.koogProvider ?: return
        // Never surface the raw exception (could echo backend/path details) — a failed save
        // simply leaves the row locked, which is itself an honest signal to retry.
        withContext(ioDispatcher) { runCatching { vault.put(provider = koog, key = trimmed) } }
        rebuildRows()
    }

    /** Deletes the key AND removes the provider from enabledProviders (see KDoc on [sanitizeSettings]). */
    suspend fun deleteApiKey(providerId: String) {
        val koog = registry.get(providerId)?.koogProvider
        if (koog != null) {
            withContext(ioDispatcher) { runCatching { vault.delete(koog) } }
        }
        mutate { it.copy(enabledProviders = it.enabledProviders - providerId) }
    }

    /**
     * enabled=true applies immediately; enabled=false opens the one-time confirm.
     *
     * The switch is flipped optimistically so the UI feels immediate, but [mutate] only wins
     * the race if its persist actually succeeds — on a failed [KumlAiSettingsStore.save] (e.g.
     * a read-only config directory) it rolls [privacyMode] back to [previous] instead of leaving
     * the switch showing a state the settings file on disk does not actually have.
     */
    fun requestPrivacyMode(enabled: Boolean) {
        if (!enabled && !privacyDisableConfirmed) {
            privacyConfirmPending = true
            return
        }
        val previous = privacyMode
        privacyMode = enabled
        launchTracked {
            val persisted = mutate { it.copy(privacyMode = enabled) }
            if (!persisted) privacyMode = previous
        }
    }

    suspend fun confirmPrivacyDisable() {
        privacyDisableConfirmed = true
        privacyConfirmPending = false
        val previous = privacyMode
        privacyMode = false
        val persisted = mutate { it.copy(privacyMode = false) }
        if (!persisted) privacyMode = previous
    }

    fun cancelPrivacyDisable() {
        privacyConfirmPending = false
    }

    /**
     * Launches [block] on this state's own [backgroundScope] and tracks the resulting [Job] so
     * [awaitPendingWrites] can wait for it. Every row action in [AiProviderSettingsDialog]
     * (checkbox, radio, model field, API-key save/delete, privacy switch) MUST go through this —
     * never through the dialog's own `rememberCoroutineScope()`, which is torn down the instant
     * the dialog leaves composition and would silently cancel an in-flight persist first (see the
     * review finding: clicking "Delete key" then immediately "Close" could cancel the delete
     * before it ever reached the vault).
     */
    internal fun launchTracked(block: suspend () -> Unit) {
        val job = backgroundScope.launch { block() }
        pendingWrites += job
        job.invokeOnCompletion { pendingWrites -= job }
    }

    /**
     * True from the moment [beginClosing] is called until the dialog actually disposes. The UI
     * MUST disable every row control (checkbox, radio, model field/menu, API-key buttons,
     * privacy switch) once this is true — see [beginClosing]'s KDoc for why disabling the
     * snapshot-then-join race in [awaitPendingWrites] alone is not sufficient.
     */
    var isClosing by mutableStateOf(false)
        private set

    /**
     * Marks this state as closing. Call ONCE, before awaiting [awaitPendingWrites] — see
     * [AiProviderSettingsDialog.requestClose].
     *
     * This exists alongside the drain-loop fix in [awaitPendingWrites] to close a residual race
     * that a single wait can never fully close on its own: [awaitPendingWrites] can only wait for
     * jobs that exist at the moment it is called (or, with the drain loop, at each successive
     * snapshot) — it cannot wait for a job that does not exist yet. Nothing stops the dialog's UI
     * from staying fully interactive while [awaitPendingWrites] is running (nothing in Compose
     * blocks on a suspend function), so a user can keep clicking checkboxes/switches during the
     * close sequence and start brand-new [launchTracked] jobs after the last drain iteration has
     * already observed an empty queue — which [dispose] would then cancel mid-flight, silently
     * discarding that very last change (see the review finding this fixes). Setting [isClosing]
     * true up front lets the UI disable every control that could call [launchTracked], so no new
     * job can be started once closing has begun — independent of how [awaitPendingWrites] is
     * implemented.
     */
    internal fun beginClosing() {
        isClosing = true
    }

    /**
     * Suspends until every write currently tracked via [launchTracked] — including
     * [requestPrivacyMode]'s own fire-and-forget persist — has actually finished.
     *
     * [AiProviderSettingsDialog] MUST call this before invoking its `onClose` callback (Close
     * button and window-close request alike). Without it, `onClose` — which triggers
     * [dev.kuml.desktop.ai.AiPanelState.reloadSettings] in `MainWindow.kt` — can run while a
     * just-toggled privacy mode (or any other row change) is still being written, so the panel
     * reloads a stale pre-write settings snapshot: the privacy switch shows "on" in the now-
     * closed dialog while the panel silently keeps a cloud provider enabled (see the review
     * finding "Privacy-Kontrolle wirkungslos, Race").
     *
     * Drains [pendingWrites] in a loop rather than joining a single snapshot: a job launched via
     * [launchTracked] WHILE an earlier job is still being joined here (e.g. a row action that
     * itself triggers a follow-up mutation, or simply two callbacks that raced) would not be in
     * the very first snapshot — a one-shot `pendingWrites.toList().forEach { it.join() }` returns
     * the instant that first batch finishes, oblivious to the newer job, which [dispose] would
     * then cancel before its persist ever reaches disk (see the review finding this fixes; the
     * companion fix is [beginClosing], which stops NEW jobs from being launched by the UI in the
     * first place once closing has begun — the two together close both ends of the race).
     */
    internal suspend fun awaitPendingWrites() {
        while (true) {
            val jobs = pendingWrites.toList()
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    /**
     * Cancels this state's [backgroundScope]. Call ONLY after [awaitPendingWrites] has returned
     * (i.e. once the dialog is actually closing) — calling it earlier would cut off an in-flight
     * persist instead of merely releasing an already-idle scope's resources (the resource-leak
     * finding this method exists to fix — [backgroundScope] previously was never cancelled at
     * all, across every dialog open/close cycle).
     */
    internal fun dispose() {
        backgroundScope.cancel()
    }

    /** Model ids for a provider: supportedModels first, pricing.json as fallback. */
    internal fun modelsFor(providerId: String): List<String> {
        val declared =
            registry
                .get(providerId)
                ?.supportedModels
                .orEmpty()
                .map { it.modelId }
        return declared.ifEmpty { pricingTable.modelsForProvider(providerId) }
    }

    /** Visible for tests — the exact KumlAiSettings that would be persisted. */
    internal fun currentSettings(): KumlAiSettings = settings

    /**
     * Reads, transforms, sanitizes, and (if changed) persists [settings].
     *
     * Returns `true` when the in-memory state now reflects [transform] — either because the
     * persist succeeded, or because sanitizing [transform]'s result turned out identical to
     * what was already persisted (nothing to write). Returns `false` when [KumlAiSettingsStore.save]
     * threw (e.g. a read-only config directory): in that case [settings]/[privacyMode]/[rows]
     * are left exactly as they were — the caller's requested change never happened, in memory
     * or on disk — rather than crashing the calling coroutine (`AiProviderSettingsDialog`'s
     * `LaunchedEffect`/`scope.launch` callbacks have no exception handler of their own) or
     * leaving the two out of sync (see [requestPrivacyMode] and [confirmPrivacyDisable], which
     * roll their optimistically-flipped switch back on `false`).
     *
     * @param forceSave Bypasses the content-equality no-op-write skip below even when the
     * sanitized candidate is identical to [previous]. [load] passes `true` here after a failed
     * [KumlAiSettingsStore.load] — `previous` in that case is in-memory-only [KumlAiSettings]
     * defaults that were never actually written to disk, so skipping the write on
     * `candidate == previous` would leave the original corrupted file in place forever.
     */
    private suspend fun mutate(
        forceSave: Boolean = false,
        transform: (KumlAiSettings) -> KumlAiSettings,
    ): Boolean =
        // Serializes the whole read-transform-sanitize-save-writeback cycle so two concurrent
        // dialog callbacks can never interleave and lose one another's change — see the KDoc
        // on [mutationMutex].
        mutationMutex.withLock {
            val previous = settings
            val result =
                withContext(ioDispatcher) {
                    val candidate =
                        sanitizeSettings(
                            settings = transform(previous),
                            isKnown = { id -> registry.get(id) != null },
                            isLocal = { id -> registry.get(id)?.isLocal ?: false },
                            hasKey = { id -> keyPresence(id) },
                            fallbackModelFor = { id -> modelsFor(id).firstOrNull() },
                        )
                    // Only persist when the sanitized result actually differs — otherwise every
                    // dialog open (load() calls mutate { it }) rewrites ai-settings.json for no
                    // reason: mtime/backup churn, and a needless write against a path that might
                    // be read-only (see the KDoc on [load]). [forceSave] bypasses this skip —
                    // see its KDoc above.
                    if (candidate == previous && !forceSave) {
                        Result.success(candidate)
                    } else {
                        // Never let a save failure escape as an uncaught exception — see this
                        // function's KDoc.
                        runCatching { settingsStore.save(candidate) }.map { candidate }
                    }
                }
            result.onSuccess { candidate ->
                settings = candidate
                privacyMode = candidate.privacyMode
                rebuildRows()
            }
            result.isSuccess
        }

    /**
     * Blocking vault presence check — callers must already be on [ioDispatcher].
     *
     * Returns `null` when [vault] itself cannot answer definitively (transient error: a denied
     * Keychain prompt, `secret-tool`/DPAPI unavailable, etc.) — distinct from a definite `false`
     * ("no key stored"). Callers that must not confuse "unknown" with "absent" (see
     * [sanitizeSettings]) use this instead of [hasKeyBlocking].
     *
     * Delegates to [ApiKeyVault.has] — NOT `vault.get(koog) != null`. The two are NOT
     * equivalent: [MacOsKeychainBackend.get]/[LinuxSecretToolBackend.get] both collapse every
     * backend failure (denied consent prompt, locked keychain/keyring, daemon unreachable) to
     * plain `null` with no way to tell it apart from "no key configured" — so
     * `vault.get(koog) != null` always evaluates to `false` on a vault error, and `runCatching`
     * around it never sees an exception to catch (these `get()` implementations don't throw on
     * backend failure, they swallow it into `null`). That made the caller's own null-handling a
     * dead branch: [keyPresence] always returned a definite `false`/`true`, never the `null` its
     * own signature promises, and [sanitizeSettings] then genuinely could not distinguish a
     * transient hiccup from an intentional key deletion — silently and permanently dropping the
     * provider from `enabledProviders` on nothing more than a denied Keychain prompt (see the
     * review finding this fixes). [ApiKeyVault.has] plumbs the real tri-state up from the OS
     * keystore's own exit code instead.
     */
    private fun keyPresence(providerId: String): Boolean? {
        val koog = registry.get(providerId)?.koogProvider ?: return false
        return runCatching { vault.has(koog) }.getOrNull()
    }

    /** UI-facing boolean — collapses a vault error (see [keyPresence]) to "no key" for display. */
    private fun hasKeyBlocking(providerId: String): Boolean = keyPresence(providerId) ?: false

    private suspend fun rebuildRows() {
        val computed = withContext(ioDispatcher) { registry.all().map { provider -> buildRow(provider) } }
        // Lokale Provider zuerst (Ollama steht damit immer oben), stabil nach Anzeigename.
        rows = computed.sortedWith(compareBy({ !it.isLocal }, { it.displayName }))
    }

    private fun buildRow(provider: KumlLlmProvider): ProviderRow {
        val id = provider.id
        val isExecutable = provider.koogProvider != null
        // Uses the provider's OWN declared static catalog (ModelCatalog-backed for built-ins),
        // NOT modelsFor()'s pricing.json fallback — pricing.json lists a few suggested Ollama
        // models for cost display, which would otherwise wrongly turn off the free-text field
        // for a provider that (per BuiltInProviders' own KDoc) accepts ANY locally-pulled model id.
        val declaredModels = provider.supportedModels.map { it.modelId }
        val hasDynamicCatalog =
            isExecutable &&
                declaredModels.isEmpty() &&
                registry.resolveModel(providerId = id, modelId = MODEL_PROBE_ID) != null
        val isSelectable = isExecutable && (declaredModels.isNotEmpty() || hasDynamicCatalog)
        val needsKey = !provider.isLocal
        val hasKey = needsKey && hasKeyBlocking(id)
        val hasDefaultModel = !settings.defaultModels[id].isNullOrBlank()
        val lockReason =
            computeLockReason(
                isSelectable = isSelectable,
                isLocal = provider.isLocal,
                hasKey = hasKey,
                privacyMode = privacyMode,
                hasDynamicCatalog = hasDynamicCatalog,
                hasDefaultModel = hasDefaultModel,
            )
        return ProviderRow(
            id = id,
            displayName = provider.displayName,
            isLocal = provider.isLocal,
            isEnabled = id in settings.enabledProviders,
            isDefault = id == settings.defaultProvider,
            hasKey = hasKey,
            needsKey = needsKey,
            models = modelsFor(id),
            hasDynamicCatalog = hasDynamicCatalog,
            selectedModel = settings.defaultModels[id] ?: "",
            checkboxEnabled = lockReason == ProviderLockReason.NONE,
            lockReason = lockReason,
        )
    }
}
