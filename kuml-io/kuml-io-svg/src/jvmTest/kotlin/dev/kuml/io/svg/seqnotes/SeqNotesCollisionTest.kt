package dev.kuml.io.svg.seqnotes

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.io.svg.SeqPrim
import dev.kuml.io.svg.SeqSceneCapture
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlInteractionOperand
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 39: sequence-diagram notes collide with nothing. The oracle reads the FINAL SVG (not the production
 * obstacle builder's scene): every note keeps 15 px (the 16 px rule minus rounding) from every drawn rectangle, text,
 * line, stem and frame, and no anchor line runs through a foreign note, head box or activation bar.
 */
class SeqNotesCollisionTest :
    FunSpec({
        val noteRe =
            Regex("<g id=\"([^\"]+)\" transform=\"translate\\((-?[\\d.]+),(-?[\\d.]+)\\)\">\\s*<path d=\"([^\"]+)\" class=\"kuml-comment")
        val numRe = Regex("-?\\d+(?:\\.\\d+)?")

        fun notesOf(svg: String): Map<String, Rect> =
            noteRe.findAll(svg).associate { m ->
                val nums = numRe.findAll(m.groupValues[4]).map { it.value.toFloat() }.toList()
                val w = nums.filterIndexed { i, _ -> i % 2 == 0 }.max()
                val h = nums.filterIndexed { i, _ -> i % 2 == 1 }.max()
                m.groupValues[1] to
                    Rect(origin = Point(x = m.groupValues[2].toFloat(), y = m.groupValues[3].toFloat()), size = Size(width = w, height = h))
            }

        fun anchorLines(svg: String): List<List<Point>> {
            val out = ArrayList<List<Point>>()
            Regex("<line x1=\"([^\"]+)\" y1=\"([^\"]+)\" x2=\"([^\"]+)\" y2=\"([^\"]+)\" class=\"kuml-note-anchor\"").findAll(svg).forEach {
                val v = it.groupValues.drop(1).map { s -> s.toFloat() }
                out += listOf(Point(x = v[0], y = v[1]), Point(x = v[2], y = v[3]))
            }
            Regex("<path d=\"([^\"]+)\" class=\"kuml-note-anchor\"").findAll(svg).forEach {
                val n = numRe.findAll(it.groupValues[1]).map { x -> x.value.toFloat() }.toList()
                out += n.chunked(2).filter { c -> c.size == 2 }.map { c -> Point(x = c[0], y = c[1]) }
            }
            return out
        }

        fun inflate(
            r: Rect,
            d: Float,
        ) = Rect(
            origin = Point(x = r.origin.x - d, y = r.origin.y - d),
            size =
                Size(
                    width = r.size.width + 2 * d,
                    height =
                        r.size.height + 2 * d,
                ),
        )

        fun hit(
            a: Rect,
            b: Rect,
        ) = a.origin.x < b.origin.x + b.size.width &&
            b.origin.x < a.origin.x + a.size.width &&
            a.origin.y < b.origin.y + b.size.height &&
            b.origin.y < a.origin.y + a.size.height

        fun segHitsRect(
            p: Point,
            q: Point,
            r: Rect,
        ): Boolean {
            // axis-parallel segments only (every anchor leg and drawn line is)
            val lo = Point(x = min(p.x, q.x), y = min(p.y, q.y))
            val seg = Rect(origin = lo, size = Size(width = max(p.x, q.x) - lo.x, height = max(p.y, q.y) - lo.y))
            return hit(seg, r)
        }

        fun outerFrame(svg: String): Rect? =
            Regex("<svg[^>]*viewBox=\"0 0 ([\\d.]+) ([\\d.]+)\"").find(svg)?.let {
                Rect(origin = Point(x = 0f, y = 0f), size = Size(width = it.groupValues[1].toFloat(), height = it.groupValues[2].toFloat()))
            }

        /** Asserts the whole rule set; returns the number of anchor-line crossings of foreign stems/messages (printed only). */
        fun assertClean(
            name: String,
            svg: String,
            expectedNotes: Int,
        ) {
            SampleOutput.write(filename = "seqnotes/$name.svg", content = svg)
            val notes = notesOf(svg)
            notes.size shouldBe expectedNotes
            val canvas = outerFrame(svg)!!
            val noteIds = notes.keys
            val anchorGroup = Regex("^(note-link:.*|n\\d+-l\\d+)$")
            val prims =
                SeqSceneCapture.parse(svg).filter {
                    it.groupId !in noteIds &&
                        !(it.groupId?.let { g -> anchorGroup.matches(g) } ?: false)
                }
            val problems = ArrayList<String>()
            for ((id, n) in notes) {
                // inside the canvas
                if (n.origin.x < 0f ||
                    n.origin.y < 0f ||
                    n.origin.x + n.size.width > canvas.size.width ||
                    n.origin.y + n.size.height > canvas.size.height
                ) {
                    problems += "$id outside the canvas"
                }
                val zone = inflate(n, 15f)
                for (p in prims) {
                    when (p) {
                        is SeqPrim.Box -> {
                            val big = p.rect.size.width >= canvas.size.width - 12f && p.rect.size.height >= canvas.size.height - 12f
                            if (!big && hit(zone, p.rect)) problems += "$id near box ${p.groupId} ${p.rect}"
                        }
                        is SeqPrim.Text -> if (hit(zone, p.rect) && p.rect.size.width > 0f) problems += "$id near text ${p.groupId}"
                        is SeqPrim.Path -> {
                            if (p.pts.any { it.x.isNaN() }) continue
                            for (i in 0 until p.pts.size - 1) {
                                if (segHitsRect(p.pts[i], p.pts[i + 1], zone)) {
                                    problems +=
                                        "$id near line ${p.groupId}"
                                }
                            }
                        }
                    }
                }
                for ((other, o) in notes) if (other != id && hit(inflate(n, 15f), o)) problems += "$id near note $other"
            }
            // anchor lines: not through foreign notes or boxes (heads, bars)
            val frameGroup = Regex("combinedFragment:.*|alt1|loop1|opt1")
            val boxes =
                prims.filterIsInstance<SeqPrim.Box>().filter {
                    it.rect.size.width < canvas.size.width - 12f && !it.dashed && !(it.groupId?.let { g -> frameGroup.matches(g) } ?: false)
                }
            for (line in anchorLines(svg)) {
                for (i in 0 until line.size - 1) {
                    for (b in boxes) {
                        if (b.rect.size.width > 0f &&
                            segHitsRect(line[i], line[i + 1], inflate(b.rect, -1f))
                        ) {
                            problems += "anchor through box ${b.groupId}"
                        }
                    }
                    for ((id, n) in notes) {
                        val own = i == 0 && (line[0].x in n.origin.x - 1f..n.origin.x + n.size.width + 1f)
                        if (!own && segHitsRect(line[i], line[i + 1], inflate(n, -1f))) problems += "anchor through note $id"
                    }
                }
            }
            problems.distinct().shouldBeEmpty()
        }

        // ------------------------------------------------------------------ SysML 2

        fun sysmlSvg(model: Sysml2Model): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            return KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
        }

        test("sysml2 (a) fifteen notes on messages of a plain sequence") {
            val model =
                sysml2Model(name = "A") {
                    val l = (0..3).map { lifelineDef(name = "L$it") }
                    val ms = (0..14).map { i -> message(label = "call$i", source = l[i % 3], target = l[i % 3 + 1], seqNo = i) }
                    ms.forEachIndexed { i, m -> comment(text = "Note on message $i", firstAnchor = m, id = "n$i") }
                    seqDiagram(name = "S") { l.forEach { include(it) } }
                }
            assertClean("sysml2-a-messages", sysmlSvg(model), 15)
        }

        test("sysml2 (b) participant notes") {
            val model =
                sysml2Model(name = "B") {
                    val l = (0..3).map { lifelineDef(name = "L$it") }
                    (0..5).forEach { i -> message(label = "m$i", source = l[i % 3], target = l[i % 3 + 1], seqNo = i) }
                    l.forEachIndexed {
                        i,
                        x,
                        ->
                        comment(text = "About participant $i with a longer text that wraps", firstAnchor = x, id = "n$i")
                    }
                    seqDiagram(name = "S") { l.forEach { include(it) } }
                }
            assertClean("sysml2-b-participants", sysmlSvg(model), 4)
        }

        test("sysml2 (c) a note across several lifelines") {
            val model =
                sysml2Model(name = "C") {
                    val l = (0..3).map { lifelineDef(name = "L$it") }
                    (0..5).forEach { i -> message(label = "m$i", source = l[i % 3], target = l[i % 3 + 1], seqNo = i) }
                    comment(text = "Spans three lifelines", firstAnchor = l[0], l[1], l[3], id = "n0")
                    seqDiagram(name = "S") { l.forEach { include(it) } }
                }
            assertClean("sysml2-c-multi", sysmlSvg(model), 1)
        }

        test("sysml2 (d) alt inside loop plus opt, fifteen notes") {
            val model =
                sysml2Model(name = "D") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    val c = lifelineDef(name = "c")
                    val ms =
                        (0..11).map { i ->
                            message(
                                label = "m$i",
                                source =
                                    if (i % 2 ==
                                        0
                                    ) {
                                        a
                                    } else {
                                        b
                                    },
                                target = if (i % 2 == 0) b else c,
                                seqNo = i,
                            )
                        }
                    combinedFragment(name = "retry", operator = CombinedFragmentOperator.Loop) {
                        operand(guard = "attempts < 3", start = 1, end = 7) {
                            combinedFragment(name = "cache", operator = CombinedFragmentOperator.Alt) {
                                operand(guard = "hit", start = 1, end = 3)
                                operand(guard = "miss", start = 4, end = 6)
                            }
                        }
                    }
                    combinedFragment(name = "tail", operator = CombinedFragmentOperator.Opt, startSeqNo = 9, endSeqNo = 10, guard = "done")
                    ms.take(12).forEachIndexed { i, m -> comment(text = "Note $i on a message", firstAnchor = m, id = "n$i") }
                    comment(text = "On a", firstAnchor = a, id = "n12")
                    comment(text = "On b", firstAnchor = b, id = "n13")
                    comment(text = "On c", firstAnchor = c, id = "n14")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            assertClean("sysml2-d-fragments", sysmlSvg(model), 15)
        }

        test("sysml2 (e) notes next to activation bars") {
            val model =
                sysml2Model(name = "E") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    (0..5).forEach { i ->
                        message(
                            label = "m$i",
                            source =
                                if (i % 2 ==
                                    0
                                ) {
                                    a
                                } else {
                                    b
                                },
                            target = if (i % 2 == 0) b else a,
                            seqNo = i,
                        )
                    }
                    executionSpec(name = "work", lifeline = b, startSeqNo = 0, endSeqNo = 2)
                    executionSpec(name = "more", lifeline = a, startSeqNo = 3, endSeqNo = 4)
                    comment(text = "Bar of b", firstAnchor = b, id = "n0")
                    comment(text = "Bar of a", firstAnchor = a, id = "n1")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            assertClean("sysml2-e-bars", sysmlSvg(model), 2)
        }

        test("sysml2 (f) narrow gap and a free-standing note") {
            val model =
                sysml2Model(name = "F") {
                    val a = lifelineDef(name = "a")
                    val b = lifelineDef(name = "b")
                    message(label = "m", source = a, target = b, seqNo = 0)
                    comment(
                        text = "A wide note: this text is long enough to need the full note width of the placer",
                        firstAnchor = a,
                        id = "n0",
                    )
                    comment(text = "free", id = "n1")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                        comment(text = "free", id = "n1x")
                    }
                }
            assertClean("sysml2-f-narrow", sysmlSvg(model), 2)
        }

        test("sysml2: placement is deterministic") {
            val build = {
                sysml2Model(name = "A") {
                    val l = (0..2).map { lifelineDef(name = "L$it") }
                    val ms = (0..5).map { i -> message(label = "c$i", source = l[i % 2], target = l[i % 2 + 1], seqNo = i) }
                    ms.forEachIndexed { i, m -> comment(text = "Note $i", firstAnchor = m, id = "n$i") }
                    seqDiagram(name = "S") { l.forEach { include(it) } }
                }
            }
            sysmlSvg(build()) shouldBe sysmlSvg(build())
        }

        // ------------------------------------------------------------------ UML

        fun umlDiagram(
            lifelineCount: Int,
            messageCount: Int,
            withFragments: Boolean,
            notes: List<Pair<String, List<String>>>,
        ): Pair<KumlDiagram, LayoutResult> {
            val ls = (0 until lifelineCount).map { UmlLifeline(id = "ll$it", name = "Life$it") }
            val ms =
                (1..messageCount).map { i ->
                    UmlMessage(
                        id = "msg$i",
                        label = "call $i",
                        fromLifelineId = ls[(i - 1) % (lifelineCount - 1)].id,
                        toLifelineId = ls[(i - 1) % (lifelineCount - 1) + 1].id,
                        sort = MessageSort.SYNC_CALL,
                        sequence = i,
                    )
                }
            val frags =
                if (withFragments) {
                    val inner =
                        UmlCombinedFragment(
                            id = "alt1",
                            operator = InteractionOperator.ALT,
                            operands =
                                listOf(
                                    UmlInteractionOperand(guard = "[hit]", messageIds = listOf("msg3", "msg4")),
                                    UmlInteractionOperand(guard = "[miss]", messageIds = listOf("msg5")),
                                ),
                        )
                    val outer =
                        UmlCombinedFragment(
                            id = "loop1",
                            operator = InteractionOperator.LOOP,
                            operands =
                                listOf(
                                    UmlInteractionOperand(
                                        guard = "[k < 3]",
                                        messageIds = listOf("msg2", "msg6"),
                                        fragmentIds = listOf("alt1"),
                                    ),
                                ),
                        )
                    val opt =
                        UmlCombinedFragment(
                            id = "opt1",
                            operator = InteractionOperator.OPT,
                            operands = listOf(UmlInteractionOperand(guard = "[done]", messageIds = listOf("msg8", "msg9"))),
                        )
                    listOf(inner, outer, opt)
                } else {
                    emptyList()
                }
            val interaction = UmlInteraction(id = "I", name = "I", lifelines = ls, messages = ms, fragments = frags)
            val elements = ArrayList<dev.kuml.uml.UmlElement>()
            elements += interaction
            notes.forEachIndexed { i, (body, targets) ->
                elements += UmlComment(id = "n$i", body = body)
                targets.forEachIndexed { j, t -> elements += UmlCommentLink(id = "n$i-l$j", commentId = "n$i", annotatedElementId = t) }
            }
            val diagram = KumlDiagram(name = "I", type = DiagramType.SEQUENCE, elements = elements)
            val nodeH = 40f + (messageCount + 1) * 32f + 96f + 40f
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 40f + lifelineCount * 200f, height = nodeH + 40f),
                    nodes =
                        ls
                            .mapIndexed { i, l ->
                                NodeId(l.id) to
                                    NodeLayout(
                                        bounds =
                                            Rect(
                                                origin = Point(x = 40f + i * 200f, y = 20f),
                                                size = Size(width = 140f, height = nodeH),
                                            ),
                                    )
                            }.toMap(),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            return diagram to layout
        }

        fun umlSvg(
            lifelines: Int,
            messages: Int,
            fragments: Boolean,
            notes: List<Pair<String, List<String>>>,
        ): String {
            val (d, l) = umlDiagram(lifelines, messages, fragments, notes)
            return KumlSvgRenderer.toSvg(diagram = d, layoutResult = l, theme = PlainTheme())
        }

        test("uml (a) fifteen notes on messages") {
            val notes = (1..15).map { "Note on message $it" to listOf("msg$it") }
            assertClean("uml-a-messages", umlSvg(4, 15, false, notes), 15)
        }

        test("uml (b) participant notes") {
            val notes = (0..3).map { "About participant $it, with a longer text" to listOf("ll$it") }
            assertClean("uml-b-participants", umlSvg(4, 6, false, notes), 4)
        }

        test("uml (c) note across lifelines") {
            assertClean("uml-c-multi", umlSvg(4, 6, false, listOf("Spans three" to listOf("ll0", "ll1", "ll3"))), 1)
        }

        test("uml (d) alt in loop plus opt with fifteen notes") {
            val notes =
                (1..12).map { "Note $it on a message" to listOf("msg$it") } +
                    listOf("On life 0" to listOf("ll0"), "On life 1" to listOf("ll1"), "On life 2" to listOf("ll2"))
            assertClean("uml-d-fragments", umlSvg(3, 12, true, notes), 15)
        }

        test("uml (f) narrow gaps and unresolved anchors do not break placement") {
            val notes =
                listOf(
                    "Anchored nowhere" to listOf("does-not-exist"),
                    "Free" to emptyList(),
                    "Wide wide wide wide wide wide wide wide wide wide note" to listOf("ll0"),
                )
            assertClean("uml-f-narrow", umlSvg(2, 3, false, notes), 3)
        }
    })
