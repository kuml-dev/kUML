package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * Self-call follow gap and loop nested in `par` operands — font-independent geometry checks
 * (only rect / path / line coordinates are read, no text metrics).
 */
class Sysml2SeqSelfCallGapSvgTest :
    StringSpec({
        fun render(
            model: Sysml2Model,
            name: String,
        ): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-seq-selfgap/$name.svg", content = svg)
            return svg
        }

        fun block(
            svg: String,
            id: String,
        ): String {
            val start = svg.indexOf("<g id=\"$id\"")
            check(start >= 0) { "no group $id" }
            return svg.substring(start, svg.indexOf("</g>", start))
        }

        fun attr(
            tag: String,
            name: String,
        ): Float =
            Regex("\\b$name=\"(-?[0-9.]+)\"")
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.toFloat() ?: error("no $name in $tag")

        /** y of the lowest point of the self-call path (loop bottom). */
        fun selfCallBottom(
            svg: String,
            id: String,
        ): Float {
            val d = Regex("<path[^>]*\\bd=\"([^\"]+)\"").find(block(svg, id))!!.groupValues[1]
            return Regex("[ML] (-?[0-9.]+) (-?[0-9.]+)").findAll(d).maxOf { it.groupValues[2].toFloat() }
        }

        /** y of the top of the white label background rect of a message. */
        fun labelBackgroundTop(
            svg: String,
            id: String,
        ): Float = attr(Regex("<rect[^>]*/>").find(block(svg, id))!!.value, "y")

        /** y of the message shaft (first line). */
        fun shaftY(
            svg: String,
            id: String,
        ): Float = attr(Regex("<line[^>]*/>").find(block(svg, id))!!.value, "y1")

        fun selfThen(
            nextSeqNo: Int,
            back: Boolean,
        ): Sysml2Model =
            sysml2Model(name = "SelfGap") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                message(label = "doSomethingHere()", source = a, target = a, seqNo = 1)
                if (back) {
                    message(label = "anotherLongerLabel()", source = b, target = a, seqNo = nextSeqNo)
                } else {
                    message(label = "anotherLongerLabel()", source = a, target = b, seqNo = nextSeqNo)
                }
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        "next message label does not cover the self-call arrowhead (arrow towards the loop lifeline)" {
            val svg = render(selfThen(nextSeqNo = 2, back = true), "e1-back")
            val loopBottom = selfCallBottom(svg, "message:a-a-1")
            labelBackgroundTop(svg, "message:b-a-2") shouldBeGreaterThanOrEqual loopBottom + 4f + 4f
        }

        "next message label does not cover the self-call arrowhead (arrow leaving the loop lifeline)" {
            val svg = render(selfThen(nextSeqNo = 2, back = false), "e1-away")
            labelBackgroundTop(svg, "message:a-b-2") shouldBeGreaterThanOrEqual selfCallBottom(svg, "message:a-a-1") + 8f
        }

        "a seqNo gap after the self call adds no extra clearance" {
            val adjacent = render(selfThen(nextSeqNo = 2, back = false), "e2-adjacent")
            val gapped = render(selfThen(nextSeqNo = 3, back = false), "e2-gapped")
            val rowHeight = 32f
            val y1 = shaftY(gapped, "message:a-b-3") - selfCallBottom(gapped, "message:a-a-1") + 20f
            // self call row y = bottom - 20; the next row is two plain rows below it
            abs(y1 - 2 * rowHeight) shouldBeLessThanOrEqual 1f
            // adjacent rows: one plain row plus the 12 px follow gap
            val adj = shaftY(adjacent, "message:a-b-2") - (selfCallBottom(adjacent, "message:a-a-1") - 20f)
            abs(adj - (rowHeight + SYSML2_SELF_CALL_FOLLOW_GAP)) shouldBeLessThanOrEqual 1f
        }

        "row offsets: the follow gap is 12 px, not doubled with the fragment rule, absent after a gap" {
            fun msg(
                model: Sysml2Model,
                seqNo: Int,
            ): MessageUsage = model.usages.filterIsInstance<MessageUsage>().first { it.seqNo == seqNo }

            val adjacent = selfThen(nextSeqNo = 2, back = false)
            val gapped = selfThen(nextSeqNo = 3, back = false)
            val none = SeqRowOffsets.of(fragments = emptyList(), refs = emptyList())
            val adj =
                SeqRowOffsets.of(
                    fragments = emptyList(),
                    refs = emptyList(),
                    messages = listOf(msg(adjacent, 1), msg(adjacent, 2)),
                )
            val gap =
                SeqRowOffsets.of(
                    fragments = emptyList(),
                    refs = emptyList(),
                    messages = listOf(msg(gapped, 1), msg(gapped, 3)),
                )
            (adj.offsetAt(2) - none.offsetAt(2)) shouldBe SYSML2_SELF_CALL_FOLLOW_GAP
            (gap.offsetAt(3) - none.offsetAt(3)) shouldBe 0f
        }

        "a fragment starting right after the self call keeps its single 9 px gap" {
            val model =
                sysml2Model(name = "SelfThenFragment") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "doSomething()", source = a, target = a, seqNo = 1)
                    message(label = "inside()", source = a, target = b, seqNo = 2)
                    combinedFragment(name = "opt", operator = CombinedFragmentOperator.Opt, startSeqNo = 2, endSeqNo = 2, guard = "ok")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val msgs = model.usages.filterIsInstance<MessageUsage>()
            val frags = model.usages.filterIsInstance<CombinedFragmentUsage>()
            val withFrag = SeqRowOffsets.of(fragments = frags, refs = emptyList(), messages = msgs)
            val noFrag = SeqRowOffsets.of(fragments = emptyList(), refs = emptyList(), messages = msgs)
            val band = SYSML2_FRAGMENT_HEADER_BAND
            (withFrag.offsetAt(2) - band) shouldBe 9f
            (noFrag.offsetAt(2)) shouldBe SYSML2_SELF_CALL_FOLLOW_GAP
            render(model, "e3-fragment")
        }

        fun parLoopModel(loopInSecondOperand: Boolean): Sysml2Model =
            sysml2Model(name = "ParLoop") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                for (i in 0..5) message(label = "m$i", source = a, target = b, seqNo = i)
                combinedFragment(name = "p", operator = CombinedFragmentOperator.Par) {
                    operand(guard = "a", start = 0, end = 2) {
                        if (!loopInSecondOperand) {
                            combinedFragment(name = "l", operator = CombinedFragmentOperator.Loop) {
                                operand(guard = "n", start = 1, end = 1)
                            }
                        }
                    }
                    operand(guard = "b", start = 3, end = 5) {
                        if (loopInSecondOperand) {
                            combinedFragment(name = "l", operator = CombinedFragmentOperator.Loop) {
                                operand(guard = "n", start = 3, end = 3)
                            }
                        }
                    }
                }
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        fun frameRect(
            svg: String,
            id: String,
        ): List<Float> {
            val tag = Regex("<rect[^>]*/>").find(block(svg, id))!!.value
            return listOf(attr(tag, "x"), attr(tag, "y"), attr(tag, "width"), attr(tag, "height"))
        }

        for (second in listOf(false, true)) {
            "loop nested in operand ${if (second) 2 else 1} of par is inset 10 px and stays inside the par frame" {
                val svg = render(parLoopModel(loopInSecondOperand = second), "g-par-loop-${if (second) 2 else 1}")
                val (px, py, pw, ph) = frameRect(svg, "combinedFragment:p")
                val (lx, ly, lw, lh) = frameRect(svg, "combinedFragment:l")
                abs((lx - px) - 10f) shouldBeLessThanOrEqual 1f
                abs((px + pw) - (lx + lw) - 10f) shouldBeLessThanOrEqual 1f
                (ly - py) shouldBeGreaterThan 20f
                (ly + lh) shouldBeLessThanOrEqual (py + ph)
            }
        }
    })
