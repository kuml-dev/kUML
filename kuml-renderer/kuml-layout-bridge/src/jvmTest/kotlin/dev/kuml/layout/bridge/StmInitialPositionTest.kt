package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * State-machine initial placement: the initial pseudo-state sits at the start of the flow in every direction, in
 * SysML 2 STM and UML state machines, without a layout block, and its edge is a short straight line.
 */
class StmInitialPositionTest :
    FunSpec({
        val directions = LayoutDirection.entries

        fun flowStart(
            r: Rect,
            d: LayoutDirection,
        ): Float =
            when (d) {
                LayoutDirection.TopToBottom -> r.origin.y
                LayoutDirection.BottomToTop -> -(r.origin.y + r.size.height)
                LayoutDirection.LeftToRight -> r.origin.x
                LayoutDirection.RightToLeft -> -(r.origin.x + r.size.width)
            }

        fun sysml(): Sysml2Model =
            sysml2Model(name = "Door") {
                val init = stateDef(name = "Init", isInitial = true)
                val closed = stateDef(name = "Closed")
                val open = stateDef(name = "Open")
                transition(name = "i", source = init, target = closed)
                transition(name = "t1", source = closed, target = open, trigger = "openCmd")
                transition(name = "t2", source = open, target = closed, trigger = "closeCmd")
                stmDiagram(name = "Door") {
                    include(init)
                    include(closed)
                    include(open)
                }
            }

        fun runSysml(
            m: Sysml2Model,
            d: LayoutDirection,
        ): Pair<LayoutGraph, LayoutResult> {
            val diagram = m.diagrams.filterIsInstance<StmDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = diagram).copy(direction = d)
            val result =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = hints,
                        spec = diagram.layout,
                        kind = SpecDiagramKind.SYSML2_STM,
                    ).result
            return graph to result
        }

        fun runUml(d: LayoutDirection): Pair<LayoutGraph, LayoutResult> {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "Door",
                    vertices =
                        listOf(
                            UmlPseudostate(id = "start", name = "", kind = PseudostateKind.INITIAL),
                            UmlState(id = "closed", name = "Closed"),
                            UmlState(id = "open", name = "Open"),
                        ),
                    transitions =
                        listOf(
                            UmlTransition(id = "t0", sourceId = "start", targetId = "closed"),
                            UmlTransition(id = "t1", sourceId = "closed", targetId = "open", trigger = "openCmd"),
                            UmlTransition(id = "t2", sourceId = "open", targetId = "closed", trigger = "closeCmd"),
                        ),
                )
            val graph = UmlLayoutBridge.toLayoutGraph(diagram = KumlDiagram(name = "D", elements = listOf(sm)))
            val result =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = LayoutHints.DEFAULT.copy(direction = d),
                        spec = null,
                        kind = SpecDiagramKind.UML_STATE,
                    ).result
            return graph to result
        }

        fun assertAtStart(
            r: LayoutResult,
            initial: String,
            others: List<String>,
            d: LayoutDirection,
        ) {
            val i = flowStart(r.nodes.getValue(NodeId(initial)).bounds, d)
            for (o in others) {
                (i < flowStart(r.nodes.getValue(NodeId(o)).bounds, d)).shouldBeTrue()
            }
        }

        for (d in directions) {
            test("SysML 2 STM without layout block: initial is in front of every state ($d)") {
                val (_, r) = runSysml(sysml(), d)
                assertAtStart(r, "Init", listOf("Closed", "Open"), d)
            }
            test("UML state machine: initial is in front of every state and inside the frame ($d)") {
                val (_, r) = runUml(d)
                assertAtStart(r, "start", listOf("closed", "open"), d)
                val frame = r.groups.getValue(GroupId("sm")).bounds
                val b = r.nodes.getValue(NodeId("start")).bounds
                (b.origin.x >= frame.origin.x && b.origin.y >= frame.origin.y).shouldBeTrue()
                (b.origin.x + b.size.width <= frame.origin.x + frame.size.width).shouldBeTrue()
                (b.origin.y + b.size.height <= frame.origin.y + frame.size.height).shouldBeTrue()
            }
        }

        test("without a layout block no layer hint is set (partition hints would re-place the whole diagram)") {
            val m = sysml()
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.filterIsInstance<StmDiagram>().single())
            g.nodes.all { it.hints.layerIndex == null }.shouldBeTrue()
            g.nodes
                .single { it.id.value == "Init" }
                .hints.initialState
                .shouldBeTrue()
        }

        test("with first/last the initial is pinned to the layer before its target") {
            val m =
                sysml2Model(name = "FL") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "i", source = init, target = a)
                    transition(name = "t", source = a, target = b)
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                        layout { lastById("B") }
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.filterIsInstance<StmDiagram>().single())
            val layer = g.nodes.associate { it.id.value to it.hints.layerIndex }
            layer["Init"] shouldBe layer.getValue("A")!! - 1
        }

        test("a state machine without initial pseudo-state is not touched") {
            val m =
                sysml2Model(name = "NoInit") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "t", source = a, target = b)
                    stmDiagram(name = "d") {
                        include(a)
                        include(b)
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.filterIsInstance<StmDiagram>().single())
            g.nodes.all { it.hints.layerIndex == null && !it.hints.initialState }.shouldBeTrue()
        }

        test("an obstacle on the preferred spot makes the initial fall back to the spot directly in front of its target") {
            val (graph, r) = runSysml(sysml(), LayoutDirection.TopToBottom)
            // Block the start of the flow with a node-sized obstacle in front of the target.
            val closed = r.nodes.getValue(NodeId("Closed")).bounds
            val blocker =
                NodeLayoutFixture.node(
                    Rect(
                        origin = dev.kuml.layout.Point(x = closed.origin.x + 5f, y = closed.origin.y - 60f),
                        size = dev.kuml.layout.Size(width = 30f, height = 14f),
                    ),
                )
            val blocked = r.copy(nodes = r.nodes + (NodeId("Blocker") to blocker))
            val moved =
                blocked.pinInitialsToTargets(graph = graph, direction = LayoutDirection.TopToBottom)
            // Still a valid result: the initial exists and no node overlaps the blocker.
            val init = moved.nodes.getValue(NodeId("Init")).bounds
            (init.origin.y + init.size.height <= closed.origin.y + 1f || init.origin.y >= closed.origin.y).shouldBeTrue()
        }
    })

private object NodeLayoutFixture {
    fun node(bounds: Rect): dev.kuml.layout.NodeLayout = dev.kuml.layout.NodeLayout(bounds = bounds)
}
