package dev.kuml.layout.bridge

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlComment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import kotlin.system.measureTimeMillis

class NoteLayoutTest :
    FunSpec(body = {

        test("constants are unchanged") {
            NoteLayout.BODY_CHAR_PX shouldBe 6.6f
            NoteLayout.LINE_H shouldBe 15f
            NoteLayout.MAX_W shouldBe 260f
            UmlCommentLayout.DEFAULT_W shouldBe 140f
        }

        test("short text yields the default size") {
            val s = NoteLayout.sizeOf(body = "hi")
            s.width shouldBe 140f
            s.height shouldBe 60f
        }

        test("100000 chars are cut at 30 lines with an ellipsis and a bounded height") {
            val t = NoteLayout.displayText(body = "word ".repeat(20_000))
            t.truncated shouldBe true
            t.lines shouldHaveSize 30
            t.lines.last() shouldEndWith "…"
            NoteLayout.sizeOf(body = "word ".repeat(20_000)).height shouldBe 466f
        }

        test("a 2000 char word is broken hard and no line is wider than the inner width") {
            val t = NoteLayout.displayText(body = "x".repeat(1990))
            val max = NoteLayout.MAX_W - NoteLayout.H_PADDING
            t.lines.forEach { (it.length * NoteLayout.BODY_CHAR_PX) shouldBeLessThanOrEqualTo max }
            (NoteLayout.sizeOf(body = "x".repeat(1990)).width <= NoteLayout.MAX_W) shouldBe true
        }

        test("64 KiB of newlines runs fast and stays bounded") {
            val body = "\n".repeat(65_536)
            var t: NoteText? = null
            val ms = measureTimeMillis { t = NoteLayout.displayText(body = body) }
            (t!!.lines.size <= 30) shouldBe true
            (ms < 30_000) shouldBe true
        }

        test("bracket and dog-ear have identical size") {
            NoteLayout.sizeOf(body = "abc def", frame = NoteFrameStyle.BRACKET) shouldBe
                NoteLayout.sizeOf(body = "abc def", frame = NoteFrameStyle.DOG_EAR)
        }

        test("UmlCommentLayout facade delegates to NoteLayout") {
            val c = UmlComment(id = "c", body = "some text ".repeat(50))
            UmlCommentLayout.wrapBody(body = c.body) shouldBe NoteLayout.displayText(body = c.body).lines
            UmlCommentLayout.sizeOf(c) shouldBe NoteLayout.sizeOf(body = c.body)
        }

        test("NoteDiagnostics reports truncation without the body text") {
            val long = UmlComment(id = "long", body = "secretword ".repeat(500))
            val short = UmlComment(id = "short", body = "ok")
            val diagram = KumlDiagram(name = "d", type = DiagramType.CLASS, elements = listOf(long, short))
            val diags = NoteDiagnostics.forUml(diagram)
            diags shouldHaveSize 1
            diags[0].code shouldBe NoteDiagnostics.NOTE_TRUNCATED
            diags[0].noteId shouldBe "long"
            diags[0].message shouldNotContain "secretword"
            NoteDiagnostics.forBodies(listOf("a" to "x")) shouldHaveSize 0
        }
    })
