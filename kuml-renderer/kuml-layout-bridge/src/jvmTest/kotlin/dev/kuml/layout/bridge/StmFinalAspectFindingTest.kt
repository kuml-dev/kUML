package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 73 (a): the state-machine aspect finding is computed from the final canvas. Pure arithmetic. */
class StmFinalAspectFindingTest :
    FunSpec({
        test("a ratio within the tolerance yields no finding") {
            stmAspectFinding(canvasWidth = 100.0, canvasHeight = 100.0, target = 1.0, directionExplicit = false) shouldBe null
        }

        test("an off-target ratio yields exactly one W003 carrying the measured value") {
            val finding = stmAspectFinding(canvasWidth = 405.0, canvasHeight = 614.0, target = 1.0, directionExplicit = false)
            finding?.code shouldBe LayoutCodes.ASPECT_UNREACHED
            finding?.message shouldContain "actual 0.66"
            finding?.message shouldNotContain "direction is set explicitly"
        }

        test("an explicit direction adds the rotation hint") {
            val finding = stmAspectFinding(canvasWidth = 405.0, canvasHeight = 614.0, target = 1.0, directionExplicit = true)
            finding?.message shouldContain " (direction is set explicitly; remove it to allow the flow to rotate)."
        }

        test("degenerate input yields no finding") {
            stmAspectFinding(canvasWidth = 0.0, canvasHeight = 10.0, target = 1.0, directionExplicit = false) shouldBe null
        }
    })
