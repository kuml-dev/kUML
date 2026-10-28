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
            val onBorder =
                cx == frame.origin.x ||
                    cy == frame.origin.y ||
                    cx == frame.origin.x + frame.size.width ||
                    cy == frame.origin.y + frame.size.height
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
