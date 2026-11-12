package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 83: the block definition diagram aspect finding is computed from the final canvas. Pure arithmetic. */
class BddFinalAspectFindingTest :
    FunSpec({
        test("a ratio within the tolerance yields no finding") {
            bddAspectFinding(canvasWidth = 100.0, canvasHeight = 100.0, target = 1.0, directionExplicit = false) shouldBe null
        }

        test("off target yields one W003 carrying the measured value and no hint") {
            val finding = bddAspectFinding(canvasWidth = 620.8, canvasHeight = 456.0, target = 1.0, directionExplicit = false)
            finding?.code shouldBe LayoutCodes.ASPECT_UNREACHED
            finding?.message shouldContain "actual 1.36"
            finding?.message shouldNotContain "direction"
        }

        test("an explicit direction adds the rotation hint") {
            val finding = bddAspectFinding(canvasWidth = 420.0, canvasHeight = 882.0, target = 1.0, directionExplicit = true)
            finding?.message shouldContain " (direction is set explicitly; remove it to allow the flow to rotate)."
        }

        test("degenerate input yields no finding") {
            bddAspectFinding(canvasWidth = 0.0, canvasHeight = 10.0, target = 1.0, directionExplicit = false) shouldBe null
        }
    })
