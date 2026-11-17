package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.roundToInt

/**
 * Nacharbeit 96: `wrapColumns` and `sameLayer` have no effect on the elements inside the system boundary of a C4
 * container diagram (the layered engine cannot assign layers to compound members). The option is reported with
 * `KUML-LAYOUT-W001` instead of being silently ignored. Geometry only, fixed node sizes, independent of fonts.
 */
class C4ContainerLayoutOptionsTest :
    FunSpec({
        val fixedSize = SizeProvider { _, _ -> Size(width = 140f, height = 60f) }
        val containerNames = listOf("Web UI", "API", "Worker", "Cache", "Store")

        fun containerModel(): Pair<C4Model, ContainerDiagram> {
            val system = C4SoftwareSystem(id = "shop", name = "Shop System")
            val mail = C4SoftwareSystem(id = "mail", name = "Mail", external = true)
            val containers =
                containerNames.mapIndexed { i, n -> C4Container(id = "c${i + 1}", name = n, system = "shop") }
            val rels =
                listOf("c1" to "c2", "c2" to "c3", "c2" to "c4", "c2" to "c5", "c2" to "mail").mapIndexed { i, (s, t) ->
                    C4Relationship(id = "r$i", source = s, target = t, label = "uses $i")
                }
            val model =
                C4Model(
                    id = "m",
                    name = "Model",
                    elements = listOf(system, mail) + containers,
                    relationships = rels,
                )
            val diagram =
                ContainerDiagram(
                    id = "d",
                    name = "Containers",
                    system = "shop",
                    elements = containers.map { it.id } + "mail",
                    relationships = rels.map { it.id },
                )
            return model to diagram
        }

        fun contextModel(): Pair<C4Model, SystemContextDiagram> {
            val shop = C4SoftwareSystem(id = "shop", name = "Shop System")
            val others = (1..5).map { C4SoftwareSystem(id = "x$it", name = "Partner $it", external = true) }
            val rels = others.mapIndexed { i, o -> C4Relationship(id = "r$i", source = "shop", target = o.id, label = "calls") }
            val model = C4Model(id = "m", name = "Model", elements = listOf(shop) + others, relationships = rels)
            val diagram =
                SystemContextDiagram(
                    id = "d",
                    name = "Context",
                    elements = listOf("shop") + others.map { it.id },
                    relationships = rels.map { it.id },
                )
            return model to diagram
        }

        fun containerGraph(): LayoutGraph {
            val (model, diagram) = containerModel()
            return C4LayoutBridge.toLayoutGraph(diagram = diagram, model = model, sizeProvider = fixedSize)
        }

        fun contextGraph(): LayoutGraph {
            val (model, diagram) = contextModel()
            return C4LayoutBridge.toLayoutGraph(diagram = diagram, model = model, sizeProvider = fixedSize)
        }

        fun run(
            graph: LayoutGraph,
            spec: LayoutSpec?,
        ): SpecLayoutOutcome =
            LayoutSpecRunner.layout(
                engine = ElkLayoutEngine(),
                graph = graph,
                hints = LayoutHints(),
                spec = spec,
                kind = SpecDiagramKind.C4,
                diagramName = "D",
            )

        fun rows(
            result: LayoutResult,
            ids: List<String>,
        ): Int =
            ids
                .map { result.nodes.getValue(NodeId(it)).bounds }
                .map { ((it.origin.y + it.size.height / 2f) / 2f).roundToInt() }
                .distinct()
                .size

        fun boundsOf(
            result: LayoutResult,
            ids: List<String>? = null,
        ) = result.nodes.entries
            .filter { ids == null || it.key.value in ids }
            .sortedBy { it.key.value }
            .map { it.key.value to it.value.bounds }

        val containerIds = listOf("c1", "c2", "c3", "c4", "c5")
        val wrapped = LayoutSpec(wrapColumns = 2)

        test("container diagram: wrapColumns is reported once with W001") {
            val findings = run(graph = containerGraph(), spec = wrapped).findings
            findings.filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 1
            findings.first { it.code == LayoutCodes.NO_EFFECT }.message shouldContain "wrap_columns"
            findings.first { it.code == LayoutCodes.NO_EFFECT }.message shouldContain "has no effect on"
        }

        test("container diagram: sameLayer is reported with W001, both options give two findings") {
            val same = LayoutSpec(sameLayer = listOf(listOf("c3", "c1")))
            run(graph = containerGraph(), spec = same).findings.filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 1
            val both = LayoutSpec(wrapColumns = 2, sameLayer = listOf(listOf("c3", "c1")))
            run(graph = containerGraph(), spec = both).findings.filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 2
        }

        test("container diagram: first, last and direction still act and are not reported") {
            val spec = LayoutSpec(direction = LayoutFlow.Right, first = listOf("c1"), last = listOf("c5"))
            run(graph = containerGraph(), spec = spec).findings.filter { it.code == LayoutCodes.NO_EFFECT }.shouldBeEmpty()
        }

        test("system context diagram: wrapColumns acts, adds rows and is not reported") {
            val plain = run(graph = contextGraph(), spec = null)
            val wrap = run(graph = contextGraph(), spec = wrapped)
            val ids = (1..5).map { "x$it" }
            rows(result = wrap.result, ids = ids) shouldBeGreaterThan rows(result = plain.result, ids = ids)
            wrap.findings.filter { it.code == LayoutCodes.NO_EFFECT }.shouldBeEmpty()
        }

        test("container diagram: wrapColumns and sameLayer do not change the drawing (what W001 reports)") {
            val plain = run(graph = containerGraph(), spec = null)
            val wrap = run(graph = containerGraph(), spec = wrapped)
            val same = run(graph = containerGraph(), spec = LayoutSpec(sameLayer = listOf(listOf("c3", "c1"))))
            // The containers inside the boundary stay exactly where they are. Only an element outside the boundary
            // (the external system "mail") can still move, because it is a root-level node of the layered graph.
            boundsOf(result = wrap.result, ids = containerIds) shouldBe boundsOf(result = plain.result, ids = containerIds)
            boundsOf(result = same.result, ids = containerIds) shouldBe boundsOf(result = plain.result, ids = containerIds)
            rows(result = plain.result, ids = containerIds) shouldBe rows(result = wrap.result, ids = containerIds)
        }

        test("container diagram without or with an empty spec has no findings and stays unchanged") {
            val none = run(graph = containerGraph(), spec = null)
            val empty = run(graph = containerGraph(), spec = LayoutSpec())
            none.findings.shouldBeEmpty()
            empty.findings.shouldBeEmpty()
            boundsOf(empty.result) shouldBe boundsOf(none.result)
        }

        test("container diagram layout is deterministic") {
            val a = run(graph = containerGraph(), spec = wrapped)
            val b = run(graph = containerGraph(), spec = wrapped)
            boundsOf(a.result) shouldBe boundsOf(b.result)
            a.result.edges shouldBe b.result.edges
            a.findings shouldBe b.findings
        }

        test("validate reports wrapColumns and sameLayer only for compound members") {
            val spec = LayoutSpec(wrapColumns = 2, sameLayer = listOf(listOf("a", "b")))
            val known = setOf("a", "b")
            val with = LayoutSpecRunner.validate(kind = SpecDiagramKind.C4, spec = spec, knownIds = known, compoundMembers = true)
            with.filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 2
            val without = LayoutSpecRunner.validate(kind = SpecDiagramKind.C4, spec = spec, knownIds = known)
            without.filter { it.code == LayoutCodes.NO_EFFECT }.shouldBeEmpty()
        }

        test("the compound rule is keyed per kind and listed in the matrix") {
            val spec = LayoutSpec(wrapColumns = 2)
            LayoutSpecRunner.ignoredOptions(spec = spec, kind = SpecDiagramKind.SYSML2_BDD, compoundMembers = true).shouldBeEmpty()
            LayoutSpecRunner.ignoredOptions(spec = spec, kind = SpecDiagramKind.C4, compoundMembers = true) shouldBe
                listOf(LayoutOption.WRAP_COLUMNS)
            LayoutEffectMatrix.ignoredInsideCompound.getValue(SpecDiagramKind.C4) shouldBe
                setOf(LayoutOption.WRAP_COLUMNS, LayoutOption.SAME_LAYER)
        }
    })
