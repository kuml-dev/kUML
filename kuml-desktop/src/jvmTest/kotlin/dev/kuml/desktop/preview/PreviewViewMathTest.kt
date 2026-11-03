package dev.kuml.desktop.preview

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

class PreviewViewMathTest :
    FunSpec({
        val content = Rectangle2D.Double(0.0, 0.0, 200.0, 100.0)

        fun fit(
            scale: Double,
            tx: Double = 0.0,
            ty: Double = 0.0,
        ) = AffineTransform(scale, 0.0, 0.0, scale, tx, ty)

        fun screen(
            current: AffineTransform,
            viewing: AffineTransform,
        ): Rectangle2D = AffineTransform(current).apply { concatenate(viewing) }.createTransformedShape(content).bounds2D

        context("actualSizeTransform") {
            listOf(0.37, 1.0, 2.5).forEach { s ->
                test("scale 1.0 and centred for viewing scale $s with offset") {
                    val viewing = fit(s, 13.0, -7.0)
                    val r = actualSizeTransform(viewing = viewing, content = content, viewportW = 800.0, viewportH = 600.0)
                    val total = AffineTransform(r).apply { concatenate(viewing) }
                    total.scaleX shouldBe (1.0 plusOrMinus 1e-9)
                    total.scaleY shouldBe (1.0 plusOrMinus 1e-9)
                    val c = total.transform(Point2D.Double(100.0, 50.0), null)
                    c.x shouldBe (400.0 plusOrMinus 1e-9)
                    c.y shouldBe (300.0 plusOrMinus 1e-9)
                }
            }
            test("non-invertible viewing gives identity") {
                actualSizeTransform(
                    viewing = AffineTransform(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                    content = content,
                    viewportW = 800.0,
                    viewportH = 600.0,
                ) shouldBe
                    AffineTransform()
            }
            test("empty content gives identity") {
                actualSizeTransform(viewing = fit(1.0), content = Rectangle2D.Double(), viewportW = 800.0, viewportH = 600.0) shouldBe
                    AffineTransform()
            }
            test("NaN input gives identity") {
                actualSizeTransform(viewing = fit(Double.NaN), content = content, viewportW = 800.0, viewportH = 600.0) shouldBe
                    AffineTransform()
            }
        }

        context("centeredTransform") {
            test("centres and keeps the scale bit-identical") {
                val viewing = fit(2.0, 0.0, 50.0)
                val current = AffineTransform(1.7, 0.0, 0.0, 1.7, 500.0, -300.0)
                val r = centeredTransform(current = current, viewing = viewing, content = content, viewportW = 400.0, viewportH = 300.0)
                r.scaleX shouldBe current.scaleX
                r.scaleY shouldBe current.scaleY
                r.shearX shouldBe current.shearX
                r.shearY shouldBe current.shearY
                val s = screen(r, viewing)
                s.centerX shouldBe (200.0 plusOrMinus 1e-9)
                s.centerY shouldBe (150.0 plusOrMinus 1e-9)
            }
            test("a sheared input keeps its linear part") {
                val current = AffineTransform(1.2, 0.3, 0.1, 1.2, 40.0, 10.0)
                val r = centeredTransform(current = current, viewing = fit(1.0), content = content, viewportW = 400.0, viewportH = 300.0)
                r.scaleX shouldBe current.scaleX
                r.shearX shouldBe current.shearX
                r.shearY shouldBe current.shearY
                r.scaleY shouldBe current.scaleY
            }
        }

        context("clampedTransform") {
            val viewing = fit(2.0, 0.0, 50.0)
            val dirs = listOf(1e6 to 0.0, -1e6 to 0.0, 0.0 to 1e6, 0.0 to -1e6)
            dirs.forEach { (dx, dy) ->
                test("far shift ($dx, $dy) leaves exactly the required strip") {
                    val current = AffineTransform.getTranslateInstance(dx, dy)
                    val r = clampedTransform(current = current, viewing = viewing, content = content, viewportW = 800.0, viewportH = 600.0)
                    val s = screen(r, viewing)
                    val needX = minOf(s.width, maxOf(0.25 * s.width, 96.0))
                    val needY = minOf(s.height, maxOf(0.25 * s.height, 96.0))
                    if (dx > 0) s.minX shouldBe (800.0 - needX plusOrMinus 1e-6)
                    if (dx < 0) s.maxX shouldBe (needX plusOrMinus 1e-6)
                    if (dy > 0) s.minY shouldBe (600.0 - needY plusOrMinus 1e-6)
                    if (dy < 0) s.maxY shouldBe (needY plusOrMinus 1e-6)
                    r.scaleX shouldBe current.scaleX
                }
            }
            test("tiny zoom keeps the whole content visible") {
                val v = fit(0.1)
                val r =
                    clampedTransform(
                        current = AffineTransform.getTranslateInstance(5000.0, 5000.0),
                        viewing = v,
                        content = content,
                        viewportW = 800.0,
                        viewportH = 600.0,
                    )
                val s = screen(r, v)
                (s.minX >= -1e-9 && s.maxX <= 800.0 + 1e-9) shouldBe true
            }
            test("huge zoom keeps at least 96 px or a quarter") {
                val current = AffineTransform(5.0, 0.0, 0.0, 5.0, 1e7, 0.0)
                val r = clampedTransform(current = current, viewing = fit(1.0), content = content, viewportW = 800.0, viewportH = 600.0)
                val s = screen(r, fit(1.0))
                s.minX shouldBe (800.0 - 0.25 * s.width plusOrMinus 1e-3)
                r.scaleX shouldBe 5.0
            }
            test("unusable content or viewport returns the input object") {
                val cur = AffineTransform.getTranslateInstance(9e5, 0.0)
                clampedTransform(
                    current = cur,
                    viewing = viewing,
                    content = Rectangle2D.Double(),
                    viewportW = 800.0,
                    viewportH = 600.0,
                ) shouldBe
                    cur
                clampedTransform(
                    current = cur,
                    viewing = viewing,
                    content = Rectangle2D.Double(0.0, 0.0, 0.2, 0.2),
                    viewportW = 800.0,
                    viewportH = 600.0,
                ) shouldBe
                    cur
                clampedTransform(current = cur, viewing = viewing, content = content, viewportW = 0.0, viewportH = 0.0) shouldBe cur
                clampedTransform(current = cur, viewing = viewing, content = content, viewportW = 0.0, viewportH = 600.0) shouldBe cur
                clampedTransform(
                    current = cur,
                    viewing = viewing,
                    content = Rectangle2D.Double(0.0, 0.0, Double.NaN, 1.0),
                    viewportW = 800.0,
                    viewportH = 600.0,
                ) shouldBe
                    cur
                clampedTransform(current = cur, viewing = viewing, content = content, viewportW = Double.NaN, viewportH = 600.0) shouldBe
                    cur
                clampedTransform(
                    current = AffineTransform(Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0, 0.0, 0.0),
                    viewing = viewing,
                    content = content,
                    viewportW = 800.0,
                    viewportH = 600.0,
                ).scaleX shouldBe Double.POSITIVE_INFINITY
            }
            test("a transform inside the limits is returned as the same object") {
                val cur = AffineTransform.getTranslateInstance(10.0, 10.0)
                (
                    clampedTransform(
                        current = cur,
                        viewing = viewing,
                        content = content,
                        viewportW = 800.0,
                        viewportH = 600.0,
                    ) === cur
                ) shouldBe
                    true
            }
        }

        context("contentBoundsOf") {
            fun doc(attrs: String) = parseSvg("""<svg xmlns="http://www.w3.org/2000/svg" $attrs></svg>""")
            test("viewBox with offset") {
                contentBoundsOf(doc = doc("""viewBox="10 20 300 150" width="5" height="5""""), fallback = { null }) shouldBe
                    Rectangle2D.Double(10.0, 20.0, 300.0, 150.0)
            }
            test("width and height only") {
                contentBoundsOf(doc = doc("""width="320px" height="240""""), fallback = { null }) shouldBe
                    Rectangle2D.Double(0.0, 0.0, 320.0, 240.0)
            }
            test("mm units fall back to the callback") {
                val fallback = Rectangle2D.Double(1.0, 2.0, 3.0, 4.0)
                contentBoundsOf(doc = doc("""width="10mm" height="10mm""""), fallback = { fallback }) shouldBe fallback
            }
            test("garbage viewBox falls back and never throws") {
                contentBoundsOf(doc = doc("""viewBox="a b c d""""), fallback = { null }) shouldBe null
                contentBoundsOf(doc = doc("""viewBox="0 0 -1 5""""), fallback = { throw IllegalStateException() }) shouldBe null
                contentBoundsOf(doc = null, fallback = { null }) shouldBe null
                contentBoundsOf(doc = doc("""viewBox="0 0 NaN 5""""), fallback = { null }) shouldBe null
            }
        }
    })
