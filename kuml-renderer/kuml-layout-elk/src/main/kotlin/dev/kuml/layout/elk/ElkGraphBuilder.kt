package dev.kuml.layout.elk

import dev.kuml.layout.GroupId
import dev.kuml.layout.LayerPin
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.PortId
import dev.kuml.layout.PortSide
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.NodeLabelPlacement
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortLabelPlacement
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkLabel
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.eclipse.elk.core.options.PortSide as ElkPortSide

/**
 * Übersetzt einen [LayoutGraph] in einen ELK-Graphen für die Layoutberechnung.
 *
 * Jede Instanz ist für genau einen Layout-Lauf gedacht (Reinheitsprinzip — Z6 aus dem Designentwurf).
 * Keine ELK-Typen verlassen diese Klasse.
 */
internal class ElkGraphBuilder(
    private val graph: LayoutGraph,
) {
    /** Mapping von kUML-NodeId → ELK-Knoten für spätere Ergebnis-Rückübersetzung. */
    val nodeMap: MutableMap<NodeId, ElkNode> = mutableMapOf()

    /** Mapping von kUML-GroupId → ELK-Gruppen-Knoten. */
    val groupMap: MutableMap<GroupId, ElkNode> = mutableMapOf()

    /** Mapping (Knoten, Port) → ELK-Port (C3-1). */
    val portMap: MutableMap<Pair<NodeId, PortId>, ElkPort> = mutableMapOf()

    /** Mapping (Knoten, Port) → ELK-Port-Label (C3-1). */
    val portLabelMap: MutableMap<Pair<NodeId, PortId>, ElkLabel> = mutableMapOf()

    /** Mapping NodeId → externes Knotenlabel (C3-1). */
    val nodeLabelMap: MutableMap<NodeId, ElkLabel> = mutableMapOf()

    /** Mapping EdgeId → (Quell-ELK-Port, Ziel-ELK-Port); Einträge nur für portgebundene Kanten. */
    val edgePorts: MutableMap<dev.kuml.layout.EdgeId, Pair<ElkPort?, ElkPort?>> = mutableMapOf()

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
        // V11.x — Per-Group `layoutAsCompound` opt-in for UML package diagrams.
        // Pre-build a quick lookup so the loop body stays terse.
        val compoundGroupIds: Set<dev.kuml.layout.GroupId> =
            graph.groups
                .filter { it.layoutAsCompound }
                .map { it.id }
                .toSet()

        for (node in graph.nodes) {
            // V2.0.44: Action nodes default to flat-root because ELK compound-
            // node handling places children at RELATIVE positions, but the
            // SysML-2 swimlane post-layout group-bounds computation in
            // ResultMapper relies on ABSOLUTE node positions. Group ELK nodes
            // (built by buildGroups) stay in the graph; they're empty unless
            // a group asks to be treated as a compound below.
            //
            // V11.x exception — UML package groups opt in via
            // [LayoutGroup.layoutAsCompound] = true. ELK then sees the package
            // group as a real compound node containing its classes, which
            // (a) lets ELK lay packages out as disjoint top-level boxes
            //     instead of overlapping each other, and
            // (b) makes the package boundary inherit the natural ELK bounds.
            // ResultMapper.buildNodeLayouts walks up the parent chain to
            // recover absolute positions so the rest of the pipeline (renderer,
            // edge router, group-bounds computation) is unchanged.
            val groupId = node.groupId
            val parent: ElkNode =
                if (groupId != null && groupId in compoundGroupIds) {
                    groupMap[groupId] ?: root
                } else {
                    root
                }
            val elkNode = ElkGraphUtil.createNode(parent)
            elkNode.identifier = node.id.value
            elkNode.width = node.intrinsicSize.width.toDouble()
            elkNode.height = node.intrinsicSize.height.toDouble()
            nodeMap[node.id] = elkNode
            applyLayerHints(elkNode = elkNode, node = node)
            buildPortsAndLabels(elkNode = elkNode, node = node)
        }
    }

    private fun applyLayerHints(
        elkNode: ElkNode,
        node: LayoutNode,
    ) {
        node.hints.layerIndex?.let { elkNode.setProperty(CoreOptions.PARTITIONING_PARTITION, it) }
        node.hints.layerPin?.let {
            elkNode.setProperty(
                LayeredOptions.LAYERING_LAYER_CONSTRAINT,
                when (it) {
                    LayerPin.First -> LayerConstraint.FIRST
                    LayerPin.Last -> LayerConstraint.LAST
                },
            )
        }
    }

    /** Legt ELK-Ports und externe Labels eines Knotens an (C3-1). */
    private fun buildPortsAndLabels(
        elkNode: ElkNode,
        node: LayoutNode,
    ) {
        val external = node.externalLabelSize
        if (external != null) {
            val label = ElkGraphUtil.createLabel(elkNode)
            label.width = external.width.toDouble()
            label.height = external.height.toDouble()
            label.text = node.id.value
            nodeLabelMap[node.id] = label
            elkNode.setProperty(CoreOptions.NODE_LABELS_PLACEMENT, NodeLabelPlacement.outsideTopCenter())
        }
        if (node.ports.isEmpty()) return
        val fixedPositions = node.ports.all { it.offset != null }
        elkNode.setProperty(
            CoreOptions.PORT_CONSTRAINTS,
            if (fixedPositions) PortConstraints.FIXED_POS else PortConstraints.FIXED_ORDER,
        )
        elkNode.setProperty(CoreOptions.PORT_LABELS_PLACEMENT, java.util.EnumSet.of(PortLabelPlacement.OUTSIDE))
        val perSide = node.ports.groupBy { it.side }
        for (port in node.ports.sortedWith(compareBy({ it.side.ordinal }, { it.order }))) {
            val elkPort = ElkGraphUtil.createPort(elkNode)
            elkPort.identifier = port.id.value
            elkPort.width = port.size.width.toDouble()
            elkPort.height = port.size.height.toDouble()
            elkPort.setProperty(CoreOptions.PORT_SIDE, port.side.toElk())
            // ELK nummeriert Ports im Uhrzeigersinn (West/Süd: von unten nach oben bzw. rechts nach links).
            // [LayoutPort.order] ist dagegen in Leserichtung (oben→unten bzw. links→rechts) definiert.
            val siblings = perSide.getValue(port.side)
            val rank = siblings.count { it.order < port.order }
            val index = if (port.side == PortSide.West || port.side == PortSide.South) siblings.size - 1 - rank else rank
            elkPort.setProperty(CoreOptions.PORT_INDEX, index)
            elkPort.setProperty(CoreOptions.PORT_BORDER_OFFSET, -(port.size.width.toDouble() / 2.0))
            if (fixedPositions) {
                val offset = port.offset!!.toDouble()
                when (port.side) {
                    PortSide.West -> {
                        elkPort.x = -port.size.width.toDouble() / 2.0
                        elkPort.y = offset - port.size.height.toDouble() / 2.0
                    }
                    PortSide.East -> {
                        elkPort.x = node.intrinsicSize.width.toDouble() - port.size.width.toDouble() / 2.0
                        elkPort.y = offset - port.size.height.toDouble() / 2.0
                    }
                    PortSide.North -> {
                        elkPort.x = offset - port.size.width.toDouble() / 2.0
                        elkPort.y = -port.size.height.toDouble() / 2.0
                    }
                    PortSide.South -> {
                        elkPort.x = offset - port.size.width.toDouble() / 2.0
                        elkPort.y = node.intrinsicSize.height.toDouble() - port.size.height.toDouble() / 2.0
                    }
                }
            }
            port.labelSize?.let {
                val label = ElkGraphUtil.createLabel(elkPort)
                label.width = it.width.toDouble()
                label.height = it.height.toDouble()
                label.text = port.id.value
                portLabelMap[node.id to port.id] = label
            }
            portMap[node.id to port.id] = elkPort
        }
    }

    private fun PortSide.toElk(): ElkPortSide =
        when (this) {
            PortSide.North -> ElkPortSide.NORTH
            PortSide.East -> ElkPortSide.EAST
            PortSide.South -> ElkPortSide.SOUTH
            PortSide.West -> ElkPortSide.WEST
        }

    private fun buildEdges(root: ElkNode) {
        for (edge in graph.edges) {
            // V3.0.11 — Endpunkt-Auflösung: ein [LayoutEdge] kann auf einen regulären Node
            // ODER auf einen Group-Anker zeigen. In C4-Container-/Component-Diagrammen wird
            // die System-/Container-Boundary über einen Group-Anker mit derselben ID wie das
            // Modell-Element ausgedrückt; dieses Element selbst wird nicht als eigener Node
            // im LayoutGraph emittiert, sondern als Group. Eine Relationship
            // `System → ExternalSystem` hat daher den Anker als Endpunkt, der nicht im
            // [nodeMap] auftaucht, sondern im [groupMap]. Ohne diesen Fallback fielen alle
            // Relationships zur Boundary geräuschlos heraus (Symptom: leere `<g id="edges">`-
            // Gruppe trotz korrekt aufgebauter `diagram.relationships`).
            val sourceNode = resolveEndpoint(edge.source.nodeId) ?: continue
            val targetNode = resolveEndpoint(edge.target.nodeId) ?: continue
            val sourcePort = edge.source.portId?.let { portMap[edge.source.nodeId to it] }
            val targetPort = edge.target.portId?.let { portMap[edge.target.nodeId to it] }
            val elkEdge =
                if (sourcePort != null || targetPort != null) {
                    ElkGraphUtil.createEdge(null).also {
                        it.sources.add(sourcePort ?: sourceNode)
                        it.targets.add(targetPort ?: targetNode)
                    }
                } else {
                    ElkGraphUtil.createSimpleEdge(sourceNode, targetNode)
                }
            elkEdge.identifier = edge.id.value
            if (sourcePort != null || targetPort != null) edgePorts[edge.id] = sourcePort to targetPort
            // updateContainment ensures the edge is in the correct containing node
            ElkGraphUtil.updateContainment(elkEdge)
            edgeIdToElkEdge[edge.id] = elkEdge
        }
    }

    /**
     * Findet den ELK-Knoten für die gegebene [NodeId]. Sucht erst unter den regulären
     * [nodeMap]-Einträgen; fällt — falls die ID gleichzeitig die einer Group ist (Anker-
     * Konvention von C4-Diagrammen) — auf den Compound-Node aus [groupMap] zurück.
     */
    private fun resolveEndpoint(nodeId: NodeId): ElkNode? = nodeMap[nodeId] ?: groupMap[GroupId(nodeId.value)]

    /** Liefert alle [LayoutNode]-Objekte des ursprünglichen Graphen. */
    fun nodes(): List<LayoutNode> = graph.nodes

    /** Liefert alle [LayoutGroup]-Objekte des ursprünglichen Graphen. */
    fun groups(): List<LayoutGroup> = graph.groups

    /** Liefert alle [LayoutEdge]-Objekte des ursprünglichen Graphen. */
    fun edges(): List<LayoutEdge> = graph.edges
}
