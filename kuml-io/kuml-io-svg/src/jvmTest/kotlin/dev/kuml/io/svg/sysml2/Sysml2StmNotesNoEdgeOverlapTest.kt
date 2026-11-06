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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Nacharbeit 25: notes of a state machine must not cover edges. Geometry only and independent of any font: the note
 * boxes come from a fixed [NoteMeasure], the edges are the ones that are DRAWN (shifted, pseudostate and endpoint
 * spread applied), the clearance is 16 px against edges, nodes and other notes.
 */
class Sysml2StmNotesNoEdgeOverlapTest :
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

        fun trafficLight(): Sysml2Model =
            sysml2Model(name = "TrafficLight") {
                val start = stateDef(name = "Start", isInitial = true)
                val red = stateDef(name = "Red", entryAction = "switchLights('red')", exitAction = "logTransition('red')")
                val green = stateDef(name = "Green", entryAction = "switchLights('green')", doAction = "tickTimer()")
                val yellow = stateDef(name = "Yellow", entryAction = "switchLights('yellow')")
                transition(name = "t0", source = start, target = red)
                transition(name = "t1", source = red, target = green, trigger = "timer60s")
                transition(name = "t2", source = green, target = yellow, trigger = "timer45s")
                val t3 = transition(name = "t3", source = yellow, target = red, trigger = "timer5s")
                note(
                    text = "Error: the controller could not switch the lights and stays in this state until reset.",
                    firstAnchor = red,
                    id = "nError",
                )
                note(
                    text = "Warning: green phase may be shortened by the traffic controller at rush hour.",
                    firstAnchor = green,
                    id = "nWarning",
                )
                note(text = "Info: yellow lasts exactly five seconds, regardless of the weather.", firstAnchor = yellow, id = "nInfo")
                note(
                    text = "Decision: the return from yellow to red is never guarded by a trigger of its own.",
                    firstAnchor = t3,
                    id = "nDecision",
                )
                note(text = "Todo: model the flashing mode for the night shift as a second region.", firstAnchor = start, id = "nTodo")
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
            val m = trafficLight()
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

        /** The edges as drawn, in the frame of the shifted layout (both shifted by the padding). */
        fun drawnEdges(r: Run): Map<String, List<Point>> {
            val visible = r.diagram.visibleStateIds(r.model)
            return KumlSvgRenderer
                .sysml2ShiftedRoutes(
                    layoutResult = r.placed,
                    padding = padding,
                    routeTransform = KumlSvgRenderer.stmRouteTransform(model = r.model, visible = visible),
                ).filterKeys { !it.startsWith("note-link:") }
                .mapValues { it.value.polylinePoints() }
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

        test("no note touches an edge, a node or another note, with 16 px clearance") {
            val r = run()
            val edges = drawnEdges(r)
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
            val failures = mutableListOf<String>()
            for ((nid, nr) in noteRects) {
                val zone = inflate(nr, clearance)
                for ((eid, pts) in edges) if (polylineHitsRect(pts, zone)) failures += "note $nid ${describe(nr)} within 16 px of edge $eid"
                for ((vid, vr) in nodeRects) {
                    if (rectsIntersect(zone, vr)) {
                        failures +=
                            "note $nid ${describe(nr)} within 16 px of node $vid ${describe(vr)}"
                    }
                }
                for ((oid, orr) in noteRects) if (oid != nid && rectsIntersect(zone, orr)) failures += "note $nid within 16 px of note $oid"
            }
            println("[nacharbeit-25] notes: " + noteRects.entries.joinToString { "${it.key}=${describe(it.value)}" })
            println("[nacharbeit-25] failures: ${failures.size}")
            failures.forEach { println("[nacharbeit-25]   $it") }
            failures.shouldBeEmpty()
        }

        test("diagnostic: how far the drawn routes deviate from the raw routes the note placer sees") {
            val r = run()
            val visible = r.diagram.visibleStateIds(r.model)
            val drawn =
                KumlSvgRenderer
                    .sysml2ShiftedRoutes(
                        layoutResult = r.base,
                        padding = padding,
                        routeTransform = KumlSvgRenderer.stmRouteTransform(model = r.model, visible = visible),
                    ).mapValues { e -> e.value.polylinePoints().map { Point(x = it.x - padding, y = it.y - padding) } }

            fun dist(
                p: Point,
                pts: List<Point>,
            ): Float =
                (0 until pts.size - 1).minOf { i ->
                    val a = pts[i]
                    val b = pts[i + 1]
                    val vx = b.x - a.x
                    val vy = b.y - a.y
                    val len2 = vx * vx + vy * vy
                    val t = if (len2 == 0f) 0f else (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0f, 1f)
                    val cx = a.x + t * vx
                    val cy = a.y + t * vy
                    sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
                }
            var worst = 0f
            for ((id, rawEdge) in r.base.edges) {
                val raw = rawEdge.polylinePoints()
                val d = drawn[id.value] ?: continue
                val dev = max(d.maxOf { dist(it, raw) }, raw.maxOf { dist(it, d) })
                println("[nacharbeit-25] edge ${id.value}: raw=${raw.size} pts, drawn=${d.size} pts, deviation=$dev")
                worst = max(worst, abs(dev))
            }
            println("[nacharbeit-25] maximum deviation raw vs drawn: $worst")
        }
    })
