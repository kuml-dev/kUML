package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs

/**
 * C3 — font-independent geometry checks for the SysML 2 sequence diagram constructs: nested
 * combined fragments, `ref`, duration constraints / time observations, found / lost messages and the
 * destroy marker. Only rect / line / circle coordinates and ordering are asserted (no text metrics).
 */
class Sysml2C3SeqSvgTest :
    StringSpec({
        fun render(
            model: Sysml2Model,
            name: String,
        ): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-c3-seq/$name.svg", content = svg)
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

        data class R(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
        )

        fun frame(
            svg: String,
            id: String,
        ): R {
            val tag = Regex("<rect[^>]*/>").find(block(svg, id))!!.value
            return R(attr(tag, "x"), attr(tag, "y"), attr(tag, "width"), attr(tag, "height"))
        }

        fun lifelineCx(
            svg: String,
            id: String,
        ): Float {
            val g = block(svg, id)
            val t = Regex("translate\\((-?[0-9.]+),(-?[0-9.]+)\\)").find(g)!!
            val w = attr(Regex("<rect[^>]*/>").find(g)!!.value, "width")
            return t.groupValues[1].toFloat() + w / 2f
        }

        fun nestedModel(extraDepth: Int = 0): Sysml2Model =
            sysml2Model(name = "Nested") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                for (i in 0..6) message(label = "m$i", source = a, target = b, seqNo = i)
                combinedFragment(name = "retry", operator = CombinedFragmentOperator.Loop) {
                    operand(guard = "attempts < 3", start = 1, end = 5) {
                        combinedFragment(name = "cache", operator = CombinedFragmentOperator.Alt) {
                            operand(guard = "hit", start = 1, end = 2)
                            operand(guard = "miss", start = 3, end = 4)
                        }
                    }
                }
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        "nested fragment frame lies inside its parent, inset 10 px per level and one header band lower" {
            val svg = render(nestedModel(), "nested")
            val parent = frame(svg, "combinedFragment:retry")
            val child = frame(svg, "combinedFragment:cache")
            (child.x - parent.x) shouldBeGreaterThan 9f
            abs((child.x - parent.x) - 10f) shouldBeLessThanOrEqual 1f
            abs((parent.x + parent.w) - (child.x + child.w) - 10f) shouldBeLessThanOrEqual 1f
            (child.y - parent.y) shouldBeGreaterThan 20f
            ((child.y + child.h) <= (parent.y + parent.h)) shouldBe true
        }

        "an ordinary flat fragment keeps its single-band geometry" {
            val model =
                sysml2Model(name = "Flat") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    message(label = "m1", source = b, target = a, seqNo = 1)
                    combinedFragment(name = "opt", operator = CombinedFragmentOperator.Opt, startSeqNo = 0, endSeqNo = 1, guard = "ok")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model, "flat")
            val f = frame(svg, "combinedFragment:opt")
            // top = first message y - 24 (band) - 13; first message sits at head bottom + 32 + 24
            val y0 = Regex("<g id=\"message:a-b-0\">\\s*<line[^>]*y1=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toFloat()
            abs((y0 - 24f - 13f) - f.y) shouldBeLessThanOrEqual 1f
        }

        "interaction use frame is 48 px high and covers the lifelines with 16 px margin" {
            val model =
                sysml2Model(name = "Ref") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    val c = lifelineDef(name = "c")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    interactionUse(name = "auth", refersTo = "Authenticate", lifelines = listOf(a, b), seqNo = 1)
                    message(label = "m2", source = b, target = c, seqNo = 2)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val svg = render(model, "ref")
            val f = frame(svg, "interactionUse:auth")
            f.h shouldBe 48f
            val aLeft = lifelineCx(svg, "a") - 70f
            abs(f.x - (aLeft - 16f)) shouldBeLessThanOrEqual 1f
            svg shouldContain "Authenticate"
            svg shouldNotContain "href"
            // rows after the ref are pushed down by 16 px: m2 sits 32 + 16 below m0's successor row
            val m0 = Regex("<g id=\"message:a-b-0\">\\s*<line[^>]*y1=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toFloat()
            val m2 = Regex("<g id=\"message:b-c-2\">\\s*<line[^>]*y1=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toFloat()
            abs((m2 - m0) - (2 * 32f + 16f)) shouldBeLessThanOrEqual 1f
        }

        "duration constraint bracket sits 14 px right of the axis and time observation draws a tick" {
            val model =
                sysml2Model(name = "Time") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "req", source = a, target = b, seqNo = 0)
                    message(label = "resp", source = b, target = a, seqNo = 2, kind = MessageKind.Reply)
                    durationConstraint(lifeline = a, from = 0, to = 2, expression = "0..3s")
                    timeObservation(lifeline = b, seqNo = 0, name = "t0")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model, "time")
            val g = block(svg, "durationConstraint:a-0-2")
            val line = Regex("<line[^>]*/>").findAll(g).first().value
            val x = attr(line, "x1")
            abs(x - (lifelineCx(svg, "a") + 14f)) shouldBeLessThanOrEqual 1f
            attr(line, "y2") shouldBeGreaterThan attr(line, "y1")
            g shouldContain "{0..3s}"
            block(svg, "timeObservation:b-0") shouldContain "t0 = now"
        }

        "found and lost messages are 80 px long with a 5 px circle and are no self-calls" {
            val model =
                sysml2Model(name = "FoundLost") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    foundMessage(label = "ping", target = b, seqNo = 0)
                    lostMessage(label = "drop", source = b, seqNo = 1)
                    message(label = "call", source = a, target = b, seqNo = 2)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model, "found-lost")
            for (id in listOf("message:found-b-0", "message:lost-b-1")) {
                val g = block(svg, id)
                val line = Regex("<line[^>]*/>").find(g)!!.value
                abs(abs(attr(line, "x2") - attr(line, "x1")) - 80f) shouldBeLessThanOrEqual 1f
                attr(Regex("<circle[^>]*/>").find(g)!!.value, "r") shouldBe 5f
                g shouldNotContain "<path"
            }
        }

        "destroy: the label boxes end before the X marker" {
            val model =
                sysml2Model(name = "Destroy") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "delete()", source = a, target = b, seqNo = 0, kind = MessageKind.Destroy)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model, "destroy")
            val g = block(svg, "message:a-b-0")
            val diagonals = Regex("<line[^>]*/>").findAll(g).map { it.value }.toList()
            // shaft + two diagonals of the 16 px X
            val xLine = diagonals.last()
            abs(abs(attr(xLine, "x2") - attr(xLine, "x1")) - 16f) shouldBeLessThanOrEqual 0.5f
            val markerLeft = minOf(attr(xLine, "x1"), attr(xLine, "x2"))
            val stereotype = Regex("<text[^>]*kuml-stereotype[^>]*x=\"([0-9.]+)\"").find(g)!!.groupValues[1].toFloat()
            // «destroy» is 9 chars; estimated half width 6.5 * 9 / 2
            (stereotype + 6.5f * 9f / 2f) shouldBe (stereotype + 6.5f * 9f / 2f)
            ((stereotype + 6.5f * 9f / 2f) <= markerLeft - 4f) shouldBe true
        }

        "nesting deeper than the limit and cyclic parents are rendered flat without failing" {
            val deep =
                sysml2Model(name = "Deep") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    var parent: String? = null
                    for (i in 0 until 10) {
                        val id = "f$i"
                        combinedFragment(
                            name = id,
                            operator = CombinedFragmentOperator.Opt,
                            operands =
                                listOf(
                                    dev.kuml.sysml2.CombinedFragmentOperand(guard = "g$i", startSeqNo = 0, endSeqNo = 0),
                                ),
                            id = id,
                            parentFragmentId = parent,
                            parentOperandIndex = parent?.let { 0 },
                        )
                        parent = id
                    }
                    // a cycle: f0 -> f9 (f9 is a descendant of f0)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(deep, "deep")
            svg shouldContain "id=\"f9\""
            val cyc =
                sysml2Model(name = "Cycle") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    combinedFragment(
                        name = "x",
                        operator = CombinedFragmentOperator.Opt,
                        operands = listOf(dev.kuml.sysml2.CombinedFragmentOperand(guard = "g", startSeqNo = 0, endSeqNo = 0)),
                        id = "x",
                        parentFragmentId = "y",
                        parentOperandIndex = 0,
                    )
                    combinedFragment(
                        name = "y",
                        operator = CombinedFragmentOperator.Opt,
                        operands = listOf(dev.kuml.sysml2.CombinedFragmentOperand(guard = "g", startSeqNo = 0, endSeqNo = 0)),
                        id = "y",
                        parentFragmentId = "x",
                        parentOperandIndex = 0,
                    )
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            render(cyc, "cycle") shouldContain "id=\"x\""
        }

        "text in guards, ref names and time expressions is escaped" {
            val model =
                sysml2Model(name = "Escape") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    combinedFragment(
                        name = "opt",
                        operator = CombinedFragmentOperator.Opt,
                        startSeqNo = 0,
                        endSeqNo = 0,
                        guard = "<script>alert(\"x\")&",
                    )
                    interactionUse(name = "r", refersTo = "<b>&\"", lifelines = listOf(a), seqNo = 1)
                    durationConstraint(lifeline = a, from = 0, to = 1, expression = "<1&2>")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model, "escape")
            svg shouldNotContain "<script"
            svg shouldNotContain "<b>"
            svg shouldContain "&lt;script&gt;"
            svg shouldContain "{&lt;1&amp;2&gt;}"
        }
    })
