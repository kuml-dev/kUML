package dev.kuml.desktop.ai

import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.settings.KumlAiSettingsStore
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.desktop.AppState
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
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
    val store = KumlAiSettingsStore(path = settingsPath)
    store.save(settings)
    return KumlAiSettingsStore(path = settingsPath)
}

private fun testVault(): ApiKeyVault {
    System.setProperty("kuml.ai.vault.backend", "plain")
    return ApiKeyVault.detect()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun makeState(
    scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    appState: AppState = AppState(),
    // Fast, deterministic stand-in for the real ELK/theme render pipeline — lets tests drive
    // checkForTurnPatches's success AND render-failure branches (review finding 3) without the
    // cost/flakiness of a full DesktopRenderPipeline.render() call.
    renderFn: (String, String) -> DesktopRenderResult = { _, _ -> DesktopRenderResult.Svg("<svg/>") },
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
        renderFn = renderFn,
    )
}

/** Applies a real add-class mutation to [ctx] — mirrors what a `add_class` @Tool call does. */
private suspend fun addTestClass(
    ctx: AgentEditingContext,
    id: String,
    name: String,
) {
    val patch =
        ModelPatch.AddElement(
            patchId = ModelPatch.newId(),
            appliedAt = ModelPatch.nowIso(),
            diagramId = null,
            elementKind = "uml.class",
            elementId = id,
            name = name,
        )
    ctx.applyPatch(patch = patch) { model ->
        val uml = model as AnyKumlModel.Uml
        uml.copy(elements = uml.elements + UmlClass(id = id, name = name))
    }
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

        // V3.2.x — Fund 1 fix: rejectAll() must NEVER write the model back to appState.script.
        // The pre-fix version of this test asserted the OLD (buggy) behaviour — a reject that
        // silently overwrote the user's script with a DSL dump was the single worst finding of
        // the design-team review. This test now asserts the opposite: appState.script is left
        // completely untouched by a reject with nothing pending.
        test("rejectAll with nothing pending does not touch appState.script") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val appState = AppState()
                val originalScript = appState.script
                val state = makeState(appState = appState)
                state.rejectAll()
                appState.script shouldBe originalScript
                appState.isDirty shouldBe false
            }
        }

        test("turnPatches and turnPreviewSvg start empty") {
            val state = makeState()
            state.turnPatches.value.shouldBeEmpty()
            state.turnPreviewSvg.value shouldBe null
        }

        // ── seedEditingContextFromScript (V3.2.x turn-based flow) ───────────────────────

        test("seedEditingContextFromScript with a valid script seeds editingContext from it") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                // AppState()'s default script (WELCOME_SCRIPT) is a real, parseable UML
                // class diagram — exercises the actual KumlScriptHost.eval() path, not a stub.
                val state = makeState()
                state.seedEditingContextFromScript()
                state.canWriteScript shouldBe true
                val model = state.editingContext.resolveModel() as AnyKumlModel.Uml
                model.elements.filterIsInstance<UmlClass>().map { it.name } shouldContainExactlyInAnyOrder
                    listOf("Fahrzeug", "Motor")
            }
        }

        test("seedEditingContextFromScript with an unparsable script falls back to an empty model and sets canWriteScript false") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val appState = AppState()
                appState.script = "this is [[[ not valid kUML DSL at all ((("
                val state = makeState(appState = appState)
                state.seedEditingContextFromScript()
                state.canWriteScript shouldBe false
                val model = state.editingContext.resolveModel() as AnyKumlModel.Uml
                model.elements.shouldBeEmpty()
            }
        }

        // ── checkForTurnPatches (V3.2.x turn-based flow, incl. review finding 3) ─────────

        test("checkForTurnPatches is a no-op when the turn made no new patches") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState()
                state.editingContext = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.checkForTurnPatches(0)
                state.turnPatches.value.shouldBeEmpty()
                state.turnPreviewSvg.value shouldBe null
                state.showPatchDialog shouldBe false
            }
        }

        test("checkForTurnPatches with a real mutation and a successful render opens the dialog with a preview") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState(renderFn = { _, _ -> DesktopRenderResult.Svg("<svg>ok</svg>") })
                val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.editingContext = ctx
                addTestClass(ctx = ctx, id = "c1", name = "Foo")

                state.checkForTurnPatches(0)

                state.turnPatches.value shouldHaveSize 1
                state.turnPreviewSvg.value shouldBe "<svg>ok</svg>"
                state.showPatchDialog shouldBe true
            }
        }

        // Review finding 3: a render failure must NOT cause the turn's real mutation to be
        // silently dropped — the dialog still opens with turnPatches populated (previewSvg
        // just stays null). Whether per-item Accept/Reject buttons appear is PatchPreviewDialog's
        // concern (isTurnMode, driven by turnPatches.isNotEmpty()) — never previewSvg nullness.
        test("checkForTurnPatches with a real mutation and a FAILED render still opens the dialog with turnPatches populated") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState(renderFn = { _, _ -> DesktopRenderResult.Error("boom") })
                val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.editingContext = ctx
                addTestClass(ctx = ctx, id = "c1", name = "Foo")

                state.checkForTurnPatches(0)

                state.turnPatches.value shouldHaveSize 1
                state.turnPreviewSvg.value shouldBe null
                state.showPatchDialog shouldBe true
            }
        }

        // ── acceptAll / rejectAll in turn mode ───────────────────────────────────────────

        test("acceptAll in turn mode writes the mutated model back to appState.script and clears turn state") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val appState = AppState()
                val state = makeState(appState = appState)
                val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.editingContext = ctx
                state.canWriteScript = true
                addTestClass(ctx = ctx, id = "c1", name = "Foo")
                state.checkForTurnPatches(0)
                state.showPatchDialog shouldBe true

                state.acceptAll()

                state.turnPatches.value.shouldBeEmpty()
                state.turnPreviewSvg.value shouldBe null
                state.showPatchDialog shouldBe false
                appState.script shouldContain "Foo"
                appState.isDirty shouldBe true
            }
        }

        test("rejectAll in turn mode rolls back only this turn's mutation and never touches appState.script") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val appState = AppState()
                val originalScript = appState.script
                val state = makeState(appState = appState)
                val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.editingContext = ctx
                state.canWriteScript = true

                // Pre-turn baseline (mirrors runAgent() capturing lastTurnSnapshot /
                // patchCountBeforeTurn right before dispatching the current turn).
                addTestClass(ctx = ctx, id = "pre", name = "PreExisting")
                state.lastTurnSnapshot = ctx.snapshot()
                state.patchCountBeforeTurn = ctx.patches().size

                // This turn's real mutation — the one rejectAll must undo.
                addTestClass(ctx = ctx, id = "c1", name = "Foo")
                state.checkForTurnPatches(state.patchCountBeforeTurn)
                state.showPatchDialog shouldBe true

                state.rejectAll()

                state.turnPatches.value.shouldBeEmpty()
                state.turnPreviewSvg.value shouldBe null
                state.showPatchDialog shouldBe false
                appState.script shouldBe originalScript
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements.filterIsInstance<UmlClass>().map { it.name } shouldBe listOf("PreExisting")
            }
        }

        // ── send() guard against a pending patch dialog (review finding 1: race condition) ──

        test("send() is blocked while showPatchDialog is true, even though isRunning is already false") {
            runTest {
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val state = makeState()
                val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
                state.editingContext = ctx
                addTestClass(ctx = ctx, id = "c1", name = "Foo")
                state.checkForTurnPatches(0)
                state.showPatchDialog shouldBe true
                state.isRunning shouldBe false

                state.send("start a second turn while turn 1's dialog is still open")

                // Guarded before appendMessage() — a second turn must never start silently
                // underneath an unresolved confirmation dialog (see AiPanelState.send KDoc).
                state.messages.value.shouldBeEmpty()
            }
        }
    })
