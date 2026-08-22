package dev.kuml.desktop

import dev.kuml.desktop.render.DesktopRenderController
import dev.kuml.desktop.render.RenderInputs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure-function tests for [renderDelayFor] -- the decision extracted out of MainWindow's
 * `LaunchedEffect(controller)` render-trigger collector (V3.7.4, design review P6) so it is
 * testable without a Compose runtime.
 */
class MainWindowRenderTriggerTest :
    FunSpec({

        test("first emission (previousScript == null) renders immediately") {
            val inputs = RenderInputs(script = "classDiagram(name = \"A\") { }", themeName = "kuml", watermark = false)
            renderDelayFor(previousScript = null, inputs = inputs) shouldBe 0L
        }

        test("only the theme changed (script identical) -> renders immediately") {
            val script = "classDiagram(name = \"A\") { }"
            val inputs = RenderInputs(script = script, themeName = "plain", watermark = false)
            renderDelayFor(previousScript = script, inputs = inputs) shouldBe 0L
        }

        test("only the watermark changed (script identical) -> renders immediately") {
            val script = "classDiagram(name = \"A\") { }"
            val inputs = RenderInputs(script = script, themeName = "kuml", watermark = true)
            renderDelayFor(previousScript = script, inputs = inputs) shouldBe 0L
        }

        test("the script itself changed -> debounced") {
            val inputs = RenderInputs(script = "classDiagram(name = \"B\") { }", themeName = "kuml", watermark = false)
            renderDelayFor(
                previousScript = "classDiagram(name = \"A\") { }",
                inputs = inputs,
            ) shouldBe DesktopRenderController.DEFAULT_DEBOUNCE_MS
        }
    })
