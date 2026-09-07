package dev.kuml.desktop.preview

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.batik.swing.JSVGCanvas

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Review fix — [SimulationHighlightPatcher] was the one new stateful non-Compose class in this
 * wave without a single test (only the pure [highlightDiff] helper it delegates to was covered).
 *
 * `JSVGCanvas` CAN be constructed headlessly (unlike this module's Compose UI, which has no test
 * harness at all — see `PreviewPaneTest`'s KDoc), so the guard branches below are real coverage,
 * not a mock. What this deliberately does NOT attempt: driving [SimulationHighlightPatcher.apply]
 * through an actual DOM mutation. That needs a live Batik [org.apache.batik.bridge.UpdateManager]
 * — which starts only once the canvas is a REALIZED, shown Swing component running its own
 * rendering thread against a real `GraphicsEnvironment` — confirmed experimentally in this
 * sandbox: `JSVGCanvas.setSVGDocument` + `ALWAYS_DYNAMIC` never fires `managerStarted` under
 * `-Djava.awt.headless=true` with no display (a `CountDownLatch.await` on it timed out at 5s on
 * every attempt), and headless=false has nothing to render to here either (no Xvfb). This is the
 * same constraint `PreviewPane.kt`'s own `documentReady` gate exists to respect in production
 * code, so a test asserting the post-apply DOM state would either hang/skip in exactly this CI
 * environment or need a real display — not worth the flakiness for a MINOR-severity gap.
 */
class SimulationHighlightPatcherTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")

        test("apply returns false before any SVG document has ever been set (no UpdateManager yet)") {
            val canvas = JSVGCanvas()
            val patcher = SimulationHighlightPatcher()
            patcher.apply(canvas = canvas, target = setOf("Red")) shouldBe false
        }

        test("apply keeps returning false on repeated calls against the same document-less canvas") {
            val canvas = JSVGCanvas()
            val patcher = SimulationHighlightPatcher()
            patcher.apply(canvas = canvas, target = setOf("Red")) shouldBe false
            patcher.apply(canvas = canvas, target = setOf("Green")) shouldBe false
            patcher.apply(canvas = canvas, target = emptySet()) shouldBe false
        }

        test("resetBaseline is safe to call before any apply(), and apply() afterwards is unaffected") {
            val canvas = JSVGCanvas()
            val patcher = SimulationHighlightPatcher()
            patcher.resetBaseline() // must not throw
            patcher.apply(canvas = canvas, target = setOf("Red")) shouldBe false
        }
    })
