package dev.kuml.desktop.preview

import org.apache.batik.swing.JSVGCanvas
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
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
)

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
 *  * double-click — fit to window
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

/**
 * The preview's Batik canvas with [installMouseNavigation] wired to its rendering transform.
 * `selectableText = false`: a plain left-drag pans the diagram; with Batik's text-selection
 * interactor armed it would start selecting diagram text instead.
 */
internal fun createPreviewCanvas(): JSVGCanvas =
    JSVGCanvas(null, true, false).also { canvas ->
        installMouseNavigation(
            component = canvas,
            navigation =
                PreviewNavigation(
                    transform = { canvas.renderingTransform ?: AffineTransform() },
                    setTransform = { canvas.renderingTransform = it },
                    fit = { canvas.resetRenderingTransform() },
                ),
        )
    }
