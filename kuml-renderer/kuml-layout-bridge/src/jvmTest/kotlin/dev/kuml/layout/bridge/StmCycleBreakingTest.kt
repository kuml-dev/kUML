package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/** Nacharbeit 58 (b): in a state cycle the edge that closes the cycle is the reversed one, with or without an initial. */
class StmCycleBreakingTest :
    FunSpec({
        fun machine(withInitial: Boolean): UmlStateMachine =
            UmlStateMachine(
                id = "sm",
                name = "Cycle",
                vertices =
                    listOfNotNull(
                        if (withInitial) UmlPseudostate(id = "start", name = "", kind = PseudostateKind.INITIAL) else null,
                        UmlState(id = "off", name = "Off"),
                        UmlState(id = "run", name = "Running"),
                        UmlState(id = "stp", name = "Stopped"),
                    ),
                transitions =
                    listOfNotNull(
                        if (withInitial) UmlTransition(id = "t0", sourceId = "start", targetId = "off") else null,
                        UmlTransition(id = "t1", sourceId = "off", targetId = "run", trigger = "start"),
                        UmlTransition(id = "t2", sourceId = "run", targetId = "stp", trigger = "stop"),
                        UmlTransition(id = "t3", sourceId = "stp", targetId = "off", trigger = "reset"),
                    ),
            )

        fun graphOf(withInitial: Boolean) =
            UmlLayoutBridge.toLayoutGraph(diagram = KumlDiagram(name = "D", elements = listOf(machine(withInitial))))

        test("without an initial pseudo-state the graph stays unchanged (the engine already orders the cycle forward)") {
            val g = graphOf(withInitial = false)
            g.withForwardEdgePriority() shouldBe g
        }

        test("keepDirection is set on the forward edges and not on the closing edge") {
            val g = graphOf(withInitial = true).withForwardEdgePriority()
            val closing = g.edges.single { it.source.nodeId == NodeId("stp") }
            closing.hints.keepDirection shouldBe false
            g.edges
                .filter { it != closing }
                .all { it.hints.keepDirection }
                .shouldBeTrue()
        }

        for (withInitial in listOf(true, false)) {
            test("real engine, direction Down: Off above Running above Stopped (initial=$withInitial)") {
                val g = graphOf(withInitial)
                val r =
                    LayoutSpecRunner
                        .layout(
                            engine = ElkLayoutEngine(),
                            graph = g,
                            hints = LayoutHints.DEFAULT.copy(direction = LayoutDirection.TopToBottom),
                            spec = null,
                            kind = SpecDiagramKind.UML_STATE,
                        ).result

                fun y(id: String) =
                    r.nodes
                        .getValue(NodeId(id))
                        .bounds.origin.y
                (y("off") < y("run")).shouldBeTrue()
                (y("run") < y("stp")).shouldBeTrue()
            }
        }
    })
