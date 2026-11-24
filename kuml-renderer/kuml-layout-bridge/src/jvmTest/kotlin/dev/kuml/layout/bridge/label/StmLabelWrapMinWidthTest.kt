package dev.kuml.layout.bridge.label

import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** PAL wave J: a name such as `T19:` sticks to the first word, and every line but the last holds at least three words. */
class StmLabelWrapMinWidthTest :
    FunSpec({
        timeout = 30_000L

        fun name(text: String) = EdgeLabelPart(text = text, kind = EdgeLabelKind.Name)

        fun trigger(text: String) = EdgeLabelPart(text = text, kind = EdgeLabelKind.Trigger)

        fun effect(text: String) = EdgeLabelPart(text = "/ $text", kind = EdgeLabelKind.Effect)

        fun words(line: StmLabelLine) = line.text.split(' ').filter { it.isNotEmpty() }

        val longLabel =
            listOf(
                name("T19:"),
                trigger("ANNAHME: Tuer geschlossen und Klappe frei"),
                effect("Regelung wieder aufnehmen"),
            )

        test("the name never stands alone on a line") {
            val lines = StmLabelWrap.wrap(parts = longLabel, maxWidthPx = 120f)
            lines.first().text.startsWith("T19: ANNAHME:") shouldBe true
            lines.none { it.text == "T19:" } shouldBe true
        }

        test("every line but the last holds at least three words") {
            for (width in listOf(120f, 150f, 180f, 220f)) {
                val lines = StmLabelWrap.wrap(parts = longLabel, maxWidthPx = width)
                lines.dropLast(1).forEach { (words(it).size >= 3) shouldBe true }
            }
        }

        test("the name part keeps its own style on the first line") {
            val first = StmLabelWrap.wrap(parts = longLabel, maxWidthPx = 120f).first()
            first.parts.first().kind shouldBe EdgeLabelKind.Name
            first.parts.first().text shouldBe "T19:"
        }

        test("a short label stays on one line and unchanged") {
            val parts = listOf(name("T1:"), trigger("go"))
            val lines = StmLabelWrap.wrap(parts = parts)
            lines.size shouldBe 1
            lines.single().parts shouldBe parts
        }

        test("the widening is capped at the default width: nothing wider than 220 px except unsplittable words") {
            val parts =
                listOf(trigger("Sicherheitsueberwachungseinrichtung Druckbegrenzungsventil Notabschaltungsanforderung Klappenstellung"))
            val lines = StmLabelWrap.wrap(parts = parts, maxWidthPx = 120f)
            lines.all { it.text.length * StmLabelWrap.CHAR_W <= StmLabelWrap.WIDE_LINE_MAX_PX + 1f } shouldBe true
        }

        test("a single extremely long word is still broken hard, nothing is lost") {
            val word = "x".repeat(900)
            val lines = StmLabelWrap.wrap(parts = listOf(name("T2:"), trigger(word)), maxWidthPx = 150f)
            lines.dropLast(1).all { it.brokenInWord } shouldBe true
            lines
                .joinToString("") { it.text }
                .replace("-", "")
                .replace("T2:", "")
                .replace(" ", "")
                .length shouldBe 900
        }

        test("hostile input: a huge label is cut at the part limit") {
            val lines = StmLabelWrap.wrap(parts = listOf(name("T3:"), trigger("wort ".repeat(100_000))), maxWidthPx = 150f)
            (lines.size < 1000) shouldBe true
        }
    })
