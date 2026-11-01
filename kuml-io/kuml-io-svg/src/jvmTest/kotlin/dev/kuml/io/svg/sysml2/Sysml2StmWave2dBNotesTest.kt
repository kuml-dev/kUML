package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** PAL wave 2D-b, P-STM-04e: notes of a state machine wrap at 220 px and stay within 125 % of the diagram width. */
class Sysml2StmWave2dBNotesTest :
    FunSpec({
        val longText =
            "This note carries a deliberately long explanation of the state it is attached to, so that it has to wrap " +
                "onto several lines instead of becoming one very wide box."

        fun model(noteCount: Int): Sysml2Model =
            sysml2Model(name = "Notes") {
                val init = stateDef(name = "Start", isInitial = true)
                val states = (1..5).map { stateDef(name = "State $it") }
                transition(name = "go", source = init, target = states[0])
                states.zipWithNext().forEachIndexed { i, (a, b) -> transition(name = "t$i", source = a, target = b) }
                for (k in 0 until noteCount) note(text = longText, firstAnchor = states[k % states.size], id = "n$k")
                stmDiagram(name = "d") {
                    include(init)
                    states.forEach { include(it) }
                }
            }

        fun laidOut(m: Sysml2Model): Pair<StmDiagram, LayoutResult> {
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val hints =
                Sysml2LayoutBridge
                    .layoutHintsFor(
                        model = m,
                        diagram = d,
                    ).copy(direction = dev.kuml.layout.LayoutDirection.LeftToRight)
            val r =
                LayoutSpecRunner
                    .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = d.layout, kind = SpecDiagramKind.SYSML2_STM)
                    .result
            return d to Sysml2LayoutBridge.withoutStmOrderEdges(r)
        }

        fun samples(route: EdgeRoute): List<Pair<Float, Float>> {
            val pts =
                buildList {
                    add(route.source)
                    when (route) {
                        is EdgeRoute.OrthogonalRounded -> addAll(route.waypoints)
                        is EdgeRoute.TreeRounded -> addAll(route.waypoints)
                        else -> Unit
                    }
                    add(route.target)
                }
            return pts.zipWithNext().flatMap { (a, b) ->
                (0..20).map { i -> (a.x + (b.x - a.x) * i / 20f) to (a.y + (b.y - a.y) * i / 20f) }
            }
        }

        test("eight notes: width at most 125 percent, notes wrap at 220 px, no anchor line runs through another state") {
            val withNotes = model(noteCount = 8)
            val (d, base) = laidOut(withNotes)
            val placed =
                KumlSvgRenderer
                    .placeSysml2NotesResult(
                        model = withNotes,
                        diagram = d,
                        layout = base,
                        noteMaxWidth = NoteLayout.STM_MAX_W,
                        noteWidthFactor = KumlSvgRenderer.STM_NOTE_WIDTH_FACTOR,
                    ).layout
            println("[2d-b] base width=${base.canvas.width} with notes=${placed.canvas.width}")
            placed.canvas.width shouldBeLessThanOrEqual base.canvas.width * 1.25f + 1f

            val noteNodes = (0 until 8).map { placed.nodes.getValue(NodeId("n$it")).bounds }
            noteNodes.forEach { it.size.width shouldBeLessThanOrEqual NoteLayout.STM_MAX_W }
            // no note leaves the canvas
            noteNodes.forEach { (it.origin.x + it.size.width) shouldBeLessThanOrEqual placed.canvas.width + 0.5f }

            val stateRects = (1..5).associate { "State $it" to placed.nodes.getValue(NodeId("State $it")).bounds }
            for (k in 0 until 8) {
                val anchor = "State ${k % 5 + 1}"
                val route =
                    placed.edges.entries
                        .first { it.key.value == "note-link:n$k::$anchor" }
                        .value
                for ((x, y) in samples(route)) {
                    stateRects.filterKeys { it != anchor }.forEach { (_, r) ->
                        val inside =
                            x > r.origin.x + 1f &&
                                x < r.origin.x + r.size.width - 1f &&
                                y > r.origin.y + 1f &&
                                y < r.origin.y + r.size.height - 1f
                        inside shouldBe false
                    }
                    (x >= -0.5f && y >= -0.5f && x <= placed.canvas.width + 0.5f && y <= placed.canvas.height + 0.5f) shouldBe true
                }
            }
            val svg = KumlSvgRenderer.toSvg(model = withNotes, diagram = d, layoutResult = base, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-stm-d2b/notes-eight.svg", content = svg)
            svg shouldContain "deliberately"
        }

        test("notes of a rendered state machine wrap at the width of their box") {
            val m = model(noteCount = 2)
            val (d, base) = laidOut(m)
            val svg = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = base, theme = PlainTheme())
            // every <tspan> line of a note has at most the characters that fit into 220 - padding
            val maxChars = ((NoteLayout.STM_MAX_W - NoteLayout.H_PADDING) / NoteLayout.BODY_CHAR_PX).toInt()
            val lines =
                Regex("<tspan[^>]*>([^<]*)</tspan>").findAll(svg).map { it.groupValues[1].trim() }.filter {
                    it.isNotEmpty() &&
                        longText.contains(it)
                }
            lines.toList().isNotEmpty() shouldBe true
            lines.forEach { (it.length <= maxChars) shouldBe true }
        }
    })
