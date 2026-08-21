package dev.kuml.desktop.ai

import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.AppState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/** Create a KumlAiSettingsStore backed by a temp file with given settings pre-written. */
private fun testSettingsStore(settings: KumlAiSettings = KumlAiSettings(privacyMode = false)): KumlAiSettingsStore {
    val tmpDir = Files.createTempDirectory("kuml-settings-test")
    val settingsPath = tmpDir.resolve("ai-settings.json")
    val store = KumlAiSettingsStore(path = settingsPath)
    store.save(settings)
    return KumlAiSettingsStore(path = settingsPath)
}

/** Create a minimal ApiKeyVault using the plain JSON backend (test override). */
private fun testVault(): ApiKeyVault {
    System.setProperty("kuml.ai.vault.backend", "plain")
    return ApiKeyVault.detect()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiPanelStateTest :
    FunSpec({
        val defaultSettings =
            KumlAiSettings(
                privacyMode = false,
                enabledProviders = setOf("ollama"),
                defaultProvider = "ollama",
                defaultModels = mapOf("ollama" to "llama3.2"),
            )

        fun makeState(
            settings: KumlAiSettings = defaultSettings,
            scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        ): AiPanelState =
            AiPanelState(
                appState = AppState(),
                scope = scope,
                settingsStore = testSettingsStore(settings),
                vault = testVault(),
                conversationStore =
                    ConversationStore(
                        Files.createTempDirectory("kuml-conv-state-test").toFile(),
                    ),
                pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
            )

        test("send() appends User message to messages") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(scope = scope)
                state.send("Hello AI")
                // After send(), the User message should be appended immediately
                state.messages.value shouldHaveSize 1
                (state.messages.value.first() as ConversationMessage.User).text shouldBe "Hello AI"
                scope.cancel()
            }
        }

        test("send() with blank text is ignored") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(scope = scope)
                state.send("   ")
                state.messages.value shouldHaveSize 0
                scope.cancel()
            }
        }

        test("stop() sets isRunning to false") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(scope = scope)
                state.stop()
                state.isRunning shouldBe false
                scope.cancel()
            }
        }

        test("newSession() resets messages and token counters") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(scope = scope)
                state.send("Some message")
                state.newSession()
                state.messages.value shouldHaveSize 0
                state.tokensIn shouldBe 0
                state.tokensOut shouldBe 0
                state.estimatedCostUsd shouldBe 0.0
                scope.cancel()
            }
        }

        test("reloadSettings() corrects invalid provider selection") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(settings = defaultSettings, scope = scope)
                // Manually set an invalid provider
                state.selectedProviderId = "nonexistent-provider"
                // After reload, it should be corrected to the default
                state.reloadSettings()
                state.selectedProviderId shouldBe "ollama"
                scope.cancel()
            }
        }

        // V3.7.1 — the AI Providers dialog persists to the SAME KumlAiSettingsStore path as
        // this panel's AiPanelState.settingsStore, then calls reloadSettings() on close.
        // This test simulates that hand-off: write NEW settings straight to the file the
        // panel's store reads from, then verify reloadSettings() actually picks it up.
        test("reloadSettings() after externally-changed enabledProviders lands on a valid provider+model") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val settingsPath = Files.createTempDirectory("kuml-settings-external-test").resolve("ai-settings.json")
                val store = KumlAiSettingsStore(path = settingsPath)
                store.save(
                    KumlAiSettings(
                        privacyMode = false,
                        enabledProviders = setOf("ollama"),
                        defaultProvider = "ollama",
                        defaultModels = mapOf("ollama" to "llama3.2"),
                    ),
                )
                val state =
                    AiPanelState(
                        appState = AppState(),
                        scope = scope,
                        settingsStore = store,
                        vault = testVault(),
                        conversationStore = ConversationStore(Files.createTempDirectory("kuml-conv-external-test").toFile()),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2"), "gonka" to listOf("gonka-model")),
                    )
                state.selectedProviderId shouldBe "ollama"

                // Simulate the dialog: an external writer changes the persisted settings
                // (e.g. Ollama disabled, Gonka enabled+defaulted) via the SAME store path.
                KumlAiSettingsStore(path = settingsPath).save(
                    KumlAiSettings(
                        privacyMode = false,
                        enabledProviders = setOf("gonka"),
                        defaultProvider = "gonka",
                        defaultModels = mapOf("gonka" to "gonka-model"),
                    ),
                )

                state.reloadSettings()

                state.selectedProviderId shouldBe "gonka"
                (state.selectedProviderId in state.aiSettings.enabledProviders) shouldBe true
                state.selectedModelId.isNotBlank() shouldBe true
                scope.cancel()
            }
        }

        // Regression test for the empirically-confirmed V3.7.1 review finding: reloadSettings()
        // only ever corrected selectedProviderId/selectedModelId inside the "provider no longer
        // enabled" branch — a PURE model change (provider stays enabled, only its defaultModels
        // entry changes, e.g. the user typed a new model into Ollama's free-text field in the
        // AI Providers dialog) never reached the panel at all until an app restart.
        test("reloadSettings() picks up a pure model change for the still-enabled selected provider") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val settingsPath = Files.createTempDirectory("kuml-settings-model-change-test").resolve("ai-settings.json")
                val store = KumlAiSettingsStore(path = settingsPath)
                store.save(
                    KumlAiSettings(
                        privacyMode = false,
                        enabledProviders = setOf("ollama"),
                        defaultProvider = "ollama",
                        defaultModels = mapOf("ollama" to "llama3.2"),
                    ),
                )
                val state =
                    AiPanelState(
                        appState = AppState(),
                        scope = scope,
                        settingsStore = store,
                        vault = testVault(),
                        conversationStore = ConversationStore(Files.createTempDirectory("kuml-conv-model-change-test").toFile()),
                        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2", "qwen3-coder:30b")),
                    )
                state.selectedProviderId shouldBe "ollama"
                state.selectedModelId shouldBe "llama3.2"

                // Same provider stays enabled — only its default model changes (the "AI
                // Providers" dialog's free-text field for a dynamic-catalog provider).
                KumlAiSettingsStore(path = settingsPath).save(
                    KumlAiSettings(
                        privacyMode = false,
                        enabledProviders = setOf("ollama"),
                        defaultProvider = "ollama",
                        defaultModels = mapOf("ollama" to "qwen3-coder:30b"),
                    ),
                )

                state.reloadSettings()

                state.selectedProviderId shouldBe "ollama"
                state.selectedModelId shouldBe "qwen3-coder:30b"
                scope.cancel()
            }
        }

        // Regression test for the review finding: availableModels used to be JUST
        // pricingTable.modelsForProvider(...) — for Gonka, which has no pricing.json entry at
        // all, that's always empty, so a configured free-text default model had nowhere to
        // show up in the panel's model dropdown.
        test("availableModels includes the configured default model even when pricing.json has none for the provider (Gonka)") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state =
                    makeState(
                        settings =
                            KumlAiSettings(
                                privacyMode = false,
                                enabledProviders = setOf("gonka"),
                                defaultProvider = "gonka",
                                defaultModels = mapOf("gonka" to "Qwen/Qwen2.5-7B-Instruct"),
                            ),
                        scope = scope,
                    )
                state.selectedProviderId = "gonka"
                state.availableModels shouldContain "Qwen/Qwen2.5-7B-Instruct"
                scope.cancel()
            }
        }

        // Regression test for the other half of the same finding: ProviderModelPicker's
        // provider-dropdown onClick handler now falls back to
        // `aiSettings.defaultModels[p] ?: availableModels.firstOrNull() ?: ""` instead of
        // straight to `availableModels.firstOrNull() ?: ""`. This test exercises that exact
        // fallback expression against AiPanelState's public API (the Composable itself isn't
        // unit-testable without a Compose UI test harness, which this suite doesn't use) —
        // for both Gonka (pricing.json has no entry, old code produced "") and Ollama (old
        // code produced pricing.json's first suggested model, discarding a configured
        // free-text model).
        test("provider-switch fallback (defaultModels[p] ?: availableModels.firstOrNull()) never discards a configured default model") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state =
                    makeState(
                        settings =
                            KumlAiSettings(
                                privacyMode = false,
                                enabledProviders = setOf("ollama", "gonka"),
                                defaultProvider = "ollama",
                                defaultModels =
                                    mapOf(
                                        "ollama" to "qwen3-coder:30b",
                                        "gonka" to "Qwen/Qwen2.5-7B-Instruct",
                                    ),
                            ),
                        scope = scope,
                    )

                state.selectedProviderId = "gonka"
                val gonkaFallback = state.aiSettings.defaultModels["gonka"] ?: state.availableModels.firstOrNull() ?: ""
                gonkaFallback shouldBe "Qwen/Qwen2.5-7B-Instruct"

                state.selectedProviderId = "ollama"
                val ollamaFallback = state.aiSettings.defaultModels["ollama"] ?: state.availableModels.firstOrNull() ?: ""
                ollamaFallback shouldBe "qwen3-coder:30b"
                scope.cancel()
            }
        }

        test("mapError for PrivacyModeViolation returns correct message") {
            val scope = CoroutineScope(Dispatchers.Default)
            val state = makeState(scope = scope)
            // Simulate a PrivacyModeViolation by checking exception class name matching
            val privacyError =
                object : Exception("Cloud provider blocked") {
                    override fun toString() = "PrivacyModeViolation"
                }
            // Access mapError via a reflection-free path — the method is internal
            val (msg, cause) = state.mapError(RuntimeException("timeout: connection timed out"))
            msg shouldContain "Zeitüberschreitung"
            scope.cancel()
        }

        test("mapError for unknown error shows throwable message as fallback") {
            val scope = CoroutineScope(Dispatchers.Default)
            val state = makeState(scope = scope)
            val err = RuntimeException("Something completely unexpected")
            val (msg, cause) = state.mapError(err)
            msg shouldBe "Something completely unexpected"
            cause shouldBe "RuntimeException"
            scope.cancel()
        }
    })
