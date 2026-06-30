package dev.kuml.jetbrains

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.svg.SVGDocument
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.StringReader
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JToolBar
import javax.swing.SwingUtilities

/**
 * Live SVG preview panel for `.kuml.kts` files.
 *
 * Renders the diagram declared in the currently edited script as a native SVG
 * inside the split editor, using Apache Batik's [JSVGCanvas] wrapped in a
 * [JScrollPane]. The scroll pane shows horizontal and vertical scrollbars
 * whenever the diagram exceeds the visible viewport — essential for large
 * diagrams that do not fit in the preview area at a comfortable zoom level.
 *
 * ## Zoom model
 *
 * Zoom and fit operations work by changing the **preferred size** of the
 * [svgCanvas], not via Batik's `setRenderingTransform`. Batik automatically
 * scales the loaded SVG to fill its actual component bounds, so resizing the
 * canvas IS the zoom:
 *
 * | Action | Canvas preferred size | Effect |
 * |---|---|---|
 * | Fit to Window | = viewport size | SVG scales to fit, no scrollbars |
 * | Fit Width | (viewW, viewW × svgH/svgW) | SVG width fills viewport, vertical scroll if tall |
 * | Fit Height | (viewH × svgW/svgH, viewH) | SVG height fills viewport, horizontal scroll if wide |
 * | 100% | = SVG natural size (px) | SVG at 1:1; scrollbars appear for large diagrams |
 * | Zoom In | × 1.25 per step | Canvas grows; scrollbars grow |
 * | Zoom Out | ÷ 1.25 per step | Canvas shrinks; scrollbars shrink |
 *
 * After loading a new SVG the canvas is initially sized to the SVG's natural
 * pixel dimensions (100 % zoom). If the diagram fits the viewport no scrollbars
 * appear; if it is larger than the viewport scrollbars become visible.
 *
 * ## Rendering pipeline
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
 * Buttons above the canvas: Fit / Zoom buttons, a Theme combobox, and an
 * Export dropdown. The theme combobox persists via [KumlPreviewSettings] (wired
 * by the split-editor provider); the export button delegates to [KumlExportAction].
 *
 * Icons are loaded from AllIcons at first use, wrapped in [runCatching]
 * so that test environments without a running IntelliJ application context
 * degrade gracefully to labelled text buttons.
 *
 * ## IntelliJ integration
 *
 * This panel is standalone Swing — it does not depend on IntelliJ Platform
 * classes in its constructor so it can be instantiated in unit tests without
 * a running IDE. The only IDE coupling is in [KumlSplitEditorProvider], which
 * wires the panel to an open `TextEditor` via a `DocumentListener` and injects
 * [initialTheme], [onThemeChanged], and [exportContext].
 *
 * @param debounceMs Debounce delay in milliseconds. Overridable for tests.
 * @param initialTheme Theme to pre-select in the combobox (default: "kuml").
 */
class KumlPreviewPanel(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    initialTheme: String = DEFAULT_THEME,
) : JPanel(BorderLayout()) {
    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 300L
        const val STATUS_RENDERING: String = "Rendering…"
        const val STATUS_NO_DIAGRAM: String = "No diagram"
        const val STATUS_READY: String = "Ready"
        const val DISABLE_SYSTEM_PROPERTY: String = "kuml.preview.disabled"

        /** Default theme — must match [KumlPreviewSettings.DEFAULT_THEME]. */
        const val DEFAULT_THEME: String = "kuml"

        /** Valid theme names exposed for the toolbar combobox. */
        val THEMES: List<String> = listOf("kuml", "plain", "elegant", "playful")

        private const val CARD_CANVAS = "canvas"
        private const val CARD_EMPTY = "empty"
        private const val ZOOM_STEP = 1.25

        // ── Toolbar icons ─────────────────────────────────────────────────────
        // AllIcons.* are from the IntelliJ Platform — wrapped in runCatching
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
        private val ICON_EXPORT: Icon? by lazy {
            runCatching { com.intellij.icons.AllIcons.ToolbarDecorator.Export }.getOrNull()
        }
    }

    // ── Theme state ───────────────────────────────────────────────────────────

    /**
     * Currently selected theme. Initialised from the constructor parameter,
     * updated by the combobox ItemListener. Never reads PropertiesComponent
     * directly — persistence is the provider's responsibility.
     */
    @Volatile
    var currentTheme: String = if (initialTheme in THEMES) initialTheme else DEFAULT_THEME

    /**
     * Optional callback invoked whenever the user changes the theme.
     * Set by [KumlSplitEditorProvider] to persist the selection via
     * [KumlPreviewSettings.setTheme]. Left `null` in headless tests.
     */
    var onThemeChanged: ((String) -> Unit)? = null

    // ── Export context ────────────────────────────────────────────────────────

    /**
     * IntelliJ platform context required for export (Project + VirtualFile +
     * live text supplier). Set by [KumlSplitEditorProvider] after construction.
     * `null` in headless test environments — the export button is a no-op then.
     */
    var exportContext: KumlExportContext? = null

    // ── Script cache (for theme-triggered re-renders) ─────────────────────────

    @Volatile
    private var lastScriptText: String = ""

    @Volatile
    private var lastScriptName: String = "untitled.kuml.kts"

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Batik JSVGCanvas — the real SVG renderer. Created lazily inside
     * [initCanvas] so that headless-test environments can override with a
     * dummy and avoid the AWT toolkit initialisation.
     */
    internal val svgCanvas: JSVGCanvas by lazy { JSVGCanvas() }

    /**
     * Natural pixel dimensions of the currently loaded SVG, or `null` if no
     * diagram has been rendered yet. Set on the EDT immediately after a
     * successful SVG parse in [triggerRender].
     */
    @Volatile
    internal var svgNaturalSize: Pair<Int, Int>? = null

    /**
     * Scroll pane wrapping [svgCanvas]. Horizontal and vertical scrollbars
     * appear automatically whenever the canvas preferred size exceeds the
     * visible viewport — i.e. when the diagram is larger than the panel at
     * the current zoom level.
     */
    internal val scrollPane: JScrollPane by lazy {
        JScrollPane(
            svgCanvas,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).also { sp ->
            sp.border = null // no default inset border
            sp.background = svgCanvas.background
        }
    }

    private val emptyLabel = JLabel(STATUS_NO_DIAGRAM, JLabel.CENTER)

    /** CardLayout to switch between the scroll pane and the "No diagram" label. */
    private val cardLayout = CardLayout()
    private val cardPanel =
        JPanel(cardLayout).also { cards ->
            cards.add(scrollPane, CARD_CANVAS)
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

    /** Toolbar with Fit / Zoom buttons, theme combobox, and export button. */
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
            bar.addSeparator()
            bar.add(JLabel("Theme: "))
            bar.add(buildThemeCombo())
            bar.addSeparator()
            bar.add(buildExportButton())
        }

    init {
        add(toolbar, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        installPanInteractor()
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

        // Cache for theme-triggered re-renders.
        lastScriptText = scriptText
        lastScriptName = scriptName

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
        val theme = currentTheme
        setStatusOnEdt(STATUS_RENDERING)
        renderExecutor.submit {
            if (disposed) return@submit
            val outcome = renderScript(scriptText, scriptName, theme)
            if (disposed) return@submit
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                when (outcome) {
                    is KumlPreviewRenderer.Outcome.Svg -> {
                        val doc = parseSvg(outcome.svg)
                        if (doc != null) {
                            svgCanvas.setSVGDocument(doc)
                            // Read natural dimensions from the freshly parsed document
                            // (not via svgCanvas.svgDocument — Batik loads async).
                            val dims = dimsFromDoc(doc)
                            svgNaturalSize = dims
                            if (dims != null) {
                                svgCanvas.preferredSize = Dimension(dims.first, dims.second)
                            }
                            scrollPane.revalidate()
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
        theme: String = currentTheme,
    ): KumlPreviewRenderer.Outcome {
        if (System.getProperty(DISABLE_SYSTEM_PROPERTY) != null) {
            return KumlPreviewRenderer.Outcome.Empty
        }
        return try {
            KumlPreviewRenderer.renderOutcome(scriptText, scriptName, theme)
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
     * Build the theme-selection combobox.
     *
     * Populated from [THEMES] with [currentTheme] pre-selected. The ItemListener
     * fires only on SELECTED events and only after the model is fully populated
     * (listener is attached after setSelectedItem to avoid spurious initial fires).
     */
    private fun buildThemeCombo(): JComboBox<String> {
        val combo = JComboBox<String>()
        combo.isFocusable = false
        combo.toolTipText = "Render-Theme auswählen"
        // Populate model first, then set selection — BEFORE adding the listener.
        THEMES.forEach { combo.addItem(it) }
        combo.selectedItem = currentTheme
        // Now attach listener so initial setSelectedItem does not fire it.
        combo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                val selected = e.item as? String ?: return@addItemListener
                currentTheme = selected
                onThemeChanged?.invoke(selected)
                // Re-render the cached script with the new theme.
                if (lastScriptText.isNotEmpty()) {
                    scheduleUpdate(lastScriptText, lastScriptName)
                }
            }
        }
        return combo
    }

    /**
     * Build the "Export As" toolbar button.
     *
     * Opens a [JPopupMenu] with entries for each [KumlExportFormat].
     * When [exportContext] is null (headless tests), menu items are disabled.
     */
    private fun buildExportButton(): JButton {
        val btn =
            if (ICON_EXPORT != null) JButton(ICON_EXPORT) else JButton("Export")
        btn.toolTipText = "Als SVG / PNG / TeX exportieren"
        btn.isFocusable = false

        val webpAvailable = KumlWebpSupport.isAvailable
        val menu = JPopupMenu()
        KumlExportFormat.entries.forEach { format ->
            val item = JMenuItem(format.displayName)
            if (format == KumlExportFormat.WEBP && !webpAvailable) {
                // Disable the Animated WebP entry when neither img2webp nor ffmpeg
                // is on PATH.  A clear tooltip explains the missing prerequisite so
                // users do not receive a cryptic CLI error deep in the export pipeline.
                item.isEnabled = false
                val webpTooltip =
                    "Animated WebP requires img2webp (libwebp) or ffmpeg on PATH. " +
                        "Install via 'brew install webp' (macOS) or 'apt-get install webp' (Debian/Ubuntu)."
                item.toolTipText = webpTooltip
            } else {
                item.addActionListener {
                    val ctx = exportContext ?: return@addActionListener
                    KumlExportAction.export(ctx, format, currentTheme)
                }
            }
            menu.add(item)
        }

        btn.addActionListener { e ->
            menu.show(btn, 0, btn.height)
        }
        return btn
    }

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
     * Extract pixel dimensions (width × height) from an [SVGDocument].
     *
     * Tries the `width`/`height` attributes on the root `<svg>` element first
     * (stripping any `px` suffix), then falls back to the third and fourth
     * fields of the `viewBox` attribute. Returns `null` if neither source
     * yields valid positive dimensions.
     *
     * Unlike [svgDimensions], this function takes the document object directly
     * instead of reading from [svgCanvas.svgDocument]. This is important in
     * [triggerRender] where the canvas may not yet have finished loading the
     * document asynchronously.
     */
    private fun dimsFromDoc(doc: SVGDocument): Pair<Int, Int>? {
        val root = doc.rootElement ?: return null
        var w = root.getAttribute("width").removeSuffix("px").toDoubleOrNull()
        var h = root.getAttribute("height").removeSuffix("px").toDoubleOrNull()
        if (w != null && h != null && w > 0 && h > 0) return w.toInt() to h.toInt()
        val vb = root.getAttribute("viewBox").trim()
        if (vb.isNotEmpty()) {
            val parts = vb.split(Regex("""[\s,]+"""), limit = 4)
            if (parts.size == 4) {
                w = parts[2].toDoubleOrNull()
                h = parts[3].toDoubleOrNull()
                if (w != null && h != null && w > 0 && h > 0) return w.toInt() to h.toInt()
            }
        }
        return null
    }

    // ── Zoom / fit actions ────────────────────────────────────────────────────
    //
    // All actions work by setting svgCanvas.preferredSize and calling
    // scrollPane.revalidate(). The JScrollPane viewport then sizes the canvas to
    //   max(preferredSize, viewportSize)
    // so scrollbars appear whenever the canvas is larger than the viewport.
    // Batik renders the SVG to fill the canvas actual bounds automatically,
    // which is the zoom effect.

    /**
     * Reset to fit-to-window: canvas fills the visible viewport, no scrollbars.
     *
     * Setting preferredSize to null lets the JScrollPane viewport stretch the
     * canvas to fill itself, which makes Batik scale the SVG to fit (with
     * letterboxing to preserve the SVG aspect ratio).
     */
    private fun fitToWindow() {
        svgCanvas.preferredSize = null
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Scale so the diagram's full width exactly fills the viewport width.
     *
     * The canvas is sized to (viewportWidth × (svgH/svgW)). If the result is
     * taller than the viewport, a vertical scrollbar appears. If the SVG is
     * wider than tall and the canvas is shorter than the viewport, the canvas
     * is expanded to viewport height (same as fit-to-window with letterboxing).
     */
    private fun fitWidth() {
        val nat = svgNaturalSize ?: return
        val (svgW, svgH) = nat.first.toDouble() to nat.second.toDouble()
        val vpWRaw = scrollPane.viewport.extentSize.width
        if (vpWRaw <= 0) return
        val vpW = vpWRaw.toDouble()
        val newH = (vpW * svgH / svgW).toInt().coerceAtLeast(1)
        svgCanvas.preferredSize = Dimension(vpW.toInt(), newH)
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Scale so the diagram's full height exactly fills the viewport height.
     *
     * Mirror of [fitWidth] for the vertical axis.
     */
    private fun fitHeight() {
        val nat = svgNaturalSize ?: return
        val (svgW, svgH) = nat.first.toDouble() to nat.second.toDouble()
        val vpHRaw = scrollPane.viewport.extentSize.height
        if (vpHRaw <= 0) return
        val vpH = vpHRaw.toDouble()
        val newW = (vpH * svgW / svgH).toInt().coerceAtLeast(1)
        svgCanvas.preferredSize = Dimension(newW, vpH.toInt())
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Show the diagram at 1:1 scale — 1 SVG user-unit = 1 canvas pixel.
     *
     * Sets the canvas preferred size to the SVG's natural dimensions and lets
     * the scroll pane show scrollbars if the diagram exceeds the viewport.
     */
    private fun actualZoom() {
        val (w, h) = svgNaturalSize ?: return
        svgCanvas.preferredSize = Dimension(w, h)
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Zoom in by [ZOOM_STEP] × relative to the current canvas preferred size.
     *
     * If the canvas has no explicit preferred size yet (still fit-to-window),
     * the current viewport extent is used as the starting point.
     */
    private fun zoomIn() {
        val cur = svgCanvas.preferredSize ?: scrollPane.viewport.extentSize
        svgCanvas.preferredSize =
            Dimension(
                (cur.width * ZOOM_STEP).toInt(),
                (cur.height * ZOOM_STEP).toInt(),
            )
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    /**
     * Zoom out by [ZOOM_STEP] × relative to the current canvas preferred size.
     *
     * Mirrors [zoomIn].
     */
    private fun zoomOut() {
        val cur = svgCanvas.preferredSize ?: scrollPane.viewport.extentSize
        svgCanvas.preferredSize =
            Dimension(
                (cur.width / ZOOM_STEP).toInt().coerceAtLeast(1),
                (cur.height / ZOOM_STEP).toInt().coerceAtLeast(1),
            )
        scrollPane.revalidate()
        svgCanvas.resetRenderingTransform()
    }

    // ── Pan interactor ────────────────────────────────────────────────────────

    /**
     * Install a left-click drag-to-pan interactor on [svgCanvas].
     *
     * Batik's built-in interactors are disabled first so they don't interfere
     * with our scroll-pane-aware navigation:
     *  - **ImageZoomInteractor** (mouse wheel → Batik rendering-transform zoom)
     *    is disabled so the mouse wheel bubbles up to the [JScrollPane] for
     *    natural vertical scrolling instead.
     *  - **PanInteractor** (Ctrl+Button1 drag → Batik rendering-transform pan)
     *    is disabled so plain left-click drag is free for our hand tool.
     *  - Zoom / rotate / reset interactors are disabled because all zoom
     *    operations go through the toolbar (preferred-size model).
     *
     * Our replacement:
     *  - **Left-click drag** → moves the scroll pane's view position (hand tool,
     *    cursor changes from open to closed hand while dragging).
     *  - **Mouse wheel (no modifier)** → dispatched to [scrollPane] for vertical
     *    scrolling (standard scroll-pane behaviour).
     *  - **Mouse wheel + Ctrl** → zooms via [zoomIn]/[zoomOut] (preferred-size
     *    model, scroll pane adjusts accordingly).
     */
    private fun installPanInteractor() {
        // Disable all Batik built-in interactors — they operate on the Batik
        // rendering transform, which is unrelated to the scroll-pane viewport
        // and would fight with our preferred-size zoom model.
        svgCanvas.enableZoomInteractor = false
        svgCanvas.enableImageZoomInteractor = false
        svgCanvas.enablePanInteractor = false
        svgCanvas.enableRotateInteractor = false
        svgCanvas.enableResetTransformInteractor = false

        val cursorHand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val cursorGrabbing = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)

        var dragOrigin: Point? = null

        val listener =
            object : MouseAdapter() {
                // ── cursor feedback ───────────────────────────────────────────
                override fun mouseEntered(e: MouseEvent) {
                    svgCanvas.cursor = cursorHand
                }

                override fun mouseExited(e: MouseEvent) {
                    svgCanvas.cursor = Cursor.getDefaultCursor()
                }

                // ── drag-to-pan ───────────────────────────────────────────────
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        dragOrigin = e.point
                        svgCanvas.cursor = cursorGrabbing
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        dragOrigin = null
                        svgCanvas.cursor = cursorHand
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val origin = dragOrigin ?: return
                    val vp = scrollPane.viewport
                    val pos = vp.viewPosition
                    val dx = origin.x - e.x
                    val dy = origin.y - e.y
                    val maxX = (svgCanvas.width - vp.width).coerceAtLeast(0)
                    val maxY = (svgCanvas.height - vp.height).coerceAtLeast(0)
                    vp.viewPosition =
                        Point(
                            (pos.x + dx).coerceIn(0, maxX),
                            (pos.y + dy).coerceIn(0, maxY),
                        )
                    // Update origin so delta is relative to last event, not the
                    // original press point — gives smooth, 1:1 panning.
                    dragOrigin = e.point
                }

                // ── mouse wheel: scroll or Ctrl+wheel zoom ────────────────────
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (e.isControlDown) {
                        // Ctrl+scroll → zoom in/out via preferred-size model
                        if (e.wheelRotation < 0) zoomIn() else zoomOut()
                    } else {
                        // Forward to the scroll pane for normal vertical scrolling
                        val converted =
                            SwingUtilities.convertMouseEvent(svgCanvas, e, scrollPane)
                        scrollPane.dispatchEvent(converted)
                    }
                }
            }

        svgCanvas.addMouseListener(listener)
        svgCanvas.addMouseMotionListener(listener)
        svgCanvas.addMouseWheelListener(listener)
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
