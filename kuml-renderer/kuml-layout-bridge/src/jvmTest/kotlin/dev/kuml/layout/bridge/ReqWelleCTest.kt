package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.GroupId
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** The grid never asks the engine: if it were not applied, this engine fails the test. */
private object WelleCNoEngine : KumlLayoutEngine {
    override val id = LayoutEngineId("none")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.Generic),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
            supportsLayerHints = false,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult = error("the engine must not be called when the group grid applies")
}

private class WelleCLaid(
    val result: LayoutResult,
    val findings: List<String>,
) {
    fun card(name: String): Rect = result.nodes.getValue(NodeId(name)).bounds

    fun frame(name: String): Rect = result.groups.getValue(GroupId("group:$name")).bounds
}

private fun laid(model: Sysml2Model): WelleCLaid {
    val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
    val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
    val outcome =
        LayoutSpecRunner.layout(
            engine = WelleCNoEngine,
            graph = graph,
            hints = LayoutHints(),
            spec = diagram.layout,
            kind = SpecDiagramKind.SYSML2_REQ,
        )
    return WelleCLaid(result = outcome.result, findings = outcome.findings.map { it.code })
}

/** PAL Welle C - reading order, no inheritance of the diagram column count, stable placement. Geometry only. */
class ReqWelleCTest :
    FunSpec({
        fun fiveInAGroup(flow: LayoutFlow) =
            sysml2Model(name = "M") {
                val reqs = (1..5).map { requirementDef(name = "R$it", text = "r$it") }
                reqDiagram(name = "D") {
                    group(name = "G") {
                        reqs.forEach { include(it) }
                        layout { wrapColumns = 2 }
                    }
                    layout { direction = flow }
                }
            }

        test("S1: direction Right fills the group row by row in include order (not column by column)") {
            val t = laid(fiveInAGroup(LayoutFlow.Right))
            val y = (1..5).map { t.card("R$it").origin.y }
            val x = (1..5).map { t.card("R$it").origin.x }
            y[0] shouldBe y[1]
            y[2] shouldBe y[3]
            (y[2] > y[0]) shouldBe true
            (y[4] > y[2]) shouldBe true
            (x[1] > x[0]) shouldBe true
            x[0] shouldBe x[2]
            x[0] shouldBe x[4]
        }

        test("S1: Right and Down give the same card order") {
            val right = laid(fiveInAGroup(LayoutFlow.Right))
            val down = laid(fiveInAGroup(LayoutFlow.Down))
            for (i in 1..5) right.card("R$i").origin.x shouldBe down.card("R$i").origin.x
            for (i in 1..5) right.card("R$i").origin.y shouldBe down.card("R$i").origin.y
        }

        fun fourInAGroup(
            localColumns: Int?,
            diagramColumns: Int,
        ) = sysml2Model(name = "M") {
            val reqs = (1..4).map { requirementDef(name = "R$it", text = "r$it") }
            reqDiagram(name = "D") {
                group(name = "G") {
                    reqs.forEach { include(it) }
                    if (localColumns != null) layout { wrapColumns = localColumns }
                }
                layout { wrapColumns = diagramColumns }
            }
        }

        fun columnsOf(t: WelleCLaid) = (1..4).map { t.card("R$it").origin.x }.toSet().size

        test("S2: the diagram wrapColumns is not inherited by a group without a local value") {
            // 1 diagram column would give 1 column per group if it were inherited; the group chooses by aspect instead.
            columnsOf(laid(fourInAGroup(localColumns = null, diagramColumns = 1))) shouldNotBe 1
        }

        test("S2: a group-local wrapColumns still wins") {
            columnsOf(laid(fourInAGroup(localColumns = 3, diagramColumns = 1))) shouldBe 3
        }
    })
