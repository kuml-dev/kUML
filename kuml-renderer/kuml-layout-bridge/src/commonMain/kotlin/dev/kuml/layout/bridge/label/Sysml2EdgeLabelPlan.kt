package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.Sysml2NotePlacement

/**
 * Plans the edge labels of SysML 2 IBD and BDD diagrams with the shared [LabelPlacer] (PAL wave 2B-c). The plan is
 * made on the layout, before the notes are placed: notes then avoid the rectangles the renderer will really draw,
 * and the renderer draws the labels at exactly these rectangles. Nodes, composite titles, group borders, port
 * labels (already placed by the layout bridge) and foreign edges are obstacles; the label keeps next to its own line.
 */
public object Sysml2EdgeLabelPlan {
    /**
     * @param layout the layout the labels belong to (no canvas padding, no notes yet)
     * @param labelSizes estimated size of the label of an edge id (edges without an entry carry no label)
     * @param configure adds more obstacles (drawn port squares, owner ports)
     * @return placed label per edge id; ids are processed sorted, so the result is deterministic
     */
    public fun compute(
        layout: LayoutResult,
        labelSizes: Map<String, Size>,
        configure: (ObstacleIndex) -> Unit = {},
        costs: LabelCostModel = LabelCostModel(),
    ): Map<String, PlacedLabel> {
        if (labelSizes.isEmpty()) return emptyMap()
        val index = Sysml2NotePlacement.obstaclesOf(layout = layout)
        configure(index)
        val requests =
            layout.edges.entries
                .sortedBy { it.key.value }
                .mapNotNull { (id, route) ->
                    val size = labelSizes[id.value] ?: return@mapNotNull null
                    val pts = polylineOf(route)
                    if (pts.size < 2) return@mapNotNull null
                    LabelRequest(id = id.value, role = LabelRole.EDGE_CENTER, ownerId = id.value, size = size, polyline = pts)
                }
        if (requests.isEmpty()) return emptyMap()
        return LabelPlacer.place(requests = requests, index = index, costs = costs)
    }

    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }
}
