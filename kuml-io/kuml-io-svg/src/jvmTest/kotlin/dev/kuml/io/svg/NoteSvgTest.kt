package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteFrameStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class NoteSvgTest :
    FunSpec(body = {
        fun layout() = NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 140f, height = 60f)))

        fun render(
            id: String,
            body: String,
            frame: NoteFrameStyle,
            cls: String,
            kind: NoteKind = NoteKind.NOTE,
        ): String {
            val b = SvgBuilder(pretty = true)
            renderNote(id = id, body = body, layout = layout(), frame = frame, frameClasses = cls, builder = b, kind = kind)
            return b.toString()
        }

        test("XSS: body and id arrive escaped exactly once") {
            val svg = render("a\"><x", "<script>alert(1)</script>&\"'", NoteFrameStyle.DOG_EAR, "kuml-comment")
            svg shouldNotContain "<script"
            svg shouldContain "&lt;script&gt;"
            svg shouldContain "&amp;"
            svg shouldNotContain "&amp;amp;"
            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "\"><x"
        }

        test("dog-ear frame has an outline and a fold") {
            val svg = render("n1", "hello", NoteFrameStyle.DOG_EAR, "kuml-comment kuml-erm-note")
            svg shouldContain "class=\"kuml-comment kuml-erm-note\""
            svg shouldContain "class=\"kuml-comment-fold\""
        }

        test("bracket frame has no fold and an open bracket path") {
            val svg = render("n1", "hello", NoteFrameStyle.BRACKET, "kuml-bpmn-text-annotation")
            svg shouldContain "class=\"kuml-bpmn-text-annotation\""
            svg shouldContain "kuml-bpmn-text-annotation-area"
            svg shouldNotContain "kuml-comment-fold"
        }

        test("anchor styles use their own classes") {
            val route = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 50f, y = 0f))
            val a = SvgBuilder(pretty = true)
            renderNoteAnchor(route = route, style = NoteAnchorStyle.DASHED, builder = a)
            a.toString() shouldContain "class=\"kuml-note-anchor\""
            val d = SvgBuilder(pretty = true)
            renderNoteAnchor(route = route, style = NoteAnchorStyle.DOTTED, builder = d)
            d.toString() shouldContain "class=\"kuml-bpmn-association\""
        }

        test("a plain note has no kind markup at all") {
            val svg = render("n1", "hello", NoteFrameStyle.DOG_EAR, "kuml-comment")
            svg shouldNotContain "kuml-note-"
            svg shouldNotContain "aria-label"
        }

        test("a kinded dog-ear gets an accent stripe, then the symbol, then the text, in this order") {
            val svg = render("n1", "hello", NoteFrameStyle.DOG_EAR, "kuml-comment", NoteKind.WARNING)
            val accent = svg.indexOf("kuml-note-accent-warning")
            val icon = svg.indexOf("kuml-note-icon")
            val text = svg.indexOf("kuml-body")
            (accent in 0 until icon && icon < text) shouldBe true
            svg shouldContain "aria-label=\"Warning\""
            svg shouldContain "fill-rule=\"evenodd\""
            svg shouldContain "class=\"kuml-note-tone-warning\""
        }

        test("a kinded bracket carries the tone on the bracket stroke and has no accent stripe") {
            val svg = render("n1", "hello", NoteFrameStyle.BRACKET, "kuml-bpmn-text-annotation", NoteKind.ERROR)
            svg shouldContain "class=\"kuml-bpmn-text-annotation kuml-note-bracket-danger\""
            svg shouldNotContain "kuml-note-accent"
            svg shouldContain "aria-label=\"Error\""
        }

        test("the text of a kinded note is escaped once and cannot reach the symbol label") {
            val svg = render("n1", "<script>&\"", NoteFrameStyle.DOG_EAR, "kuml-comment", NoteKind.INFO)
            svg shouldNotContain "<script"
            svg shouldNotContain "&amp;amp;"
            svg shouldContain "aria-label=\"Info\""
        }

        test("every kind renders a symbol with its own label") {
            val labels =
                NoteKind.entries.filter { it.hasIcon }.map { k ->
                    Regex("aria-label=\"([^\"]+)\"").find(render("n", "x", NoteFrameStyle.DOG_EAR, "kuml-comment", k))!!.groupValues[1]
                }
            labels.toSet().size shouldBe 14
        }
    })
