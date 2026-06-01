package dev.kuml.layout.bridge

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

        // Process element IDs listed in the diagram
        for (elementId in diagram.elements) {
            val element = elementIndex[elementId]
            if (element == null) {
                // Unresolvable ID: silently skip
                continue
            }

            // Determine group membership: element belongs to the group anchor if applicable
            val nodeGroupId: GroupId? =
                when {
                    groupId != null && isChildOfGroup(diagram, element.id) -> groupId
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
     * Determines whether an element with [elementId] is a child of the group anchor
     * defined by [diagram].
     *
     * - [ContainerDiagram]: element is a child if it appears in diagram.elements and
     *   the diagram group is the system — all diagram elements are treated as children.
     * - [ComponentDiagram]: similarly, all diagram elements belong to the container group.
     */
    private fun isChildOfGroup(
        diagram: C4Diagram,
        elementId: String,
    ): Boolean {
        // For ContainerDiagram: the group anchor is the system itself; elements in
        // the diagram that are NOT the system anchor are children of the group.
        // For ComponentDiagram: the group anchor is the container; elements that are
        // NOT the container anchor are children.
        val anchorId: String =
            when (diagram) {
                is ContainerDiagram -> diagram.system
                is ComponentDiagram -> diagram.container
                else -> return false
            }
        // The anchor element itself is rendered as the group, not a node inside it
        return elementId != anchorId
    }
}
