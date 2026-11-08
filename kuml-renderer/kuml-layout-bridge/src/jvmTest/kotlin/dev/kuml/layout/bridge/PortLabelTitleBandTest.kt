package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.LabelSide
import dev.kuml.layout.bridge.label.PortLabelSpec
import dev.kuml.layout.bridge.label.Sysml2EdgeLabelPlan
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 43: a port label that falls back to the inside of a part box must not cover the box title. The part boxes
 * here carry no nested frames, which is the case that used to have no title obstacle. Real ELK layout, geometry only
 * (rectangles from the label plan, no measured text, no byte comparison).
 */
class PortLabelTitleBandTest :
    FunSpec({
        // A hub with three ports on its top edge, each wired to its own source part: the connector lines of the neighbours
        // block the spots outside the box, so a port label used to be placed inside, over the title.
        fun hubModel(): Sysml2Model =
            sysml2Model(name = "Hub") {
                val dc = portDef(name = "DcPort")
                val link = connectionDef(name = "Link")
                val hub =
                    partDef(name = "Hub") {
                        port(name = "p1", typeId = dc.id)
                        port(name = "p2", typeId = dc.id)
                        port(name = "p3", typeId = dc.id)
                    }
                val sources =
                    (1..3).map { i -> partDef(name = "Source$i") { port(name = "outputChannel$i", typeId = dc.id) } }
                val system =
                    partDef(name = "System") {
                        part(name = "hub", typeId = hub.id)
                        sources.forEachIndexed { i, s -> part(name = "source${i + 1}", typeId = s.id) }
                        (1..3).forEach { n ->
                            val i = n - 1
                            connect(
                                name = "c${i + 1}",
                                typeId = link.id,
                                sourceEndId = "source${i + 1}.outputChannel${i + 1}",
                                targetEndId = "hub.p$n",
                            )
                        }
                    }
                ibd(name = "Hub IBD", owner = system)
            }

        // Three parts of different shape, one of them with a single long port name each side.
        fun chainModel(): Sysml2Model =
            sysml2Model(name = "Chain") {
                val dc = portDef(name = "DcPort")
                val link = connectionDef(name = "Link")
                val a =
                    partDef(name = "PartA") {
                        port(name = "outputChannelPrimaryLongName", typeId = dc.id)
                        port(name = "outputChannelSecondaryLongName", typeId = dc.id)
                    }
                val b =
                    partDef(name = "PartB") {
                        port(name = "inputChannelPrimaryLongName", typeId = dc.id)
                        port(name = "inputChannelSecondaryLongName", typeId = dc.id)
                    }
                val system =
                    partDef(name = "System") {
                        part(name = "a", typeId = a.id)
                        part(name = "b", typeId = b.id)
                        connect(
                            name = "c1",
                            typeId = link.id,
                            sourceEndId = "a.outputChannelPrimaryLongName",
                            targetEndId = "b.inputChannelPrimaryLongName",
                        )
                        connect(
                            name = "c2",
                            typeId = link.id,
                            sourceEndId = "a.outputChannelSecondaryLongName",
                            targetEndId = "b.inputChannelSecondaryLongName",
                        )
                    }
                ibd(name = "Chain IBD", owner = system)
            }

        fun layoutOf(m: Sysml2Model): LayoutResult {
            val d = m.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_IBD,
                    ).result
            val nested = Sysml2LayoutBridge.addIbdNestedFrames(model = m, diagram = d, layoutResult = raw)
            val framed = Sysml2LayoutBridge.addIbdOwnerFrame(model = m, diagram = d, layoutResult = nested)
            return Sysml2LayoutBridge.enrichIbdPortPositions(model = m, diagram = d, layoutResult = framed)
        }

        fun portLabelRects(
            m: Sysml2Model,
            layout: LayoutResult,
            titles: List<Rect>,
        ): Map<String, Rect> {
            val d = m.diagrams.filterIsInstance<IbdDiagram>().single()
            val specs = Sysml2LayoutBridge.ibdPortLabelSpecs(model = m, diagram = d, layout = layout)
            return Sysml2EdgeLabelPlan
                .compute(layout = layout, labelSizes = emptyMap(), portLabels = specs, titleRects = titles)
                .mapValues { it.value.rect }
        }

        fun overlaps(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        fun titleHits(
            labels: Map<String, Rect>,
            bands: List<Rect>,
        ): List<String> = labels.filter { (_, r) -> bands.any { overlaps(a = r, b = it) } }.keys.sorted()

        for ((name, make) in listOf<Pair<String, () -> Sysml2Model>>("hub" to ::hubModel, "chain" to ::chainModel)) {
            test("$name: no port label covers the title band of a part box") {
                val m = make()
                val layout = layoutOf(m)
                val bands = Sysml2LayoutBridge.ibdTitleObstacles(model = m, layout = layout)
                // one band per part box without nested frames
                (bands.size >= 2) shouldBe true
                titleHits(labels = portLabelRects(m = m, layout = layout, titles = bands), bands = bands) shouldBe emptyList()
            }

            test("$name: the plan with title bands is deterministic") {
                val m = make()
                val layout = layoutOf(m)
                val bands = Sysml2LayoutBridge.ibdTitleObstacles(model = m, layout = layout)
                portLabelRects(m = m, layout = layout, titles = bands) shouldBe portLabelRects(m = m, layout = layout, titles = bands)
            }
        }

        test("hub: without the bands (the old title list) a port label does cover a title, so the guard has teeth") {
            val m = hubModel()
            val layout = layoutOf(m)
            val bands = Sysml2LayoutBridge.ibdTitleObstacles(model = m, layout = layout)
            val old = Sysml2LayoutBridge.ibdTitleRects(model = m, layout = layout)
            old shouldBe emptyList()
            titleHits(labels = portLabelRects(m = m, layout = layout, titles = old), bands = bands).isNotEmpty() shouldBe true
        }

        test("a large diagram: the title bands stay one per box and the label plan still finishes within its work budget") {
            val n = 3_000
            val nodes =
                (0 until n).associate { i ->
                    NodeId("p$i") to
                        NodeLayout(
                            bounds =
                                Rect(
                                    origin = Point(x = (i % 60) * 120f, y = (i / 60) * 90f),
                                    size = Size(width = 100f, height = 60f),
                                ),
                        )
                }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("synthetic"),
                    seed = 1L,
                    canvas = Size(width = 7_200f, height = 4_500f),
                    nodes = nodes,
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bands = partTitleBands(layout = layout, nodeIds = nodes.keys, hasStereotype = { true })
            bands.size shouldBe n
            val specs =
                (0 until 300).map { i ->
                    val b = nodes.getValue(NodeId("p$i")).bounds
                    PortLabelSpec(
                        id = "port:p$i::k",
                        ownerId = "p$i::k",
                        size = Size(width = 60f, height = 14f),
                        anchorRect = Rect(origin = Point(x = b.origin.x + 10f, y = b.origin.y - 4f), size = Size(width = 8f, height = 8f)),
                        excludedSide = LabelSide.NORTH,
                        outward = LabelSide.NORTH,
                        parentId = "p$i",
                    )
                }
            val started = System.nanoTime()
            val placed = Sysml2EdgeLabelPlan.compute(layout = layout, labelSizes = emptyMap(), portLabels = specs, titleRects = bands)
            val seconds = (System.nanoTime() - started) / 1e9
            placed.size shouldBe specs.size
            (seconds < 30.0) shouldBe true
        }
    })
