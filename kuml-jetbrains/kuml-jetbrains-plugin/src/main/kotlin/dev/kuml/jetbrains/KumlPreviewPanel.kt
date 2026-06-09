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
 * Three [JButton]s above the canvas:
 *  - **Fit to Window** — resets the canvas transform so the entire diagram fits.
 *  - **100%** — shows the diagram at its native 1:1 pixel scale.
 *  - **Zoom In** — enlarges the canvas view by a fixed 1.25× factor.
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

    /** Toolbar with Fit / 100% / Zoom In buttons. */
    val toolbar: JToolBar =
        JToolBar().also { bar ->
            bar.isFloatable = false

            val fitBtn = JButton("Fit to Window")
            fitBtn.addActionListener { svgCanvas.resetRenderingTransform() }
            bar.add(fitBtn)

            val oneToOneBtn = JButton("100%")
            oneToOneBtn.addActionListener {
                svgCanvas.resetRenderingTransform()
            }
            bar.add(oneToOneBtn)

            val zoomInBtn = JButton("Zoom In")
            zoomInBtn.addActionListener {
                val at = svgCanvas.renderingTransform.clone() as java.awt.geom.AffineTransform
                at.scale(ZOOM_STEP, ZOOM_STEP)
                svgCanvas.setRenderingTransform(at, true)
            }
            bar.add(zoomInBtn)
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
            val svgOrNull = renderScript(scriptText, scriptName)
            if (disposed) return@submit
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                if (svgOrNull != null) {
                    val doc = parseSvg(svgOrNull)
                    if (doc != null) {
                        svgCanvas.setSVGDocument(doc)
                        cardLayout.show(cardPanel, CARD_CANVAS)
                        setStatus(STATUS_READY)
                    } else {
                        cardLayout.show(cardPanel, CARD_EMPTY)
                        emptyLabel.text = STATUS_NO_DIAGRAM
                        setStatus(STATUS_NO_DIAGRAM)
                    }
                } else {
                    cardLayout.show(cardPanel, CARD_EMPTY)
                    emptyLabel.text = STATUS_NO_DIAGRAM
                    setStatus(STATUS_NO_DIAGRAM)
                }
            }
        }
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
    ): String? {
        if (System.getProperty(DISABLE_SYSTEM_PROPERTY) != null) return null
        return try {
            KumlPreviewRenderer.render(scriptText, scriptName)
        } catch (_: Exception) {
            null
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
