package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 74 (c): the use case aspect finding is computed from the final canvas, in both directions. Pure arithmetic. */
class UcFinalAspectFindingTest :
    FunSpec({
        test("a ratio within the tolerance yields no finding") {
            ucAspectFinding(canvasWidth = 100.0, canvasHeight = 100.0, target = 1.0, wrapColumnsExplicit = false) shouldBe null
        }

        test("too wide yields one W003 carrying the measured value") {
            val finding = ucAspectFinding(canvasWidth = 620.8, canvasHeight = 456.0, target = 1.0, wrapColumnsExplicit = false)
            finding?.code shouldBe LayoutCodes.ASPECT_UNREACHED
            finding?.message shouldContain "actual 1.36"
            finding?.message shouldNotContain "wrapColumns"
        }

        test("too narrow yields one W003 as well") {
            val finding = ucAspectFinding(canvasWidth = 420.0, canvasHeight = 882.0, target = 1.0, wrapColumnsExplicit = false)
            finding?.code shouldBe LayoutCodes.ASPECT_UNREACHED
            finding?.message shouldContain "actual 0.48"
        }

        test("an explicit wrapColumns adds the precedence hint") {
            val finding = ucAspectFinding(canvasWidth = 420.0, canvasHeight = 882.0, target = 1.0, wrapColumnsExplicit = true)
            finding?.message shouldContain " (wrapColumns is set explicitly; remove it to let aspectRatio choose the columns)."
        }

        test("degenerate input yields no finding") {
            ucAspectFinding(canvasWidth = 0.0, canvasHeight = 10.0, target = 1.0, wrapColumnsExplicit = false) shouldBe null
            ucAspectFinding(canvasWidth = 10.0, canvasHeight = -1.0, target = 1.0, wrapColumnsExplicit = false) shouldBe null
            ucAspectFinding(canvasWidth = 10.0, canvasHeight = 10.0, target = 0.0, wrapColumnsExplicit = false) shouldBe null
        }
    })
