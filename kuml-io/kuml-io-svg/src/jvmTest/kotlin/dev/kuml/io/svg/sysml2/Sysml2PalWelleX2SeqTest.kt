package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/** PAL Welle X2 (12a): a fragment frame edge never cuts the label of a self message (SVG geometry, no byte comparison). */
class Sysml2PalWelleX2SeqTest :
    StringSpec({
        fun render(model: Sysml2Model): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            return KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
        }

        fun num(
            tag: String,
            name: String,
        ): Float = Regex("\\b$name=\"(-?[0-9.]+)\"").find(tag)!!.groupValues[1].toFloat()

        fun selfLabelModel(label: String): Sysml2Model =
            sysml2Model(name = "SelfLabel") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                val c = lifelineDef(name = "c")
                message(label = "go", source = a, target = b, seqNo = 0)
                message(label = label, source = b, target = b, seqNo = 1)
                message(label = "later", source = b, target = c, seqNo = 3)
                combinedFragment(name = "f", operator = CombinedFragmentOperator.Opt, startSeqNo = 0, endSeqNo = 1, guard = "g")
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                    include(c)
                }
            }

        "12a: the right frame edge stands at least 4 px right of a self-message label that reaches past the lifeline box" {
            val label = "a rather long self message label"
            val svg = render(selfLabelModel(label))
            val frame = Regex("<g id=\"combinedFragment:f\">\\s*(<rect[^>]*/>)").find(svg)!!.groupValues[1]
            val frameRight = num(frame, "x") + num(frame, "width")
            val rect = Regex("<rect[^>]*fill=\"white\"[^>]*stroke=\"none\"[^>]*/>\\s*<text[^>]*>\\s*$label").find(svg)!!.value
            val labelRight = num(rect, "x") + num(rect, "width")
            (frameRight - labelRight) shouldBeGreaterThanOrEqual 4f
        }

        "12a: fragmentRightPastSelfLabels leaves a frame without an interfering label unchanged" {
            val ends = listOf(SeqSelfLabelEnd(seqNo = 1, axisX = 100f, labelLeft = 136f, labelRight = 200f))
            // label wholly left of the edge by more than 4 px, wrong row, wrong lifeline: nothing moves
            fragmentRightPastSelfLabels(baseRight = 300f, ends = ends, rows = 0..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe 300f
            fragmentRightPastSelfLabels(baseRight = 150f, ends = ends, rows = 2..3, axisRange = 0f..200f, levelsBelow = 0) shouldBe 150f
            fragmentRightPastSelfLabels(baseRight = 150f, ends = ends, rows = 0..1, axisRange = 0f..50f, levelsBelow = 0) shouldBe 150f
            fragmentRightPastSelfLabels(baseRight = 150f, ends = emptyList(), rows = 0..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe
                150f
        }

        "12a: fragmentRightPastSelfLabels pushes a cutting edge out past the label, one indent more per nested level" {
            val ends = listOf(SeqSelfLabelEnd(seqNo = 1, axisX = 100f, labelLeft = 136f, labelRight = 200f))
            fragmentRightPastSelfLabels(baseRight = 170f, ends = ends, rows = 0..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe 204f
            fragmentRightPastSelfLabels(baseRight = 170f, ends = ends, rows = 0..1, axisRange = 0f..200f, levelsBelow = 2) shouldBe
                204f + 2 * SYSML2_FRAGMENT_INDENT
            // an edge 2 px right of the label end is still closer than the 4 px clearance
            fragmentRightPastSelfLabels(baseRight = 202f, ends = ends, rows = 0..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe 204f
        }

        // ---- 12f duration constraint on the right of the lifeline ----------------------------------------------

        fun durationModel(): Sysml2Model =
            sysml2Model(name = "DC") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                message(label = "req", source = a, target = b, seqNo = 0)
                message(label = "resp", source = b, target = a, seqNo = 2, kind = dev.kuml.sysml2.MessageKind.Reply)
                durationConstraint(lifeline = a, from = 0, to = 2, expression = "0..3s")
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        fun lifelineAxisX(
            svg: String,
            id: String,
        ): Float {
            val start = svg.indexOf("<g id=\"$id\"")
            val g = svg.substring(start, svg.indexOf("</g>", start))
            val t = Regex("translate\\((-?[0-9.]+),(-?[0-9.]+)\\)").find(g)!!
            return t.groupValues[1].toFloat() + num(Regex("<rect[^>]*/>").find(g)!!.value, "width") / 2f
        }

        // PAL Welle Y1 (12f): the dimension line stands right of the lifeline (axis or activation bar), label to its right.
        "12f: the duration dimension line stands 14 px right of the lifeline axis, its label starts 8 px right of it" {
            val svg = render(durationModel())
            val start = svg.indexOf("<g id=\"durationConstraint:a-0-2\"")
            val g = svg.substring(start, svg.indexOf("</g>", start))
            val shaftX = num(Regex("<line[^>]*/>").find(g)!!.value, "x1")
            val axis = lifelineAxisX(svg, "a")
            shaftX shouldBe (axis + 14f)
            val text = Regex("<text[^>]*>").find(g)!!.value
            // anchor "start" is the SVG default and is not written out
            (text.contains("text-anchor")) shouldBe false
            num(text, "x") shouldBe (shaftX + 8f)
        }

        "12f: with an activation bar the dimension line stands 14 px right of the bar edge" {
            val model =
                sysml2Model(name = "DCBar") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "req", source = a, target = b, seqNo = 0)
                    message(label = "resp", source = b, target = a, seqNo = 2, kind = dev.kuml.sysml2.MessageKind.Reply)
                    executionSpec(name = "act", lifeline = a, startSeqNo = 0, endSeqNo = 2)
                    durationConstraint(lifeline = a, from = 0, to = 2, expression = "0..3s")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model)
            val start = svg.indexOf("<g id=\"durationConstraint:a-0-2\"")
            val g = svg.substring(start, svg.indexOf("</g>", start))
            val shaftX = num(Regex("<line[^>]*/>").find(g)!!.value, "x1")
            shaftX shouldBe (lifelineAxisX(svg, "a") + 5f + 14f)
        }

        "12f: the label background rectangle starts at the label and covers the text width" {
            val svg = render(durationModel())
            val start = svg.indexOf("<g id=\"durationConstraint:a-0-2\"")
            val g = svg.substring(start, svg.indexOf("</g>", start))
            val rect = Regex("<rect[^>]*fill=\"white\"[^>]*/>").find(g)!!.value
            val shaftX = num(Regex("<line[^>]*/>").find(g)!!.value, "x1")
            num(rect, "x") shouldBe (shaftX + 8f - 2f)
            durationLabelRight(shaftX = shaftX, expression = "0..3s") shouldBe (num(rect, "x") + num(rect, "width"))
        }

        "12f: a frame covering the duration rows clears the label by 8 px, a frame outside the rows is unchanged" {
            val ends = listOf(SeqSelfLabelEnd(seqNo = 0, axisX = 100f, labelLeft = 110f, labelRight = 200f, lastSeqNo = 2, clearance = 8f))
            fragmentRightPastSelfLabels(baseRight = 150f, ends = ends, rows = 1..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe 208f
            fragmentRightPastSelfLabels(baseRight = 150f, ends = ends, rows = 3..4, axisRange = 0f..200f, levelsBelow = 0) shouldBe 150f
            fragmentRightPastSelfLabels(baseRight = 300f, ends = ends, rows = 1..1, axisRange = 0f..200f, levelsBelow = 0) shouldBe 300f
        }

        "12f: the dimension line frame ends reach from the shaft to the label right edge for the whole row span" {
            val dc =
                sysml2Model(name = "X") {
                    val a = lifelineDef(name = "a")
                    durationConstraint(lifeline = a, from = 2, to = 0, expression = "0..3s")
                }.usages.filterIsInstance<dev.kuml.sysml2.DurationConstraintUsage>()
            val ends = durationLabelEnds(durations = dc, hasBarOf = { _ -> false }, axisXOf = { 100f })
            ends.single().seqNo shouldBe 0
            ends.single().lastSeqNo shouldBe 2
            ends.single().labelRight shouldBe durationLabelRight(shaftX = 114f, expression = "0..3s")
        }
    })
