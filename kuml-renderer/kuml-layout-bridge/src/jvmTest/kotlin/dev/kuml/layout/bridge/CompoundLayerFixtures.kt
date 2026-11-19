package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlPackage

/**
 * Nacharbeit 115: shared fixtures for the layer-hint tests of class, component and deployment diagrams. Fixed node
 * sizes, geometry only, independent of fonts. Every fixture has a self-contained container (`C1`) with the members
 * [members] (`M1`..`M6` linked as a tree, plus the loose `X1`, `X2`), and variants with a crossing relationship, a nested
 * container and an association class.
 */
internal object CompoundLayerFixtures {
    val fixed = SizeProvider { _, _ -> Size(width = 140f, height = 60f) }

    val members: List<String> = (1..6).map { "M$it" } + listOf("X1", "X2")
    private val tree = listOf("M1" to "M2", "M2" to "M3", "M2" to "M4", "M2" to "M5", "M2" to "M6")

    /** The three kinds that honour layer hints inside a self-contained compound since Nacharbeit 115. */
    enum class Flavour(
        val kind: SpecDiagramKind,
    ) {
        CLASS(SpecDiagramKind.UML_CLASS),
        COMPONENT(SpecDiagramKind.UML_COMPONENT),
        DEPLOYMENT(SpecDiagramKind.UML_DEPLOYMENT),
    }

    fun assoc(
        id: String,
        a: String,
        b: String,
    ) = UmlAssociation(id = id, ends = listOf(UmlAssociationEnd(typeId = a), UmlAssociationEnd(typeId = b)))

    private fun dep(
        id: String,
        a: String,
        b: String,
    ) = UmlDependency(id = id, clientId = a, supplierId = b)

    private fun leaf(
        flavour: Flavour,
        id: String,
    ): UmlNamedElement =
        when (flavour) {
            Flavour.CLASS -> UmlClass(id = id, name = id)
            Flavour.COMPONENT -> UmlComponent(id = id, name = id)
            Flavour.DEPLOYMENT -> UmlArtifact(id = id, name = id)
        }

    private fun link(
        flavour: Flavour,
        id: String,
        a: String,
        b: String,
    ): KumlElement = if (flavour == Flavour.DEPLOYMENT) dep(id = id, a = a, b = b) else assoc(id = id, a = a, b = b)

    private fun inner(flavour: Flavour): List<KumlElement> =
        tree.mapIndexed { i, (a, b) -> link(flavour = flavour, id = "a$i", a = a, b = b) }

    /** The container `C1` with [members]: a package (class, component) or a deployment node holding artifacts. */
    private fun container(
        flavour: Flavour,
        extra: List<UmlNamedElement> = emptyList(),
    ): KumlElement {
        val all = members.map { leaf(flavour = flavour, id = it) } + extra
        return if (flavour == Flavour.DEPLOYMENT) {
            UmlNode(id = "C1", name = "C1", artifacts = all.filterIsInstance<UmlArtifact>(), children = all.filterNot { it is UmlArtifact })
        } else {
            UmlPackage(id = "C1", name = "C1", members = all)
        }
    }

    fun graphOf(elements: List<KumlElement>): LayoutGraph =
        UmlLayoutBridge.toLayoutGraph(diagram = KumlDiagram(name = "D", elements = elements), sizeProvider = fixed)

    /** C1 is self-contained; a second container with two linked members sits next to it, unrelated. */
    fun selfContained(flavour: Flavour): LayoutGraph {
        val second: KumlElement =
            if (flavour == Flavour.DEPLOYMENT) {
                UmlNode(
                    id = "C2",
                    name = "C2",
                    artifacts = listOf(UmlArtifact(id = "P1", name = "P1"), UmlArtifact(id = "P2", name = "P2")),
                )
            } else {
                UmlPackage(id = "C2", name = "C2", members = listOf(leaf(flavour = flavour, id = "P1"), leaf(flavour = flavour, id = "P2")))
            }
        return graphOf(
            listOf(container(flavour = flavour), second, link(flavour = flavour, id = "ap", a = "P1", b = "P2")) + inner(flavour),
        )
    }

    /** One relationship from member M1 to an element O1 outside C1: C1 is no longer self-contained. */
    fun crossing(flavour: Flavour): LayoutGraph =
        graphOf(
            listOf(
                container(flavour = flavour),
                leaf(flavour = flavour, id = "O1"),
                link(flavour = flavour, id = "ax", a = "M1", b = "O1"),
            ) +
                inner(flavour),
        )

    /** C1 contains a nested container N1 (a sub-package, a nested deployment node): hints stay reported. */
    fun nested(flavour: Flavour): LayoutGraph {
        val nestedMember: KumlElement =
            if (flavour == Flavour.DEPLOYMENT) {
                UmlNode(id = "N1", name = "N1", artifacts = listOf(UmlArtifact(id = "Q1", name = "Q1")))
            } else {
                UmlPackage(id = "N1", name = "N1", members = listOf(leaf(flavour = flavour, id = "Q1")))
            }
        return if (flavour == Flavour.DEPLOYMENT) {
            val all = members.map { UmlArtifact(id = it, name = it) }
            graphOf(
                listOf<KumlElement>(
                    UmlNode(id = "C1", name = "C1", artifacts = all, children = listOf(nestedMember as UmlNode)),
                ) + inner(flavour),
            )
        } else {
            graphOf(
                listOf<KumlElement>(
                    UmlPackage(
                        id = "C1",
                        name = "C1",
                        members =
                            members.map { leaf(flavour = flavour, id = it) } + (nestedMember as UmlNamedElement),
                    ),
                ) + inner(flavour),
            )
        }
    }

    /** Class diagrams only: an association class between two members of C1 sits outside the package (groupId = null). */
    fun withAssociationClass(): LayoutGraph =
        graphOf(
            listOf(
                container(flavour = Flavour.CLASS),
                UmlAssociationClass(
                    id = "AC",
                    name = "AC",
                    ends = listOf(UmlAssociationEnd(typeId = "M1"), UmlAssociationEnd(typeId = "M3")),
                ),
            ) + inner(Flavour.CLASS),
        )

    fun run(
        flavour: Flavour,
        graph: LayoutGraph,
        spec: LayoutSpec?,
    ): SpecLayoutOutcome =
        LayoutSpecRunner.layout(
            engine = ElkLayoutEngine(),
            graph = graph,
            hints = LayoutHints(),
            spec = spec,
            kind = flavour.kind,
            diagramName = "D",
        )

    fun y(
        r: LayoutResult,
        id: String,
    ): Float =
        r.nodes
            .getValue(NodeId(id))
            .bounds.origin.y

    fun boundsOf(
        result: LayoutResult,
        ids: List<String>,
    ) = ids.map { it to result.nodes.getValue(NodeId(it)).bounds }
}
