package dev.kuml.io.svg.sysml2.edge

import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class StmLabelPlacerTest :
    FunSpec({
        fun p(
            t: String,
            k: EdgeLabelKind = EdgeLabelKind.Trigger,
        ) = EdgeLabelPart(text = t, kind = k)

        val guard60 = "stroemungOk and not stbAusgeloest and druckImBereich(p1,p22)"

        /** The text of all lines without separators; a trailing hyphen of a hard break is dropped. */
        fun glued(lines: List<StmLabelLine>) =
            lines.joinToString("") { (if (it.hyphenated) it.text.dropLast(1) else it.text).replace(" ", "") }

        test("maxChars follows the width") {
            StmLabelPlacer.maxChars(220f) shouldBe 30
            StmLabelPlacer.maxChars(120f) shouldBe 16
            StmLabelPlacer.maxChars(400f) shouldBe 55
        }

        test("short label stays on one line with all parts") {
            val lines = StmLabelPlacer.wrap(parts = listOf(p("go"), p("[x > 1]", EdgeLabelKind.Guard)))
            lines.size shouldBe 1
            lines.single().parts.size shouldBe 2
        }

        test("a 60 character guard wraps to several lines and nothing is cut") {
            guard60.length shouldBe 60
            val lines = StmLabelPlacer.wrap(parts = listOf(p("[$guard60]", EdgeLabelKind.Guard)))
            (lines.size >= 2) shouldBe true
            lines.forEach { it.text shouldNotContain "…" }
            glued(lines) shouldBe "[$guard60]".replace(" ", "")
            val max = StmLabelPlacer.maxChars(StmLabelPlacer.DEFAULT_MAX_WIDTH_PX)
            lines.forEach { (it.text.length <= max) shouldBe true }
        }

        test("every part starts on its own line once the label is too wide") {
            val lines =
                StmLabelPlacer.wrap(
                    parts = listOf(p("trigger"), p("[" + guard60 + "]", EdgeLabelKind.Guard), p("/ effect", EdgeLabelKind.Effect)),
                )
            lines.first().text shouldBe "trigger"
            lines.last().text shouldBe "/ effect"
        }

        test("words are not split in the middle when they fit a line") {
            val lines =
                StmLabelPlacer.wrap(
                    parts = listOf(p("[CLOSED and OPEN or CLOSED and OPEN or CLOSED and OPEN]", EdgeLabelKind.Guard)),
                )
            lines.none { it.hyphenated } shouldBe true
            lines.forEach { it.text.trim() shouldBe it.text }
        }

        test("a slash stays glued to the following word") {
            val lines = StmLabelPlacer.wrap(parts = listOf(p("/ " + "doSomethingLong ".repeat(5).trim(), EdgeLabelKind.Effect)))
            lines.first().text.startsWith("/ doSomethingLong") shouldBe true
        }

        test("an overlong word breaks hard with a hyphen") {
            val lines = StmLabelPlacer.wrap(parts = listOf(p("x".repeat(100))))
            (lines.size >= 4) shouldBe true
            lines.dropLast(1).forEach { it.hyphenated shouldBe true }
            lines.last().hyphenated shouldBe false
            glued(lines) shouldBe "x".repeat(100)
        }

        test("break opportunities after comma and open parenthesis") {
            val lines = StmLabelPlacer.wrap(parts = listOf(p("call(argumentNumberOne,argumentNumberTwo,argumentNumberThree)")))
            lines.none { it.hyphenated } shouldBe true
            glued(lines) shouldBe "call(argumentNumberOne,argumentNumberTwo,argumentNumberThree)"
        }

        test("widths 120 and 400 change the wrap and prose stays prose on every line") {
            val prose = p("[" + "the door is open and the operator confirmed the request" + "]", EdgeLabelKind.GuardProse)
            val narrow = StmLabelPlacer.wrap(parts = listOf(prose), maxWidthPx = 120f)
            val wide = StmLabelPlacer.wrap(parts = listOf(prose), maxWidthPx = 400f)
            (narrow.size > wide.size) shouldBe true
            narrow.forEach { it.parts.single().kind shouldBe EdgeLabelKind.GuardProse }
            narrow.forEach { (it.text.length <= StmLabelPlacer.maxChars(120f)) shouldBe true }
        }

        test("box size follows the longest line") {
            val lines = StmLabelPlacer.wrap(parts = listOf(p("abcdef")))
            val size = StmLabelPlacer.boxSize(lines)
            size.width shouldBe 6 * StmLabelPlacer.CHAR_W + 2 * StmLabelPlacer.PAD
            size.height shouldBe StmLabelPlacer.LINE_H + 2 * StmLabelPlacer.PAD
        }
    })
