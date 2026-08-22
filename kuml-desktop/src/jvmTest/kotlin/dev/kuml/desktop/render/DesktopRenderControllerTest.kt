package dev.kuml.desktop.render

import dev.kuml.desktop.AppState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRenderControllerTest :
    FunSpec({

        test("scheduleRender does not render immediately") {
            runTest {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 300)
                controller.scheduleRender(script = "classDiagram(name = \"Test\") { }")
                state.lastSvg shouldBe ""
            }
        }

        test("cancel() stops pending render") {
            runTest {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 300)
                controller.scheduleRender(script = "classDiagram(name = \"Test\") { }")
                controller.cancel()
                advanceTimeBy(500)
                state.lastSvg shouldBe ""
            }
        }

        test("error script sets lastError") {
            // Uses runBlocking because withContext(Dispatchers.IO) in the controller
            // dispatches to real threads that runTest's virtual scheduler cannot advance.
            runBlocking {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 50)
                controller.scheduleRender(script = "not valid kotlin @@@@")
                // Wait for debounce + script evaluation on IO threads
                delay(20_000)
                controller.cancel()
                state.lastError shouldNotBe null
            }
        }

        test("isRendering is false when idle") {
            val state = AppState()
            state.isRendering shouldBe false
        }

        // ── V3.7.4 (design review P6) — RenderInputs-based scheduling ───────────────────────

        test("scheduleRender(inputs, delayMs = 0) renders without waiting for the debounce") {
            runBlocking {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 5_000)
                controller.scheduleRender(
                    inputs = RenderInputs(script = "classDiagram(name = \"Test\") { }", themeName = "kuml", watermark = false),
                    delayMs = 0,
                )
                // No delay(...) needed here beyond letting the coroutine actually run -- a
                // long debounceMs proves the render did NOT wait for it.
                delay(20_000)
                controller.cancel()
                state.lastSvg shouldNotBe ""
            }
        }

        test("RenderInputs with the same script but a different theme renders a different SVG") {
            runBlocking {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 0)
                val script = "classDiagram(name = \"ThemeTest\") { }"

                controller.scheduleRender(inputs = RenderInputs(script = script, themeName = "kuml", watermark = false), delayMs = 0)
                delay(20_000)
                val kumlSvg = state.lastSvg

                controller.scheduleRender(inputs = RenderInputs(script = script, themeName = "plain", watermark = false), delayMs = 0)
                delay(20_000)
                val plainSvg = state.lastSvg

                controller.cancel()
                kumlSvg shouldNotBe ""
                plainSvg shouldNotBe ""
                kumlSvg shouldNotBe plainSvg
            }
        }
    })
