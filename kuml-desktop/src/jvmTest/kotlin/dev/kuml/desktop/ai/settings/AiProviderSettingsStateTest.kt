package dev.kuml.desktop.ai.settings

import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.ai.PricingTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/**
 * V3.7.1 — tests for [AiProviderSettingsState] and its standalone [sanitizeSettings] /
 * [computeLockReason] pure functions.
 *
 * S5 (plan stolperfalle): [dev.kuml.ai.vault.PlainJsonFallbackBackend] writes to
 * `XdgPaths.plainSecretsPath()`, which is process-/machine-wide by default — NOT a temp
 * directory. Every test redirects it via the `kuml.config.home` system property
 * ([dev.kuml.ai.settings.XdgPaths]'s documented test override) so this suite never touches
 * — or depends on the state of — a developer's real stored API keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiProviderSettingsStateTest :
    FunSpec({
        beforeEach {
            val configHome = Files.createTempDirectory("kuml-provider-settings-test-config")
            System.setProperty("kuml.config.home", configHome.toString())
            System.setProperty("kuml.ai.vault.backend", "plain")
        }

        afterEach {
            System.clearProperty("kuml.config.home")
            System.clearProperty("kuml.ai.vault.backend")
        }

        fun testSettingsStore(settings: KumlAiSettings): KumlAiSettingsStore {
            val path = Files.createTempDirectory("kuml-provider-settings-store").resolve("ai-settings.json")
            val store = KumlAiSettingsStore(path = path)
            store.save(settings)
            return KumlAiSettingsStore(path = path)
        }

        fun makeState(settings: KumlAiSettings = KumlAiSettings(privacyMode = false)): AiProviderSettingsState =
            AiProviderSettingsState(
                settingsStore = testSettingsStore(settings),
                vault = ApiKeyVault.detect(),
                registry = ProviderRegistry.builtIns(),
                pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                ioDispatcher = UnconfinedTestDispatcher(),
            )

        // ── AiProviderSettingsState behaviour ──────────────────────────────────

        test("cloud provider without a key cannot be enabled (NEEDS_KEY, checkbox locked)") {
            runTest {
                val state = makeState()
                state.load()
                val openai = state.rows.first { it.id == "openai" }
                openai.checkboxEnabled shouldBe false
                openai.lockReason shouldBe ProviderLockReason.NEEDS_KEY
            }
        }

        test("saveApiKey unlocks the row immediately, no separate save step") {
            runTest {
                val state = makeState()
                state.load()
                state.saveApiKey(providerId = "openai", apiKey = "sk-test-123")
                val openai = state.rows.first { it.id == "openai" }
                openai.hasKey shouldBe true
                openai.checkboxEnabled shouldBe true
                openai.lockReason shouldBe ProviderLockReason.NONE
            }
        }

        test("deleteApiKey after enabling removes the provider from enabledProviders") {
            runTest {
                val state = makeState()
                state.load()
                state.saveApiKey(providerId = "openai", apiKey = "sk-test-123")
                state.setEnabled(providerId = "openai", enabled = true)
                state.currentSettings().enabledProviders shouldContain "openai"

                state.deleteApiKey(providerId = "openai")
                state.currentSettings().enabledProviders shouldNotContain "openai"
                state.rows.first { it.id == "openai" }.hasKey shouldBe false
            }
        }

        test("privacyMode = true blocks all cloud rows, Ollama stays unlocked") {
            runTest {
                val state = makeState(settings = KumlAiSettings(privacyMode = true))
                state.load()
                state.rows.filterNot { it.isLocal }.forEach {
                    it.lockReason shouldBe ProviderLockReason.BLOCKED_BY_PRIVACY
                }
                state.rows.first { it.id == "ollama" }.lockReason shouldBe ProviderLockReason.NONE
            }
        }

        test(
            "requestPrivacyMode(false) opens the confirmation without changing privacyMode yet; " +
                "confirming applies it; a second disable in the same instance does not ask again",
        ) {
            runTest {
                val state = makeState(settings = KumlAiSettings(privacyMode = true))
                state.load()

                state.requestPrivacyMode(enabled = false)
                state.privacyConfirmPending shouldBe true
                state.privacyMode shouldBe true

                state.confirmPrivacyDisable()
                state.privacyConfirmPending shouldBe false
                state.privacyMode shouldBe false

                state.requestPrivacyMode(enabled = true)
                state.privacyMode shouldBe true

                state.requestPrivacyMode(enabled = false)
                state.privacyConfirmPending shouldBe false
                state.privacyMode shouldBe false
            }
        }

        test("turning privacy mode on strips an already-enabled cloud provider from enabledProviders") {
            runTest {
                val state = makeState(settings = KumlAiSettings(privacyMode = false))
                state.load()
                state.saveApiKey(providerId = "openai", apiKey = "sk-test-123")
                state.setEnabled(providerId = "openai", enabled = true)
                state.currentSettings().enabledProviders shouldContain "openai"

                // Turning privacy mode ON never needs confirmation (only turning it OFF does).
                state.requestPrivacyMode(enabled = true)

                state.currentSettings().enabledProviders shouldNotContain "openai"
                state.rows.first { it.id == "openai" }.isEnabled shouldBe false
            }
        }

        test("Ollama row has a dynamic catalog and needs no key") {
            runTest {
                val state = makeState()
                state.load()
                val ollama = state.rows.first { it.id == "ollama" }
                ollama.hasDynamicCatalog shouldBe true
                ollama.needsKey shouldBe false
            }
        }

        test("Gonka row has a dynamic catalog; locked by missing key, or by privacy mode when it's on") {
            runTest {
                val stateNoPrivacy = makeState(settings = KumlAiSettings(privacyMode = false))
                stateNoPrivacy.load()
                val gonkaNoPrivacy = stateNoPrivacy.rows.first { it.id == "gonka" }
                gonkaNoPrivacy.hasDynamicCatalog shouldBe true
                gonkaNoPrivacy.lockReason shouldBe ProviderLockReason.NEEDS_KEY

                val statePrivacy = makeState(settings = KumlAiSettings(privacyMode = true))
                statePrivacy.load()
                statePrivacy.rows.first { it.id == "gonka" }.lockReason shouldBe ProviderLockReason.BLOCKED_BY_PRIVACY
            }
        }

        // Regression test for the empirically-confirmed V3.7.1 review finding: Gonka's checkbox
        // could never be checked because sanitizeSettings' rule 4 silently dropped it the
        // instant it was enabled (no resolvable default model, and — unlike Ollama — no hard-
        // coded fallback; pricing.json has no gonka block at all). Uses the REAL
        // PricingTable.loadFromResources(), not PricingTable.forTest(...), because the bug is
        // specifically about the real, shipped pricing.json missing a gonka entry — a test
        // fixture that hands modelsFor("gonka") a stub model would hide it.
        test("Gonka: checkbox stays locked (NEEDS_MODEL) after a key is stored but before a model is chosen") {
            runTest {
                val state =
                    AiProviderSettingsState(
                        settingsStore = testSettingsStore(KumlAiSettings(privacyMode = false)),
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.loadFromResources(),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                state.load()
                state.saveApiKey(providerId = "gonka", apiKey = "gonka-test-key")

                val gonka = state.rows.first { it.id == "gonka" }
                gonka.hasKey shouldBe true
                gonka.hasDynamicCatalog shouldBe true
                gonka.lockReason shouldBe ProviderLockReason.NEEDS_MODEL
                gonka.checkboxEnabled shouldBe false

                // Enabling anyway (bypassing the locked checkbox, as the old UI accidentally
                // let a user do by clicking too fast) must NOT silently drop right back out —
                // sanitizeSettings' own invariant (every enabled provider is executable) still
                // requires a resolvable model, so it stays excluded, honestly, not "briefly
                // shown as checked".
                state.setEnabled(providerId = "gonka", enabled = true)
                state.currentSettings().enabledProviders shouldNotContain "gonka"
            }
        }

        test("Gonka: typing a model unlocks the checkbox, and enabling it then actually sticks") {
            runTest {
                val state =
                    AiProviderSettingsState(
                        settingsStore = testSettingsStore(KumlAiSettings(privacyMode = false)),
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.loadFromResources(),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                state.load()
                state.saveApiKey(providerId = "gonka", apiKey = "gonka-test-key")

                // The free-text model field is reachable regardless of the checkbox's locked
                // state (see AiProviderSettingsDialog — ApiKeyRow/the model field are never
                // gated on row.isEnabled).
                state.setDefaultModel(providerId = "gonka", modelId = "some-network-model")
                val afterModel = state.rows.first { it.id == "gonka" }
                afterModel.lockReason shouldBe ProviderLockReason.NONE
                afterModel.checkboxEnabled shouldBe true

                state.setEnabled(providerId = "gonka", enabled = true)
                state.currentSettings().enabledProviders shouldContain "gonka"
                state.currentSettings().defaultModels["gonka"] shouldBe "some-network-model"
            }
        }

        // Regression test for the lost-update finding: two overlapping mutate() calls (as
        // happen when two dialog callbacks fire close together — each is its own
        // `scope.launch { ... }`) must serialize rather than both reading the same
        // pre-mutation snapshot and racing on the writeback.
        test("concurrent setEnabled + setDefaultModel calls never lose either change") {
            runTest {
                val path = Files.createTempDirectory("kuml-provider-settings-concurrency").resolve("ai-settings.json")
                val store = KumlAiSettingsStore(path = path)
                store.save(KumlAiSettings(privacyMode = false))
                val state =
                    AiProviderSettingsState(
                        settingsStore = store,
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        // Real IO dispatcher (not Unconfined) — the point of this test is to
                        // exercise two genuinely interleaved mutate() calls, which Unconfined's
                        // synchronous-until-first-suspension semantics would mask.
                        ioDispatcher = Dispatchers.IO,
                    )
                state.load()
                state.saveApiKey(providerId = "openai", apiKey = "sk-test-123")

                coroutineScope {
                    val a = launch { state.setEnabled(providerId = "openai", enabled = true) }
                    val b = launch { state.setDefaultModel(providerId = "ollama", modelId = "qwen2.5") }
                    a.join()
                    b.join()
                }

                // Both effects must be present in the FINAL persisted state — neither call's
                // read-modify-write may have clobbered the other's.
                state.currentSettings().enabledProviders shouldContain "openai"
                state.currentSettings().defaultModels["ollama"] shouldBe "qwen2.5"

                val reloaded = KumlAiSettingsStore(path = path).load()
                reloaded shouldBe state.currentSettings()
            }
        }

        test("round trip: mutations persist through a fresh KumlAiSettingsStore instance on the same path") {
            runTest {
                val path = Files.createTempDirectory("kuml-provider-settings-roundtrip").resolve("ai-settings.json")
                val store = KumlAiSettingsStore(path = path)
                store.save(KumlAiSettings(privacyMode = false))
                val state =
                    AiProviderSettingsState(
                        settingsStore = store,
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                state.load()
                state.setDefaultProvider(providerId = "ollama")
                state.setDefaultModel(providerId = "ollama", modelId = "qwen2.5")

                val reloaded = KumlAiSettingsStore(path = path).load()
                reloaded shouldBe state.currentSettings()
                reloaded.defaultModels["ollama"] shouldBe "qwen2.5"
            }
        }

        test("defaultModels entry of a disabled provider survives so re-enabling restores the earlier choice") {
            runTest {
                val state = makeState()
                state.load()
                state.saveApiKey(providerId = "openai", apiKey = "sk-test-123")
                state.setEnabled(providerId = "openai", enabled = true)
                state.setDefaultModel(providerId = "openai", modelId = "gpt-4o-mini")
                state.setEnabled(providerId = "openai", enabled = false)

                state.currentSettings().enabledProviders shouldNotContain "openai"
                state.currentSettings().defaultModels["openai"] shouldBe "gpt-4o-mini"

                state.setEnabled(providerId = "openai", enabled = true)
                state.rows.first { it.id == "openai" }.selectedModel shouldBe "gpt-4o-mini"
            }
        }

        // Regression test for the "self-heal is only half wirksam" review finding: load()
        // catching SettingsCorrupted and falling back to defaults is NOT enough on its own —
        // sanitizeSettings(KumlAiSettings()) is content-equal to KumlAiSettings(), so without
        // forceSave the identity-mutate() call in load() would hit the no-op-write skip and
        // never actually overwrite the corrupted bytes on disk.
        test("load(): a corrupted ai-settings.json is actually overwritten on disk, not just recovered in memory") {
            runTest {
                val path = Files.createTempDirectory("kuml-provider-settings-corrupted").resolve("ai-settings.json")
                Files.createDirectories(path.parent)
                Files.writeString(path, "{ this is not valid json,,, ")

                val state =
                    AiProviderSettingsState(
                        settingsStore = KumlAiSettingsStore(path = path),
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )

                // Must not throw — load() catches SettingsCorrupted and falls back to defaults.
                state.load()
                state.rows.isNotEmpty() shouldBe true

                // The actual finding: a FRESH store reading the same path must not hit
                // SettingsCorrupted again — before the fix it would, because the corrupted
                // bytes were never replaced.
                val reloaded = KumlAiSettingsStore(path = path).load()
                reloaded.enabledProviders shouldContain "ollama"
            }
        }

        // Regression guard for the OTHER direction of the same fix: forceSave must not turn
        // every load() into an unconditional rewrite — once a settings file is already
        // sanitized, a second, independent load() against it must still be a pure no-op save
        // (verified here by byte-for-byte file content equality, since a real write would at
        // least reformat/re-timestamp the temp file used during KumlAiSettingsStore.save()).
        test("load(): loading an already-sanitized file a second time does not rewrite it") {
            runTest {
                val path = Files.createTempDirectory("kuml-provider-settings-noop-save").resolve("ai-settings.json")
                KumlAiSettingsStore(path = path).save(
                    KumlAiSettings(
                        privacyMode = false,
                        enabledProviders = setOf("ollama"),
                        defaultProvider = "ollama",
                        defaultModels = mapOf("ollama" to "llama3.2"),
                    ),
                )

                fun freshState() =
                    AiProviderSettingsState(
                        settingsStore = KumlAiSettingsStore(path = path),
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )

                freshState().load()
                val bytesAfterFirstLoad = Files.readAllBytes(path)

                freshState().load()
                Files.readAllBytes(path) shouldBe bytesAfterFirstLoad
            }
        }

        // Regression test for the mutate() failure path and the rollback it must trigger in
        // requestPrivacyMode()/confirmPrivacyDisable() — simulated via a settings path whose
        // parent directory can never be created (it already exists as a plain file), so
        // KumlAiSettingsStore.save()'s Files.createDirectories(...) throws.
        test("confirmPrivacyDisable() rolls privacyMode back when save() throws (unwritable settings path)") {
            runTest {
                val unwritableParent = Files.createTempFile("kuml-unwritable-settings-parent", "")
                val path = unwritableParent.resolve("ai-settings.json")
                val state =
                    AiProviderSettingsState(
                        settingsStore = KumlAiSettingsStore(path = path),
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    )
                // No file exists yet at `path` — load() returns defaults without touching the
                // filesystem, and the identity-mutate() below is a no-op save (defaults are
                // already sanitized), so the unwritable parent isn't hit yet.
                state.load()
                state.privacyMode shouldBe true // KumlAiSettings() default

                state.requestPrivacyMode(enabled = false)
                state.privacyConfirmPending shouldBe true

                // confirmPrivacyDisable() actually changes privacyMode (true -> false), so
                // mutate() attempts a real save this time — which throws because
                // Files.createDirectories(unwritableParent) fails (it exists as a regular file).
                state.confirmPrivacyDisable()

                state.privacyMode shouldBe true
                state.currentSettings().privacyMode shouldBe true
            }
        }

        // Regression test for the V3.7.2 review finding ("Privacy-Kontrolle wirkungslos, Race"):
        // AiProviderSettingsDialog's requestClose() awaits state.awaitPendingWrites() before
        // invoking onClose() (which triggers AiPanelState.reloadSettings() in MainWindow.kt).
        // This proves the underlying mechanism: a write launched via launchTracked() (exactly
        // what requestPrivacyMode() does) is guaranteed fully persisted by the time
        // awaitPendingWrites() returns, even on a real (non-Unconfined) dispatcher.
        test("launchTracked + awaitPendingWrites: a tracked write is fully persisted once awaitPendingWrites returns") {
            runTest {
                val path = Files.createTempDirectory("kuml-provider-settings-await-writes").resolve("ai-settings.json")
                val store = KumlAiSettingsStore(path = path)
                store.save(KumlAiSettings(privacyMode = false))
                val state =
                    AiProviderSettingsState(
                        settingsStore = store,
                        vault = ApiKeyVault.detect(),
                        registry = ProviderRegistry.builtIns(),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
                        // Real IO dispatcher — the point is to exercise genuine asynchrony
                        // between launchTracked()'s fire-and-forget launch and the later
                        // awaitPendingWrites() call, which UnconfinedTestDispatcher would mask.
                        ioDispatcher = Dispatchers.IO,
                    )
                state.load()

                state.requestPrivacyMode(enabled = true)
                state.awaitPendingWrites()

                state.currentSettings().privacyMode shouldBe true
                val reloaded = KumlAiSettingsStore(path = path).load()
                reloaded.privacyMode shouldBe true
            }
        }

        // ── sanitizeSettings (pure, no instance needed) ────────────────────────

        test("sanitizeSettings filters out a keyless cloud provider") {
            val result =
                sanitizeSettings(
                    settings = KumlAiSettings(enabledProviders = setOf("ollama", "openai"), defaultProvider = "ollama"),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { false },
                    fallbackModelFor = { null },
                )
            result.enabledProviders shouldBe setOf("ollama")
        }

        test("sanitizeSettings always keeps defaultProvider inside enabledProviders") {
            val result =
                sanitizeSettings(
                    settings = KumlAiSettings(enabledProviders = setOf("anthropic"), defaultProvider = "openai"),
                    isKnown = { true },
                    isLocal = { false },
                    hasKey = { true },
                    fallbackModelFor = { "some-model" },
                )
            (result.defaultProvider in result.enabledProviders) shouldBe true
        }

        test("sanitizeSettings falls back to ollama when enabledProviders is empty") {
            val result =
                sanitizeSettings(
                    settings = KumlAiSettings(enabledProviders = emptySet(), defaultModels = emptyMap()),
                    isKnown = { false },
                    isLocal = { false },
                    hasKey = { false },
                    fallbackModelFor = { null },
                )
            result.enabledProviders shouldBe setOf("ollama")
            result.defaultModels["ollama"] shouldBe "llama3.2"
        }

        test("sanitizeSettings drops an enabled provider with no resolvable default model and no fallback") {
            val result =
                sanitizeSettings(
                    // privacyMode = false: isolates rule 4 (no-resolvable-model drop) from rule
                    // 1's privacy filter below — otherwise "openai" would be dropped for the
                    // WRONG reason and this test would stop actually exercising rule 4.
                    settings =
                        KumlAiSettings(
                            enabledProviders = setOf("ollama", "openai"),
                            defaultProvider = "ollama",
                            defaultModels = mapOf("ollama" to "llama3.2"),
                            privacyMode = false,
                        ),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { true },
                    fallbackModelFor = { id -> if (id == "openai") null else "llama3.2" },
                )
            result.enabledProviders shouldBe setOf("ollama")
        }

        test("sanitizeSettings strips an enabled cloud provider once privacy mode is on, even with a valid key") {
            val result =
                sanitizeSettings(
                    settings =
                        KumlAiSettings(
                            enabledProviders = setOf("ollama", "openai"),
                            defaultProvider = "openai",
                            privacyMode = true,
                        ),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { true }, // a valid key alone must not be enough while privacy mode is on
                    fallbackModelFor = { "some-model" },
                )
            result.enabledProviders shouldBe setOf("ollama")
            result.defaultProvider shouldBe "ollama"
        }

        // Regression test for the fail-open/fail-destructive review finding: a transient vault
        // error (e.g. a denied Keychain prompt, secret-tool unavailable) must NOT be treated the
        // same as "no key configured" — that would silently and permanently strip an
        // already-enabled, previously-working cloud provider from enabledProviders on nothing
        // more than a hiccup, with no rollback and no visible warning. hasKey() returning null
        // signals exactly that "unknown" case, and sanitizeSettings must conservatively keep the
        // provider's current (enabled) state instead.
        test("sanitizeSettings keeps an already-enabled cloud provider when hasKey signals a vault error (null)") {
            val result =
                sanitizeSettings(
                    settings =
                        KumlAiSettings(
                            enabledProviders = setOf("ollama", "openai"),
                            defaultProvider = "ollama",
                            defaultModels = mapOf("ollama" to "llama3.2", "openai" to "gpt-4o-mini"),
                            privacyMode = false,
                        ),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { id -> if (id == "openai") null else true }, // simulated vault error for openai
                    fallbackModelFor = { "some-model" },
                )
            result.enabledProviders shouldContain "openai"
        }

        // Companion case: a vault error must NOT override privacy mode — turning privacy mode on
        // still strips every cloud provider regardless of what hasKey() reports, since rule 1's
        // privacy check runs before hasKey() is even consulted (see sanitizeSettings' KDoc).
        test("sanitizeSettings still strips a cloud provider under privacy mode even when hasKey signals a vault error") {
            val result =
                sanitizeSettings(
                    settings =
                        KumlAiSettings(
                            enabledProviders = setOf("ollama", "openai"),
                            defaultProvider = "ollama",
                            privacyMode = true,
                        ),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { null }, // vault error — must not matter while privacy mode is on
                    fallbackModelFor = { "some-model" },
                )
            result.enabledProviders shouldNotContain "openai"
        }

        test("sanitizeSettings keeps a privacy-stripped provider's defaultModels entry for later re-enabling") {
            val result =
                sanitizeSettings(
                    settings =
                        KumlAiSettings(
                            enabledProviders = setOf("ollama", "openai"),
                            defaultModels = mapOf("ollama" to "llama3.2", "openai" to "gpt-4o-mini"),
                            privacyMode = true,
                        ),
                    isKnown = { true },
                    isLocal = { it == "ollama" },
                    hasKey = { true },
                    fallbackModelFor = { "some-model" },
                )
            result.enabledProviders shouldBe setOf("ollama")
            result.defaultModels["openai"] shouldBe "gpt-4o-mini"
        }

        // ── computeLockReason (pure predicate — stands in for a custom-SPI provider) ───

        test("computeLockReason: a non-selectable provider is NOT_EXECUTABLE regardless of key/privacy state") {
            computeLockReason(isSelectable = false, isLocal = false, hasKey = false, privacyMode = false) shouldBe
                ProviderLockReason.NOT_EXECUTABLE
            computeLockReason(isSelectable = false, isLocal = true, hasKey = true, privacyMode = false) shouldBe
                ProviderLockReason.NOT_EXECUTABLE
        }

        test("computeLockReason: selectable cloud provider is BLOCKED_BY_PRIVACY before NEEDS_KEY") {
            computeLockReason(isSelectable = true, isLocal = false, hasKey = false, privacyMode = true) shouldBe
                ProviderLockReason.BLOCKED_BY_PRIVACY
        }

        test("computeLockReason: selectable, non-private, keyless cloud provider is NEEDS_KEY") {
            computeLockReason(isSelectable = true, isLocal = false, hasKey = false, privacyMode = false) shouldBe
                ProviderLockReason.NEEDS_KEY
        }

        test("computeLockReason: selectable local provider is always NONE") {
            computeLockReason(isSelectable = true, isLocal = true, hasKey = false, privacyMode = true) shouldBe
                ProviderLockReason.NONE
        }

        test("computeLockReason: dynamic-catalog cloud provider with a key but no chosen model is NEEDS_MODEL") {
            computeLockReason(
                isSelectable = true,
                isLocal = false,
                hasKey = true,
                privacyMode = false,
                hasDynamicCatalog = true,
                hasDefaultModel = false,
            ) shouldBe ProviderLockReason.NEEDS_MODEL
        }

        test("computeLockReason: NEEDS_KEY still wins over NEEDS_MODEL when both apply") {
            computeLockReason(
                isSelectable = true,
                isLocal = false,
                hasKey = false,
                privacyMode = false,
                hasDynamicCatalog = true,
                hasDefaultModel = false,
            ) shouldBe ProviderLockReason.NEEDS_KEY
        }

        test("computeLockReason: dynamic-catalog provider with a chosen model is NONE") {
            computeLockReason(
                isSelectable = true,
                isLocal = false,
                hasKey = true,
                privacyMode = false,
                hasDynamicCatalog = true,
                hasDefaultModel = true,
            ) shouldBe ProviderLockReason.NONE
        }
    })
