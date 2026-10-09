package dev.kuml.desktop.render

import dev.kuml.desktop.AppState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopRenderControllerRenderNowTest :
    FunSpec({
        val script = "import dev.kuml.uml.*\nclassDiagram(name = \"T\") { classOf(name = \"A\") { } }"

        test("renderNow returns the result and sets lastSvg") {
            runBlocking {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this)
                val result =
                    withTimeout(60_000) {
                        controller.renderNow(RenderInputs(script = script, themeName = state.theme, watermark = false)).await()
                    }
                result.shouldBeInstanceOf<DesktopRenderResult.Svg>()
                state.lastSvg.contains("<svg") shouldBe true
                state.lastError shouldBe null
            }
        }

        test("a later scheduleRender cancels the earlier renderNow job") {
            runBlocking {
                val state = AppState()
                val controller = DesktopRenderController(state = state, scope = this, debounceMs = 60_000)
                val first = controller.renderNow(RenderInputs(script = script, themeName = state.theme, watermark = false))
                controller.scheduleRender(
                    inputs = RenderInputs(script = script, themeName = state.theme, watermark = false),
                    delayMs = 60_000,
                )
                var cancelled = false
                try {
                    first.await()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                cancelled shouldBe true
                controller.cancel()
            }
        }
    })
