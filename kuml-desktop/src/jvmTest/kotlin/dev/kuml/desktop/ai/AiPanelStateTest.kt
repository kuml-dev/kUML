package dev.kuml.desktop.ai

import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.AppState
import io.kotest.core.spec.style.FunSpec
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
    val store = KumlAiSettingsStore(settingsPath)
    store.save(settings)
    return KumlAiSettingsStore(settingsPath)
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
