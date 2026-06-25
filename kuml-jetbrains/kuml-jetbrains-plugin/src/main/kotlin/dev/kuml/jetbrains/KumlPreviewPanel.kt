package dev.kuml.jetbrains

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.svg.SVGDocument
import java.awt.BorderLayout
import java.awt.CardLayout
import java.io.StringReader
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.SwingUtilities

/**
 * Live SVG preview panel for `.kuml.kts` files (V2.0.30).
 *
 * Renders the diagram declared in the currently edited script as a native SVG
 * inside the split editor, using Apache Batik's [JSVGCanvas]. The rendering
 * pipeline runs on a background thread to avoid blocking the EDT:
 *
 *  1. A document listener on the editor triggers [scheduleUpdate].
 *  2. [scheduleUpdate] debounces rapid keystrokes with a [debounceMs] delay.
 *  3. After the delay the render task runs on a background executor, calls
 *     [renderScript] (which delegates to [KumlPreviewRenderer.render]),
 *     parses the SVG string into a Batik [SVGDocument], and swaps it into
 *     the canvas on the EDT.
 *  4. The panel shows "Rendering…" while computing and "No diagram" when the
 *     script produces no renderable diagram or throws.
 *  5. Calling [dispose] cancels any pending timer and stops the render pool.
 *
 * ## Toolbar
 *
 * Six icon buttons above the canvas (with tooltip text):
 *  - **Fit to Window** — resets the canvas transform so the entire diagram fits.
 *  - **Fit Width** — scales so the diagram width fills the panel exactly.
 *  - **Fit Height** — scales so the diagram height fills the panel exactly.
 *  - **100%** — shows the diagram at its native 1:1 pixel scale.
 *  - **Zoom In** — enlarges the canvas view by a fixed 1.25× factor.
 *  - **Zoom Out** — shrinks the canvas view by a fixed 1.25× factor.
 *
 * Icons are loaded from [AllIcons.Graph] at first use, wrapped in [runCatching]
 * so that test environments without a running IntelliJ application context
 * degrade gracefully to labelled text buttons.
 *
 * ## IntelliJ integration
 *
 * This panel is standalone Swing — it does not depend on IntelliJ Platform
 * classes in its constructor so it can be instantiated in unit tests without
 * a running IDE. The only IDE coupling is in [KumlSplitEditorProvider], which
 * wires the panel to an open `TextEditor` via a `DocumentListener`.
 *
 * @param debounceMs Debounce delay in milliseconds. Overridable for tests.
 */
class KumlPreviewPanel(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : JPanel(BorderLayout()) {
    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 300L
        const val STATUS_RENDERING: String = "Rendering…"
        const val STATUS_NO_DIAGRAM: String = "No diagram"
        const val STATUS_READY: String = "Ready"
        const val DISABLE_SYSTEM_PROPERTY: String = "kuml.preview.disabled"

        private const val CARD_CANVAS = "canvas"
        private const val CARD_EMPTY = "empty"
        private const val ZOOM_STEP = 1.25

        // ── Toolbar icons ─────────────────────────────────────────────────────
        // AllIcons.Graph.* are from the IntelliJ Platform — wrapped in runCatching
        // so tests that run without a full application context still produce
        // functional (text-labelled) buttons instead of crashing at class init.

        private val ICON_FIT_WINDOW: Icon? by lazy {
            runCatching { com.intellij.icons.AllIcons.General.FitContent }.getOrNull()
        }
        private val ICON_FIT_WIDTH: Icon? by lazy {
            runCatching {
                com.intellij.openapi.util.IconLoader
                    .getIcon("/icons/toolbar/fit-width.svg", KumlPreviewPanel::class.java)
            }.getOrNull()
        }
        private val ICON_FIT_HEIGHT: Icon? by lazy {
            runCatching {
                com.intellij.openapi.util.IconLoader
                    .getIcon("/icons/toolbar/fit-height.svg", KumlPreviewPanel::class.java)
            }.getOrNull()
        }
        private val ICON_ZOOM_100: Icon? by lazy {
            runCatching { com.intellij.icons.AllIcons.General.ActualZoom }.getOrNull()
        }
        private val ICON_ZOOM_IN: Icon? by lazy {
            runCatching { com.intellij.icons.AllIcons.General.ZoomIn }.getOrNull()
        }
        private val ICON_ZOOM_OUT: Icon? by lazy {
            runCatching { com.intellij.icons.AllIcons.General.ZoomOut }.getOrNull()
        }
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Batik JSVGCanvas — the real SVG renderer. Created lazily inside
     * [initCanvas] so that headless-test environments can override with a
     * dummy and avoid the AWT toolkit initialisation.
     */
    internal val svgCanvas: JSVGCanvas by lazy { JSVGCanvas() }

    private val emptyLabel = JLabel(STATUS_NO_DIAGRAM, JLabel.CENTER)

    /** CardLayout to switch between the canvas and the "No diagram" label. */
    private val cardLayout = CardLayout()
    private val cardPanel =
        JPanel(cardLayout).also { cards ->
            cards.add(svgCanvas, CARD_CANVAS)
            cards.add(emptyLabel, CARD_EMPTY)
        }

    private val statusLabel = JLabel(STATUS_RENDERING)

    /** Current human-readable status — exposed for unit tests. */
    @Volatile
    var currentStatus: String = STATUS_RENDERING
        private set

    /** Last scheduled render task — cancelled when a newer one arrives. */
    private val pendingTask = AtomicReference<TimerTask?>(null)

    // isDaemon = true so the JVM exits without waiting for pending renders.
    private val debounceTimer = Timer("kuml-preview-debounce", true)

    @Volatile
    private var disposed = false

    // ── Render executor (single background thread) ────────────────────────────

    private val renderExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kuml-preview-render").also { it.isDaemon = true }
        }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    /** Toolbar with Fit / Zoom buttons. */
    val toolbar: JToolBar =
        JToolBar().also { bar ->
            bar.isFloatable = false
            bar.add(toolbarBtn(ICON_FIT_WINDOW, "⊡", "An Fenster anpassen") { fitToWindow() })
            bar.add(toolbarBtn(ICON_FIT_WIDTH, "↔", "Breite anpassen") { fitWidth() })
            bar.add(toolbarBtn(ICON_FIT_HEIGHT, "↕", "Höhe anpassen") { fitHeight() })
            bar.add(toolbarBtn(ICON_ZOOM_100, "100%", "100% (Originalgröße)") { actualZoom() })
            bar.addSeparator()
            bar.add(toolbarBtn(ICON_ZOOM_IN, "+", "Vergrößern") { zoomIn() })
            bar.add(toolbarBtn(ICON_ZOOM_OUT, "−", "Verkleinern") { zoomOut() })
        }

    init {
        add(toolbar, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule a fresh render after [debounceMs] milliseconds.
     *
     * Calling this method multiple times in quick succession cancels the
     * previous pending task so only the latest edit triggers an actual render.
     *
     * @param scriptText The current `.kuml.kts` text to render.
     * @param scriptName A label used for error messages and logging.
     */
    fun scheduleUpdate(
        scriptText: String,
        scriptName: String = "untitled.kuml.kts",
    ) {
        if (disposed) return

        // Cancel any pending render scheduled from the previous keystroke.
        pendingTask.getAndSet(null)?.cancel()

        val task =
            object : TimerTask() {
                override fun run() {
                    if (disposed) return
                    triggerRender(scriptText, scriptName)
                }
            }
        pendingTask.set(task)
        try {
            debounceTimer.schedule(task, debounceMs)
        } catch (_: IllegalStateException) {
            // Timer was already cancelled (panel disposed) — ignore.
        }
    }

    /**
     * Cancel pending renders and shut down background resources.
     * Safe to call multiple times.
     */
    fun dispose() {
        disposed = true
        pendingTask.getAndSet(null)?.cancel()
        debounceTimer.cancel()
        renderExecutor.shutdown()
    }

    // ── Private render pipeline ───────────────────────────────────────────────

    /**
     * Execute the actual render on the background thread pool.
     * Updates the panel on the EDT when done.
     */
    private fun triggerRender(
        scriptText: String,
        scriptName: String,
    ) {
        setStatusOnEdt(STATUS_RENDERING)
        renderExecutor.submit {
            if (disposed) return@submit
            val outcome = renderScript(scriptText, scriptName)
            if (disposed) return@submit
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                when (outcome) {
                    is KumlPreviewRenderer.Outcome.Svg -> {
                        val doc = parseSvg(outcome.svg)
                        if (doc != null) {
                            svgCanvas.setSVGDocument(doc)
                            cardLayout.show(cardPanel, CARD_CANVAS)
                            setStatus(STATUS_READY)
                        } else {
                            showMessage("SVG konnte nicht geparst werden", STATUS_NO_DIAGRAM)
                        }
                    }
                    is KumlPreviewRenderer.Outcome.Failure -> {
                        showMessage(outcome.message, STATUS_NO_DIAGRAM)
                    }
                    is KumlPreviewRenderer.Outcome.Empty -> {
                        showMessage(STATUS_NO_DIAGRAM, STATUS_NO_DIAGRAM)
                    }
                }
            }
        }
    }

    /** Show a (possibly multi-line) message in the empty/error card. */
    private fun showMessage(
        message: String,
        status: String,
    ) {
        cardLayout.show(cardPanel, CARD_EMPTY)
        // HTML so multi-line diagnostics (script errors, stack frames) wrap and
        // render with line breaks inside the JLabel.
        val html =
            "<html><div style='padding:8px;font-family:monospace;'>" +
                message
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br/>") +
                "</div></html>"
        emptyLabel.text = html
        emptyLabel.verticalAlignment = JLabel.TOP
        emptyLabel.horizontalAlignment = JLabel.LEFT
        setStatus(status)
    }

    /**
     * Runs the kUML rendering pipeline on the calling thread.
     * Returns the SVG string on success, `null` if no diagram is found or
     * compilation fails.
     *
     * **This method must be called from a background thread** — it invokes the
     * Kotlin scripting host which is CPU-intensive.
     */
    internal fun renderScript(
        scriptText: String,
        scriptName: String,
    ): KumlPreviewRenderer.Outcome {
        if (System.getProperty(DISABLE_SYSTEM_PROPERTY) != null) {
            return KumlPreviewRenderer.Outcome.Empty
        }
        return try {
            KumlPreviewRenderer.renderOutcome(scriptText, scriptName)
        } catch (t: Throwable) {
            // Throwable, nicht nur Exception: KumlScriptHost kann NoClassDefFoundError
            // (ein Error) werfen, wenn der Scripting-Host nicht im Plugin-Bundle liegt.
            // Würde das nicht gefangen, stirbt der Render-Thread und das Panel bleibt
            // bei "Rendering…" hängen.
            KumlPreviewRenderer.Outcome.Failure(
                "Render-Ausnahme: ${t::class.java.name}: ${t.message ?: "(keine Meldung)"}",
            )
        }
    }

    /**
     * Parse an SVG string into a Batik [SVGDocument].
     *
     * Returns `null` on any parse error so callers can show "No diagram"
     * gracefully instead of crashing.
     */
    internal fun parseSvg(svgString: String): SVGDocument? =
        try {
            val parser = XMLResourceDescriptor.getXMLParserClassName()
            val factory = SAXSVGDocumentFactory(parser)
            factory.createSVGDocument(
                "https://kuml.dev/preview",
                StringReader(svgString),
            )
        } catch (_: Exception) {
            null
        }

    // ── Toolbar helpers ───────────────────────────────────────────────────────

    /**
     * Create a toolbar [JButton] with an optional icon.
     * Falls back to [fallback] text if [icon] is null (e.g., in test environments
     * without an initialised IntelliJ application context).
     */
    private fun toolbarBtn(
        icon: Icon?,
        fallback: String,
        tooltip: String,
        action: () -> Unit,
    ): JButton =
        (if (icon != null) JButton(icon) else JButton(fallback)).also { btn ->
            btn.toolTipText = tooltip
            btn.isFocusable = false
            btn.addActionListener { action() }
        }

    /**
     * Read the SVG document's intrinsic dimensions in user units.
     *
     * Tries `width`/`height` attributes first, then falls back to the `viewBox`
     * third and fourth fields. Returns `null` if no SVG is loaded or the
     * dimensions cannot be parsed.
     */
    private fun svgDimensions(): Pair<Double, Double>? {
        val doc = svgCanvas.svgDocument ?: return null
        val root = doc.rootElement
        var w = root.getAttribute("width").removeSuffix("px").toDoubleOrNull()
        var h = root.getAttribute("height").removeSuffix("px").toDoubleOrNull()
        if (w != null && h != null && w > 0 && h > 0) return w to h
        val vb = root.getAttribute("viewBox").trim()
        if (vb.isNotEmpty()) {
            val parts = vb.split(Regex("""[\s,]+"""), limit = 4)
            if (parts.size == 4) {
                w = parts[2].toDoubleOrNull()
                h = parts[3].toDoubleOrNull()
                if (w != null && h != null && w > 0 && h > 0) return w to h
            }
        }
        return null
    }

    // ── Zoom / fit actions ────────────────────────────────────────────────────

    /** Reset to the auto-fit-to-window view (the Batik default). */
    private fun fitToWindow() {
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Scale so the diagram's full width exactly fills the panel, regardless of
     * the current letterbox fit direction.
     *
     * The implementation computes the viewing scale `v = min(cW/svgW, cH/svgH)`
     * that Batik applies as its initial fit-to-window transform, then sets the
     * rendering transform so the effective horizontal scale = `cW / svgW`.
     */
    private fun fitWidth() {
        val (svgW, svgH) = svgDimensions() ?: return
        val cW = svgCanvas.width.toDouble().takeIf { it > 0.0 } ?: return
        val cH = svgCanvas.height.toDouble().takeIf { it > 0.0 } ?: return
        val viewScale = minOf(cW / svgW, cH / svgH)
        val renderScale = (cW / svgW) / viewScale
        svgCanvas.setRenderingTransform(
            java.awt.geom.AffineTransform
                .getScaleInstance(renderScale, renderScale),
            true,
        )
    }

    /**
     * Scale so the diagram's full height exactly fills the panel.
     * Mirror of [fitWidth] for the vertical axis.
     */
    private fun fitHeight() {
        val (svgW, svgH) = svgDimensions() ?: return
        val cW = svgCanvas.width.toDouble().takeIf { it > 0.0 } ?: return
        val cH = svgCanvas.height.toDouble().takeIf { it > 0.0 } ?: return
        val viewScale = minOf(cW / svgW, cH / svgH)
        val renderScale = (cH / svgH) / viewScale
        svgCanvas.setRenderingTransform(
            java.awt.geom.AffineTransform
                .getScaleInstance(renderScale, renderScale),
            true,
        )
    }

    /**
     * Show the diagram at 1:1 scale — 1 SVG user-unit = 1 canvas pixel.
     *
     * Batik's initial viewing transform scales the SVG to fit the canvas
     * (`viewScale = min(cW/svgW, cH/svgH)`). To undo it, set the rendering
     * transform to `1 / viewScale`.
     */
    private fun actualZoom() {
        val (svgW, svgH) = svgDimensions() ?: return
        val cW = svgCanvas.width.toDouble().takeIf { it > 0.0 } ?: return
        val cH = svgCanvas.height.toDouble().takeIf { it > 0.0 } ?: return
        val viewScale = minOf(cW / svgW, cH / svgH)
        svgCanvas.setRenderingTransform(
            java.awt.geom.AffineTransform
                .getScaleInstance(1.0 / viewScale, 1.0 / viewScale),
            true,
        )
    }

    /** Zoom in by [ZOOM_STEP] × relative to the current rendering transform. */
    private fun zoomIn() {
        val at = svgCanvas.renderingTransform.clone() as java.awt.geom.AffineTransform
        at.scale(ZOOM_STEP, ZOOM_STEP)
        svgCanvas.setRenderingTransform(at, true)
    }

    /** Zoom out by [ZOOM_STEP] × relative to the current rendering transform. */
    private fun zoomOut() {
        val at = svgCanvas.renderingTransform.clone() as java.awt.geom.AffineTransform
        at.scale(1.0 / ZOOM_STEP, 1.0 / ZOOM_STEP)
        svgCanvas.setRenderingTransform(at, true)
    }

    private fun setStatusOnEdt(status: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            setStatus(status)
        } else {
            SwingUtilities.invokeLater { setStatus(status) }
        }
    }

    private fun setStatus(status: String) {
        currentStatus = status
        statusLabel.text = status
    }
}
