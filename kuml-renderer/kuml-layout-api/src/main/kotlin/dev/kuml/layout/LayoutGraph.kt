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
)

/**
 * Eine Gruppe (Paket, Container, Subgraph) zur hierarchischen Strukturierung von Knoten.
 *
 * Gruppen können verschachtelt werden ([parent]). Die Engine garantiert, dass
 * Kindknoten innerhalb der berechneten Gruppen-Bounds liegen.
 */
@Serializable
public data class LayoutGroup(
    val id: GroupId,
    val parent: GroupId? = null,
    val padding: Insets = Insets.ZERO,
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
