package dev.kuml.desktop.preview

import dev.kuml.desktop.simulation.highlightDiff
import org.apache.batik.swing.JSVGCanvas

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Flips the visibility of `highlight-ring-<id>` `<rect>` elements (emitted by
 * `KumlSvgRenderer.emitHighlightRings` via `SvgRenderOptions.preparedHighlightVertexIds`) in a
 * LIVE Batik [org.w3c.dom.svg.SVGDocument] — instead of setting a whole new document on every
 * simulation step, which would reset [JSVGCanvas.setSVGDocument]'s `renderingTransform`, i.e.
 * throw away the user's zoom and pan (see `PreviewPane.kt`).
 *
 * NOT thread-safe: call only from the Compose/EDT call path. The actual DOM mutation is
 * dispatched onto the Batik [org.apache.batik.bridge.UpdateManager]'s own runnable queue — never
 * write to the DOM from any other thread (Batik's GVT tree is single-threaded internally).
 */
internal class SimulationHighlightPatcher {
    private var applied: Set<String> = emptySet()

    /** Call after every `setSVGDocument` — the previous diff baseline no longer applies. */
    fun resetBaseline() {
        applied = emptySet()
    }

    /**
     * Applies [target] as the new set of visible highlight rings.
     *
     * @return `false` when the canvas isn't ready to accept a DOM patch yet (no
     *   [org.apache.batik.bridge.UpdateManager], or it hasn't started running) — the caller
     *   should retry once `documentReady` becomes `true` (see `PreviewPane.kt`).
     */
    fun apply(
        canvas: JSVGCanvas,
        target: Set<String>,
    ): Boolean {
        val manager = canvas.updateManager ?: return false
        if (!manager.isRunning) return false
        val doc = canvas.svgDocument ?: return false

        val diff = highlightDiff(previous = applied, current = target)
        if (diff.show.isEmpty() && diff.hide.isEmpty()) {
            applied = target
            return true
        }
        manager.updateRunnableQueue.invokeLater {
            for (id in diff.show) {
                doc.getElementById("highlight-ring-$id")?.setAttribute("visibility", "visible")
            }
            for (id in diff.hide) {
                doc.getElementById("highlight-ring-$id")?.setAttribute("visibility", "hidden")
            }
        }
        applied = target
        return true
    }
}
