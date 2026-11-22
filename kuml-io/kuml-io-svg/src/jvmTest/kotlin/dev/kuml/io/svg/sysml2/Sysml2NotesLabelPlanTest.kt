package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.note.NoteMeasure
import dev.kuml.layout.bridge.note.NotePlacementResult
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.polylinePoints
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2PackageDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.PackageEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.visibleStateIds
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 133: hinted notes in requirement, package and state machine diagrams avoid the label rectangle the renderer
 * draws, not an estimate at the polyline midpoint. Geometry only, independent of any font.
 */
class Sysml2NotesLabelPlanTest :
    FunSpec({
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

        fun overlaps(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        fun reqModel(withNotes: Boolean): Sysml2Model =
            sysml2Model(name = "Req") {
                val a = requirementDef(name = "Top", reqId = "R-1", text = "The system shall work")
                val b = requirementDef(name = "Child", reqId = "R-2", text = "The child shall work too")
                val c = requirementDef(name = "Other", reqId = "R-3", text = "The other shall work")
                if (withNotes) {
                    comment(text = "Info: a note beside the derive edge.", firstAnchor = a, b, id = "n1", kind = NoteKind.INFO)
                    comment(text = "Warning: check the third one.", firstAnchor = c, id = "n2", kind = NoteKind.WARNING)
                }
                reqDiagram(name = "REQ") {
                    include(a)
                    include(b)
                    include(c)
                    derive(source = b, target = a)
                    derive(source = c, target = a)
                }
            }

        fun pkgModel(withNotes: Boolean): Sysml2Model =
            sysml2Model(name = "Pkg") {
                val common = pkg(name = "Common") { partDef(name = "Mass") }
                val vehicle =
                    pkg(name = "Vehicle") {
                        imports(target = "Common::*")
                        partDef(name = "Car")
                    }
                if (withNotes) {
                    comment(text = "Info: vehicles import the common types.", "Car", id = "n1", kind = NoteKind.INFO)
                }
                packageDiagram(name = "PKG") {
                    include(common)
                    include(vehicle)
                }
            }

        fun stmModel(withNotes: Boolean): Sysml2Model =
            sysml2Model(name = "Stm") {
                val red = stateDef(name = "Red")
                val green = stateDef(name = "Green")
                transition(
                    name = "go",
                    source = red,
                    target = green,
                    trigger = "timerSixtySecondsHasElapsedAndNobodyIsCrossing",
                    guard = "the junction is clear and no emergency vehicle approaches",
                )
                transition(name = "back", source = green, target = red, trigger = "timer5s")
                if (withNotes) {
                    comment(text = "Info: red first.", firstAnchor = red, id = "n1", kind = NoteKind.INFO)
                    comment(text = "Warning: green is shortened at rush hour.", firstAnchor = green, id = "n2", kind = NoteKind.WARNING)
                }
                stmDiagram(name = "STM") {
                    include(red)
                    include(green)
                }
            }

        fun baseLayout(
            model: Sysml2Model,
            graph: dev.kuml.layout.LayoutGraph,
        ): LayoutResult = ElkLayoutEngine().layout(graph = graph, hints = LayoutHints.DEFAULT)

        fun assertNotesAvoid(
            placed: NotePlacementResult,
            plan: Map<String, dev.kuml.layout.bridge.label.PlacedLabel>,
            noteIds: List<String>,
        ) {
            withClue("plan empty") { plan.isNotEmpty() shouldBe true }
            for (nid in noteIds) {
                val note =
                    placed.layout.nodes
                        .getValue(NodeId(nid))
                        .bounds
                for ((eid, label) in plan) {
                    val shifted =
                        Rect(
                            origin =
                                dev.kuml.layout.Point(
                                    x = label.rect.origin.x + placed.dx,
                                    y = label.rect.origin.y + placed.dy,
                                ),
                            size = label.rect.size,
                        )
                    withClue("note $nid $note overlaps label $eid $shifted") { overlaps(note, shifted) shouldBe false }
                    check(eid.isNotBlank())
                }
            }
        }

        test("req: the pre-plan holds the on-line label and the notes avoid it") {
            val model = reqModel(withNotes = true)
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val base = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            val plan =
                KumlSvgRenderer.onLineLabelPlan(
                    model = model,
                    layout = base,
                    adapter = ReqEdgeAdapter(d),
                    labelFrameStrips = true,
                    reqLabelPolicy = true,
                )
            val placed =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = base,
                    adapter = ReqEdgeAdapter(d),
                    placedLabels = plan,
                    measure = fixedMeasure,
                )
            assertNotesAvoid(placed, plan, listOf("n1", "n2"))
            // Source-end policy: the label is not at the polyline midpoint the estimate would use.
            val differs =
                plan.entries.any { (id, l) ->
                    val pts =
                        base.edges.entries
                            .first { it.key.value == id }
                            .value
                            .polylinePoints()
                    val mx = (pts.first().x + pts.last().x) / 2f
                    val my = (pts.first().y + pts.last().y) / 2f
                    abs(l.rect.origin.x + l.rect.size.width / 2f - mx) + abs(l.rect.origin.y + l.rect.size.height / 2f - my) > 1f
                }
            differs shouldBe true
        }

        test("pkg: the pre-plan holds the import label and the note avoids it") {
            val model = pkgModel(withNotes = true)
            val d = model.diagrams.filterIsInstance<Sysml2PackageDiagram>().single()
            val adapter = PackageEdgeAdapter(model)
            val base = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            val plan =
                KumlSvgRenderer.onLineLabelPlan(
                    model = model,
                    layout = base,
                    adapter = adapter,
                    labelFrameStrips = true,
                    reqLabelPolicy = false,
                )
            val placed =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = base,
                    adapter = adapter,
                    placedLabels = plan,
                    measure = fixedMeasure,
                )
            assertNotesAvoid(placed, plan, listOf("n1"))
        }

        test("stm: the pre-plan holds the wrapped label block, in layout coordinates, and the notes avoid it") {
            val model = stmModel(withNotes = true)
            val d = model.diagrams.filterIsInstance<StmDiagram>().single()
            val padding = SvgRenderOptions.DEFAULT.paddingPx
            val visible = d.visibleStateIds(model)
            val base =
                Sysml2LayoutBridge.withoutStmOrderEdges(
                    baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)),
                )
            val adapter = StmEdgeAdapter(model = model, diagram = d)
            val plan =
                KumlSvgRenderer.stmLabelPlan(
                    model = model,
                    layout = base,
                    adapter = adapter,
                    padding = padding,
                    obstacles = { lr, p ->
                        dev.kuml.io.svg.sysml2
                            .stmObstacleNodes(model = model, visibleIds = visible, layoutResult = lr, paddingPx = p)
                    },
                    routeTransform = KumlSvgRenderer.stmRouteTransform(model = model, visible = visible),
                    maxWidthPx = dev.kuml.io.svg.sysml2.edge.StmLabelPlacer.DEFAULT_MAX_WIDTH_PX,
                )
            val placed =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = base,
                    adapter = adapter,
                    placedLabels = plan,
                    noteMaxWidth = NoteLayout.STM_MAX_W,
                    noteWidthFactor = KumlSvgRenderer.STM_NOTE_WIDTH_FACTOR,
                    measure = fixedMeasure,
                )
            assertNotesAvoid(placed, plan, listOf("n1", "n2"))
            // A label may overhang the left edge (the renderer shifts blocks and notes by the same dx afterwards).
            withClue("plan $plan") { plan.values.all { it.rect.size.width > 0f && it.rect.size.height > 0f } shouldBe true }
        }

        test("no label: an edge-free model yields empty plans and no note is affected") {
            val model =
                sysml2Model(name = "Plain") {
                    val a = requirementDef(name = "A", reqId = "R-1", text = "text")
                    comment(text = "Info: lonely.", firstAnchor = a, id = "n1")
                    reqDiagram(name = "REQ") { include(a) }
                }
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val base = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            KumlSvgRenderer
                .onLineLabelPlan(
                    model = model,
                    layout = base,
                    adapter = ReqEdgeAdapter(d),
                    labelFrameStrips = true,
                    reqLabelPolicy = true,
                ).isEmpty() shouldBe true
        }

        test("no notes: the plans are not computed and the output is unchanged by them") {
            val model = reqModel(withNotes = false)
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val base = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            KumlSvgRenderer
                .onLineLabelPlan(
                    model = model,
                    layout = base,
                    adapter = ReqEdgeAdapter(d),
                    labelFrameStrips = true,
                    reqLabelPolicy = true,
                ).isEmpty() shouldBe true
            val stm = stmModel(withNotes = false)
            val sd = stm.diagrams.filterIsInstance<StmDiagram>().single()
            val sbase =
                Sysml2LayoutBridge.withoutStmOrderEdges(
                    baseLayout(stm, Sysml2LayoutBridge.toLayoutGraph(model = stm, diagram = sd)),
                )
            KumlSvgRenderer
                .stmLabelPlan(
                    model = stm,
                    layout = sbase,
                    adapter = StmEdgeAdapter(model = stm, diagram = sd),
                    padding = 24f,
                    obstacles = { _, _ -> emptyList() },
                    routeTransform = null,
                    maxWidthPx = 160f,
                ).isEmpty() shouldBe true
            // Rendering stays deterministic.
            val lr = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = lr) shouldBe
                KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = lr)
        }

        test("planLabelsOnLine returns the rectangles emitLabelsOnLine draws") {
            val model = reqModel(withNotes = false)
            val d = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val base = baseLayout(model, Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d))
            val adapter = ReqEdgeAdapter(d)
            val routes = KumlSvgRenderer.sysml2ShiftedRoutes(layoutResult = base, padding = 0f, routeTransform = null)
            val pending =
                base.edges.keys.mapNotNull { e ->
                    adapter.metadataFor(e.value)?.let { Sysml2EdgeRenderer.pendingLabelOf(id = e.value, metadata = it) }
                }
            val nodeRects = base.nodes.values.map { it.bounds }
            val planned =
                Sysml2EdgeRenderer.planLabelsOnLine(pending = pending, allRoutes = routes, obstacles = nodeRects, preferSourceEnd = true)
            planned.isNotEmpty() shouldBe true
            val builder = SvgBuilder(pretty = false)
            Sysml2EdgeRenderer.emitLabelsOnLine(
                pending = pending,
                allRoutes = routes,
                obstacles = nodeRects,
                backdropFor = { "#fff" },
                builder = builder,
                preferSourceEnd = true,
            )
            val svg = builder.toString()
            for ((id, r) in planned) {
                svg shouldContain "data-label-id=\"$id\""
                svg shouldContain
                    "data-label-rect=\"${fmt2(r.origin.x)} ${fmt2(r.origin.y)} ${fmt2(r.size.width)} ${fmt2(r.size.height)}\""
            }
        }
    })
