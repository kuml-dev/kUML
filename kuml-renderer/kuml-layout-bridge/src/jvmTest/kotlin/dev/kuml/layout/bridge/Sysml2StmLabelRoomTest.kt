package dev.kuml.layout.bridge

import dev.kuml.layout.LAYOUT_SPACING_SCALE
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.label.StmLabelLayerGap
import dev.kuml.layout.bridge.label.StmLabelSpec
import dev.kuml.layout.bridge.label.StmLabelWrap
import dev.kuml.layout.bridge.label.Sysml2DiagramLabelPlan
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import dev.kuml.layout.bridge.note.intersects
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.StmEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Wave F (Nacharbeit 40): transition labels of a state machine get room in the layout. Named regression test
 * "T21 Rot Gruen": a long `T21 [guard] / effect` label on a left-to-right flow used to be wider than the gap between
 * the states and ended on a state. All geometry is font independent (character estimate, no measured text).
 */
class Sysml2StmLabelRoomTest :
    FunSpec({
        fun t21Model(flow: Sysml2LayoutFlow): Sysml2Model =
            sysml2Model(name = "T21") {
                val init = stateDef(name = "Init", isInitial = true)
                val rot = stateDef(name = "Rot", entryAction = "lightsOn('red')")
                val gruen = stateDef(name = "Gruen", entryAction = "lightsOn('green')")
                val gelb = stateDef(name = "Gelb")
                transition(name = "i0", source = init, target = rot)
                transition(
                    name = "t21",
                    source = rot,
                    target = gruen,
                    trigger = "T21",
                    guard = "supplyVoltage > 11.5 && !emergency",
                    effect = "switchLights('green'); notifyController()",
                )
                transition(
                    name = "t22",
                    source = gruen,
                    target = gelb,
                    trigger = "T22",
                    guard = "timerExpired",
                    effect = "switchLights('yellow')",
                )
                transition(name = "t23", source = gelb, target = rot, trigger = "T23", effect = "switchLights('red'); logCycle()")
                stmDiagram(name = "d") {
                    listOf(init, rot, gruen, gelb).forEach { include(it) }
                    layout { direction = flow }
                }
            }

        fun shortModel(): Sysml2Model =
            sysml2Model(name = "Short") {
                val init = stateDef(name = "Init", isInitial = true)
                val a = stateDef(name = "A")
                val b = stateDef(name = "B")
                transition(name = "i0", source = init, target = a)
                transition(name = "ab", source = a, target = b, trigger = "go")
                stmDiagram(name = "d") {
                    listOf(init, a, b).forEach { include(it) }
                    layout { direction = Sysml2LayoutFlow.Right }
                }
            }

        fun diagramOf(m: Sysml2Model): StmDiagram = m.diagrams.filterIsInstance<StmDiagram>().single()

        fun layoutOf(m: Sysml2Model): LayoutResult {
            val d = diagramOf(m)
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d)
            val r =
                LayoutSpecRunner
                    .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = d.layout, kind = SpecDiagramKind.SYSML2_STM)
                    .result
            return Sysml2LayoutBridge.withoutStmOrderEdges(r)
        }

        fun specsOf(
            m: Sysml2Model,
            layout: LayoutResult,
        ): List<StmLabelSpec> {
            val adapter = StmEdgeAdapter(model = m, diagram = diagramOf(m))
            return layout.edges.keys.sortedBy { it.value }.mapNotNull { id ->
                val parts = adapter.metadataFor(id.value)?.labelParts.orEmpty()
                if (parts.isEmpty()) return@mapNotNull null
                val lines = StmLabelWrap.wrap(parts = parts)
                StmLabelSpec(id = id.value, lines = lines.map { it.text }, size = StmLabelWrap.boxSize(lines = lines))
            }
        }

        fun plan(
            m: Sysml2Model,
            layout: LayoutResult,
        ) = Sysml2DiagramLabelPlan.planStmLabels(
            edges = layout.edges.mapKeys { it.key.value },
            nodes = layout.nodes.mapKeys { it.key.value }.mapValues { it.value.bounds },
            composites = emptyList(),
            labels = specsOf(m = m, layout = layout),
            frameTop = 0f,
        )

        test("a long label on a left-to-right flow raises the layer gap; short labels and a downward flow leave the engine default") {
            val long = t21Model(Sysml2LayoutFlow.Right)
            val extent = StmLabelLayerGap.maxLabelExtent(model = long, diagram = diagramOf(long))
            (extent > 100f) shouldBe true
            val base = StmLabelLayerGap.requiredLayerGapBase(model = long, diagram = diagramOf(long))!!
            (base > StmLabelLayerGap.ENGINE_DEFAULT_BASE_PX) shouldBe true
            base shouldBe ((extent + 2f * StmLabelLayerGap.CLEARANCE_PX) / LAYOUT_SPACING_SCALE)
            Sysml2LayoutBridge.layoutHintsFor(model = long, diagram = diagramOf(long)).spacing.layerToLayer shouldBe base

            val down = t21Model(Sysml2LayoutFlow.Down)
            Sysml2LayoutBridge
                .layoutHintsFor(model = down, diagram = diagramOf(down))
                .spacing.layerToLayer
                .isNaN() shouldBe true

            val short = shortModel()
            Sysml2LayoutBridge
                .layoutHintsFor(model = short, diagram = diagramOf(short))
                .spacing.layerToLayer
                .isNaN() shouldBe true
        }

        test("T21 Rot Gruen: the gap between neighbouring columns holds the widest label plus 16 px (Right)") {
            val m = t21Model(Sysml2LayoutFlow.Right)
            val layout = layoutOf(m)
            val extent = StmLabelLayerGap.maxLabelExtent(model = m, diagram = diagramOf(m))
            val columns =
                // the initial pseudo-state sits close to its target: its edge carries no label
                layout.nodes
                    .filterKeys { it.value != "Init" }
                    .values
                    .map { it.bounds }
                    .sortedBy { it.origin.x }
                    .fold(mutableListOf<Rect>()) { acc, r ->
                        if (acc.isEmpty() || r.origin.x > acc.last().origin.x + acc.last().size.width) acc.add(r)
                        acc
                    }
            (columns.size >= 2) shouldBe true
            columns.zipWithNext().forEach { (a, b) ->
                (b.origin.x - (a.origin.x + a.size.width)) shouldBeGreaterThanOrEqual (extent + 16f - 0.5f)
            }
        }

        for (flow in listOf(Sysml2LayoutFlow.Right, Sysml2LayoutFlow.Down)) {
            test("T21 Rot Gruen ($flow): no label covers a state or another label, and the plan is deterministic") {
                val m = t21Model(flow)
                val layout = layoutOf(m)
                val placed = plan(m = m, layout = layout)
                val rects =
                    placed.labels.entries
                        .sortedBy { it.key }
                        .map { it.key to it.value.rect }
                rects.size shouldBe 3
                for ((id, r) in rects) {
                    for ((nid, n) in layout.nodes) {
                        if (r.intersects(n.bounds)) throw AssertionError("label $id covers state ${nid.value}")
                    }
                }
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        if (rects[i].second.intersects(rects[j].second)) {
                            throw AssertionError("labels ${rects[i].first} and ${rects[j].first} overlap")
                        }
                    }
                }
                val again = plan(m = m, layout = layoutOf(m)).labels.mapValues { it.value.rect }
                again shouldBe placed.labels.mapValues { it.value.rect }
            }
        }

        test("one character width for the edge labels: the wrap, the box and the layout read the same constant") {
            StmLabelWrap.CHAR_W shouldBe Sysml2LabelSizing.CHAR_PX_EDGE_LABEL
            val lines =
                StmLabelWrap.wrap(
                    parts =
                        listOf(
                            dev.kuml.sysml2.edge
                                .EdgeLabelPart(text = "abcdef", kind = dev.kuml.sysml2.edge.EdgeLabelKind.Trigger),
                        ),
                )
            val box = StmLabelWrap.boxSize(lines = lines)
            box.width shouldBe 6 * Sysml2LabelSizing.CHAR_PX_EDGE_LABEL + 2 * StmLabelWrap.PAD
            box.width shouldBeLessThanOrEqual StmLabelWrap.DEFAULT_MAX_WIDTH_PX + 2 * StmLabelWrap.PAD
            // PAR, ACT and note labels keep their own estimate
            Sysml2LabelSizing.CHAR_PX shouldBe 6.6f
        }
    })
