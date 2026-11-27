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

        // BLOCKED (PAL Welle X2): moving the dimension line to the right of the lifeline redraws existing diagram 50
        // (vault example "50 SysML 2 SEQ - Verschachtelte Fragmente, ref, Zeit"), sysml2-c4/seq-nested and
        // sysml2-f2/seq-fragments-time, contradicts the deliberate "12 px left" rule pinned by Sysml2C3SeqSvgTest, and
        // needs the layout (seqSideTextNeeds, which reserves the room LEFT of the axis) to reserve the room on the right.
        // Needs approval to change those references; the test documents the target geometry and was red on df533387.
        "12f: the duration dimension line stands 14 px right of the lifeline axis, its label starts 8 px right of it".config(
            enabled = false,
        ) {
            val svg = render(durationModel())
            val start = svg.indexOf("<g id=\"durationConstraint:a-0-2\"")
            val g = svg.substring(start, svg.indexOf("</g>", start))
            val shaftX = num(Regex("<line[^>]*/>").find(g)!!.value, "x1")
            val axis = lifelineAxisX(svg, "a")
            shaftX shouldBe (axis + 14f)
            val text = Regex("<text[^>]*>").find(g)!!.value
            (text.contains("text-anchor=\"start\"")) shouldBe true
            num(text, "x") shouldBe (shaftX + 8f)
        }
    })
