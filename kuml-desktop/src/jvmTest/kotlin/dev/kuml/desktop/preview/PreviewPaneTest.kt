package dev.kuml.desktop.preview

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D

/**
 * Unit tests for [zoomedTransform] (P4, design review — Preview pane zoom controls).
 *
 * Extracted as a pure function over [AffineTransform] (a plain `java.awt.geom` value
 * type) so it is testable without a live Batik `JSVGCanvas`/Swing harness.
 */
class PreviewPaneTest :
    FunSpec({

        test("zooming in by 2x moves a point twice as far from the pivot") {
            val identity = AffineTransform()
            val zoomed = zoomedTransform(identity, factor = 2.0, centerX = 100.0, centerY = 100.0)

            // Pivot itself must not move.
            val pivotDst = Point2D.Double()
            zoomed.transform(Point2D.Double(100.0, 100.0), pivotDst)
            pivotDst.x shouldBe (100.0 plusOrMinus 1e-9)
            pivotDst.y shouldBe (100.0 plusOrMinus 1e-9)

            // The point 50px from the pivot should now be 100px from the pivot (2x).
            val src = Point2D.Double(150.0, 100.0)
            val dst = Point2D.Double()
            zoomed.transform(src, dst)
            dst.x shouldBe (200.0 plusOrMinus 1e-9)
        }

        test("zoom in followed by the inverse zoom out returns to the original transform") {
            val identity = AffineTransform()
            val zoomedIn = zoomedTransform(identity, factor = 1.25, centerX = 50.0, centerY = 50.0)
            val roundTripped = zoomedTransform(zoomedIn, factor = 1.0 / 1.25, centerX = 50.0, centerY = 50.0)

            val probe = Point2D.Double(37.0, 91.0)
            val expected = Point2D.Double()
            val actual = Point2D.Double()
            identity.transform(probe, expected)
            roundTripped.transform(probe, actual)

            actual.x shouldBe (expected.x plusOrMinus 1e-9)
            actual.y shouldBe (expected.y plusOrMinus 1e-9)
        }

        test("factor of 1.0 is a no-op regardless of pivot") {
            val current = AffineTransform.getTranslateInstance(10.0, 20.0)
            val result = zoomedTransform(current, factor = 1.0, centerX = 500.0, centerY = 500.0)

            val probe = Point2D.Double(3.0, 4.0)
            val expected = Point2D.Double()
            val actual = Point2D.Double()
            current.transform(probe, expected)
            result.transform(probe, actual)

            actual.x shouldBe (expected.x plusOrMinus 1e-9)
            actual.y shouldBe (expected.y plusOrMinus 1e-9)
        }
    })
