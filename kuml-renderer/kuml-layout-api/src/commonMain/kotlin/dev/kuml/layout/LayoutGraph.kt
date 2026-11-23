package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Engine-agnostische Sicht auf einen Diagrammgraphen.
 *
 * Wird vom Diagramm-Builder erzeugt. Die Engine kennt keine UML- oder C4-Semantik —
 * sie sieht ausschließlich Knoten, Kanten und Gruppen mit geometrischen Hints.
 */
@Serializable
public data class LayoutGraph(
    val nodes: List<LayoutNode>,
    val edges: List<LayoutEdge>,
    val groups: List<LayoutGroup> = emptyList(),
    /**
     * Group memberships the bridge dropped because a node can stand in one group only (Nacharbeit 55, SysML-2-REQ):
     * the first group in declaration order wins. The layout reports each entry as `KUML-LAYOUT-W007`.
     */
    val ignoredMemberships: List<IgnoredMembership> = emptyList(),
)

/** A node that is a member of [ignoredGroup] as well, but stands in [keptGroup] (the first one in declaration order). */
@Serializable
public data class IgnoredMembership(
    val nodeId: NodeId,
    val keptGroup: GroupId,
    val ignoredGroup: GroupId,
)

/**
 * Ein Knoten im Layout-Graphen mit vorab gemessener intrinsischer Größe.
 *
 * [intrinsicSize] wird vom Renderer geliefert (Text-Metriken kennt nur der Renderer).
 * Die Engine darf Knoten nicht skalieren, nur positionieren.
 */
@Serializable
public data class LayoutNode(
    val id: NodeId,
    val intrinsicSize: Size,
    val hints: NodeHints = NodeHints.NONE,
    val groupId: GroupId? = null,
    /** Ports am Knotenrand (C3-1). Engines mit [LayoutCapabilities.supportsPorts] verankern Kanten daran. */
    val ports: List<LayoutPort> = emptyList(),
    /**
     * Optionale Größe eines externen Namens-Labels (OUTSIDE, oben zentriert), z. B. Fork/Join-Name.
     * Die Engine reserviert dafür Platz; die berechnete Position steht in [NodeLayout.labels] unter `"node"`.
     */
    val externalLabelSize: Size? = null,
)

/** Seite eines Knotenrands, an dem ein [LayoutPort] andockt. */
@Serializable
public enum class PortSide { North, East, South, West }

/**
 * Ein Port am Rand eines [LayoutNode].
 *
 * @property order Reihenfolge entlang der Seite (feste Port-Reihenfolge, [PortSide]-weise aufsteigend).
 * @property size Größe des Port-Markers; der Port-Mittelpunkt liegt auf der Knotenkante.
 * @property labelSize Größe des Port-Labels (OUTSIDE); reserviert Platz, die Position steht in
 *     [NodeLayout.labels] unter `"port:<id>"`.
 * @property offset Feste Lage des Port-Mittelpunkts entlang der Seite (Abstand vom oberen bzw. linken Rand des Knotens).
 *     Haben **alle** Ports eines Knotens einen [offset], platziert die Engine sie dort (feste Positionen, [order] und
 *     Gleichverteilung entfallen); sonst bleibt es bei der Reihenfolge-Verteilung.
 */
@Serializable
public data class LayoutPort(
    val id: PortId,
    val side: PortSide,
    val order: Int,
    val size: Size = Size(width = 8f, height = 8f),
    val labelSize: Size? = null,
    val offset: Float? = null,
)

/**
 * Eine gerichtete Kante zwischen zwei Endpunkten im Layout-Graphen.
 *
 * [source] und [target] können entweder Knoten oder Ports referenzieren
 * (siehe [EndpointRef]).
 */
@Serializable
public data class LayoutEdge(
    val id: EdgeId,
    val source: EndpointRef,
    val target: EndpointRef,
    val hints: EdgeHints = EdgeHints.NONE,
    /**
     * Estimated size of the edge's centre label (name / stereotype block). The engine reserves room for it next to the
     * line, so the label placer finds a free spot (PAL wave 2B-b). `null` = the edge carries no centre label.
     */
    val centerLabel: Size? = null,
)

/**
 * Eine Gruppe (Paket, Container, Subgraph) zur hierarchischen Strukturierung von Knoten.
 *
 * Gruppen können verschachtelt werden ([parent]). Die Engine garantiert, dass
 * Kindknoten innerhalb der berechneten Gruppen-Bounds liegen.
 *
 * **layoutAsCompound** (V11.x — Package-Diagramm-Fix): wenn `true`, werden
 * Member-Knoten dieser Gruppe in der ELK-Engine als Kinder eines Compound-
 * Knotens gelayoutet (statt flach unter `root`). Das verhindert, dass
 * mehrere Top-Level-Groups sich überlappen, wenn ELK ihre Member als
 * gleichwertige Geschwister behandelt. Default `false`, weil die SysML-2-
 * Swimlane-Pipeline (V2.0.44+) bewusst auf flache Root-Member angewiesen
 * ist und ihre Group-Bounds nachträglich aus den absoluten Kindkoordinaten
 * berechnet. UML-Paketdiagramme setzen das Flag in [dev.kuml.layout.bridge.UmlLayoutBridge].
 */
@Serializable
public data class LayoutGroup(
    val id: GroupId,
    val parent: GroupId? = null,
    val padding: Insets = Insets.ZERO,
    val layoutAsCompound: Boolean = false,
    /**
     * Optionaler unterer Grenzwert für die von der Engine berechnete Gruppen-Größe
     * (inklusive [padding]). Nur relevant für compound-Gruppen ([layoutAsCompound] =
     * true) — bei ELK sorgt das dafür, dass die Container-Box nie kleiner wird als
     * [minSize], selbst wenn die Kindknoten weniger Platz beanspruchen würden.
     * Wird benötigt, wenn die Container-Box selbst ein (zentriertes) Text-Label
     * trägt, dessen Breite unabhängig von den Kindknoten ist — z. B. der Titel
     * eines expandierten BPMN-SubProcess-Rahmens, siehe
     * [dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge]. `null` (Default) bedeutet:
     * kein Mindestmaß, Verhalten unverändert (Engine-Default).
     */
    val minSize: Size? = null,
    /**
     * Group-lokale Spaltenzahl des Member-Rasters (Nacharbeit 49, SysML-2-REQ-Gruppenraster). `null` = Wert des
     * Diagramm-Layouts. Wird nur vom Gruppenraster der Requirement-Diagramme ausgewertet.
     */
    val wrapColumns: Int? = null,
    /**
     * Room the group frame reserves below its content for the notes only this group owns (Nacharbeit 55, SysML-2-REQ
     * group grid): width = widest note, height = top gap plus the stacked notes. `null` = nothing reserved.
     */
    val noteStrip: Size? = null,
    /**
     * Estimated widths (px) of the header texts a state-machine composite draws inside its frame (Nacharbeit 58): the
     * centred title and the widest action line (left-aligned below the header divider). `null` = not a composite with
     * header texts. Read by the header-band edge avoidance only; engines ignore it.
     */
    val headerTextWidths: HeaderTextWidths? = null,
    /**
     * Flow direction of the members of this group (Nacharbeit 106). Only honoured for compound groups
     * ([layoutAsCompound] = true) that are self-contained: no edge may connect a member (or the group itself, or a
     * nested group) with an element outside. Otherwise the layered engine cannot route the edge and the group keeps
     * the diagram direction; the engine then reports the warning `group.direction.crossEdges`. `null` (default) =
     * the group follows the diagram direction, output unchanged.
     */
    val direction: LayoutDirection? = null,
    /**
     * Nacharbeit 107: the members' layer hints ([NodeHints.layerIndex] / [NodeHints.layerPin]) are honoured inside this
     * compound when it and all groups nested in it are self-contained (see [blockingEdgeInSubtree], Nacharbeit 119); the
     * engine then lays it out and its nested groups separately from the diagram. `false` (default) = unchanged: the hints are ignored for compound members.
     */
    val memberLayerHints: Boolean = false,
)

/**
 * Nacharbeit 107 (moved here from the ELK mapper, Nacharbeit 106): the first edge (declaration order) that crosses the
 * border of [group], or `null` when the group is self-contained. Members are the nodes of the group and of its nested
 * groups, plus the nested groups themselves (an edge may end at their anchor). An edge at the group's own anchor does
 * not count when its other end is also a non-member (anchor to a sibling), but an edge from the anchor to one of its own
 * members does. An edge crosses when exactly one end is a member; edges with an unknown endpoint never count.
 * Iterative with a visited set, so a cyclic parent chain cannot loop.
 */
public fun LayoutGraph.crossingEdgeOf(group: LayoutGroup): EdgeId? {
    val groupIds = descendantGroupIds(group)
    val allGroupNames = groups.map { it.id.value }.toSet()
    val allNodeNames = nodes.map { it.id.value }.toSet()
    val members =
        nodes.filter { it.groupId in groupIds }.map { it.id.value }.toSet() + (groupIds - group.id).map { it.value }
    for (edge in edges) {
        val s = edge.source.nodeId.value
        val t = edge.target.nodeId.value
        if ((s !in allNodeNames && s !in allGroupNames) || (t !in allNodeNames && t !in allGroupNames)) continue
        if ((s in members) != (t in members)) return edge.id
    }
    return null
}

/** [group] and all groups nested in it (any depth). Iterative with a visited set, so a cyclic parent chain cannot loop. */
private fun LayoutGraph.descendantGroupIds(group: LayoutGroup): Set<GroupId> {
    val groupIds = mutableSetOf(group.id)
    var grew = true
    while (grew) {
        grew = false
        for (g in groups) if (g.parent in groupIds && groupIds.add(g.id)) grew = true
    }
    return groupIds
}

/** The groups nested in [group] at any depth, in declaration order ([group] itself excluded). */
public fun LayoutGraph.descendantGroups(group: LayoutGroup): List<LayoutGroup> {
    val ids = descendantGroupIds(group) - group.id
    return groups.filter { it.id in ids }
}

/** An edge that crosses the border of [group] (the group whose border it crosses). */
public class BlockingEdge(
    public val edge: EdgeId,
    public val group: GroupId,
)

/**
 * Nacharbeit 119: the first edge (declaration order) that crosses the border of [group] or of any group nested in it
 * (any depth), together with the group whose border it crosses; `null` when the whole subtree is self-contained. The
 * group's own border is checked first. Used where nested groups are laid out separately: ELK cannot route an edge
 * across the border of a separately laid out compound, so every group of the subtree must be self-contained.
 */
public fun LayoutGraph.blockingEdgeInSubtreeDetailed(group: LayoutGroup): BlockingEdge? {
    crossingEdgeOf(group)?.let { return BlockingEdge(edge = it, group = group.id) }
    for (nested in descendantGroups(group)) {
        crossingEdgeOf(nested)?.let { return BlockingEdge(edge = it, group = nested.id) }
    }
    return null
}

/** The edge of [blockingEdgeInSubtreeDetailed], or `null` when the whole subtree of [group] is self-contained. */
public fun LayoutGraph.blockingEdgeInSubtree(group: LayoutGroup): EdgeId? = blockingEdgeInSubtreeDetailed(group)?.edge

/** Estimated widths of a composite frame's header texts, see [LayoutGroup.headerTextWidths]. */
@Serializable
public data class HeaderTextWidths(
    val title: Float,
    val actions: Float,
    /**
     * Extra room (px) the group reserves between its header band and the children so that edges from outside can
     * enter from the side below the header (PAL wave E1). It is part of the group padding but not of the header.
     */
    val sideEntryGap: Float = 0f,
)

/**
 * Referenz auf einen Knoten oder einen Port als Endpunkt einer Kante.
 *
 * Ist [portId] null, ist das Routing knotengebunden; andernfalls portgebunden.
 */
@Serializable
public data class EndpointRef(
    val nodeId: NodeId,
    val portId: PortId? = null,
)
