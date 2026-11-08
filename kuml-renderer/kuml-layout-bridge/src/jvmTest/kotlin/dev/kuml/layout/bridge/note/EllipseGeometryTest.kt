package dev.kuml.layout.bridge.note

import dev.kuml.layout.Point
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Nacharbeit 42: every returned point lies on the drawn ellipse outline. */
class EllipseGeometryTest :
    FunSpec({
        val e = AnchorGeometry.Ellipse(cx = 200f, cy = 100f, rx = 140f, ry = 35f)

        test("onRay lies on the outline for the axes and for diagonals") {
            val targets =
                listOf(
                    Point(x = 500f, y = 100f),
                    Point(x = -100f, y = 100f),
                    Point(x = 200f, y = -50f),
                    Point(x = 200f, y = 400f),
                    Point(x = 450f, y = 20f),
                    Point(x = 10f, y = 260f),
                )
            for (t in targets) EllipseGeometry.residual(e = e, p = EllipseGeometry.onRay(e = e, toward = t)) shouldBeLessThanOrEqual 0.01f
        }

        test("onRay keeps the direction of the ray") {
            val p = EllipseGeometry.onRay(e = e, toward = Point(x = 500f, y = 100f))
            p.x shouldBe 340f
            p.y shouldBe 100f
            val q = EllipseGeometry.onRay(e = e, toward = Point(x = 200f, y = -300f))
            q.x shouldBe 200f
            q.y shouldBe 65f
        }

        test("horizontalHit and verticalHit lie on the outline, on the requested half") {
            for (y in listOf(70f, 85f, 100f, 120f, 130f)) {
                val left = EllipseGeometry.horizontalHit(e = e, y = y, fromLeft = true).shouldNotBeNull()
                val right = EllipseGeometry.horizontalHit(e = e, y = y, fromLeft = false).shouldNotBeNull()
                EllipseGeometry.residual(e = e, p = left) shouldBeLessThanOrEqual 0.01f
                EllipseGeometry.residual(e = e, p = right) shouldBeLessThanOrEqual 0.01f
                (left.x < e.cx) shouldBe true
                (right.x > e.cx) shouldBe true
            }
            for (x in listOf(100f, 150f, 200f, 260f, 300f)) {
                val top = EllipseGeometry.verticalHit(e = e, x = x, fromTop = true).shouldNotBeNull()
                val bottom = EllipseGeometry.verticalHit(e = e, x = x, fromTop = false).shouldNotBeNull()
                EllipseGeometry.residual(e = e, p = top) shouldBeLessThanOrEqual 0.01f
                EllipseGeometry.residual(e = e, p = bottom) shouldBeLessThanOrEqual 0.01f
                (top.y < e.cy) shouldBe true
                (bottom.y > e.cy) shouldBe true
            }
        }

        test("a projection at or beyond the extreme gives null") {
            EllipseGeometry.horizontalHit(e = e, y = 135f, fromLeft = true).shouldBeNull()
            EllipseGeometry.horizontalHit(e = e, y = 500f, fromLeft = true).shouldBeNull()
            EllipseGeometry.verticalHit(e = e, x = 340f, fromTop = true).shouldBeNull()
            EllipseGeometry.verticalHit(e = e, x = -10f, fromTop = false).shouldBeNull()
        }

        test("the outline is symmetric about both axes") {
            val a = EllipseGeometry.horizontalHit(e = e, y = 80f, fromLeft = false).shouldNotBeNull()
            val b = EllipseGeometry.horizontalHit(e = e, y = 120f, fromLeft = false).shouldNotBeNull()
            abs(a.x - b.x) shouldBeLessThanOrEqual 0.01f
            val c = EllipseGeometry.horizontalHit(e = e, y = 80f, fromLeft = true).shouldNotBeNull()
            abs((a.x - e.cx) - (e.cx - c.x)) shouldBeLessThanOrEqual 0.01f
        }

        test("leavesOutward tells an outward segment from one that runs through the ellipse") {
            val q = Point(x = 340f, y = 100f)
            EllipseGeometry.leavesOutward(e = e, q = q, prev = Point(x = 500f, y = 100f)) shouldBe true
            EllipseGeometry.leavesOutward(e = e, q = q, prev = Point(x = 0f, y = 100f)) shouldBe false
        }
    })
