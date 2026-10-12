package dev.kuml.desktop.preview

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.batik.swing.gvt.GVTTreeRendererAdapter
import org.apache.batik.swing.gvt.GVTTreeRendererEvent
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PreviewMouseNavigationTest :
    FunSpec({
        class Fixture {
            val panel = JPanel()
            var transform = AffineTransform()
            var fitCalls = 0

            init {
                installMouseNavigation(
                    component = panel,
                    navigation =
                        PreviewNavigation(
                            transform = { transform },
                            setTransform = { transform = it },
                            fit = { fitCalls++ },
                        ),
                )
            }

            fun mouse(
                id: Int,
                x: Int,
                y: Int,
                modifiers: Int = InputEvent.BUTTON1_DOWN_MASK,
                button: Int = MouseEvent.BUTTON1,
                clicks: Int = 1,
            ) = panel.dispatchEvent(MouseEvent(panel, id, 0L, modifiers, x, y, clicks, false, button))

            fun wheel(
                rotation: Int,
                x: Int = 100,
                y: Int = 100,
                modifiers: Int = 0,
            ) = panel.dispatchEvent(
                MouseWheelEvent(
                    panel,
                    MouseEvent.MOUSE_WHEEL,
                    0L,
                    modifiers,
                    x,
                    y,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    rotation,
                ),
            )

            fun drag(
                fromX: Int,
                fromY: Int,
                toX: Int,
                toY: Int,
                modifiers: Int = InputEvent.BUTTON1_DOWN_MASK,
                button: Int = MouseEvent.BUTTON1,
            ) {
                mouse(MouseEvent.MOUSE_PRESSED, fromX, fromY, modifiers, button)
                mouse(MouseEvent.MOUSE_DRAGGED, toX, toY, modifiers, button)
                mouse(MouseEvent.MOUSE_RELEASED, toX, toY, modifiers, button)
            }
        }

        test("pannedTransform moves by the given pixels") {
            val result = pannedTransform(current = AffineTransform.getScaleInstance(2.0, 2.0), dx = 10.0, dy = -4.0)
            result.translateX shouldBe 10.0
            result.translateY shouldBe -4.0
            result.scaleX shouldBe 2.0
        }

        test("dragging with the left button pans the diagram by the mouse distance") {
            val f = Fixture()
            f.drag(fromX = 50, fromY = 60, toX = 80, toY = 40)
            f.transform.translateX shouldBe 30.0
            f.transform.translateY shouldBe -20.0
        }

        test("a drag is applied incrementally, so several drag events add up") {
            val f = Fixture()
            f.mouse(MouseEvent.MOUSE_PRESSED, 10, 10)
            f.mouse(MouseEvent.MOUSE_DRAGGED, 20, 10)
            f.mouse(MouseEvent.MOUSE_DRAGGED, 35, 25)
            f.mouse(MouseEvent.MOUSE_RELEASED, 35, 25)
            f.transform.translateX shouldBe 25.0
            f.transform.translateY shouldBe 15.0
        }

        test("the cursor switches to move while dragging and back afterwards") {
            val f = Fixture()
            f.mouse(MouseEvent.MOUSE_PRESSED, 1, 1)
            f.panel.cursor.type shouldBe Cursor.MOVE_CURSOR
            f.mouse(MouseEvent.MOUSE_RELEASED, 1, 1)
            f.panel.cursor.type shouldBe Cursor.DEFAULT_CURSOR
        }

        test("a Shift-drag is left to Batik's own pan interactor") {
            val f = Fixture()
            f.drag(fromX = 0, fromY = 0, toX = 40, toY = 40, modifiers = InputEvent.BUTTON1_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
            f.transform shouldBe AffineTransform()
        }

        test("a right-button drag does not pan") {
            val f = Fixture()
            f.drag(fromX = 0, fromY = 0, toX = 40, toY = 40, modifiers = InputEvent.BUTTON3_DOWN_MASK, button = MouseEvent.BUTTON3)
            f.transform shouldBe AffineTransform()
        }

        test("a drag event without a preceding press is ignored") {
            val f = Fixture()
            f.mouse(MouseEvent.MOUSE_DRAGGED, 40, 40)
            f.transform shouldBe AffineTransform()
        }

        test("a double-click fits the diagram to the window") {
            val f = Fixture()
            f.mouse(MouseEvent.MOUSE_CLICKED, 5, 5, clicks = 2)
            f.fitCalls shouldBe 1
        }

        test("a single click does not fit") {
            val f = Fixture()
            f.mouse(MouseEvent.MOUSE_CLICKED, 5, 5, clicks = 1)
            f.fitCalls shouldBe 0
        }

        test("a plain wheel scrolls vertically: up moves the content down") {
            val f = Fixture()
            f.wheel(rotation = -1)
            f.transform.translateY shouldBe PREVIEW_WHEEL_PAN_PX
            f.transform.translateX shouldBe 0.0
            f.wheel(rotation = 2)
            f.transform.translateY shouldBe (PREVIEW_WHEEL_PAN_PX - 2 * PREVIEW_WHEEL_PAN_PX)
        }

        test("Shift + wheel scrolls horizontally") {
            val f = Fixture()
            f.wheel(rotation = -1, modifiers = InputEvent.SHIFT_DOWN_MASK)
            f.transform.translateX shouldBe PREVIEW_WHEEL_PAN_PX
            f.transform.translateY shouldBe 0.0
        }

        test("Ctrl + wheel up zooms in and keeps the point under the pointer fixed") {
            val f = Fixture()
            val before = Point2D.Double(120.0, 80.0)
            f.wheel(rotation = -1, x = 120, y = 80, modifiers = InputEvent.CTRL_DOWN_MASK)
            f.transform.scaleX shouldBe (PREVIEW_ZOOM_STEP plusOrMinus 1e-9)
            val after = f.transform.transform(before, null)
            after.x shouldBe (120.0 plusOrMinus 1e-9)
            after.y shouldBe (80.0 plusOrMinus 1e-9)
        }

        test("Cmd + wheel down zooms out") {
            val f = Fixture()
            f.wheel(rotation = 1, modifiers = InputEvent.META_DOWN_MASK)
            f.transform.scaleX shouldBe (1.0 / PREVIEW_ZOOM_STEP plusOrMinus 1e-9)
        }
        test("the real preview canvas pans on a left-drag and zooms on Ctrl + wheel") {
            System.setProperty("java.awt.headless", "true")
            val canvas = createPreviewCanvas()
            canvas.setSize(400, 300)
            val rendered = CountDownLatch(1)
            canvas.addGVTTreeRendererListener(
                object : GVTTreeRendererAdapter() {
                    override fun gvtRenderingCompleted(e: GVTTreeRendererEvent) = rendered.countDown()
                },
            )
            val svg =
                """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">""" +
                    """<rect x="10" y="10" width="180" height="80" fill="#1d2b4f"/></svg>"""
            SwingUtilities.invokeAndWait { canvas.setSVGDocument(parseSvg(svg)) }
            rendered.await(15, TimeUnit.SECONDS) shouldBe true

            fun send(event: java.awt.event.MouseEvent) = SwingUtilities.invokeAndWait { canvas.dispatchEvent(event) }
            val left = InputEvent.BUTTON1_DOWN_MASK
            send(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, left, 100, 100, 1, false, MouseEvent.BUTTON1))
            send(MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0L, left, 130, 90, 1, false, MouseEvent.BUTTON1))
            send(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, left, 130, 90, 1, false, MouseEvent.BUTTON1))
            val panned = canvas.renderingTransform
            panned shouldNotBe null
            panned.translateX shouldBe (30.0 plusOrMinus 1e-6)
            panned.translateY shouldBe (-10.0 plusOrMinus 1e-6)

            val scaleBefore = canvas.renderingTransform.scaleX
            SwingUtilities.invokeAndWait {
                canvas.dispatchEvent(
                    MouseWheelEvent(
                        canvas,
                        MouseEvent.MOUSE_WHEEL,
                        0L,
                        InputEvent.CTRL_DOWN_MASK,
                        200,
                        150,
                        0,
                        false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        1,
                        -1,
                    ),
                )
            }
            canvas.renderingTransform.scaleX shouldBe (scaleBefore * PREVIEW_ZOOM_STEP plusOrMinus 1e-6)
            canvas.dispose()
        }
    })
