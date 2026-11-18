package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

/**
 * Nacharbeit 107: in a UML package diagram `wrapColumns`, `sameLayer`, `first` and `last` act on the members of a package
 * when no relationship connects a member with an element outside it. Geometry only, fixed node sizes, no fonts.
 */
class UmlPackageLayerHintsTest :
    FunSpec({
        val fixed = SizeProvider { _, _ -> Size(width = 140f, height = 60f) }
        val kind = SpecDiagramKind.UML_PACKAGE

        fun assoc(
            id: String,
            a: String,
            b: String,
        ) = UmlAssociation(id = id, ends = listOf(UmlAssociationEnd(typeId = a), UmlAssociationEnd(typeId = b)))

        fun cls(id: String) = UmlClass(id = id, name = id)

        fun graphOf(elements: List<KumlElement>): LayoutGraph =
            UmlLayoutBridge.toLayoutGraph(diagram = KumlDiagram(name = "D", elements = elements), sizeProvider = fixed)

        val inner = listOf("M1" to "M2", "M2" to "M3", "M2" to "M4", "M2" to "M5", "M2" to "M6")
        val innerAssocs = inner.mapIndexed { i, (a, b) -> assoc(id = "a$i", a = a, b = b) }
        val pkg1Members = (1..6).map { "M$it" } + listOf("X1", "X2")

        /** Pkg1 (six linked members plus two loose ones) is self-contained; an import links it to Pkg2. */
        fun selfContained(): LayoutGraph =
            graphOf(
                listOf<KumlElement>(
                    UmlPackage(id = "Pkg1", name = "Pkg1", members = pkg1Members.map(::cls)),
                    UmlPackage(id = "Pkg2", name = "Pkg2", members = listOf(cls("P1"), cls("P2"))),
                    UmlDependency(id = "imp", clientId = "Pkg1", supplierId = "Pkg2"),
                    assoc(id = "ap", a = "P1", b = "P2"),
                ) + innerAssocs,
            )

        /** Same as [selfContained] plus one association from a Pkg1 member to a top-level class: Pkg1 is no longer self-contained. */
        fun crossing(): LayoutGraph =
            graphOf(
                listOf<KumlElement>(
                    UmlPackage(id = "Pkg1", name = "Pkg1", members = pkg1Members.map(::cls)),
                    cls("O1"),
                    assoc(id = "ax", a = "M1", b = "O1"),
                ) + innerAssocs,
            )

        fun run(
            graph: LayoutGraph,
            spec: LayoutSpec?,
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

        fun y(
            r: LayoutResult,
            id: String,
        ) = r.nodes
            .getValue(NodeId(id))
            .bounds.origin.y

        fun w001(o: SpecLayoutOutcome) = o.findings.filter { it.code == LayoutCodes.NO_EFFECT }

        fun maxPerRow(
            r: LayoutResult,
            ids: List<String>,
        ): Int =
            ids
                .groupBy { Math.round(y(r, it) / 4f) }
                .values
                .maxOf { it.size }

        test("self-contained package: wrapColumns moves the members into rows of at most two, no W001") {
            val plain = run(selfContained(), null)
            val wrap = run(selfContained(), LayoutSpec(wrapColumns = 2))
            boundsOf(wrap.result, pkg1Members) shouldNotBe boundsOf(plain.result, pkg1Members)
            maxPerRow(wrap.result, pkg1Members) shouldBeLessThanOrEqualTo 2
            w001(wrap).shouldBeEmpty()
        }

        test("self-contained package: sameLayer puts the listed members on one row, no W001") {
            val r = run(selfContained(), LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))))
            (abs(y(r.result, "M3") - y(r.result, "X1")) < 0.5f) shouldBe true
            w001(r).shouldBeEmpty()
        }

        test("self-contained package: first and last put members on the extreme rows") {
            val r = run(selfContained(), LayoutSpec(first = listOf("X1"), last = listOf("X2"))).result
            val others = pkg1Members - setOf("X1", "X2")
            others.forEach {
                (y(r, "X1") <= y(r, it) + 0.5f) shouldBe true
                (y(r, "X2") >= y(r, it) - 0.5f) shouldBe true
            }
            (y(r, "X1") < y(r, "X2")) shouldBe true
        }

        test("crossing relationship: members stay where they are and W001 names the package and the relationship") {
            val plain = run(crossing(), null)
            val wrap = run(crossing(), LayoutSpec(wrapColumns = 2))
            boundsOf(wrap.result, pkg1Members) shouldBe boundsOf(plain.result, pkg1Members)
            w001(wrap) shouldHaveSize 1
            w001(wrap).single().message shouldContain "wrap_columns"
            w001(wrap).single().message shouldContain "package 'Pkg1'"
            w001(wrap).single().message shouldContain "'ax'"
            val same = run(crossing(), LayoutSpec(sameLayer = listOf(listOf("M3", "X1"))))
            boundsOf(same.result, pkg1Members) shouldBe boundsOf(plain.result, pkg1Members)
            w001(same) shouldHaveSize 1
            w001(same).single().message shouldContain "package 'Pkg1'"
        }

        test("two packages, one crossing: the self-contained one moves and W001 names the crossing one") {
            val g =
                graphOf(
                    listOf<KumlElement>(
                        UmlPackage(id = "Pkg1", name = "Pkg1", members = pkg1Members.map(::cls)),
                        UmlPackage(id = "Pkg2", name = "Pkg2", members = (1..6).map { cls("Q$it") }),
                        cls("O1"),
                        assoc(id = "ax", a = "Q1", b = "O1"),
                    ) + innerAssocs,
                )
            val plain = run(g, null)
            val wrap = run(g, LayoutSpec(wrapColumns = 2))
            boundsOf(wrap.result, pkg1Members) shouldNotBe boundsOf(plain.result, pkg1Members)
            val q = (1..6).map { "Q$it" }

            fun relative(r: LayoutResult) =
                q.map { id ->
                    val b = r.nodes.getValue(NodeId(id)).bounds
                    val o =
                        r.groups
                            .getValue(GroupId("Pkg2"))
                            .bounds.origin
                    Pair(b.origin.x - o.x, b.origin.y - o.y)
                }
            relative(wrap.result) shouldBe relative(plain.result)
            w001(wrap).single().message shouldContain "package 'Pkg2'"
        }

        test("wrapColumns counts the rows inside the package, also when a top-level class is declared before it") {
            val ids = listOf("A", "B", "C", "D")
            val g =
                graphOf(
                    listOf<KumlElement>(
                        cls("T1"),
                        UmlPackage(id = "P", name = "P", members = ids.map(::cls)),
                    ),
                )
            val r = run(g, LayoutSpec(wrapColumns = 2)).result
            val rows = ids.groupBy { Math.round(y(r, it) / 4f) }.values.map { it.size }
            rows shouldBe listOf(2, 2)
        }

        test("unflagged package: layer indices are counted over the whole diagram, as without the package") {
            val rs = (1..8).map { "R$it" }
            val g =
                graphOf(
                    listOf<KumlElement>(
                        UmlPackage(id = "P", name = "P", members = listOf("A", "B", "C", "D", "E").map(::cls)),
                        cls("H"),
                    ) + rs.map(::cls) +
                        listOf(assoc(id = "ab", a = "A", b = "B")) +
                        rs.mapIndexed { i, r -> assoc(id = "h$i", a = "H", b = r) },
                )
            check(g.groups.none { it.memberLayerHints })
            val spec = LayoutSpec(wrapColumns = 3)
            val withPackage = g.withSpec(spec = spec)
            val flat = g.copy(groups = emptyList(), nodes = g.nodes.map { it.copy(groupId = null) }).withSpec(spec = spec)

            fun layers(graph: LayoutGraph) = graph.nodes.associate { it.id.value to it.hints.layerIndex }
            layers(withPackage) shouldBe layers(flat)
        }

        test("sameLayer with an unknown id does not report the mixed-scope message") {
            val o = run(selfContained(), LayoutSpec(sameLayer = listOf(listOf("M1", "Tippfehler"))))
            o.findings.none { it.message.contains("different packages") } shouldBe true
        }

        test("sameLayer across two packages is reported with the mixed-scope message") {
            val o = run(selfContained(), LayoutSpec(sameLayer = listOf(listOf("M1", "P1"))))
            w001(o) shouldHaveSize 1
            w001(o).single().message shouldContain "different packages"
        }

        test("default: no spec gives the plain engine result and withMemberLayerHints keeps the same instance") {
            val g = selfContained()
            g.withMemberLayerHints(null) shouldBe g
            (g.withMemberLayerHints(LayoutSpec()) === g) shouldBe true
            (g.withMemberLayerHints(null) === g) shouldBe true
            val viaRunner = run(g, null).result
            val direct = ElkLayoutEngine().layout(graph = g, hints = LayoutHints())
            viaRunner.nodes shouldBe direct.nodes
            viaRunner.edges shouldBe direct.edges
        }

        test("aspectRatio alone does not switch honouring on") {
            val g = selfContained()
            g.withMemberLayerHints(LayoutSpec(aspectRatio = 2.0)).groups.none { it.memberLayerHints } shouldBe true
            val ar = run(g, LayoutSpec(aspectRatio = 2.0))
            w001(ar).shouldBeEmpty()
        }

        test("validate with a scope reports W001 only for a crossing package, without a scope it is unchanged") {
            val ids = pkg1Members.toSet() + setOf("O1", "P1", "P2")
            val spec = LayoutSpec(wrapColumns = 2)

            fun count(
                g: LayoutGraph,
                scoped: Boolean,
            ) = LayoutSpecRunner
                .validate(
                    kind = kind,
                    spec = spec,
                    knownIds = ids,
                    compoundMembers = LayoutSpecRunner.hasCompoundMembers(g),
                    scope = if (scoped) CompoundLayerScope.of(g) else null,
                ).count { it.code == LayoutCodes.NO_EFFECT }
            count(selfContained(), scoped = true) shouldBe 0
            count(crossing(), scoped = true) shouldBe 1
            count(selfContained(), scoped = false) shouldBe 1
        }

        test("C4 and other kinds keep the old W001 behaviour with a scope") {
            val g = selfContained()
            val scope = CompoundLayerScope.of(g)
            LayoutSpecRunner
                .ignoredOptions(
                    spec = LayoutSpec(wrapColumns = 2),
                    kind = SpecDiagramKind.UML_COMPONENT,
                    compoundMembers = true,
                    scope = scope,
                ) shouldHaveSize 1
            LayoutSpecRunner
                .ignoredOptions(
                    spec = LayoutSpec(wrapColumns = 2),
                    kind = SpecDiagramKind.C4,
                    compoundMembers = true,
                    scope = scope,
                ) shouldHaveSize 1
        }
    })
