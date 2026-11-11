package dev.kuml.layout.bridge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/** Nacharbeit 69 - forced line breaks in action names and the free-side choice of a control-node name. */
class ActTextWrapTest :
    FunSpec({
        test("a forced break is kept and no words are glued together") {
            val lines = ActTextWrap.actionNameLines("Hold damper,\nsuspend pressure control and mixing")
            lines.first() shouldBe "Hold damper,"
            lines.none { it.contains(",suspend") } shouldBe true
        }

        test("empty paragraphs are dropped and CR LF counts as one break") {
            ActTextWrap.actionNameLines("a\n\nb") shouldBe listOf("a", "b")
            ActTextWrap.actionNameLines("a\r\nb") shouldBe listOf("a", "b")
            ActTextWrap.actionNameLines("a\rb") shouldBe listOf("a", "b")
        }

        test("a name of only breaks stays one empty line, a huge name stays bounded") {
            ActTextWrap.actionNameLines("\n\n") shouldBe listOf("")
            ActTextWrap.actionNameLines("x".repeat(100_000)).size shouldBe ActTextWrap.MAX_LINES_CAP
            ActTextWrap.actionNameLines("word ".repeat(50_000)).size shouldBe ActTextWrap.MAX_LINES_CAP
        }

        test("text without a break wraps as before") {
            ActTextWrap.wrapActName(text = "Check the request now", maxWidthPx = 100f, maxLines = 4) shouldBe
                ActTextWrap.wrap(text = "Check the request now", maxWidthPx = 100f, maxLines = 4).lines
            ActTextWrap.actionNameLines("Short").size shouldBe 1
        }

        test("a control node name takes the first side no edge crosses") {
            val node = Rect(origin = Point(x = 100f, y = 100f), size = Size(width = 28f, height = 28f))
            // an edge leaves to the right at the node centre
            val right = listOf(Point(x = 128f, y = 114f), Point(x = 300f, y = 114f))
            val free =
                ActControlNamePlacement.place(
                    node = node,
                    charCount = 5,
                    routes = emptyList(),
                    order = ActControlNamePlacement.DIAMOND_ORDER,
                )
            free.side shouldBe ActControlNamePlacement.Side.Right
            val moved =
                ActControlNamePlacement.place(
                    node = node,
                    charCount = 5,
                    routes = listOf(right),
                    order = ActControlNamePlacement.DIAMOND_ORDER,
                )
            listOf(ActControlNamePlacement.Side.Left, ActControlNamePlacement.Side.Top, ActControlNamePlacement.Side.Bottom) shouldContain
                moved.side
        }

        test("when every side is crossed the first preference is kept") {
            val node = Rect(origin = Point(x = 100f, y = 100f), size = Size(width = 28f, height = 28f))
            val cross =
                listOf(
                    listOf(Point(x = 0f, y = 114f), Point(x = 400f, y = 114f)),
                    listOf(Point(x = 114f, y = 0f), Point(x = 114f, y = 400f)),
                )
            val placed =
                ActControlNamePlacement.place(
                    node = node,
                    charCount = 5,
                    routes = cross,
                    order = ActControlNamePlacement.DIAMOND_ORDER,
                )
            placed.side shouldBe ActControlNamePlacement.Side.Right
        }
    })
