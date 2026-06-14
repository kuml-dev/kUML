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
class DesktopRenderControllerTest : FunSpec({

    test("scheduleRender does not render immediately") {
        runTest {
            val state = AppState()
            val controller = DesktopRenderController(state, this, debounceMs = 300)
            controller.scheduleRender("classDiagram(name = \"Test\") { }")
            state.lastSvg shouldBe ""
        }
    }

    test("cancel() stops pending render") {
        runTest {
            val state = AppState()
            val controller = DesktopRenderController(state, this, debounceMs = 300)
            controller.scheduleRender("classDiagram(name = \"Test\") { }")
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
            val controller = DesktopRenderController(state, this, debounceMs = 50)
            controller.scheduleRender("not valid kotlin @@@@")
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
})
