package dev.kuml.desktop.ai

import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.AppState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Files

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun testSettingsStore(settings: KumlAiSettings = KumlAiSettings(privacyMode = false)): KumlAiSettingsStore {
    val tmpDir = Files.createTempDirectory("kuml-patch-test")
    val settingsPath = tmpDir.resolve("ai-settings.json")
    val store = KumlAiSettingsStore(settingsPath)
    store.save(settings)
    return KumlAiSettingsStore(settingsPath)
}

private fun testVault(): ApiKeyVault {
    System.setProperty("kuml.ai.vault.backend", "plain")
    return ApiKeyVault.detect()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun makeState(
    scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    appState: AppState = AppState(),
): AiPanelState {
    val settings =
        KumlAiSettings(
            privacyMode = false,
            enabledProviders = setOf("ollama"),
            defaultProvider = "ollama",
            defaultModels = mapOf("ollama" to "llama3.2"),
        )
    return AiPanelState(
        appState = appState,
        scope = scope,
        settingsStore = testSettingsStore(settings),
        vault = testVault(),
        conversationStore =
            ConversationStore(
                Files.createTempDirectory("kuml-patch-conv-test").toFile(),
            ),
        pricingTable = PricingTable.forTest("ollama" to listOf("llama3.2")),
    )
}

// ── Tests ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class AiPanelStatePatchTest :
    FunSpec({

        beforeEach {
            // Install a test Main dispatcher so withContext(Dispatchers.Main) works in tests
            Dispatchers.setMain(StandardTestDispatcher())
        }

        afterEach {
            Dispatchers.resetMain()
        }

        test("initial state: pendingPatches is empty and showPatchDialog is false") {
            val state = makeState()
            state.pendingPatches.value.shouldBeEmpty()
            state.showPatchDialog shouldBe false
        }

        test("newSession resets pendingPatches and sets showPatchDialog false") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher())
                val state = makeState(scope = scope)
                state.newSession()
                state.pendingPatches.value.shouldBeEmpty()
                state.showPatchDialog shouldBe false
            }
        }

        test("acceptAll when no patches — isApplying stays false, dialog closed") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState()
                state.acceptAll()
                state.isApplying shouldBe false
                state.showPatchDialog shouldBe false
            }
        }

        test("rejectAll when no patches — dialog stays closed, pendingPatches empty") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState()
                state.rejectAll()
                state.showPatchDialog shouldBe false
                state.pendingPatches.value.shouldBeEmpty()
            }
        }

        test("dismissPatchDialog sets showPatchDialog to false") {
            runTest {
                val state = makeState()
                state.dismissPatchDialog()
                state.showPatchDialog shouldBe false
            }
        }

        test("rejectAll triggers updateScriptFromModel — appState.script gets a DSL with classDiagram") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val appState = AppState()
                val state = makeState(appState = appState)
                state.rejectAll()
                appState.script shouldContain "classDiagram"
                appState.isDirty shouldBe true
            }
        }
    })
