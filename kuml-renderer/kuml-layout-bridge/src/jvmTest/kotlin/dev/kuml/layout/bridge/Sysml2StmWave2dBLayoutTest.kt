package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.Sysml2LayoutBuilder
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** PAL wave 2D-b: initial pseudo-state pinning (P-STM-04b) and `sameLayer` centring (P-STM-04c). */
class Sysml2StmWave2dBLayoutTest :
    FunSpec({
        fun diagram(m: Sysml2Model) = m.diagrams.filterIsInstance<StmDiagram>().single()

        fun run(
            m: Sysml2Model,
            flow: LayoutDirection,
        ): LayoutResult {
            val d = diagram(m)
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d).copy(direction = flow)
            return LayoutSpecRunner
                .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = d.layout, kind = SpecDiagramKind.SYSML2_STM)
                .result
        }

        fun chain(layoutBlock: (Sysml2LayoutBuilder.() -> Unit)?) =
            sysml2Model(name = "Chain") {
                val init = stateDef(name = "Init", isInitial = true)
                val a = stateDef(name = "A")
                val b = stateDef(name = "B")
                val c = stateDef(name = "C")
                val x = stateDef(name = "X")
                transition(name = "t0", source = init, target = a)
                transition(name = "t1", source = a, target = b)
                transition(name = "t2", source = b, target = c)
                transition(name = "t3", source = c, target = x)
                stmDiagram(name = "d") {
                    listOf(init, a, b, c, x).forEach { include(it) }
                    if (layoutBlock != null) layout { layoutBlock() }
                }
            }

        test("initial is pinned to the layer before its target, also when sameLayer pulls the target down") {
            val m = chain { sameLayerById("A", "X") }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m))
            val layer = g.nodes.associate { it.id.value to it.hints.layerIndex }
            layer["A"] shouldBe layer["X"]
            layer["Init"] shouldBe (layer.getValue("A")!! - 1)
        }

        for (flow in listOf(LayoutDirection.TopToBottom, LayoutDirection.LeftToRight)) {
            test("initial edge is short, straight and centred on the target ($flow)") {
                val m = chain { sameLayerById("A", "X") }
                val r = run(m, flow)
                val init = r.nodes.getValue(NodeId("Init")).bounds
                val a = r.nodes.getValue(NodeId("A")).bounds
                val initEdge =
                    Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m)).edges.single {
                        it.source.nodeId.value ==
                            "Init"
                    }
                val route = r.edges.getValue(initEdge.id)
                val pointCount =
                    when (route) {
                        is EdgeRoute.Direct -> 2
                        is EdgeRoute.OrthogonalRounded -> route.waypoints.size + 2
                        is EdgeRoute.TreeRounded -> route.waypoints.size + 2
                        is EdgeRoute.Bezier -> route.controlPoints.size + 2
                    }
                println("[2d-b] $flow init=$init a=$a route=$route")
                pointCount shouldBe 2
                if (flow == LayoutDirection.TopToBottom) {
                    (a.origin.y - (init.origin.y + init.size.height)) shouldBeLessThanOrEqual 40f
                    abs((init.origin.x + init.size.width / 2f) - (a.origin.x + a.size.width / 2f)) shouldBeLessThanOrEqual 1f
                } else {
                    (a.origin.x - (init.origin.x + init.size.width)) shouldBeLessThanOrEqual 40f
                    abs((init.origin.y + init.size.height / 2f) - (a.origin.y + a.size.height / 2f)) shouldBeLessThanOrEqual 1f
                }
            }
        }

        for (flow in listOf(LayoutDirection.TopToBottom, LayoutDirection.LeftToRight)) {
            test("sameLayer members of different size share their centre on the flow axis ($flow)") {
                val m =
                    sysml2Model(name = "SL") {
                        val s = stateDef(name = "Start", isInitial = true)
                        val small = stateDef(name = "Small")
                        val big = stateDef(name = "Big", entryAction = "a()", doAction = "b()", exitAction = "c()", invariant = "x > 0")
                        transition(name = "e1", source = s, target = small)
                        transition(name = "e2", source = small, target = big)
                        stmDiagram(name = "d") {
                            listOf(s, small, big).forEach { include(it) }
                            layout { sameLayerById("Small", "Big") }
                        }
                    }
                val r = run(m, flow)
                val a = r.nodes.getValue(NodeId("Small")).bounds
                val b = r.nodes.getValue(NodeId("Big")).bounds
                println("[2d-b] sameLayer $flow small=$a big=$b")
                if (flow == LayoutDirection.TopToBottom) {
                    abs((a.origin.y + a.size.height / 2f) - (b.origin.y + b.size.height / 2f)) shouldBeLessThanOrEqual 0.5f
                } else {
                    abs((a.origin.x + a.size.width / 2f) - (b.origin.x + b.size.width / 2f)) shouldBeLessThanOrEqual 0.5f
                }
                (a.size.height != b.size.height) shouldBe true
            }
        }

        test("pinInitialsToTarget leaves initials alone that are first or have two targets, and never goes negative") {
            val layers = mutableMapOf("i" to 5, "t" to 0, "j" to 3, "u" to 1, "v" to 2)
            pinInitialsToTarget(
                layers = layers,
                initials = setOf("i", "j"),
                edges = listOf("i" to "t", "j" to "u", "j" to "v"),
                fixed = setOf("t"),
            )
            layers["i"] shouldBe 5 // target is fixed -> untouched
            layers["j"] shouldBe 3 // two targets -> untouched
            val l2 = mutableMapOf("i" to 4, "t" to 0, "o" to 2)
            pinInitialsToTarget(layers = l2, initials = setOf("i"), edges = listOf("i" to "t"), fixed = emptySet())
            l2.values.min() shouldBe 0
            l2.getValue("t") - l2.getValue("i") shouldBe 1
        }
    })
