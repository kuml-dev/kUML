package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId

/**
 * Übersetzt ein C4-Diagramm in einen [LayoutGraph].
 *
 * Im Gegensatz zur UML-Variante referenziert ein C4-Diagramm seine Elemente
 * nur per ID. Daher braucht die Bridge zusätzlich das umgebende [C4Model] als
 * Lookup-Kontext.
 *
 * Diagrammtypen: SystemContext, Container, Component, Landscape, Deployment.
 * Dynamic-Diagramme sind nicht im Scope.
 *
 * Gruppen-Heuristik je Diagrammtyp:
 * - [ContainerDiagram]: `diagram.system` ist die einzige [LayoutGroup].
 * - [ComponentDiagram]: `diagram.container` ist die einzige [LayoutGroup].
 * - Alle anderen: keine Group.
 *
 * Nicht-auflösbare IDs werden schweigend übersprungen (Modell-Inkonsistenz,
 * kein Bridge-Fehler).
 *
 * Beispiel:
 * ```kotlin
 * val graph = C4LayoutBridge.toLayoutGraph(containerDiagram, model)
 * ```
 */
public object C4LayoutBridge {
    /**
     * Übersetzt [diagram] mithilfe von [model] als Lookup-Kontext in einen [LayoutGraph].
     *
     * @param diagram Das zu übersetzende C4-Diagramm.
     * @param model Das umgebende C4-Modell mit allen Elementen und Beziehungen.
     * @param sizeProvider Liefert die intrinsische Größe pro Knoten.
     */
    public fun toLayoutGraph(
        diagram: C4Diagram,
        model: C4Model,
        sizeProvider: SizeProvider = SizeProvider.constant(),
    ): LayoutGraph {
        // Build a flat lookup map from all elements in the model
        val elementIndex = model.elements.associateBy { it.id }
        // model.relationships is List<C4Relationship> — index by ID for O(1) lookup
        val relationshipIndex = model.relationships.associateBy { it.id }

        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        // Determine group anchor from diagram type (V1: max. 1 Group)
        val groupId: GroupId? =
            when (diagram) {
                is ContainerDiagram -> GroupId(diagram.system)
                is ComponentDiagram -> GroupId(diagram.container)
                else -> null
            }

        if (groupId != null) {
            groups.add(LayoutGroup(id = groupId, parent = null))
        }

        // V2.0.44: anchor-element ID is rendered AS the boundary itself, never
        // as a node inside it. Skip it from the node-emission loop.
        val anchorId: String? =
            when (diagram) {
                is ContainerDiagram -> diagram.system
                is ComponentDiagram -> diagram.container
                else -> null
            }

        // Process element IDs listed in the diagram
        for (elementId in diagram.elements) {
            if (elementId == anchorId) continue
            val element = elementIndex[elementId]
            if (element == null) {
                // Unresolvable ID: silently skip
                continue
            }

            // Determine group membership: element belongs to the group anchor if applicable
            val nodeGroupId: GroupId? =
                when {
                    groupId != null && isChildOfGroup(diagram, element) -> groupId
                    else -> null
                }

            nodes.add(
                LayoutNode(
                    id = NodeId(element.id),
                    intrinsicSize =
                        sizeProvider.sizeOf(
                            element.id,
                            element::class.simpleName ?: "Unknown",
                        ),
                    hints = HintsReader.read(element.metadata),
                    groupId = nodeGroupId,
                ),
            )
        }

        // Process relationship IDs listed in the diagram
        for (relId in diagram.relationships) {
            val rel = relationshipIndex[relId]
            if (rel == null) {
                // Unresolvable ID: silently skip
                continue
            }

            // Only emit edge if both endpoints are present in the element index
            if (elementIndex[rel.source] == null || elementIndex[rel.target] == null) {
                // Unresolvable endpoint: silently skip
                continue
            }

            edges.add(
                LayoutEdge(
                    id = EdgeId(rel.id),
                    source = EndpointRef(nodeId = NodeId(rel.source)),
                    target = EndpointRef(nodeId = NodeId(rel.target)),
                    hints = EdgeHints.NONE,
                ),
            )
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }

    /**
     * Determines whether [element] is a child of the group anchor defined by [diagram].
     *
     * V2.0.44: the previous implementation returned true for any element other
     * than the anchor itself, which inflated the system-boundary group to also
     * enclose external software systems and persons. The boundary then visually
     * "swallowed" everything in the diagram. We now check the actual structural
     * relationship: a [C4Container] belongs to the system boundary iff its
     * [C4Container.system] matches the diagram's `system`; a [C4Component] belongs
     * to the container boundary iff its parent container matches `diagram.container`.
     * Anything else (external systems, persons, peer containers, etc.) renders
     * outside the boundary.
     */
    private fun isChildOfGroup(
        diagram: C4Diagram,
        element: dev.kuml.c4.model.C4Element,
    ): Boolean =
        when (diagram) {
            is ContainerDiagram ->
                element is C4Container && element.system == diagram.system
            is ComponentDiagram ->
                element is C4Component && element.container == diagram.container
            else -> false
        }
}
