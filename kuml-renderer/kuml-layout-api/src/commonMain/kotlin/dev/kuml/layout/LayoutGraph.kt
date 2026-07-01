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
