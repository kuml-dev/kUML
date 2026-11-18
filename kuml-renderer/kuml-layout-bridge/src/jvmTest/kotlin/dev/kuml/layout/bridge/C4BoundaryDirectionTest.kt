package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

/** Nacharbeit 106: `boundaryDirection` of C4 diagrams. Geometry only, fixed node sizes, independent of fonts. */
class C4BoundaryDirectionTest :
    FunSpec({
        val fixedSize = SizeProvider { _, _ -> Size(width = 140f, height = 60f) }
        val right = LayoutSpec(boundaryDirection = LayoutFlow.Right)

        fun componentGraph(): LayoutGraph {
            val system = C4SoftwareSystem(id = "shop", name = "Shop")
            val api = C4Container(id = "api", name = "API", system = "shop")
            val db = C4Container(id = "db", name = "DB", system = "shop")
            val parts = (1..3).map { C4Component(id = "k$it", name = "Part $it", container = "api") }
            val rels =
                listOf("k1" to "k2", "k2" to "k3").mapIndexed { i, (s, t) ->
                    C4Relationship(id = "r$i", source = s, target = t, label = "uses")
                }
            val model = C4Model(id = "m", name = "M", elements = listOf(system, api, db) + parts, relationships = rels)
            val diagram =
                ComponentDiagram(
                    id = "d",
                    name = "Components",
                    container = "api",
                    elements = parts.map { it.id } + "db",
                    relationships = rels.map { it.id },
                )
            return C4LayoutBridge.toLayoutGraph(diagram = diagram, model = model, sizeProvider = fixedSize)
        }

        fun containerGraph(): LayoutGraph {
            val system = C4SoftwareSystem(id = "shop", name = "Shop")
            val mail = C4SoftwareSystem(id = "mail", name = "Mail", external = true)
            val cs = (1..3).map { C4Container(id = "c$it", name = "C$it", system = "shop") }
            val rels =
                listOf("c1" to "c2", "c2" to "c3", "c3" to "mail").mapIndexed { i, (s, t) ->
                    C4Relationship(id = "r$i", source = s, target = t, label = "uses")
                }
            val model = C4Model(id = "m", name = "M", elements = listOf(system, mail) + cs, relationships = rels)
            val diagram =
                ContainerDiagram(
                    id = "d",
                    name = "Containers",
                    system = "shop",
                    elements = cs.map { it.id } + "mail",
                    relationships = rels.map { it.id },
                )
            return C4LayoutBridge.toLayoutGraph(diagram = diagram, model = model, sizeProvider = fixedSize)
        }

        fun contextGraph(): LayoutGraph {
            val a = C4SoftwareSystem(id = "a", name = "A")
            val b = C4SoftwareSystem(id = "b", name = "B", external = true)
            val rel = C4Relationship(id = "r0", source = "a", target = "b", label = "calls")
            val model = C4Model(id = "m", name = "M", elements = listOf(a, b), relationships = listOf(rel))
            val diagram = SystemContextDiagram(id = "d", name = "Ctx", elements = listOf("a", "b"), relationships = listOf("r0"))
            return C4LayoutBridge.toLayoutGraph(diagram = diagram, model = model, sizeProvider = fixedSize)
        }

        fun run(
            graph: LayoutGraph,
            spec: LayoutSpec?,
        ) = LayoutSpecRunner.layout(
            engine = ElkLayoutEngine(),
            graph = graph,
            hints = LayoutHints(),
            spec = spec,
            kind = SpecDiagramKind.C4,
            diagramName = "D",
        )

        test("self-contained boundary: members flow left to right while the diagram flows top to bottom") {
            val outcome = run(graph = componentGraph(), spec = right)
            val b =
                listOf("k1", "k2", "k3").map {
                    outcome.result.nodes
                        .getValue(NodeId(it))
                        .bounds
                }
            (b[0].origin.x < b[1].origin.x && b[1].origin.x < b[2].origin.x) shouldBe true
            b.all { abs(it.origin.y - b[0].origin.y) < 0.5f } shouldBe true
            outcome.findings.filter { it.code == LayoutCodes.BOUNDARY_DIRECTION_IGNORED }.shouldBeEmpty()
            outcome.result.warnings.none { it.code == "group.direction.crossEdges" } shouldBe true
            // the member row stays inside the boundary group
            val frame =
                outcome.result.groups.values
                    .single()
                    .bounds
            val frameRight = frame.origin.x + frame.size.width
            val inside = b.all { m -> m.origin.x >= frame.origin.x && m.origin.x + m.size.width <= frameRight }
            inside shouldBe true
        }

        test("crossing relationship: picture identical to the one without the keyword, exactly one W009") {
            val with = run(graph = containerGraph(), spec = right)
            val without = run(graph = containerGraph(), spec = null)
            with.result.nodes shouldBe without.result.nodes
            with.result.edges shouldBe without.result.edges
            with.result.groups shouldBe without.result.groups
            with.result.warnings shouldBe without.result.warnings
            val w009 = with.findings.filter { it.code == LayoutCodes.BOUNDARY_DIRECTION_IGNORED }
            w009 shouldHaveSize 1
            w009.single().message shouldContain "crosses the boundary"
            w009.single().message shouldContain "r2"
        }

        test("system context diagram has no boundary: W001, picture unchanged") {
            val with = run(graph = contextGraph(), spec = right)
            val without = run(graph = contextGraph(), spec = null)
            with.result.nodes shouldBe without.result.nodes
            with.findings.filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 1
            with.findings.first { it.code == LayoutCodes.NO_EFFECT }.message shouldContain "boundaryDirection"
        }

        test("validate reports boundaryDirection only for C4 and not on other kinds") {
            LayoutSpecRunner
                .validate(kind = SpecDiagramKind.C4, spec = right, knownIds = null, compoundMembers = true)
                .filter { it.code == LayoutCodes.NO_EFFECT }
                .shouldBeEmpty()
            LayoutSpecRunner
                .validate(kind = SpecDiagramKind.UML_CLASS, spec = right, knownIds = null)
                .filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 1
        }

        test("without the keyword nothing changes: no direction on the group") {
            componentGraph().groups.all { it.direction == null } shouldBe true
        }
    })
