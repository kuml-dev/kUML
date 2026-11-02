@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.core.model.note.KumlNoteLimits
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NoteSpan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NoteLayoutFormatTest :
    FunSpec(body = {

        val eps = 0.01f
        val innerDefault = NoteLayout.MAX_W - NoteLayout.H_PADDING

        test("plain text has no styled lines") {
            val t = NoteLayout.displayText(body = "Todo: <b>soon</b> & 2*3*4 carefully.")
            t.styledLines shouldBe null
        }

        test("markup yields styled lines and the visible text without markers") {
            val t = NoteLayout.displayText(body = "a **b** c\n- x\n1. y")
            val styled = t.styledLines!!
            t.lines shouldBe listOf("a b c", "x", "y")
            styled[1].marker shouldBe NoteLayout.BULLET_MARKER
            styled[2].marker shouldBe "1."
        }

        test("bold text is wider than plain text, factor is exactly 1.1") {
            val bold = NoteLayout.sizeOf(body = "**WWWWWWWWWWWWWWWWWWWW**").width
            val plain = NoteLayout.sizeOf(body = "WWWWWWWWWWWWWWWWWWWW").width
            (bold > plain) shouldBe true
            NoteLayout.spanWidth(NoteSpan("abcdefghij", bold = true)) shouldBe 10 * 6.6f * 1.1f
            NoteLayout.spanWidth(NoteSpan("abcdefghij", code = true)) shouldBe 10 * 6.6f * 1.1f
            NoteLayout.spanWidth(NoteSpan("abcdefghij", italic = true)) shouldBe 10 * 6.6f
        }

        test("bullet items hang with indent 14, continuation lines carry no marker") {
            val t = NoteLayout.displayText(body = "- " + "word ".repeat(30))
            val lines = t.styledLines!!
            (lines.size > 1) shouldBe true
            lines.forEach { it.indentPx shouldBe NoteLayout.BULLET_INDENT }
            lines[0].marker shouldBe NoteLayout.BULLET_MARKER
            lines.drop(1).forEach { it.marker shouldBe null }
        }

        test("numbered items get a column sized for the longest number") {
            val body = (9..10).joinToString("\n") { "$it. item" }
            val lines = NoteLayout.displayText(body = body).styledLines!!
            lines.forEach { it.indentPx shouldBe 3 * NoteLayout.BODY_CHAR_PX + NoteLayout.NUMBER_MARKER_GAP }
            lines.map { it.marker } shouldBe listOf("9.", "10.")
        }

        test("every line fits the available width in all variants") {
            val body =
                "A **very long bold sentence that has to wrap several times** with `some_long_identifier_name` and " +
                    "[a link text that wraps](https://kuml.dev/a/very/long/path) ${"x".repeat(
                        80,
                    )}\n- ${"item ".repeat(20)}\n1. ${"num ".repeat(20)}"
            for (kind in listOf(NoteKind.NOTE, NoteKind.WARNING)) {
                for (maxW in listOf(NoteLayout.MAX_W, NoteLayout.STM_MAX_W)) {
                    val inner = maxW - NoteLayout.H_PADDING
                    val t = NoteLayout.displayText(body = body, maxInnerWidth = inner, kind = kind)
                    val avail = inner - NoteLayout.iconColumn(kind)
                    t.styledLines!!.forEach { (NoteLayout.lineWidth(it) <= avail + eps) shouldBe true }
                    val size = NoteLayout.sizeOf(body = body, maxWidth = maxW, kind = kind)
                    (size.width <= maxW + eps) shouldBe true
                }
            }
        }

        test("sizing and rendering agree: wrapping at the final box width gives the same lines") {
            val body = "Some **bold** text, `code` and a [link](https://kuml.dev) that wrap over lines because it is long enough here."
            val size = NoteLayout.sizeOf(body = body)
            val sized = NoteLayout.displayText(body = body, maxInnerWidth = NoteLayout.MAX_W - NoteLayout.H_PADDING)
            val drawn = NoteLayout.displayText(body = body, maxInnerWidth = size.width - NoteLayout.H_PADDING + 0.5f)
            drawn.lines shouldBe sized.lines
        }

        test("a link spanning two lines keeps its href on both fragments") {
            val t = NoteLayout.displayText(body = "[${"link ".repeat(20)}](https://kuml.dev)")
            val lines = t.styledLines!!
            (lines.size > 1) shouldBe true
            lines.forEach { line -> line.spans.all { it.href == "https://kuml.dev" } shouldBe true }
        }

        test("spaces between styled spans survive") {
            val t = NoteLayout.displayText(body = "word **bold** word")
            t.lines shouldBe listOf("word bold word")
        }

        test("truncation at 30 lines adds an ellipsis and stays consistent") {
            val body = (1..60).joinToString("\n") { "**line $it**" }
            val t = NoteLayout.displayText(body = body)
            t.truncated shouldBe true
            t.lines.size shouldBe KumlNoteLimits.MAX_DISPLAY_LINES
            t.lines.last().endsWith(KumlNoteLimits.ELLIPSIS) shouldBe true
            NoteLayout.isTruncated(body = body) shouldBe true
            NoteLayout.isTruncated(body = "**short**") shouldBe false
        }

        test("blank lines are kept") {
            NoteLayout.displayText(body = "**a**\n\nb").lines shouldBe listOf("a", "", "b")
        }

        test("an over-long word is split by width") {
            val t = NoteLayout.displayText(body = "**${"W".repeat(100)}**")
            val lines = t.styledLines!!
            (lines.size > 1) shouldBe true
            lines.forEach { (NoteLayout.lineWidth(it) <= innerDefault + eps) shouldBe true }
            lines.joinToString("") { l -> l.spans.joinToString("") { it.text } } shouldBe "W".repeat(100)
        }
    })
