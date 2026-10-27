package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteFrameStyle
import io.kotest.core.spec.style.FunSpec
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
        ): String {
            val b = SvgBuilder(pretty = true)
            renderNote(id = id, body = body, layout = layout(), frame = frame, frameClasses = cls, builder = b)
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
    })
