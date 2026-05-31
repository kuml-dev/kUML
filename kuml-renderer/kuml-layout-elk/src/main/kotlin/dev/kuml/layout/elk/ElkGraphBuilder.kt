package dev.kuml.layout.elk

import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Übersetzt einen [LayoutGraph] in einen ELK-Graphen für die Layoutberechnung.
 *
 * Jede Instanz ist für genau einen Layout-Lauf gedacht (Reinheitsprinzip — Z6 aus dem Designentwurf).
 * Keine ELK-Typen verlassen diese Klasse.
 */
internal class ElkGraphBuilder(private val graph: LayoutGraph) {
    /** Mapping von kUML-NodeId → ELK-Knoten für spätere Ergebnis-Rückübersetzung. */
    val nodeMap: MutableMap<NodeId, ElkNode> = mutableMapOf()

    /** Mapping von kUML-GroupId → ELK-Gruppen-Knoten. */
    val groupMap: MutableMap<GroupId, ElkNode> = mutableMapOf()

    /** Mapping von kUML-EdgeId → ELK-Kante. */
    val edgeIdToElkEdge: MutableMap<dev.kuml.layout.EdgeId, ElkEdge> = mutableMapOf()

    /**
     * Baut den vollständigen ELK-Graphen aus dem [LayoutGraph].
     *
     * @return Root-Knoten des ELK-Graphen (enthält alle Knoten und Kanten).
     */
    fun build(): ElkNode {
        val root = ElkGraphUtil.createGraph()
        root.identifier = "root"

        // 1. Gruppen als hierarchische Eltern-Knoten anlegen (top-level zuerst)
        buildGroups(root)

        // 2. Knoten anlegen — entweder unter ihrer Gruppe oder direkt unter root
        buildNodes(root)

        // 3. Kanten anlegen
        buildEdges(root)

        return root
    }

    private fun buildGroups(root: ElkNode) {
        // Iteriere in Breiten-First-Reihenfolge damit Eltern vor Kindern angelegt werden
        val topLevel = graph.groups.filter { it.parent == null }
        val queue = ArrayDeque<LayoutGroup>()
        queue.addAll(topLevel)

        while (queue.isNotEmpty()) {
            val group = queue.removeFirst()
            val parent = group.parent?.let { groupMap[it] } ?: root
            val elkGroup = ElkGraphUtil.createNode(parent)
            elkGroup.identifier = group.id.value
            groupMap[group.id] = elkGroup

            // Enqueue children
            graph.groups.filter { it.parent == group.id }.forEach { queue.add(it) }
        }
    }

    private fun buildNodes(root: ElkNode) {
        for (node in graph.nodes) {
            val parent = node.groupId?.let { groupMap[it] } ?: root
            val elkNode = ElkGraphUtil.createNode(parent)
            elkNode.identifier = node.id.value
            elkNode.width = node.intrinsicSize.width.toDouble()
            elkNode.height = node.intrinsicSize.height.toDouble()
            nodeMap[node.id] = elkNode
        }
    }

    private fun buildEdges(root: ElkNode) {
        for (edge in graph.edges) {
            val sourceNode = nodeMap[edge.source.nodeId] ?: continue
            val targetNode = nodeMap[edge.target.nodeId] ?: continue
            val elkEdge = ElkGraphUtil.createSimpleEdge(sourceNode, targetNode)
            elkEdge.identifier = edge.id.value
            // updateContainment ensures the edge is in the correct containing node
            ElkGraphUtil.updateContainment(elkEdge)
            edgeIdToElkEdge[edge.id] = elkEdge
        }
    }

    /** Liefert alle [LayoutNode]-Objekte des ursprünglichen Graphen. */
    fun nodes(): List<LayoutNode> = graph.nodes

    /** Liefert alle [LayoutGroup]-Objekte des ursprünglichen Graphen. */
    fun groups(): List<LayoutGroup> = graph.groups

    /** Liefert alle [LayoutEdge]-Objekte des ursprünglichen Graphen. */
    fun edges(): List<LayoutEdge> = graph.edges
}
