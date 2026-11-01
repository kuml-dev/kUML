package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.Sysml2NotePlacement
import dev.kuml.layout.bridge.note.rectOf

/** A port label to place: the label of the port square [anchorRect] of the owner [ownerId] (PAL wave 2C-b2). */
public data class PortLabelSpec(
    val id: String,
    val ownerId: String,
    val size: Size,
    val anchorRect: Rect,
    /** The side of the port square where an edge or the owner box docks; the label stays off it. */
    val excludedSide: LabelSide?,
)

/**
 * One label plan for a whole SysML 2 diagram (PAL wave 2C-b2): port labels and edge labels go through the same
 * [LabelPlacer] run with one shared obstacle index, so a port label and an edge label can no longer be planned
 * independently of each other. Arrow heads enter the index as [ObstacleKind.ARROWHEAD] squares. The result is keyed
 * by label id (edge id or [PortLabelSpec.id]) and deterministic.
 */
public object Sysml2DiagramLabelPlan {
    /** Side length of the square a label keeps clear around an arrow tip. */
    public const val ARROWHEAD_PX: Float = 12f

    /**
     * @param edgeLabelSizes estimated size per edge id (edges without an entry carry no label)
     * @param portLabels port labels to place together with the edge labels
     * @param arrowheads arrow-tip points (the [ARROWHEAD_PX] square around each becomes an obstacle)
     * @param configure adds more obstacles (drawn port squares, owner ports)
     */
    public fun compute(
        layout: LayoutResult,
        edgeLabelSizes: Map<String, Size>,
        portLabels: List<PortLabelSpec> = emptyList(),
        arrowheads: List<Point> = emptyList(),
        configure: (ObstacleIndex) -> Unit = {},
        costs: LabelCostModel = LabelCostModel(lineClearance = 2f),
    ): Map<String, PlacedLabel> {
        if (edgeLabelSizes.isEmpty() && portLabels.isEmpty()) return emptyMap()
        val index = Sysml2NotePlacement.obstaclesOf(layout = layout)
        configure(index)
        for (tip in arrowheads) {
            val h = ARROWHEAD_PX / 2f
            index.addRect(
                kind = ObstacleKind.ARROWHEAD,
                r = rectOf(left = tip.x - h, top = tip.y - h, right = tip.x + h, bottom = tip.y + h),
            )
        }
        val requests = ArrayList<LabelRequest>()
        for (p in portLabels.sortedBy { it.id }) {
            requests +=
                LabelRequest(
                    id = p.id,
                    role = LabelRole.PORT,
                    ownerId = p.ownerId,
                    size = p.size,
                    anchorRect = p.anchorRect,
                    excludedSide = p.excludedSide,
                )
        }
        for ((id, route) in layout.edges.entries.sortedBy { it.key.value }) {
            val size = edgeLabelSizes[id.value] ?: continue
            val pts = polylineOf(route)
            if (pts.size < 2) continue
            requests += LabelRequest(id = id.value, role = LabelRole.EDGE_CENTER, ownerId = id.value, size = size, polyline = pts)
        }
        if (requests.isEmpty()) return emptyMap()
        return LabelPlacer.place(requests = requests, index = index, costs = costs)
    }

    /** The tip of an edge's arrow head, i.e. its target point. */
    public fun tipOf(route: EdgeRoute): Point = polylineOf(route).last()

    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }
}
