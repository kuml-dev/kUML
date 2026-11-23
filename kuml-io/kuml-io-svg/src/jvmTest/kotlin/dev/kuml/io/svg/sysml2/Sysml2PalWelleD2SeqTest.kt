package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * PAL Welle D2 (points 12 SEQ): font-independent geometry checks read from the SVG (no byte comparison).
 * Text widths are estimated as `6.5 px * length`, so every text assertion carries a tolerance.
 */
class Sysml2PalWelleD2SeqTest :
    StringSpec({
        fun render(model: Sysml2Model): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            return KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
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

        fun lifelineCx(
            svg: String,
            id: String,
        ): Float {
            val g = block(svg, id)
            val t = Regex("translate\\((-?[0-9.]+),(-?[0-9.]+)\\)").find(g)!!
            val w = attr(Regex("<rect[^>]*/>").find(g)!!.value, "width")
            return t.groupValues[1].toFloat() + w / 2f
        }

        fun svgWidth(svg: String): Float = attr(Regex("<svg[^>]*>").find(svg)!!.value.replace("xmlns", "x"), "width")

        // ---- 12d found / lost -------------------------------------------------------------------------------

        fun foundLostModel(
            targetIndex: Int,
            found: Boolean,
        ): Pair<Sysml2Model, String> {
            var kind = ""
            val model =
                sysml2Model(name = "FoundLost") {
                    val ll = listOf("a", "b", "c").map { lifelineDef(name = it) }
                    if (found) {
                        foundMessage(label = "ping", target = ll[targetIndex], seqNo = 0)
                    } else {
                        lostMessage(label = "ping", source = ll[targetIndex], seqNo = 0)
                    }
                    kind = if (found) "found" else "lost"
                    seqDiagram(name = "S") { ll.forEach { include(it) } }
                }
            return model to kind
        }

        for (found in listOf(true, false)) {
            for (idx in 0..2) {
                "12d: ${if (found) "found" else "lost"} message at lifeline $idx: circle outside, >= 16 px clear, inside canvas" {
                    val (model, kind) = foundLostModel(idx, found)
                    val svg = render(model)
                    val g = block(svg, "message:$kind-${listOf("a", "b", "c")[idx]}-0")
                    val circle = Regex("<circle[^>]*/>").find(g)!!.value
                    val cx = attr(circle, "cx")
                    val axes = listOf("a", "b", "c").map { lifelineCx(svg, it) }
                    axes.forEach { abs(cx - it) shouldBeGreaterThanOrEqualTo 16f }
                    if (found) (cx < axes.min()) shouldBe true else (cx > axes.max()) shouldBe true
                    (cx - 5f) shouldBeGreaterThanOrEqualTo 0f
                    (cx + 5f) shouldBeLessThanOrEqual svgWidth(svg)
                }
            }
        }

        // ---- 12e exec labels --------------------------------------------------------------------------------

        fun execLabelModel(name: String): Sysml2Model =
            sysml2Model(name = "Exec") {
                val client = lifelineDef(name = "Client")
                val server = lifelineDef(name = "Server")
                val store = lifelineDef(name = "Store")
                message(label = "request", source = client, target = server, seqNo = 0)
                message(label = "load", source = server, target = store, seqNo = 1)
                message(label = "reply", source = server, target = client, seqNo = 2)
                executionSpec(name = name, lifeline = server, startSeqNo = 0, endSeqNo = 2)
                seqDiagram(name = "S") {
                    include(client)
                    include(server)
                    include(store)
                }
            }

        "12e: a long exec label is shown in full over at most three lines and stays clear of the next lifeline" {
            val name = "Tuerkontaktfehler erkennen und Sperre der betroffenen Schleuse setzen"
            val svg = render(execLabelModel(name))
            val g = block(svg, "executionSpec:Server-0-2")
            val texts =
                Regex("<text[^>]*>([^<]*)</text>")
                    .findAll(g)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            (texts.size in 2..3) shouldBe true
            texts.joinToString(" ") shouldBe name
            (texts.none { it.contains("…") }) shouldBe true
            val xRight = lifelineCx(svg, "Store")
            val labelX = Regex("<text[^>]*x=\"(-?[0-9.]+)\"").find(g)!!.groupValues[1].toFloat()
            val widest = texts.maxOf { it.length } * 6.5f
            (labelX + widest) shouldBeLessThanOrEqual (xRight - 8f)
        }

        // ---- 12c tab / guard above the lifelines ------------------------------------------------------------

        "12c: operator tab, name and guards are painted after every lifeline" {
            val model =
                sysml2Model(name = "Frag") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    for (i in 0..3) message(label = "m$i", source = a, target = b, seqNo = i)
                    combinedFragment(name = "check", operator = dev.kuml.sysml2.CombinedFragmentOperator.Alt) {
                        operand(guard = "ok", start = 0, end = 1)
                        operand(guard = "fail", start = 2, end = 3)
                    }
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model)
            val lastLifeline = maxOf(svg.indexOf("<g id=\"a\""), svg.indexOf("<g id=\"b\""))
            val overlay = svg.indexOf("<g class=\"kuml-seq-fragment-tab\"")
            (overlay > lastLifeline) shouldBe true
            val tab = svg.substring(overlay, svg.indexOf("</g>", overlay))
            tab.contains("<polygon") shouldBe true
            tab.contains("fill=\"white\"") shouldBe true
            // both guards are in the overlay, each with an opaque background rect right before its text
            Regex("<rect[^>]*fill=\"white\"[^>]*/>\\s*<text[^>]*>\\s*\\[(ok|fail)\\]").findAll(tab).count() shouldBe 2
            // the frame group itself holds neither tab nor guards
            val frameStart = svg.indexOf("<g id=\"combinedFragment:check\"")
            val frame = svg.substring(frameStart, svg.indexOf("</g>", frameStart))
            frame.contains("<polygon") shouldBe false
            frame.contains("<text") shouldBe false
        }

        // ---- 12a fragment frame width ------------------------------------------------------------------------

        "12a: fragmentFrameBounds keeps 12 px from every uncovered lifeline axis" {
            // a wide guard asks for left = 100, the uncovered axis stands at 120 -> clamped to 132
            fragmentFrameBounds(left = 100f, right = 500f, coveredLeft = 200f, coveredRight = 400f, foreignAxes = listOf(120f, 800f))
                .shouldBe(132f to 500f)
            fragmentFrameBounds(left = 100f, right = 790f, coveredLeft = 200f, coveredRight = 400f, foreignAxes = listOf(120f, 800f))
                .shouldBe(132f to 788f)
            // nothing foreign -> unchanged; the clamp never cuts into the covered span
            fragmentFrameBounds(left = 100f, right = 500f, coveredLeft = 200f, coveredRight = 400f, foreignAxes = emptyList())
                .shouldBe(100f to 500f)
            fragmentFrameBounds(left = 100f, right = 500f, coveredLeft = 200f, coveredRight = 400f, foreignAxes = listOf(195f))
                .shouldBe(200f to 500f)
        }

        "12a: a fragment over b and c with a long guard does not reach the axis of a or d" {
            val model =
                sysml2Model(name = "Frag") {
                    val ll = listOf("a", "b", "c", "d").map { lifelineDef(name = it) }
                    message(label = "m0", source = ll[1], target = ll[2], seqNo = 0)
                    message(label = "m1", source = ll[2], target = ll[1], seqNo = 1)
                    message(label = "m2", source = ll[1], target = ll[2], seqNo = 2)
                    message(label = "m3", source = ll[0], target = ll[3], seqNo = 3)
                    combinedFragment(name = "x", operator = dev.kuml.sysml2.CombinedFragmentOperator.Alt) {
                        operand(guard = "ein sehr langer Guard-Text der nach links ragt", start = 0, end = 1)
                        operand(guard = "noch ein sehr langer Guard-Text fuer den zweiten Operanden", start = 2, end = 2)
                    }
                    seqDiagram(name = "S") { ll.forEach { include(it) } }
                }
            val svg = render(model)
            val g = block(svg, "combinedFragment:x")
            val rect = Regex("<rect[^>]*/>").find(g)!!.value
            val left = attr(rect, "x")
            val right = left + attr(rect, "width")
            val a = lifelineCx(svg, "a")
            val d = lifelineCx(svg, "d")
            (left - a) shouldBeGreaterThanOrEqualTo 12f
            (d - right) shouldBeGreaterThanOrEqualTo 12f
        }

        // ---- 12b frame bottom vs. message label -------------------------------------------------------------

        "12b: the label of the message below a fragment ending in a self call keeps 4 px from the frame bottom" {
            val model =
                sysml2Model(name = "Frag") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m0", source = a, target = b, seqNo = 0)
                    message(label = "selfCall", source = b, target = b, seqNo = 1)
                    message(label = "nextLabel", source = a, target = b, seqNo = 2)
                    combinedFragment(
                        name = "x",
                        operator = dev.kuml.sysml2.CombinedFragmentOperator.Opt,
                        startSeqNo = 0,
                        endSeqNo = 1,
                        guard = "g",
                    )
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val svg = render(model)
            val g = block(svg, "combinedFragment:x")
            val rect = Regex("<rect[^>]*/>").find(g)!!.value
            val bottom = attr(rect, "y") + attr(rect, "height")
            val label = Regex("<rect([^>]*)fill=\"white\"([^>]*)/>\\s*<text[^>]*>\\s*nextLabel").find(svg)!!
            val labelTop = attr(label.value, "y")
            (labelTop - bottom) shouldBeGreaterThanOrEqualTo 4f
        }

        // ---- 12f duration constraint dimension line ---------------------------------------------------------

        "12f: durationDimensionGeometry puts a tip on each tick, outwards for a long span and inwards for a short one" {
            val long = durationDimensionGeometry(x = 50f, y1 = 100f, y2 = 200f)
            long.topArrow.first() shouldBe (50f to 100f)
            long.bottomArrow.first() shouldBe (50f to 200f)
            long.topArrow[1].second shouldBe 106f
            long.bottomArrow[1].second shouldBe 194f
            val short = durationDimensionGeometry(x = 50f, y1 = 100f, y2 = 110f)
            short.topArrow[1].second shouldBe 94f
            short.bottomArrow[1].second shouldBe 116f
        }

        "12f: a duration constraint draws a vertical dimension line with two arrowheads at its ends and an opaque label" {
            val model =
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
            val svg = render(model)
            val g = block(svg, "durationConstraint:a-0-2")
            val shaft = Regex("<line[^>]*/>").find(g)!!.value
            val y1 = attr(shaft, "y1")
            val y2 = attr(shaft, "y2")
            (attr(shaft, "x1") == attr(shaft, "x2")) shouldBe true
            (y2 - y1) shouldBeGreaterThan 40f
            val arrows = Regex("<polygon[^>]*points=\"([^\"]+)\"").findAll(g).map { it.groupValues[1] }.toList()
            arrows.size shouldBe 2
            val tips =
                arrows
                    .map {
                        it
                            .split(" ")
                            .first()
                            .split(",")[1]
                            .toFloat()
                    }.sorted()
            abs(tips[0] - y1) shouldBeLessThanOrEqual 1f
            abs(tips[1] - y2) shouldBeLessThanOrEqual 1f
            // the label has an opaque white background rect directly before its text
            Regex("<rect[^>]*fill=\"white\"[^>]*/>\\s*<text[^>]*>\\s*\\{0\\.\\.3s\\}").containsMatchIn(g) shouldBe true
        }
    })
