package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import kotlin.math.max
import kotlin.math.min

/**
 * Docks allocation edges of a block definition diagram at the row of their action (wave 2E-b).
 *
 * The layout bridge gives every allocation edge whose target block shows the allocated action in its `actions`
 * compartment a `targetAnchorOffset` (distance from the top of the block to the row). For horizontal flows this pass
 * redraws such an edge orthogonally: it leaves the source on the side facing the target, runs in an own lane in the gap
 * between the two columns and ends horizontally at the row. Every edge gets its own lane, so no two vertical segments
 * lie on top of each other. Edges without anchor, vertical flows and edges whose source does not lie on the expected
 * side keep the route of the layout engine.
 */
internal object BddAllocationDocking {
    private const val LANE_SPACING_PX = 6f
    private const val LANE_MARGIN_PX = 14f

    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
        direction: LayoutDirection,
    ): LayoutResult {
        if (direction != LayoutDirection.LeftToRight && direction != LayoutDirection.RightToLeft) return result
        val toRight = direction == LayoutDirection.LeftToRight
        val anchored = graph.edges.filter { it.hints.targetAnchorOffset != null }
        if (anchored.isEmpty()) return result
        val edges = LinkedHashMap(result.edges)
        // group by target so that the lanes of one block are distinct
        for ((target, group) in anchored.groupBy { it.target.nodeId }.entries.sortedBy { it.key.value }) {
            val t = result.nodes[target]?.bounds ?: continue
            val usable =
                group
                    .mapNotNull { e ->
                        val s = result.nodes[e.source.nodeId]?.bounds ?: return@mapNotNull null
                        val sideOk = if (toRight) s.origin.x + s.size.width <= t.origin.x else s.origin.x >= t.origin.x + t.size.width
                        if (sideOk) e to s else null
                    }.sortedWith(compareBy({ it.second.origin.y }, { it.first.id.value }))
            if (usable.isEmpty()) continue
            val gap =
                usable.minOf { (_, s) ->
                    if (toRight) {
                        t.origin.x - (s.origin.x + s.size.width)
                    } else {
                        s.origin.x -
                            (t.origin.x + t.size.width)
                    }
                }
            val spacing = min(LANE_SPACING_PX, max(2f, (gap - 2 * LANE_MARGIN_PX) / usable.size))
            // lanes: the edge that has to go furthest in y takes the lane closest to the source column
            val byDistance =
                usable.sortedWith(
                    compareByDescending<Pair<dev.kuml.layout.LayoutEdge, dev.kuml.layout.Rect>> {
                        kotlin.math.abs(
                            (it.second.origin.y + it.second.size.height / 2f) - (t.origin.y + (it.first.hints.targetAnchorOffset ?: 0f)),
                        )
                    }.thenBy { it.first.id.value },
                )
            for ((rank, pair) in byDistance.withIndex()) {
                val (e, s) = pair
                val sy = s.origin.y + s.size.height / 2f
                val ty = t.origin.y + (e.hints.targetAnchorOffset ?: 0f)
                val sx = if (toRight) s.origin.x + s.size.width else s.origin.x
                val tx = if (toRight) t.origin.x else t.origin.x + t.size.width
                val lane = LANE_MARGIN_PX + rank * spacing
                val lx = if (toRight) tx - lane else tx + lane
                edges[EdgeId(e.id.value)] =
                    EdgeRoute.OrthogonalRounded(
                        source = Point(x = sx, y = sy),
                        target = Point(x = tx, y = ty),
                        waypoints = listOf(Point(x = lx, y = sy), Point(x = lx, y = ty)).distinct(),
                        cornerRadiusPx = 0f,
                    )
            }
        }
        return result.copy(edges = edges)
    }
}
