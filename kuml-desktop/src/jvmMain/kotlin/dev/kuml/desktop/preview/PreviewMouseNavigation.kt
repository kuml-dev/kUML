package dev.kuml.desktop.preview

import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.swing.gvt.GVTTreeRendererAdapter
import org.apache.batik.swing.gvt.GVTTreeRendererEvent
import org.apache.batik.swing.svg.SVGDocumentLoaderAdapter
import org.apache.batik.swing.svg.SVGDocumentLoaderEvent
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.pow

/** Zoom step shared by the preview's buttons and the Ctrl/Cmd + mouse-wheel zoom. */
internal const val PREVIEW_ZOOM_STEP = 1.25

/** Pixels a plain mouse-wheel notch moves the diagram. */
internal const val PREVIEW_WHEEL_PAN_PX = 48.0

/**
 * The few operations mouse navigation needs from the preview canvas. A seam instead of a direct
 * `JSVGCanvas` dependency so the gesture logic is unit-testable without a live Batik/Swing canvas.
 */
internal class PreviewNavigation(
    val transform: () -> AffineTransform,
    val setTransform: (AffineTransform) -> Unit,
    val fit: () -> Unit,
    val actualSize: () -> Unit,
    val center: () -> Unit,
)

/** Client-property key under which [createPreviewCanvas] stores the canvas' [PreviewNavigation]. */
internal const val PREVIEW_NAV_KEY = "kuml.preview.navigation"

/** [current] moved by ([dx], [dy]) screen pixels. */
internal fun pannedTransform(
    current: AffineTransform,
    dx: Double,
    dy: Double,
): AffineTransform = AffineTransform.getTranslateInstance(dx, dy).apply { concatenate(current) }

/**
 * Standard diagram-viewer mouse navigation for the preview, which Batik's `JSVGCanvas` lacks (its
 * own interactors hide behind Shift/Ctrl+Shift chords, and plain drag or wheel do nothing):
 *
 *  * drag with the left button — pan
 *  * mouse wheel / trackpad scroll — pan (Shift: horizontally)
 *  * Ctrl or Cmd + wheel — zoom around the pointer
 *  * double-click — fit to window (also Home or Ctrl/Cmd+0, see [installViewKeys])
 *
 * Gestures that carry a Shift/Ctrl/Alt/Meta modifier on a drag are left to Batik's own
 * interactors (Shift+drag pans, Ctrl+Shift+drag zooms), so nothing it offers is shadowed.
 */
internal fun installMouseNavigation(
    component: Component,
    navigation: PreviewNavigation,
) {
    val modifierMask = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
    var dragFrom: Point? = null

    val handler =
        object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Any button: keyboard shortcuts (Home, Ctrl/Cmd+0) act on the focused preview.
                component.requestFocusInWindow()
                if (SwingUtilities.isLeftMouseButton(e) && e.modifiersEx and modifierMask == 0) {
                    dragFrom = e.point
                    component.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val from = dragFrom ?: return
                val dx = (e.x - from.x).toDouble()
                val dy = (e.y - from.y).toDouble()
                dragFrom = e.point
                if (dx != 0.0 || dy != 0.0) {
                    navigation.setTransform(pannedTransform(current = navigation.transform(), dx = dx, dy = dy))
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragFrom != null) {
                    dragFrom = null
                    component.cursor = Cursor.getDefaultCursor()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) navigation.fit()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val rotation = e.preciseWheelRotation
                if (e.isControlDown || e.isMetaDown) {
                    val factor = PREVIEW_ZOOM_STEP.pow(-rotation)
                    navigation.setTransform(
                        zoomedTransform(
                            current = navigation.transform(),
                            factor = factor,
                            centerX = e.x.toDouble(),
                            centerY = e.y.toDouble(),
                        ),
                    )
                } else {
                    val delta = -rotation * PREVIEW_WHEEL_PAN_PX
                    val horizontal = e.isShiftDown
                    navigation.setTransform(
                        pannedTransform(
                            current = navigation.transform(),
                            dx = if (horizontal) delta else 0.0,
                            dy = if (horizontal) 0.0 else delta,
                        ),
                    )
                }
            }
        }
    component.addMouseListener(handler)
    component.addMouseMotionListener(handler)
    component.addMouseWheelListener(handler)
}

/** Platform menu-shortcut modifier (Cmd on macOS, Ctrl elsewhere); safe in headless mode. */
internal fun previewMenuMask(): Int =
    runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }.getOrElse {
        if (System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        ) {
            InputEvent.META_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK
        }
    }

/**
 * Binds Home and Ctrl/Cmd+0 to [fit] while [component] is focused (`WHEN_FOCUSED`, so the editor's
 * own Home key is untouched). 1:1 and Center deliberately have no shortcut: Ctrl/Cmd+1 belongs
 * to the "source" view mode.
 */
internal fun installViewKeys(
    component: JComponent,
    fit: () -> Unit,
) {
    val action =
        object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = fit()
        }
    val id = "kuml.preview.fit"
    component.getInputMap(JComponent.WHEN_FOCUSED).apply {
        put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), id)
        put(KeyStroke.getKeyStroke(KeyEvent.VK_0, previewMenuMask()), id)
    }
    component.actionMap.put(id, action)
}

/**
 * The preview's Batik canvas with [installMouseNavigation] wired to its rendering transform.
 * `selectableText = false`: a plain left-drag pans the diagram; with Batik's text-selection
 * interactor armed it would start selecting diagram text instead.
 *
 * Every transform change (drag, wheel, buttons, keys) goes through one path that keeps at least
 * a quarter of the diagram (and at least 96 px) in view; see [clampedTransform].
 */
internal fun createPreviewCanvas(): JSVGCanvas =
    JSVGCanvas(null, true, false).also { canvas ->
        var lastView = PreviewLastView.Fit
        var clampedForDocument = false

        fun viewport(): Pair<Double, Double> = canvas.width.toDouble() to canvas.height.toDouble()

        fun viewing(): AffineTransform = canvas.viewingTransform ?: AffineTransform()

        fun content(): Rectangle2D? =
            contentBoundsOf(doc = canvas.svgDocument) {
                val b = canvas.graphicsNode?.primitiveBounds ?: return@contentBoundsOf null
                runCatching { viewing().createInverse().createTransformedShape(b).bounds2D }.getOrNull()
            }

        fun current(): AffineTransform = canvas.renderingTransform ?: AffineTransform()

        fun clamp(t: AffineTransform): AffineTransform {
            val bounds = content() ?: return t
            val (w, h) = viewport()
            return clampedTransform(current = t, viewing = viewing(), content = bounds, viewportW = w, viewportH = h)
        }

        fun apply(t: AffineTransform) {
            if (t != current()) canvas.renderingTransform = t
        }

        val navigation =
            PreviewNavigation(
                transform = { current() },
                setTransform = {
                    lastView = PreviewLastView.Free
                    apply(clamp(it))
                },
                fit = {
                    lastView = PreviewLastView.Fit
                    canvas.resetRenderingTransform()
                    apply(clamp(current()))
                },
                actualSize = {
                    lastView = PreviewLastView.ActualSize
                    val bounds = content()
                    if (bounds != null) {
                        val (w, h) = viewport()
                        apply(clamp(actualSizeTransform(viewing = viewing(), content = bounds, viewportW = w, viewportH = h)))
                    }
                },
                center = {
                    lastView = PreviewLastView.Free
                    val bounds = content()
                    if (bounds != null) {
                        val (w, h) = viewport()
                        apply(
                            clamp(
                                centeredTransform(current = current(), viewing = viewing(), content = bounds, viewportW = w, viewportH = h),
                            ),
                        )
                    }
                },
            )
        canvas.putClientProperty(PREVIEW_NAV_KEY, navigation)
        canvas.isFocusable = true
        installMouseNavigation(component = canvas, navigation = navigation)
        installViewKeys(component = canvas, fit = navigation.fit)

        // Batik recomputes its fit transform on resize; run after it so 1:1 can be re-applied.
        canvas.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    SwingUtilities.invokeLater {
                        reapplyAfterResize(
                            lastView = lastView,
                            actualSize = { navigation.actualSize() },
                            clamp = { apply(clamp(current())) },
                        )
                    }
                }
            },
        )
        // A new document restarts the view at "fit"; clamp once after its first render.
        canvas.addSVGDocumentLoaderListener(
            object : SVGDocumentLoaderAdapter() {
                override fun documentLoadingStarted(e: SVGDocumentLoaderEvent) {
                    clampedForDocument = false
                    lastView = PreviewLastView.Fit
                }
            },
        )
        canvas.addGVTTreeRendererListener(
            object : GVTTreeRendererAdapter() {
                override fun gvtRenderingCompleted(e: GVTTreeRendererEvent) {
                    if (clampedForDocument) return
                    clampedForDocument = true
                    SwingUtilities.invokeLater { apply(clamp(current())) }
                }
            },
        )
    }
