package dev.kuml.layout.grid

import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect

/**
 * Liefert für jeden Endpunkt einer Kante den Anfass-Punkt am Knotenrand.
 *
 * Strategie (V1.1.12 MVP):
 *  - Wenn der Endpunkt einen [PortId] trägt UND der Knoten diesen Port in
 *    seiner Layout-Map definiert, wird die Port-Koordinate verwendet.
 *  - Sonst wird der Schnittpunkt der Verbindungslinie (Center-zu-Center) mit
 *    dem Knotenrand berechnet — das gibt eine saubere Verbindung am Knotenrand,
 *    egal von welcher Seite die Kante kommt.
 *
 * Channel-Routing und automatische Port-Reservierung (mehrere Kanten am
 * selben Knoten verteilen) sind V1.2-Thema.
 */
internal fun endpointPoint(
    endpoint: EndpointRef,
    nodeLayouts: Map<NodeId, NodeLayout>,
    counterpartCenter: Point,
): Point {
    val nl =
        nodeLayouts[endpoint.nodeId]
            ?: error("EndpointRef references unknown node '${endpoint.nodeId.value}'")
    val portCoord = endpoint.portId?.let { nl.ports[it] }
    if (portCoord != null) return portCoord
    return edgeIntersection(nl.bounds, counterpartCenter)
}

/**
 * Berechnet den Schnittpunkt der Verbindungslinie zwischen [box]-Zentrum und
 * [target] mit dem Rand des [box]-Rechtecks. Wenn [target] innerhalb der Box
 * liegt, wird das Zentrum zurückgegeben.
 *
 * Verwendet die Standard-Liang-Barsky-Verschnittformel auf dem Halbstrahl
 * Center→Target, projiziert auf alle vier Box-Seiten und wählt den Treffer
 * mit dem kleinsten positiven `t`-Parameter.
 */
internal fun edgeIntersection(
    box: Rect,
    target: Point,
): Point {
    val cx = box.origin.x + box.size.width / 2f
    val cy = box.origin.y + box.size.height / 2f
    val dx = target.x - cx
    val dy = target.y - cy
    if (dx == 0f && dy == 0f) return Point(cx, cy)
    val left = box.origin.x
    val right = box.origin.x + box.size.width
    val top = box.origin.y
    val bottom = box.origin.y + box.size.height

    var tMin = Float.POSITIVE_INFINITY

    fun consider(t: Float) {
        if (t > 0f && t < tMin) tMin = t
    }
    if (dx > 0f) {
        consider((right - cx) / dx)
    } else if (dx < 0f) {
        consider((left - cx) / dx)
    }
    if (dy > 0f) {
        consider((bottom - cy) / dy)
    } else if (dy < 0f) {
        consider((top - cy) / dy)
    }
    if (tMin == Float.POSITIVE_INFINITY) return Point(cx, cy)
    return Point(cx + tMin * dx, cy + tMin * dy)
}

/**
 * Verteilt Ports gleichmäßig auf den vier Kanten des [bounds]-Rechtecks.
 *
 * V1.1.12 verteilt die Ports zyklisch auf top/right/bottom/left in der Reihenfolge,
 * in der sie übergeben werden. Bessere Heuristiken (Ports auf der Seite platzieren,
 * von der die Kante kommt) sind V1.2.
 */
internal fun allocatePorts(
    bounds: Rect,
    portIds: List<PortId>,
): Map<PortId, Point> {
    if (portIds.isEmpty()) return emptyMap()
    val result = mutableMapOf<PortId, Point>()
    val perSide = (portIds.size + 3) / 4 // ceil(n/4)
    for ((index, id) in portIds.withIndex()) {
        val side = index / perSide
        val withinSide = index % perSide
        val ratio = (withinSide + 1).toFloat() / (perSide + 1).toFloat()
        result[id] =
            when (side) {
                0 -> Point(bounds.origin.x + bounds.size.width * ratio, bounds.origin.y)
                1 -> Point(bounds.origin.x + bounds.size.width, bounds.origin.y + bounds.size.height * ratio)
                2 -> Point(bounds.origin.x + bounds.size.width * (1f - ratio), bounds.origin.y + bounds.size.height)
                else -> Point(bounds.origin.x, bounds.origin.y + bounds.size.height * (1f - ratio))
            }
    }
    return result
}

/**
 * Liefert das Zentrum eines Knotens (bzw. einer aller-Endpunkt-spezifischen
 * Box). Wird vom Edge-Router als Referenzpunkt für die
 * `counterpartCenter`-Heuristik gebraucht.
 */
internal fun centerOf(bounds: Rect): Point =
    Point(
        x = bounds.origin.x + bounds.size.width / 2f,
        y = bounds.origin.y + bounds.size.height / 2f,
    )

/**
 * Vorab gemeldete Ports pro Knoten aus den Kanten extrahieren.
 *
 * Wir scannen alle Endpunkte und sammeln die referenzierten Port-IDs pro
 * Knoten — daraus ergibt sich die Port-Belegungs-Reihenfolge.
 */
internal fun portsByNode(edges: List<LayoutEdge>): Map<NodeId, List<PortId>> {
    val acc = mutableMapOf<NodeId, MutableList<PortId>>()
    for (edge in edges) {
        for (ep in listOf(edge.source, edge.target)) {
            val pid = ep.portId ?: continue
            val list = acc.getOrPut(ep.nodeId) { mutableListOf() }
            if (pid !in list) list += pid
        }
    }
    return acc
}
