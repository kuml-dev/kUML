@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class NoteSvgFormatTest :
    FunSpec(body = {
        fun render(
            body: String,
            kind: NoteKind = NoteKind.NOTE,
            frame: NoteFrameStyle = NoteFrameStyle.DOG_EAR,
            pretty: Boolean = true,
        ): String {
            val size = NoteLayout.sizeOf(body = body, frame = frame, kind = kind)
            val layout = NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = size))
            val b = SvgBuilder(pretty = pretty)
            renderNote(id = "n1", body = body, layout = layout, frame = frame, frameClasses = "kuml-comment", builder = b, kind = kind)
            return b.toString()
        }

        test("a plain note renders the unchanged reference markup") {
            render("hello", NoteKind.NOTE) shouldBe PLAIN_REFERENCE
        }

        test("a note with look-alike characters but no markup is rendered as before") {
            val svg = render("Todo: <b>soon</b> & 2*3*4 carefully.")
            svg shouldNotContain "font-weight"
            svg shouldContain "&lt;b&gt;soon&lt;/b&gt; &amp; 2*3*4"
        }

        test("formatted text uses presentation attributes and a link") {
            val svg = render("**b** *i* `c` [l](https://kuml.dev)")
            svg shouldContain "font-weight=\"bold\""
            svg shouldContain "font-style=\"italic\""
            svg shouldContain "font-family=\"monospace\""
            svg shouldContain "<a href=\"https://kuml.dev\"><tspan text-decoration=\"underline\">l</tspan></a>"
            svg shouldNotContain "xlink"
            svg shouldNotContain "class=\"kuml-note-"
        }

        test("a formatted line contains no newline between its runs") {
            val svg = render("word **bold** word *it* end", pretty = true)
            val line = svg.lines().first { it.contains("font-weight") }
            line shouldContain "word <tspan font-weight=\"bold\">bold</tspan> word <tspan font-style=\"italic\">it</tspan> end"
        }

        test("list items carry a marker and a hanging indent") {
            val svg = render("- a\n1. b")
            svg shouldContain "•"
            svg shouldContain "1."
        }

        test("injection: markup in the text and in the url arrives escaped") {
            val svg = render("**<script>alert(1)</script> &** [y](https://e.x/\"onmouseover=\"a)")
            svg shouldNotContain "<script"
            svg shouldContain "&lt;script&gt;"
            svg shouldContain "href=\"https://e.x/&quot;onmouseover=&quot;a\""
            svg shouldNotContain "onmouseover=\"a\""
        }

        test("rejected schemes never produce an anchor and stay visible") {
            for (url in listOf("javascript:alert(1)", "data:text/html,x", "vbscript:x")) {
                val svg = render("**b** [x]($url)")
                svg shouldNotContain "<a "
                svg shouldContain "[x]($url)"
            }
        }

        test("the renderer re-validates the href (second safety layer)") {
            val b = SvgBuilder(pretty = false)
            renderNoteText(
                text =
                    dev.kuml.layout.bridge.NoteText(
                        lines = listOf("x"),
                        truncated = false,
                        styledLines =
                            listOf(
                                dev.kuml.layout.bridge.StyledLine(
                                    0f,
                                    null,
                                    listOf(
                                        dev.kuml.core.model.note
                                            .NoteSpan("x", href = "javascript:alert(1)"),
                                    ),
                                ),
                            ),
                    ),
                xInset = 8f,
                builder = b,
            )
            b.toString() shouldNotContain "<a "
            b.toString() shouldNotContain "href"
        }

        test("PNG: a note with a link renders without an exception") {
            val svg =
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"200\">" +
                    render("**b** *i* `c` [link](https://kuml.dev)\n- item", pretty = false) + "</svg>"
            val png = KumlPngRenderer.toPng(svg = svg, options = PngRenderOptions(widthPx = 600))
            (png.size > 100) shouldBe true
        }
    })

/** Recorded from the plain-text path (unchanged by N3; also guarded by the byte-identity check of all sample SVGs). */
private val PLAIN_REFERENCE: String =
    listOf(
        "<g id=\"n1\" transform=\"translate(10,10)\">",
        "  <path d=\"M 0 0 L 126 0 L 140.0 14 L 140.0 60.0 L 0 60.0 Z\" class=\"kuml-comment\"/>",
        "  <path d=\"M 126 0 L 126 14 L 140.0 14\" class=\"kuml-comment-fold\"/>",
        "  <text class=\"kuml-body\" x=\"8\" y=\"18.50\">",
        "    <tspan x=\"8\" dy=\"0\">",
        "      hello",
        "    </tspan>",
        "  </text>",
        "</g>",
    ).joinToString(separator = "\n", postfix = "\n")
