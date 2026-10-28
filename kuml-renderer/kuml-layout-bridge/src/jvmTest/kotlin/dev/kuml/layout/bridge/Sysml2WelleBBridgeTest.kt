package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2ArrowHead
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class Sysml2WelleBBridgeTest :
    FunSpec({
        val model =
            sysml2Model(name = "M") {
                portDef(name = "P") { flowProperty(name = "x", typeId = "Item", direction = PortDirection.Out) }
                attributeDef(name = "Item")
                connectionDef(name = "L")
                partDef(name = "A") { port(name = "o", typeId = "P", direction = PortDirection.Out) }
                partDef(name = "B") { port(name = "i", typeId = "P", direction = PortDirection.In) }
                partDef(name = "S") {
                    port(name = "ext", typeId = "P", direction = PortDirection.In)
                    part(name = "a", typeId = "A")
                    part(name = "b", typeId = "B")
                    refPart(name = "c", typeId = "B")
                    connect(name = "w", typeId = "L", sourceEndId = "a.o", targetEndId = "b.i")
                    connect(name = "d", typeId = "L", sourceEndId = "S::ext", targetEndId = "b.i")
                    flow(item = "Item", from = "a.o", to = "b.i", via = "w")
                    flow(item = "Item", from = "a.o", to = "b.i")
                }
            }
        val ibd = IbdDiagram(name = "i", ownerId = "S")

        test("ibd graph: owner port node, own-line flow, carried flow has no layout edge") {
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            g.nodes.map { it.id.value } shouldContain "S::ext"
            g.nodes.first { it.id.value == "S::ext" }.intrinsicSize shouldBe Size(width = 12f, height = 12f)
            val ids = g.edges.map { it.id.value }
            ids shouldContain "conn:S::w"
            ids shouldContain "conn:S::d"
            ids.count { it.startsWith("flow:") } shouldBe 1
        }

        test("frame is added with owner ports on the border; legacy ibd is untouched") {
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes =
                g.nodes
                    .mapIndexed { i, n ->
                        n.id to
                            NodeLayout(bounds = Rect(origin = Point(x = 40f + i * 200f, y = 40f), size = n.intrinsicSize))
                    }.toMap()
            val edges =
                g.edges
                    .associate {
                        it.id to
                            EdgeRoute.Direct(source = Point(x = 50f, y = 50f), target = Point(x = 250f, y = 50f)) as EdgeRoute
                    }
            val lr =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 900f, height = 200f),
                    nodes = nodes,
                    edges = edges,
                    groups = emptyMap(),
                )
            val framed = Sysml2LayoutBridge.addIbdOwnerFrame(model = model, diagram = ibd, layoutResult = lr)
            framed.groups.keys.map { it.value } shouldContain "frame:S"
            val frame =
                framed.groups.values
                    .single()
                    .bounds
            val port = framed.nodes[NodeId("S::ext")].shouldNotBeNull().bounds
            val cx = port.origin.x + 6f
            val cy = port.origin.y + 6f
            val tol = 0.5f
            val onBorder =
                kotlin.math.abs(cx - frame.origin.x) < tol ||
                    kotlin.math.abs(cy - frame.origin.y) < tol ||
                    kotlin.math.abs(cx - (frame.origin.x + frame.size.width)) < tol ||
                    kotlin.math.abs(cy - (frame.origin.y + frame.size.height)) < tol
            onBorder shouldBe true

            val legacy =
                sysml2Model(name = "L") {
                    portDef(name = "P")
                    partDef(name = "A") { port(name = "o", typeId = "P") }
                    partDef(name = "B") { port(name = "i", typeId = "P") }
                    partDef(name = "S") {
                        part(name = "a", typeId = "A")
                        part(name = "b", typeId = "B")
                        connect(name = "w", typeId = "P", sourceEndId = "S::a::o", targetEndId = "S::b::i")
                    }
                }
            val lg = Sysml2LayoutBridge.toLayoutGraph(model = legacy, diagram = IbdDiagram(name = "i", ownerId = "S"))
            lg.nodes shouldHaveSize 2
            lg.edges.map { it.id.value } shouldBe listOf("conn:S::w")
            val lr2 =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 10f, height = 10f),
                    nodes =
                        lg.nodes.associate {
                            it.id to
                                NodeLayout(
                                    bounds = Rect(origin = Point(x = 0f, y = 0f), size = it.intrinsicSize),
                                )
                        },
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            Sysml2LayoutBridge.addIbdOwnerFrame(
                model = legacy,
                diagram = IbdDiagram(name = "i", ownerId = "S"),
                layoutResult = lr2,
            ) shouldBe
                lr2
        }

        test("bdd graph gets composition / aggregation edges and smaller boxes for hidden rows") {
            val d = BdDiagram(name = "b", elementIds = listOf("S", "A", "B"))
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)
            g.edges.map { it.id.value } shouldContain "comp:S::a"
            g.edges.map { it.id.value } shouldContain "agg:S::c"
            val full = Sysml2LayoutBridge.bddContentAwareSizeProvider(model).sizeOf(elementId = "S", elementKind = "PartDefinition")
            val trimmed =
                Sysml2LayoutBridge
                    .bddContentAwareSizeProvider(
                        model = model,
                        diagram = d,
                    ).sizeOf(elementId = "S", elementKind = "PartDefinition")
            (trimmed.height < full.height) shouldBe true
            val only = BdDiagram(name = "b", elementIds = listOf("S"))
            Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = only).edges shouldHaveSize 0
        }

        test("edge adapters: diamonds at the source, mid-arrow on carrying connector") {
            val bdd = BddEdgeAdapter(model = model, diagram = BdDiagram(name = "b", elementIds = listOf("S", "A", "B")))
            bdd.metadataFor("comp:S::a")!!.arrowHead shouldBe Sysml2ArrowHead.FilledDiamond
            bdd.metadataFor("comp:S::a")!!.headAtSource shouldBe true
            bdd.metadataFor("agg:S::c")!!.arrowHead shouldBe Sysml2ArrowHead.OpenDiamond
            val ib = IbdEdgeAdapter(model = model, diagram = ibd)
            ib.metadataFor("conn:S::w")!!.midArrows shouldHaveSize 1
            ib.metadataFor("conn:S::w")!!.midArrows[0].forward shouldBe true
            ib.metadataFor("conn:S::d")!!.midArrows shouldHaveSize 0
        }
    })

/**
 * Welle B correction: IBD frame geometry. All assertions are font independent — they use the bridge's own
 * label-size heuristic ([Sysml2LayoutBridge.estimateLabelSize]).
 */
class Sysml2IbdFrameLayoutTest :
    FunSpec({
        data class Box(
            val l: Float,
            val t: Float,
            val r: Float,
            val b: Float,
        ) {
            fun hits(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b

            fun inside(o: Box) = l >= o.l - 0.5f && t >= o.t - 0.5f && r <= o.r + 0.5f && b <= o.b + 0.5f
        }

        fun Rect.box() = Box(origin.x, origin.y, origin.x + size.width, origin.y + size.height)

        fun layoutOf(
            model: dev.kuml.sysml2.Sysml2Model,
            ibd: IbdDiagram,
            boxes: Map<String, Rect>,
            ends: Map<String, Pair<Point, Point>>,
        ): LayoutResult {
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd)
            val nodes =
                g.nodes.associate { n ->
                    n.id to NodeLayout(bounds = boxes[n.id.value] ?: Rect(origin = Point(x = 0f, y = 0f), size = n.intrinsicSize))
                }
            val edges =
                g.edges.associate { e ->
                    val (s, t) = ends[e.id.value] ?: (Point(x = 0f, y = 0f) to Point(x = 1f, y = 1f))
                    e.id to EdgeRoute.Direct(source = s, target = t) as EdgeRoute
                }
            return LayoutResult(
                engineId = LayoutEngineId("t"),
                seed = 1L,
                canvas = Size(width = 900f, height = 700f),
                nodes = nodes,
                edges = edges,
                groups = emptyMap(),
            )
        }

        fun r(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        val portsModel =
            sysml2Model(name = "Ports") {
                attributeDef(name = "Fuel")
                connectionDef(name = "Line")
                portDef(name = "FuelPort") { flowProperty(name = "fuel", typeId = "Fuel", direction = PortDirection.In) }
                partDef(name = "Tank") { port(name = "outlet", typeId = "FuelPort", direction = PortDirection.Out) }
                partDef(name = "Engine") { port(name = "injector", typeId = "FuelPort", direction = PortDirection.In, conjugated = true) }
                partDef(name = "Vehicle") {
                    port(name = "fuelIn", typeId = "FuelPort", direction = PortDirection.In)
                    port(name = "telemetry", typeId = "FuelPort")
                    part(name = "tank", typeId = "Tank")
                    part(name = "engine", typeId = "Engine")
                    connect(name = "c1", typeId = "Line", sourceEndId = "Vehicle::fuelIn", targetEndId = "tank.outlet")
                    connect(name = "c2", typeId = "Line", sourceEndId = "engine.injector", targetEndId = "Vehicle::telemetry")
                }
            }
        val portsIbd = IbdDiagram(name = "p", ownerId = "Vehicle")

        fun portsLayout(): LayoutResult =
            layoutOf(
                model = portsModel,
                ibd = portsIbd,
                boxes = mapOf("Vehicle::tank" to r(200f, 100f, 300f, 110f), "Vehicle::engine" to r(200f, 300f, 300f, 110f)),
                ends =
                    mapOf(
                        "conn:Vehicle::c1" to (Point(x = 0f, y = 0f) to Point(x = 350f, y = 100f)),
                        "conn:Vehicle::c2" to (Point(x = 350f, y = 410f) to Point(x = 0f, y = 0f)),
                    ),
            )

        fun frameOf(lr: LayoutResult) = lr.groups.getValue(Sysml2LayoutBridge.ibdFrameGroupId("Vehicle")).bounds

        fun squares(
            lr: LayoutResult,
            model: dev.kuml.sysml2.Sysml2Model,
            ibd: IbdDiagram,
        ): List<Box> {
            val owner = Sysml2LayoutBridge.ibdAvoidRects(model = model, diagram = ibd, layout = lr)
            return owner.map { it.box() }
        }

        test("every box, port square and port label lies inside the frame, clear of the tab zone") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val f = frameOf(lr).box()
            val tabW = Sysml2LayoutBridge.ibdTabWidth("Vehicle")
            val tab = Box(f.l, f.t, f.l + tabW, f.t + Sysml2LayoutBridge.IBD_FRAME_TAB_H_PX)
            val contentTabClear = Box(tab.l, tab.t, tab.r, tab.b + Sysml2LayoutBridge.IBD_TAB_CONTENT_GAP_PX - 0.5f)
            for ((id, nl) in lr.nodes) {
                if (id.value == "Vehicle::fuelIn" || id.value == "Vehicle::telemetry") continue
                nl.bounds.box().inside(f) shouldBe true
                nl.bounds.box().hits(contentTabClear) shouldBe false
            }
            val labels = Sysml2LayoutBridge.ibdLabelRects(model = portsModel, diagram = portsIbd, layout = lr)
            labels.filter { it.kind == Sysml2LayoutBridge.IbdLabelKind.PartPort }.forEach {
                it.rect.box().inside(f) shouldBe true
                it.rect.box().hits(contentTabClear) shouldBe false
            }
        }

        test("no label overlaps a port square (with clearance), a part box or another label") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val labels = Sysml2LayoutBridge.ibdLabelRects(model = portsModel, diagram = portsIbd, layout = lr)
            labels shouldHaveSize 4
            val c = Sysml2LayoutBridge.IBD_PORT_CLEARANCE_PX
            val half = Sysml2LayoutBridge.IBD_PORT_SIZE_PX / 2f
            val sq = mutableListOf<Box>()
            val parts = mutableListOf<Box>()
            for ((id, nl) in lr.nodes) {
                if (id.value.startsWith("Vehicle::f") || id.value == "Vehicle::telemetry") {
                    sq += nl.bounds.box().let { Box(it.l - c, it.t - c, it.r + c, it.b + c) }
                } else {
                    parts += nl.bounds.box()
                    for (p in nl.ports.values) {
                        sq +=
                            Box(
                                nl.bounds.origin.x + p.x - half - c,
                                nl.bounds.origin.y + p.y - half - c,
                                nl.bounds.origin.x + p.x + half + c,
                                nl.bounds.origin.y + p.y + half + c,
                            )
                    }
                }
            }
            for (l in labels) {
                val b = l.rect.box()
                sq.none { it.hits(b) } shouldBe true
                parts.none { it.hits(b) } shouldBe true
                labels.filter { it !== l }.none { it.rect.box().hits(b) } shouldBe true
            }
        }

        test("owner ports sit exactly on the frame border, outside the tab zone") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val f = frameOf(lr).box()
            val tabW = Sysml2LayoutBridge.ibdTabWidth("Vehicle")
            for (id in listOf("Vehicle::fuelIn", "Vehicle::telemetry")) {
                val b = lr.nodes.getValue(NodeId(id)).bounds
                val cx = b.origin.x + b.size.width / 2f
                val cy = b.origin.y + b.size.height / 2f
                val onTopOrBottom = kotlin.math.abs(cy - f.t) < 0.5f || kotlin.math.abs(cy - f.b) < 0.5f
                val onLeftOrRight = kotlin.math.abs(cx - f.l) < 0.5f || kotlin.math.abs(cx - f.r) < 0.5f
                (onTopOrBottom || onLeftOrRight) shouldBe true
                if (kotlin.math.abs(cy - f.t) < 0.5f) (cx >= f.l + tabW + Sysml2LayoutBridge.IBD_TAB_CONTENT_GAP_PX) shouldBe true
            }
        }

        test("canvas is measured from the content (no empty space), margin 16 px") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val m = Sysml2LayoutBridge.IBD_CANVAS_MARGIN_PX
            val boxes = mutableListOf(frameOf(lr).box())
            lr.nodes.values.forEach { boxes += it.bounds.box() }
            Sysml2LayoutBridge.ibdLabelRects(model = portsModel, diagram = portsIbd, layout = lr).forEach { boxes += it.rect.box() }
            val w = boxes.maxOf { it.r } - boxes.minOf { it.l }
            val h = boxes.maxOf { it.b } - boxes.minOf { it.t }
            (lr.canvas.width <= w + 2 * m + 1f) shouldBe true
            (lr.canvas.height <= h + 2 * m + 1f) shouldBe true
            (boxes.minOf { it.l } >= m - 0.5f) shouldBe true
            (boxes.minOf { it.t } >= m - 0.5f) shouldBe true
        }

        test("collinear part port and owner port get a 40 px offset and a kinked route") {
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val tank = lr.nodes.getValue(NodeId("Vehicle::tank"))
            val outlet = tank.ports.getValue(dev.kuml.layout.PortId("outlet"))
            val outletX = tank.bounds.origin.x + outlet.x
            val ob = lr.nodes.getValue(NodeId("Vehicle::fuelIn")).bounds
            val ownerX = ob.origin.x + ob.size.width / 2f
            kotlin.math
                .abs(
                    ownerX - outletX,
                ).let { (kotlin.math.abs(it - Sysml2LayoutBridge.IBD_COLLINEAR_OFFSET_PX) < 0.5f) shouldBe true }
            val route = lr.edges.getValue(dev.kuml.layout.EdgeId("conn:Vehicle::c1")) as EdgeRoute.OrthogonalRounded
            (route.waypoints.size >= 2) shouldBe true
        }

        test("layout is deterministic") {
            val a = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            val b = Sysml2LayoutBridge.addIbdOwnerFrame(model = portsModel, diagram = portsIbd, layoutResult = portsLayout())
            a shouldBe b
        }

        test("ports on several sides and several owner ports on one side keep their minimum distance") {
            val m =
                sysml2Model(name = "Multi") {
                    portDef(name = "P")
                    connectionDef(name = "L")
                    partDef(name = "A") {
                        port(name = "alphaPort", typeId = "P")
                        port(name = "betaPort", typeId = "P")
                        port(name = "leftPort", typeId = "P")
                        port(name = "bottomPort", typeId = "P")
                    }
                    partDef(name = "S") {
                        port(name = "o1", typeId = "P")
                        port(name = "o2", typeId = "P")
                        port(name = "o3", typeId = "P")
                        port(name = "o4", typeId = "P")
                        part(name = "a", typeId = "A")
                        connect(name = "w1", typeId = "L", sourceEndId = "S::o1", targetEndId = "a.alphaPort")
                        connect(name = "w2", typeId = "L", sourceEndId = "S::o2", targetEndId = "a.betaPort")
                        connect(name = "w3", typeId = "L", sourceEndId = "S::o3", targetEndId = "a.leftPort")
                        connect(name = "w4", typeId = "L", sourceEndId = "S::o4", targetEndId = "a.bottomPort")
                    }
                }
            val ibd = IbdDiagram(name = "m", ownerId = "S")
            val lr0 =
                layoutOf(
                    model = m,
                    ibd = ibd,
                    boxes = mapOf("S::a" to r(300f, 200f, 300f, 140f)),
                    ends =
                        mapOf(
                            "conn:S::w1" to (Point(x = 0f, y = 0f) to Point(x = 330f, y = 200f)),
                            "conn:S::w2" to (Point(x = 0f, y = 0f) to Point(x = 340f, y = 200f)),
                            "conn:S::w3" to (Point(x = 0f, y = 0f) to Point(x = 300f, y = 270f)),
                            "conn:S::w4" to (Point(x = 0f, y = 0f) to Point(x = 450f, y = 340f)),
                        ),
                )
            val lr = Sysml2LayoutBridge.addIbdOwnerFrame(model = m, diagram = ibd, layoutResult = lr0)
            val frame =
                lr.groups
                    .getValue(Sysml2LayoutBridge.ibdFrameGroupId("S"))
                    .bounds
                    .box()
            val top =
                listOf("S::o1", "S::o2")
                    .map { lr.nodes.getValue(NodeId(it)).bounds }
                    .map { it.origin.x + it.size.width / 2f to it.origin.y + it.size.height / 2f }
            top.forEach { (kotlin.math.abs(it.second - frame.t) < 0.5f) shouldBe true }
            val xs = top.map { it.first }.sorted()
            val need =
                maxOf(
                    56f,
                    Sysml2LayoutBridge.estimateLabelSize(text = "o1 : P").width / 2f +
                        Sysml2LayoutBridge.estimateLabelSize(text = "o2 : P").width / 2f +
                        12f,
                )
            (xs[1] - xs[0] >= need - 0.5f) shouldBe true
            // left and bottom ports produce part-port placements on three distinct sides
            val ports = lr.nodes.getValue(NodeId("S::a")).ports
            ports.size shouldBe 4
            val labels = Sysml2LayoutBridge.ibdLabelRects(model = m, diagram = ibd, layout = lr)
            labels.map { it.rect.box() }.forEachIndexed { i, a ->
                labels.map { it.rect.box() }.drop(i + 1).none { it.hits(a) } shouldBe true
            }
        }

        test("owner label of a top port stays above the frame line, part-port labels outside the part") {
            val p =
                Sysml2LayoutBridge.ibdLabelPlacement(
                    side = Sysml2LayoutBridge.IbdSide.Top,
                    cx = 100f,
                    cy = 50f,
                    text = "x : Y",
                    partPort = true,
                )
            (p.rect.origin.y + p.rect.size.height <= 50f - Sysml2LayoutBridge.IBD_PORT_CLEARANCE_PX + 0.01f) shouldBe true
            (p.rect.origin.x >= 100f + Sysml2LayoutBridge.IBD_PORT_SIZE_PX / 2f + Sysml2LayoutBridge.IBD_PORT_LABEL_GAP_PX - 0.01f) shouldBe
                true
            val left =
                Sysml2LayoutBridge.ibdLabelPlacement(
                    side = Sysml2LayoutBridge.IbdSide.Left,
                    cx = 0f,
                    cy = 50f,
                    text = "x : Y",
                    partPort = true,
                )
            (left.rect.origin.x + left.rect.size.width <= -Sysml2LayoutBridge.IBD_PORT_SIZE_PX / 2f).shouldBe(true)
        }
    })
