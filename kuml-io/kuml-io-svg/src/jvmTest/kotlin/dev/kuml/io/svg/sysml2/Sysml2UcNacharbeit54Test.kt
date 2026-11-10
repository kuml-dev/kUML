package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.edge.UcEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun actorSvg(height: Float): String {
    val b = SvgBuilder(pretty = true)
    renderSysml2Actor(
        element = ActorDefinition(id = "A", name = "A"),
        layout = NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 60f, height = height))),
        theme = PlainTheme(),
        builder = b,
    )
    return b.toString()
}

/** Nacharbeit 54: extend labels wrap at 23 characters; a taller actor box keeps the figure size and centres it. */
class Sysml2UcNacharbeit54Test :
    FunSpec({
        test("an extend condition wraps at 23 characters and nothing is cut") {
            val cond = "Door lock yes (door contact fault, room pressure main alarm)"
            val md = UcEdgeAdapter.extendMetadata(UcExtend(id = "x", sourceUseCaseId = "a", targetUseCaseId = "b", condition = cond))
            val lines = Sysml2EdgeRenderer.drawnLabelLines(md).map { it.text }
            lines.first() shouldBe "«extend»"
            val body = lines.drop(1)
            body.all { it.length <= 23 } shouldBe true
            body.joinToString(" ") shouldBe "[$cond]"
        }

        test("a short extend condition keeps the default wrap, a long one wraps narrow") {
            val short =
                UcEdgeAdapter.extendMetadata(
                    UcExtend(id = "x", sourceUseCaseId = "a", targetUseCaseId = "b", condition = "customer owns a voucher"),
                )
            short.labelWrapChars shouldBe null
        }

        test("an extend without condition and other edges keep the default wrap") {
            val md = UcEdgeAdapter.METADATA_EXTEND
            md.labelWrapChars shouldBe null
            Sysml2EdgeRenderer.drawnLabelLines(md).map { it.text }.shouldContainExactly("«extend»")
        }

        test("a box of the default height draws the figure as before, a taller box centres the same figure") {
            val base = actorSvg(100f)
            val tall = actorSvg(136f)
            base shouldContain "cy=\"12\""
            tall shouldContain "cy=\"30\"" // 12 + (136 - 100) / 2
        }
    })
