package dev.kuml.layout.bridge

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

/** The grid never asks the engine: if it were not applied, this engine fails the test. */
private object ReqGridNoEngine : KumlLayoutEngine {
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

/** Nacharbeit 49 - group-local wrapColumns (b), empty part def size (d), «subject» edge rules (a). */
class ReqNacharbeit49Test :
    FunSpec({
        fun sixInAGroup(localColumns: Int?) =
            sysml2Model(name = "M") {
                val reqs = (1..6).map { requirementDef(name = "R$it", text = "r$it") }
                reqDiagram(name = "D") {
                    group(name = "G") {
                        reqs.forEach { include(it) }
                        if (localColumns != null) layout { wrapColumns = localColumns }
                    }
                }
            }

        fun rows(model: dev.kuml.sysml2.Sysml2Model): Map<String, Int> {
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            val outcome =
                LayoutSpecRunner.layout(
                    engine = ReqGridNoEngine,
                    graph = graph,
                    hints = LayoutHints(),
                    spec = diagram.layout,
                    kind = SpecDiagramKind.SYSML2_REQ,
                )
            return outcome.result.nodes.entries
                .groupBy { it.value.bounds.origin.y }
                .toSortedMap()
                .values
                .withIndex()
                .flatMap { (row, es) -> es.map { it.key.value to row } }
                .toMap()
        }

        test("group-local wrapColumns reaches the LayoutGroup") {
            val model = sixInAGroup(localColumns = 3)
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            Sysml2LayoutBridge
                .toLayoutGraph(model = model, diagram = diagram)
                .groups
                .single()
                .wrapColumns shouldBe 3
        }

        test("group-local wrapColumns = 3 gives 2 rows for 6 members, without a diagram layout block") {
            val r = rows(sixInAGroup(localColumns = 3))
            r.values.toSet() shouldBe setOf(0, 1)
            r.filterValues { it == 0 }.keys shouldContainExactlyInAnyOrder listOf("R1", "R2", "R3")
        }

        test("group-local wrapColumns = 2 gives 3 rows for 6 members") {
            rows(sixInAGroup(localColumns = 2)).values.toSet() shouldBe setOf(0, 1, 2)
        }

        test("without a local value the grid does not apply and the diagram is unchanged (no grid, no group wrapColumns)") {
            val model = sixInAGroup(localColumns = null)
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            graph.groups.single().wrapColumns shouldBe null
            ReqGroupGrid.applies(graph = graph, spec = null) shouldBe false
        }

        test("applies: a group-local wrapColumns alone is enough, even without a diagram spec") {
            val model = sixInAGroup(localColumns = 4)
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            ReqGroupGrid.applies(graph = graph, spec = null) shouldBe true
        }

        test("the local value wins over the diagram wrapColumns inside its group only") {
            val model =
                sysml2Model(name = "M") {
                    val reqs = (1..4).map { requirementDef(name = "R$it", text = "r$it") }
                    reqDiagram(name = "D") {
                        group(name = "G") {
                            reqs.forEach { include(it) }
                            layout { wrapColumns = 4 }
                        }
                        layout { wrapColumns = 1 }
                    }
                }
            rows(model).values.toSet() shouldBe setOf(0)
        }

        test("empty part def in a REQ diagram is compact; a non-empty one keeps the default size") {
            val model =
                sysml2Model(name = "M") {
                    partDef(name = "SensorUnit")
                    partDef(name = "Full") { attribute(name = "mass", typeId = "Real") }
                }
            val provider = Sysml2LayoutBridge.reqContentAwareSizeProvider(model)
            val empty = provider.sizeOf(elementId = "SensorUnit", elementKind = "PartDefinition")
            empty.height shouldBeLessThan 60f
            empty.width shouldBeGreaterThanOrEqual 100f
            val full = provider.sizeOf(elementId = "Full", elementKind = "PartDefinition")
            full.width shouldBe Sysml2LayoutBridge.REQ_DEFAULT_WIDTH
            full.height shouldBe Sysml2LayoutBridge.REQ_DEFAULT_HEIGHT
        }

        test("«subject» edge: drawn for an unsatisfied subject, suppressed when a satisfy edge connects the pair") {
            val model =
                sysml2Model(name = "M") {
                    val p = partDef(name = "Vitec")
                    val q = partDef(name = "Other")
                    val r1 = requirementDef(name = "R1", subjectRef = "Vitec")
                    val r2 = requirementDef(name = "R2", subjectRef = "Other")
                    reqDiagram(name = "D") {
                        include(p)
                        include(q)
                        include(r1)
                        include(r2)
                        satisfy(source = q, requirement = r2)
                        layout { showSubjectEdges = true }
                    }
                }
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            Sysml2LayoutBridge
                .toLayoutGraph(model = model, diagram = diagram)
                .edges
                .map { it.id.value } shouldContainExactlyInAnyOrder listOf("satisfy:Other::R2", "subject:R1::Vitec")
        }

        test("graph: an empty part def with few edges is compact, a hub with many satisfy edges keeps the default box") {
            val model =
                sysml2Model(name = "M") {
                    val few = partDef(name = "Few")
                    val hub = partDef(name = "Hub")
                    val reqs = (1..4).map { requirementDef(name = "R$it", text = "r") }
                    reqDiagram(name = "D") {
                        include(few)
                        include(hub)
                        reqs.forEach { include(it) }
                        satisfy(source = few, requirement = reqs[0])
                        reqs.forEach { satisfy(source = hub, requirement = it) }
                    }
                }
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val nodes = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram).nodes.associateBy { it.id.value }
            nodes.getValue("Few").intrinsicSize.height shouldBeLessThan 60f
            nodes.getValue("Hub").intrinsicSize.height shouldBe Sysml2LayoutBridge.REQ_DEFAULT_HEIGHT
            nodes.getValue("Hub").intrinsicSize.width shouldBe Sysml2LayoutBridge.REQ_DEFAULT_WIDTH
        }
    })
