package dev.kuml.layout.bridge.label

import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.EdgeLabelPart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StmLabelWrapTest :
    FunSpec({
        timeout = 30_000L

        fun part(
            text: String,
            kind: EdgeLabelKind = EdgeLabelKind.Effect,
        ) = EdgeLabelPart(text = text, kind = kind)

        fun wrap(
            vararg parts: EdgeLabelPart,
            width: Float = 150f,
        ) = StmLabelWrap.wrap(parts = parts.toList(), maxWidthPx = width)

        val maxC = StmLabelWrap.maxChars(150f)

        test("the limit for 150 px is 20 characters") {
            maxC shouldBe 20
        }

        test("a word that fits a line is never split") {
            val lines = wrap(part("Spannungsausfall", EdgeLabelKind.Guard), part("Kanaltemperatur pruefen"))
            lines.count { it.text == "Spannungsausfall" } shouldBe 1
            lines.none { it.brokenInWord } shouldBe true
        }

        test("a guard in brackets stays on one line") {
            val lines = wrap(part("start"), part("[Spannungsausfall]", EdgeLabelKind.Guard), part("/ Kanaltemperatur pruefen"))
            lines.count { it.text == "[Spannungsausfall]" } shouldBe 1
        }

        test("empty parentheses stay with the name: no line starts with a closing bracket or ends with an opening one") {
            val lines = wrap(part("/ Kuehlung.freigeben()"))
            lines.forEach {
                it.text.first() shouldNotBeIn ")],;"
                it.text.last() shouldNotBeIn "("
            }
            lines.joinToString("") { it.text.replace(" ", "") } shouldBe "/Kuehlung.freigeben()"
            lines.none { it.brokenInWord } shouldBe true
        }

        test("an argument list too long for a line breaks after the opening parenthesis, never before a closing one") {
            val text = "/ Auf-/Zulauf.schliessen(Ventil1,Ventil2)"
            val lines = wrap(part(text))
            lines.forEach {
                it.text.first() shouldNotBeIn ")],;"
                (it.text.length <= maxC) shouldBe true
            }
            lines.none { it.brokenInWord } shouldBe true
            lines.joinToString("") { it.text.replace(" ", "") } shouldBe text.replace(" ", "")
        }

        test("a 30 character word without a break point stays whole on a widened line") {
            val word = "abcdefghijklmnopqrstuvwxyz0123"
            val lines = wrap(part(word))
            lines.size shouldBe 1
            lines.single().text shouldBe word
            lines.single().brokenInWord shouldBe false
            (StmLabelWrap.boxSize(lines).width >= word.length * StmLabelWrap.CHAR_W) shouldBe true
        }

        test("a word beyond 400 px breaks hard, every non-last piece ends with a hyphen and nothing else is lost") {
            val word = "a".repeat(70)
            val lines = wrap(part(word))
            (lines.size > 1) shouldBe true
            lines.forEach { (it.text.length <= maxC) shouldBe true }
            lines.dropLast(1).forEach {
                it.text.last() shouldBe '-'
                it.brokenInWord shouldBe true
            }
            lines.last().brokenInWord shouldBe false
            lines.joinToString("") { it.text }.replace("-", "") shouldBe word
        }

        test("sicherheitskritisches is never split at a narrow label width") {
            val lines = wrap(part("T26:"), part("ANNAHME:"), part("sicherheitskritisches Ereignis"), width = 130f)
            lines.any { it.text == "sicherheitskritisches" } shouldBe true
            lines.none { it.brokenInWord } shouldBe true
            lines.forEach { l ->
                (l.text.length * StmLabelWrap.CHAR_W + 2 * StmLabelWrap.PAD <= StmLabelWrap.boxSize(lines).width + 0.5f) shouldBe
                    true
            }
        }

        test("a word with a hyphen or slash breaks there first") {
            val word = "Zulaufventil-/Ablaufventil"
            val lines = wrap(part(word))
            lines.none { it.brokenInWord } shouldBe true
            lines.first().text.last() shouldBeIn "-/"
            lines.joinToString("") { it.text } shouldBe word
        }

        test("operators start a new line, they are not cut") {
            val lines = wrap(part("[Spuelzeit&&Spannungsausfall||x]", EdgeLabelKind.Guard))
            lines.none { it.brokenInWord } shouldBe true
            lines.joinToString("") { it.text } shouldBe "[Spuelzeit&&Spannungsausfall||x]"
        }
    })

private infix fun Char.shouldNotBeIn(set: String) = (this in set) shouldBe false

private infix fun Char.shouldBeIn(set: String) = (this in set) shouldBe true
