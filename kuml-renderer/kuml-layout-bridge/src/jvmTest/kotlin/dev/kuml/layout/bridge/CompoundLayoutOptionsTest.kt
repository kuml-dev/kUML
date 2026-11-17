package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
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
import dev.kuml.sysml2.Sysml2PackageDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Nacharbeit 97: `wrapColumns` and `sameLayer` have no effect on the members of a compound group (package, deployment
 * node, state-machine frame, SysML 2 package frame): the members stay exactly where they are. The option is reported
 * with `KUML-LAYOUT-W001` instead of being silently ignored. Geometry only, fixed node sizes, independent of fonts.
 */
class CompoundLayoutOptionsTest :
    FunSpec({
        val fixed = SizeProvider { _, _ -> Size(width = 140f, height = 60f) }

        fun assoc(
            id: String,
            a: String,
            b: String,
        ) = UmlAssociation(id = id, ends = listOf(UmlAssociationEnd(typeId = a), UmlAssociationEnd(typeId = b)))

        val links = listOf("M1" to "M2", "M2" to "M3", "M2" to "M4", "M2" to "M5", "M2" to "M6", "M1" to "O1", "O1" to "O2", "O1" to "O3")
        val associations = links.mapIndexed { i, (x, y) -> assoc(id = "a$i", a = x, b = y) }

        fun uml(elements: List<KumlElement>): LayoutGraph =
            UmlLayoutBridge.toLayoutGraph(diagram = KumlDiagram(name = "D", elements = elements), sizeProvider = fixed)

        fun classPackage(): LayoutGraph =
            uml(
                listOf<KumlElement>(UmlPackage(id = "Pkg1", name = "Pkg1", members = (1..6).map { UmlClass(id = "M$it", name = "M$it") })) +
                    (1..3).map { UmlClass(id = "O$it", name = "O$it") } + associations,
            )

        fun componentPackage(): LayoutGraph =
            uml(
                listOf<KumlElement>(
                    UmlPackage(id = "Pkg1", name = "Pkg1", members = (1..6).map { UmlComponent(id = "M$it", name = "M$it") }),
                ) +
                    (1..3).map { UmlComponent(id = "O$it", name = "O$it") } + associations,
            )

        fun deployment(): LayoutGraph {
            val members = (1..6).map { UmlNode(id = "M$it", name = "M$it", nodeKind = "node") }
            val cluster = UmlNode(id = "Cl", name = "Cl", nodeKind = "executionEnvironment", children = members)
            return uml(
                listOf<KumlElement>(cluster) + (1..3).map { UmlNode(id = "O$it", name = "O$it", nodeKind = "node") } + associations,
            )
        }

        fun stateMachine(): LayoutGraph {
            val states = (1..6).map { UmlState(id = "M$it", name = "M$it") }
            val transitions = links.take(5).mapIndexed { i, (x, y) -> UmlTransition(id = "t$i", sourceId = x, targetId = y) }
            return uml(listOf(UmlStateMachine(id = "sm", name = "SM", vertices = states, transitions = transitions)))
        }

        fun sysml2Package(): LayoutGraph {
            val m =
                sysml2Model(name = "N") {
                    pkg(name = "A") {
                        (1..6).forEach { partDef(name = "M$it", id = "M$it") }
                        pkg(name = "B") { (1..2).forEach { partDef(name = "N$it", id = "N$it") } }
                    }
                    packageDiagram(name = "N") { includeById("pkg:A") }
                }
            return Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.single() as Sysml2PackageDiagram)
        }

        val fixtures: List<Triple<SpecDiagramKind, String, () -> LayoutGraph>> =
            listOf(
                Triple(SpecDiagramKind.UML_CLASS, "class diagram with a package", ::classPackage),
                Triple(SpecDiagramKind.UML_PACKAGE, "package diagram", ::classPackage),
                Triple(SpecDiagramKind.UML_COMPONENT, "component diagram with a package", ::componentPackage),
                Triple(SpecDiagramKind.UML_DEPLOYMENT, "deployment diagram with a nested node", ::deployment),
                Triple(SpecDiagramKind.UML_STATE, "state machine", ::stateMachine),
                Triple(SpecDiagramKind.SYSML2_PKG, "SysML 2 package diagram", ::sysml2Package),
            )

        fun run(
            graph: LayoutGraph,
            spec: LayoutSpec?,
            kind: SpecDiagramKind,
        ): SpecLayoutOutcome =
            LayoutSpecRunner.layout(
                engine = ElkLayoutEngine(),
                graph = graph,
                hints = LayoutHints(),
                spec = spec,
                kind = kind,
                diagramName = "D",
            )

        fun boundsOf(
            result: LayoutResult,
            ids: List<String>,
        ) = ids.map { it to result.nodes.getValue(NodeId(it)).bounds }

        val wrapped = LayoutSpec(wrapColumns = 2)

        fun w001(outcome: SpecLayoutOutcome) = outcome.findings.filter { it.code == LayoutCodes.NO_EFFECT }

        fixtures.forEach { (kind, name, build) ->
            val members = build().nodes.filter { it.groupId != null }.map { it.id.value }

            test("$kind ($name): wrapColumns and sameLayer leave the members where they are and are reported with W001") {
                val graph = build()
                val same = LayoutSpec(sameLayer = listOf(listOf(members.first(), members.last())))
                val plain = run(graph = graph, spec = null, kind = kind)
                val wrap = run(graph = graph, spec = wrapped, kind = kind)
                val sameRun = run(graph = graph, spec = same, kind = kind)
                // What W001 reports: the members are identical with and without the option.
                boundsOf(result = wrap.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
                boundsOf(result = sameRun.result, ids = members) shouldBe boundsOf(result = plain.result, ids = members)
                w001(plain).shouldBeEmpty()
                w001(wrap) shouldHaveSize 1
                w001(wrap).single().message shouldContain "wrap_columns"
                w001(wrap).single().message shouldContain "has no effect on"
                w001(sameRun) shouldHaveSize 1
                w001(sameRun).single().message shouldContain "same_layer"
                w001(run(graph = graph, spec = LayoutSpec(wrapColumns = 2, sameLayer = same.sameLayer), kind = kind)) shouldHaveSize 2
            }

            test("$kind ($name): first, last and direction are not reported") {
                val spec = LayoutSpec(direction = LayoutFlow.Right, first = listOf(members.first()), last = listOf(members.last()))
                w001(run(graph = build(), spec = spec, kind = kind)).shouldBeEmpty()
            }

            test("$kind ($name): layout is deterministic and validate reports the options only for compound members") {
                val a = run(graph = build(), spec = wrapped, kind = kind)
                val b = run(graph = build(), spec = wrapped, kind = kind)
                boundsOf(result = a.result, ids = members) shouldBe boundsOf(result = b.result, ids = members)
                a.result.edges shouldBe b.result.edges
                a.findings shouldBe b.findings
                val ids = build().nodes.map { it.id.value }.toSet()
                val graph = build()
                LayoutSpecRunner.hasCompoundMembers(graph) shouldBe true
                LayoutSpecRunner
                    .validate(kind = kind, spec = wrapped, knownIds = ids, compoundMembers = true)
                    .filter { it.code == LayoutCodes.NO_EFFECT } shouldHaveSize 1
                LayoutSpecRunner
                    .validate(kind = kind, spec = wrapped, knownIds = ids)
                    .filter { it.code == LayoutCodes.NO_EFFECT }
                    .shouldBeEmpty()
            }
        }

        test("control: without a package the same classes react to wrapColumns and are not reported") {
            val flat = (1..6).map { UmlClass(id = "M$it", name = "M$it") }
            val graph = uml(flat + links.take(5).mapIndexed { i, (x, y) -> assoc(id = "a$i", a = x, b = y) })
            val ids = flat.map { it.id }
            val plain = run(graph = graph, spec = null, kind = SpecDiagramKind.UML_CLASS)
            val wrap = run(graph = graph, spec = wrapped, kind = SpecDiagramKind.UML_CLASS)
            boundsOf(result = wrap.result, ids = ids) shouldNotBe boundsOf(result = plain.result, ids = ids)
            w001(wrap).shouldBeEmpty()
            LayoutSpecRunner.hasCompoundMembers(graph) shouldBe false
        }

        test("kinds with a grid of their own or without a measured compound rule have no entry") {
            listOf(
                SpecDiagramKind.SYSML2_REQ,
                SpecDiagramKind.SYSML2_UC,
                SpecDiagramKind.SYSML2_ACT,
                SpecDiagramKind.SYSML2_BDD,
                SpecDiagramKind.ERM,
            ).forEach {
                LayoutEffectMatrix.ignoredInsideCompound.containsKey(it) shouldBe false
                LayoutSpecRunner.ignoredOptions(spec = wrapped, kind = it, compoundMembers = true).shouldBeEmpty()
            }
        }

        test("the matrix lists exactly the measured kinds with both options") {
            LayoutEffectMatrix.ignoredInsideCompound shouldBe
                (fixtures.map { it.first } + SpecDiagramKind.C4).associateWith {
                    setOf(LayoutOption.WRAP_COLUMNS, LayoutOption.SAME_LAYER)
                }
        }
    })
