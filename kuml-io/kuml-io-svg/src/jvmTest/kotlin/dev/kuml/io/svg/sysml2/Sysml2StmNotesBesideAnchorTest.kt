package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.bridge.note.NoteMeasure
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.polylinePoints
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.visibleStateIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 26: notes of a state machine sit beside their anchor, not in a strip below the diagram, and their anchor
 * lines cross no state, no foreign note and no foreign edge. Geometry only, independent of any font, measured on the
 * edges and anchor lines as DRAWN.
 */
class Sysml2StmNotesBesideAnchorTest :
    FunSpec({
        val clearance = 16f
        val padding = SvgRenderOptions.DEFAULT.paddingPx

        /** 7 px per character, wrapped at 220 px, 16 px per line plus 16 px padding: deterministic. */
        val fixedMeasure =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size {
                    val width = min(NoteLayout.STM_MAX_W, 7f * body.length + 16f)
                    val perLine = max(1, ((width - 16f) / 7f).toInt())
                    val lines = ceil(body.length.toFloat() / perLine).toInt().coerceAtLeast(1)
                    return Size(width = width, height = 16f * lines + 16f)
                }

                override fun measure(
                    body: String,
                    maxWidth: Float,
                    kind: NoteKind,
                ): Size = measure(body = body, maxWidth = maxWidth)
            }

        fun model(): Sysml2Model =
            sysml2Model(name = "TrafficLight") {
                val start = stateDef(name = "Start", isInitial = true)
                val red = stateDef(name = "Red", entryAction = "switchLights('red')")
                val green = stateDef(name = "Green", entryAction = "switchLights('green')")
                val yellow = stateDef(name = "Yellow", entryAction = "switchLights('yellow')")
                transition(name = "t0", source = start, target = red)
                transition(name = "t1", source = red, target = green, trigger = "timer60s")
                transition(name = "t2", source = green, target = yellow, trigger = "slow")
                val t3 = transition(name = "t3", source = yellow, target = red, trigger = "timer5s")
                note(text = "Error: the lights could not be switched.", firstAnchor = red, id = "nError")
                note(text = "Warning: green may be shortened at rush hour.", firstAnchor = green, id = "nWarning")
                note(text = "Info: yellow lasts five seconds.", firstAnchor = yellow, id = "nInfo")
                note(text = "Decision: the return needs no trigger of its own.", firstAnchor = t3, id = "nDecision")
                note(text = "Todo: model the night mode.", firstAnchor = start, id = "nTodo")
                stmDiagram(name = "d") {
                    include(start)
                    include(red)
                    include(green)
                    include(yellow)
                }
            }

        class Run(
            val model: Sysml2Model,
            val diagram: StmDiagram,
            val base: LayoutResult,
            val placed: LayoutResult,
            val noteIds: List<String>,
        )

        fun run(): Run {
            val m = model()
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = d.layout, kind = SpecDiagramKind.SYSML2_STM)
                    .result
            val base = Sysml2LayoutBridge.withoutStmOrderEdges(raw)
            val placed =
                KumlSvgRenderer
                    .placeSysml2NotesResult(
                        model = m,
                        diagram = d,
                        layout = base,
                        noteMaxWidth = NoteLayout.STM_MAX_W,
                        noteWidthFactor = KumlSvgRenderer.STM_NOTE_WIDTH_FACTOR,
                        measure = fixedMeasure,
                    ).layout
            return Run(m, d, base, placed, m.comments.map { it.id })
        }

        fun shiftedRoutes(r: Run): Map<String, List<Point>> {
            val visible = r.diagram.visibleStateIds(r.model)
            return KumlSvgRenderer
                .sysml2ShiftedRoutes(
                    layoutResult = r.placed,
                    padding = padding,
                    routeTransform = KumlSvgRenderer.stmRouteTransform(model = r.model, visible = visible),
                ).mapValues { it.value.polylinePoints() }
        }

        fun cross2(
            o: Point,
            a: Point,
            b: Point,
        ): Float = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        fun segmentsCross(
            a: Point,
            b: Point,
            c: Point,
            d: Point,
        ): Boolean {
            val d1 = cross2(a, b, c)
            val d2 = cross2(a, b, d)
            val d3 = cross2(c, d, a)
            val d4 = cross2(c, d, b)
            return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        }

        fun shifted(r: Rect) = Rect(origin = Point(x = r.origin.x + padding, y = r.origin.y + padding), size = r.size)

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

        fun rectsIntersect(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        fun segmentHitsRect(
            a: Point,
            b: Point,
            r: Rect,
        ): Boolean {
            // Liang-Barsky clipping against the rectangle.
            val x0 = r.origin.x
            val x1 = r.origin.x + r.size.width
            val y0 = r.origin.y
            val y1 = r.origin.y + r.size.height
            var t0 = 0f
            var t1 = 1f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(a.x - x0, x1 - a.x, a.y - y0, y1 - a.y)
            for (i in 0..3) {
                if (p[i] == 0f) {
                    if (q[i] < 0f) return false
                } else {
                    val t = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (t > t1) return false
                        if (t > t0) t0 = t
                    } else {
                        if (t < t0) return false
                        if (t < t1) t1 = t
                    }
                }
            }
            return true
        }

        fun polylineHitsRect(
            pts: List<Point>,
            r: Rect,
        ): Boolean = (0 until pts.size - 1).any { segmentHitsRect(a = pts[it], b = pts[it + 1], r = r) }

        fun describe(r: Rect) = "(${r.origin.x},${r.origin.y} ${r.size.width}x${r.size.height})"

        fun analyse(r: Run): Triple<List<String>, List<String>, Map<String, Rect>> {
            val routes = shiftedRoutes(r)
            val edges = routes.filterKeys { !it.startsWith("note-link:") }
            val noteRects =
                r.noteIds.associateWith {
                    shifted(
                        r.placed.nodes
                            .getValue(dev.kuml.layout.NodeId(it))
                            .bounds,
                    )
                }
            val nodeRects =
                r.placed.nodes
                    .filterKeys { it.value !in r.noteIds }
                    .mapKeys { it.key.value }
                    .mapValues { shifted(it.value.bounds) }
            val overlaps = mutableListOf<String>()
            val lineFailures = mutableListOf<String>()
            for ((nid, nr) in noteRects) {
                val zone = inflate(nr, clearance)
                for ((eid, pts) in edges) if (polylineHitsRect(pts, zone)) overlaps += "note $nid within 16 px of edge $eid"
                for ((vid, vr) in nodeRects) if (rectsIntersect(zone, vr)) overlaps += "note $nid within 16 px of node $vid"
                for ((oid, orr) in noteRects) if (oid != nid && rectsIntersect(zone, orr)) overlaps += "note $nid within 16 px of note $oid"
            }
            val inner = { rr: Rect -> inflate(rr, -1.5f) }
            for ((lid, pts) in routes.filter { it.key.startsWith("note-link:") }) {
                val owner = lid.removePrefix("note-link:").substringBefore(':')
                // Own anchor target: the state of that name or the edge (aliases are matched by substring of the id).
                for ((vid, vr) in nodeRects) {
                    if (lid.contains(vid, ignoreCase = true)) continue
                    if (polylineHitsRect(pts, inner(vr))) lineFailures += "$lid crosses node $vid"
                }
                for ((oid, orr) in noteRects) {
                    if (lid.contains(oid)) continue
                    if (polylineHitsRect(pts, inner(orr))) lineFailures += "$lid crosses note $oid"
                }
                for ((eid, epts) in edges) {
                    if (lid.contains(eid)) continue
                    for (i in 0 until pts.size - 1) {
                        for (j in 0 until epts.size - 1) {
                            if (segmentsCross(pts[i], pts[i + 1], epts[j], epts[j + 1])) lineFailures += "$lid crosses edge $eid"
                        }
                    }
                }
                check(owner.isNotEmpty())
            }
            return Triple(overlaps.distinct(), lineFailures.distinct(), noteRects)
        }

        test("notes sit beside their anchor and their anchor lines cross nothing") {
            val r = run()
            val (overlaps, lineFailures, noteRects) = analyse(r)
            val baseBottom =
                r.base.nodes.values
                    .maxOf { it.bounds.origin.y + it.bounds.size.height } + padding
            println("[nacharbeit-26] notes: " + noteRects.entries.joinToString { "${it.key}=${describe(it.value)}" })
            println(
                "[nacharbeit-26] routes: " +
                    shiftedRoutes(r).filterKeys { it.startsWith("note-link:") }.entries.joinToString { "${it.key}=${it.value}" },
            )
            println("[nacharbeit-26] overlaps=$overlaps lineFailures=$lineFailures baseBottom=$baseBottom")
            overlaps.shouldBeEmpty()
            lineFailures.shouldBeEmpty()
            val inStrip = noteRects.values.count { it.origin.y >= baseBottom }
            (inStrip <= 1) shouldBe true
        }

        test("placement is deterministic") {
            val a = analyse(run()).third.mapValues { describe(it.value) }
            val b = analyse(run()).third.mapValues { describe(it.value) }
            a shouldBe b
        }
    })
