package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlRelationship

/**
 * Übersetzt ein UML-Diagramm in einen [LayoutGraph].
 *
 * Liest `kuml.layout.*` aus den Element-`metadata`-Maps und materialisiert
 * sie als [LayoutNode.hints] (NodeHints). Beziehungen werden zu [LayoutEdge].
 * UML-Packages werden zu [LayoutGroup]s (V1: keine Verschachtelung).
 *
 * Diagrammtypen: Class, Component, UseCase, State. Sequenzdiagramme sind
 * nicht im Scope — sie haben ihre eigene Pipeline.
 *
 * Beispiel:
 * ```kotlin
 * val graph = UmlLayoutBridge.toLayoutGraph(diagram)
 * ```
 */
public object UmlLayoutBridge {
    /**
     * Übersetzt [diagram] in einen [LayoutGraph].
     *
     * Elemente in [KumlDiagram.elements] werden wie folgt verarbeitet:
     * - [UmlPackage] → [LayoutGroup] (parent = null, V1: keine Verschachtelung);
     *   direkte Members werden zu [LayoutNode] mit gesetztem groupId.
     * - Weitere [UmlNamedElement]-Subtypen (außer [UmlPackage]) → [LayoutNode].
     * - [UmlRelationship]-Subtypen → [LayoutEdge] (EdgeHints.NONE in V1).
     * - Alles andere wird schweigend ignoriert.
     *
     * @param diagram Das zu übersetzende UML-Diagramm.
     * @param sizeProvider Liefert die intrinsische Größe pro Knoten.
     */
    public fun toLayoutGraph(
        diagram: KumlDiagram,
        sizeProvider: SizeProvider = SizeProvider.constant(),
    ): LayoutGraph {
        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        for (element in diagram.elements) {
            when (element) {
                is UmlPackage -> {
                    // V1: max. 1 Ebene — Package wird zur Group, Members zu Nodes
                    val groupId = GroupId(element.id)
                    groups.add(LayoutGroup(id = groupId, parent = null))
                    for (member in element.members) {
                        when (member) {
                            is UmlPackage -> {
                                // Sub-Package: eigene eigenständige Group ohne parent (V1: keine Verschachtelung)
                                val subGroupId = GroupId(member.id)
                                groups.add(LayoutGroup(id = subGroupId, parent = null))
                                for (subMember in member.members) {
                                    if (subMember !is UmlPackage && subMember !is UmlRelationship) {
                                        nodes.add(
                                            LayoutNode(
                                                id = NodeId(subMember.id),
                                                intrinsicSize =
                                                    sizeProvider.sizeOf(
                                                        subMember.id,
                                                        subMember::class.simpleName ?: "Unknown",
                                                    ),
                                                hints = HintsReader.read(subMember.metadata),
                                                groupId = subGroupId,
                                            ),
                                        )
                                    }
                                }
                            }
                            is UmlRelationship -> {
                                // Relationships inside packages are treated as edges
                                val endpoints = EndpointResolver.resolve(member)
                                if (endpoints != null) {
                                    edges.add(
                                        LayoutEdge(
                                            id = EdgeId(member.id),
                                            source = EndpointRef(nodeId = NodeId(endpoints.first)),
                                            target = EndpointRef(nodeId = NodeId(endpoints.second)),
                                            hints = EdgeHints.NONE,
                                        ),
                                    )
                                }
                            }
                            else -> {
                                // Regular named element inside a package
                                nodes.add(
                                    LayoutNode(
                                        id = NodeId(member.id),
                                        intrinsicSize =
                                            sizeProvider.sizeOf(
                                                member.id,
                                                member::class.simpleName ?: "Unknown",
                                            ),
                                        hints = HintsReader.read(member.metadata),
                                        groupId = groupId,
                                    ),
                                )
                            }
                        }
                    }
                }
                is UmlRelationship -> {
                    val endpoints = EndpointResolver.resolve(element)
                    if (endpoints != null) {
                        edges.add(
                            LayoutEdge(
                                id = EdgeId(element.id),
                                source = EndpointRef(nodeId = NodeId(endpoints.first)),
                                target = EndpointRef(nodeId = NodeId(endpoints.second)),
                                hints = EdgeHints.NONE,
                            ),
                        )
                    }
                }
                is UmlNamedElement -> {
                    // Any other named element (classifier, state machine vertex, etc.)
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize =
                                sizeProvider.sizeOf(
                                    element.id,
                                    element::class.simpleName ?: "Unknown",
                                ),
                            hints = HintsReader.read(element.metadata),
                            groupId = null,
                        ),
                    )
                }
                else -> {
                    // Non-UML or non-named elements: silently ignored
                }
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }
}
