package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.KumlElement
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlCommunicationPath
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink

/**
 * Erkennt Self-Loops (Kanten, deren Source und Target derselbe Knoten sind)
 * und ersetzt das vom Layout-Engine gelieferte — typischerweise stark
 * verkrüppelte — Mini-U-Routing durch einen großen, gut lesbaren C-Loop
 * an der rechten Seite des Knotens.
 *
 * ELK liefert für Self-Loops in Praxis Pfade wie
 * `M 625.93 204.67 L 615.93 204.67 L 615.93 237.33 L 625.93 237.33` — eine
 * 10 px breite, 32 px hohe U-Form, die kaum als Loop erkennbar ist und an
 * der die Edge-Label-Logik zwangsläufig in Multiplicity-Labels hineinläuft.
 *
 * Lösung: Wenn das Modell-Element [isSelfLoop] meldet und der Self-Loop-Knoten
 * im Layout gefunden wird, ersetzt [adjust] die Route durch einen
 * [EdgeRoute.OrthogonalRounded] mit drei Wegpunkten, der **rechts** außerhalb
 * der Knotenbox eine deutliche C-Schleife zeichnet:
 *
 * ```
 *   Source: (right,  y + h * 0.30)
 *   WP1:    (right + LOOP_EXTENT_PX, y + h * 0.30)
 *   WP2:    (right + LOOP_EXTENT_PX, y + h * 0.70)
 *   Target: (right,  y + h * 0.70)
 * ```
 *
 * Damit landet der Pfeilkopf am unteren Anker, die Label-Mitte sitzt rechts
 * außerhalb des Knotens und kollidiert nicht mehr mit Multiplicity-Labels.
 */
public object SelfLoopRouter {
    /** Smallest x any loop-band may start at; a left loop must not leave the layout's coordinate space (Nacharbeit 129). */
    private const val BAND_INSET_PX = 1f

    /** Wie weit der C-Loop seitlich neben den Knoten hinausragt (px). */
    private const val LOOP_EXTENT_PX = 32f

    /** Anteil der Knotenhöhe, an dem oberer/unterer Anker sitzen. */
    private const val UPPER_ANCHOR_FRACTION = 0.30f
    private const val LOWER_ANCHOR_FRACTION = 0.70f

    /** Eck-Radius des Self-Loop-C in px. */
    private const val LOOP_CORNER_RADIUS_PX = 6f

    /**
     * Liefert das gemeinsame Endknoten-ID-Paar zurück, wenn das [element] ein
     * Self-Loop ist (also `sourceNodeId == targetNodeId`), sonst `null`.
     */
    public fun selfLoopNodeId(element: KumlElement): String? =
        when (element) {
            is UmlAssociation ->
                element.ends
                    .takeIf { it.size >= 2 }
                    ?.let { if (it[0].typeId == it[1].typeId) it[0].typeId else null }
            is UmlAssociationClass ->
                // Same self-loop detection as UmlAssociation — a self-association
                // class (both ends on the same classifier) needs the same widened
                // C-loop route, not ELK's cramped ~10px U-shape.
                element.ends
                    .takeIf { it.size >= 2 }
                    ?.let { if (it[0].typeId == it[1].typeId) it[0].typeId else null }
            is UmlGeneralization ->
                if (element.specificId == element.generalId) element.specificId else null
            is UmlInterfaceRealization ->
                if (element.implementingId == element.interfaceId) element.implementingId else null
            is UmlDependency ->
                if (element.clientId == element.supplierId) element.clientId else null
            is UmlConnector ->
                if (element.end1Id == element.end2Id) element.end1Id else null
            is UmlCommunicationPath ->
                if (element.end1Id == element.end2Id) element.end1Id else null
            is UmlInclude ->
                if (element.baseId == element.additionId) element.baseId else null
            is UmlExtend ->
                if (element.baseId == element.extensionId) element.baseId else null
            is UmlLink ->
                if (element.sourceInstanceId == element.targetInstanceId) element.sourceInstanceId else null
            is UmlActivityEdge ->
                if (element.sourceId == element.targetId) element.sourceId else null
            is C4Relationship ->
                if (element.source == element.target) element.source else null
            // V3.4.x — ERM self-references (e.g. `Category.parent_id` "subcategory
            // of" self) previously bypassed this router entirely: `renderErm` /
            // `renderErmIdef1x` shift ELK's raw route straight to the SVG without
            // ever calling `adjust`. ELK's cramped ~10px U-shape then collided with
            // the `ERM_ROLE_LABEL_*` offsets in `ErmEdgeLabels`, which were tuned
            // assuming the wide, purely-horizontal C-loop tangent this router
            // produces for every other diagram type. Recognizing the relationship
            // here is the first half of the fix — callers still need to invoke
            // [adjust].
            is ErmRelationship ->
                if (element.sourceEntityId == element.targetEntityId) element.sourceEntityId else null
            else -> null
        }

    /** `true` wenn das Modell-Element einen Self-Loop beschreibt. */
    public fun isSelfLoop(element: KumlElement): Boolean = selfLoopNodeId(element) != null

    /**
     * Erzeugt eine ausgeprägte C-förmige Self-Loop-Route auf der gewählten Seite
     * der [nodeLayout]-Box ([SelfLoopSide.RIGHT] ist die bisherige, unveränderte
     * Geometrie). Koordinaten sind im rohen Layout-Raum (vor dem Padding-Shift
     * in [KumlSvgRenderer]).
     */
    public fun selfLoopRoute(
        nodeLayout: NodeLayout,
        side: SelfLoopSide = SelfLoopSide.RIGHT,
    ): EdgeRoute.OrthogonalRounded {
        val bounds = nodeLayout.bounds
        val top = bounds.origin.y
        val h = bounds.size.height
        val upperY = top + h * UPPER_ANCHOR_FRACTION
        val lowerY = top + h * LOWER_ANCHOR_FRACTION
        val edgeX: Float
        val outerX: Float
        when (side) {
            SelfLoopSide.RIGHT -> {
                edgeX = bounds.origin.x + bounds.size.width
                outerX = edgeX + LOOP_EXTENT_PX
            }
            SelfLoopSide.LEFT -> {
                edgeX = bounds.origin.x
                outerX = edgeX - LOOP_EXTENT_PX
            }
        }
        return EdgeRoute.OrthogonalRounded(
            source = Point(x = edgeX, y = upperY),
            target = Point(x = edgeX, y = lowerY),
            waypoints =
                listOf(
                    Point(x = outerX, y = upperY),
                    Point(x = outerX, y = lowerY),
                ),
            cornerRadiusPx = LOOP_CORNER_RADIUS_PX,
        )
    }

    /**
     * Nacharbeit 129 — the side a self-loop of [node] is drawn on: right unless another edge runs through the right-hand
     * band (a [LOOP_EXTENT_PX]-wide strip along the node, one pixel inset so an edge ending exactly on the border counts),
     * then left if that band is free and still inside the coordinate space (`left - 32 >= 0`), otherwise right again.
     * Pure and deterministic: no weighting, no randomness. [otherRoutes] are the raw routes of the non-loop edges.
     */
    public fun chooseSide(
        node: Rect,
        otherRoutes: List<EdgeRoute>,
    ): SelfLoopSide {
        val left = node.origin.x
        val right = left + node.size.width
        val top = node.origin.y
        val bottom = top + node.size.height
        val rightBand =
            Rect(
                origin = Point(x = right - BAND_INSET_PX, y = top),
                size = Size(width = LOOP_EXTENT_PX + BAND_INSET_PX, height = bottom - top),
            )
        if (otherRoutes.none { routeHitsRect(route = it, r = rightBand) }) return SelfLoopSide.RIGHT
        if (left - LOOP_EXTENT_PX < 0f) return SelfLoopSide.RIGHT
        val leftBand =
            Rect(
                origin = Point(x = left - LOOP_EXTENT_PX, y = top),
                size = Size(width = LOOP_EXTENT_PX + BAND_INSET_PX, height = bottom - top),
            )
        return if (otherRoutes.none { routeHitsRect(route = it, r = leftBand) }) SelfLoopSide.LEFT else SelfLoopSide.RIGHT
    }

    /**
     * One decision per layout: the placement (route + side) of every self-loop edge of [layout], keyed by edge id.
     * [elementLookup] resolves an edge id to its model element; only edges whose element [isSelfLoop] and whose node exists
     * in the layout are planned. Self-loop routes and node rectangles are not obstacles; every consumer of one render pass
     * reads this same map (a loop drawn left with labels placed right would be wrong).
     */
    public fun planAll(
        layout: LayoutResult,
        elementLookup: (String) -> KumlElement?,
    ): Map<String, SelfLoopPlacement> {
        val selfLoops = LinkedHashMap<String, Pair<KumlElement, NodeLayout>>()
        val others = ArrayList<EdgeRoute>()
        val edgeIds =
            layout.edges.keys
                .map { it.value }
                .sorted()
        for (id in edgeIds) {
            val element = elementLookup(id)
            val nodeId = element?.let { selfLoopNodeId(it) }
            val node = nodeId?.let { layout.nodes[NodeId(it)] }
            if (element != null && node != null) {
                selfLoops[id] = element to node
            } else if (element == null || nodeId == null) {
                others += layout.edges.getValue(dev.kuml.layout.EdgeId(id))
            }
        }
        if (selfLoops.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, SelfLoopPlacement>()
        for ((id, pair) in selfLoops) {
            val side = chooseSide(node = pair.second.bounds, otherRoutes = others)
            result[id] = SelfLoopPlacement(route = selfLoopRoute(nodeLayout = pair.second, side = side), side = side)
        }
        return result
    }

    /** The side planned for [edgeId]; [SelfLoopSide.RIGHT] when the edge is not in [plan]. */
    public fun sideOf(
        plan: Map<String, SelfLoopPlacement>?,
        edgeId: String?,
    ): SelfLoopSide = if (plan != null && edgeId != null) plan[edgeId]?.side ?: SelfLoopSide.RIGHT else SelfLoopSide.RIGHT

    /**
     * Wenn [element] ein Self-Loop ist und der Knoten in [nodeLookup] existiert,
     * gibt es eine vergrößerte C-Loop-Route zurück; sonst die unveränderte
     * [originalRoute]. Mit [plan] und [edgeId] gilt die dort geplante Seite, sonst rechts.
     *
     * Aufrufer liefern eine Lookup-Funktion statt der Layout-Map, damit die
     * NodeId-Konvertierung lokal beim Aufrufer bleibt.
     */
    public fun adjust(
        element: KumlElement,
        originalRoute: EdgeRoute,
        plan: Map<String, SelfLoopPlacement>? = null,
        edgeId: String? = null,
        nodeLookup: (String) -> NodeLayout?,
    ): EdgeRoute {
        val nodeId = selfLoopNodeId(element) ?: return originalRoute
        val nodeLayout = nodeLookup(nodeId) ?: return originalRoute
        return selfLoopRoute(nodeLayout = nodeLayout, side = sideOf(plan = plan, edgeId = edgeId))
    }

    private fun routePoints(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            // conservative: the control polygon bounds the curve
            is EdgeRoute.Bezier -> listOf(route.source) + route.controlPoints + listOf(route.target)
        }

    private fun routeHitsRect(
        route: EdgeRoute,
        r: Rect,
    ): Boolean {
        val pts = routePoints(route)
        val x0 = r.origin.x
        val y0 = r.origin.y
        val x1 = x0 + r.size.width
        val y1 = y0 + r.size.height
        if (pts.any { it.x in x0..x1 && it.y in y0..y1 }) return true
        for (i in 0 until pts.size - 1) {
            if (segmentHitsRect(a = pts[i], b = pts[i + 1], x0 = x0, y0 = y0, x1 = x1, y1 = y1)) return true
        }
        return false
    }

    /** Liang-Barsky clip of the segment a-b against the closed rectangle. */
    private fun segmentHitsRect(
        a: Point,
        b: Point,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - x0, x1 - a.x, a.y - y0, y1 - a.y)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return t0 <= t1
    }
}

/** Which side of its node a self-loop is drawn on (Nacharbeit 129). */
public enum class SelfLoopSide { RIGHT, LEFT }

/** A planned self-loop: the route as drawn and the side it sits on. */
public data class SelfLoopPlacement(
    val route: EdgeRoute.OrthogonalRounded,
    val side: SelfLoopSide,
)
