package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 66: lifeline head wrapping and message labels across foreign execution bars.
 * Font independent: only presence, order, coordinates and text content are asserted.
 */
class Sysml2SeqHeadWrapSvgTest :
    StringSpec({
        fun render(
            model: Sysml2Model,
            name: String,
        ): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-seq-head-wrap/$name.svg", content = svg)
            return svg.replace(Regex(">\\s+"), ">").replace(Regex("\\s+<"), "<")
        }

        fun headGroup(
            svg: String,
            nameText: String,
        ): String {
            // the head group is the one holding a name text line; find the group that contains the first line of the name
            val idx = svg.indexOf(">$nameText</text>")
            check(idx >= 0) { "no text $nameText" }
            val start = svg.lastIndexOf("<g id=", idx)
            return svg.substring(start, svg.indexOf("</g>", idx))
        }

        fun titleTexts(group: String): List<String> =
            Regex("<text[^>]*class=\"kuml-title[^\"]*\"[^>]*>([^<]*)</text>").findAll(group).map { it.groupValues[1] }.toList()

        fun headWidth(group: String): Float = Regex("<rect[^>]*width=\"([0-9.]+)\"").find(group)!!.groupValues[1].toFloat()

        fun wrapModel(
            first: String,
            second: String = "Client",
        ): Sysml2Model =
            sysml2Model(name = "Heads") {
                val a = lifelineDef(name = first)
                val b = lifelineDef(name = second)
                val c = lifelineDef(name = "Store")
                message(label = "go", source = a, target = b, seqNo = 0)
                message(label = "load", source = b, target = c, seqNo = 1)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                    include(c)
                }
            }

        "c1: a long name wraps into two lines, the keyword is dropped diagram-wide, the title keeps the full name" {
            val long = "Alpha Beta Gamma Delta Epsilon Zeta"
            val svg = render(wrapModel(first = long), "c1")
            val g = headGroup(svg, "Alpha Beta Gamma Delta")
            titleTexts(g) shouldBe listOf("Alpha Beta Gamma Delta", "Epsilon Zeta")
            (headWidth(g) <= 240f) shouldBe true
            g shouldContain "<title>$long</title>"
            svg shouldNotContain "«lifeline»"
        }

        "c2: a newline in the name is the first line break" {
            val svg = render(wrapModel(first = "First\nSecond"), "c2")
            val g = headGroup(svg, "First")
            titleTexts(g) shouldBe listOf("First", "Second")
            svg shouldNotContain "FirstSecond"
        }

        "c3: short names keep the keyword and the standard width" {
            val svg = render(wrapModel(first = "Alpha"), "c3")
            svg shouldContain "«lifeline»"
            val g = headGroup(svg, "Alpha")
            headWidth(g) shouldBe 140f
            g shouldNotContain "<title>"
        }

        fun barModel(label: String): Sysml2Model =
            sysml2Model(name = "Bars") {
                val a = lifelineDef(name = "Alpha")
                val b = lifelineDef(name = "Beta")
                val c = lifelineDef(name = "Gamma")
                message(label = "first", source = a, target = b, seqNo = 0)
                message(label = label, source = a, target = c, seqNo = 1)
                executionSpec(name = "", lifeline = b, startSeqNo = 1, endSeqNo = 1)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                    include(c)
                }
            }

        data class R(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
        )

        fun rects(svg: String): List<Pair<String, R>> =
            Regex("<rect[^>]*/>")
                .findAll(svg)
                .map { m ->
                    fun f(n: String) =
                        Regex("\\b$n=\"(-?[0-9.]+)\"")
                            .find(m.value)
                            ?.groupValues
                            ?.get(1)
                            ?.toFloat() ?: 0f
                    m.value to R(f("x"), f("y"), f("width"), f("height"))
                }.toList()

        fun overlaps(
            a: R,
            b: R,
        ): Boolean = a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y

        "d1: a short label across a foreign bar leaves the bar uncovered" {
            val svg = render(barModel("go"), "d1")
            val all = rects(svg)
            val bar = all.first { it.first.contains("kuml-class") && it.first.contains("fill=\"white\"") && it.second.w == 10f }.second
            val backgrounds = all.filter { it.first.contains("stroke=\"none\"") && it.first.contains("fill=\"white\"") }
            backgrounds.none { overlaps(it.second, bar) } shouldBe true
            svg shouldContain ">go</text>"
        }

        "d2: a label too long for any gap cuts the bar out of its background and keeps its full length" {
            val label = "a_label_that_is_far_too_long_for_a_gap"
            val svg = render(barModel(label), "d2")
            val all = rects(svg)
            val bar = all.first { it.first.contains("kuml-class") && it.first.contains("fill=\"white\"") && it.second.w == 10f }.second
            val backgrounds = all.filter { it.first.contains("stroke=\"none\"") && it.first.contains("fill=\"white\"") }
            backgrounds.none { overlaps(it.second, bar) } shouldBe true
            svg shouldNotContain "textLength"
            svg shouldContain ">$label</text>"
        }
    })
