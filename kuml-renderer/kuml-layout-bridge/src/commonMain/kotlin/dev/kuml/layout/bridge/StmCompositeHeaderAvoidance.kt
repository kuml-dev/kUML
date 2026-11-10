package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect

/** Clearance (px) kept between a re-routed vertical segment and the header text. */
private const val HEADER_TEXT_CLEARANCE_PX = 8f

/** Clearance (px) kept between a re-routed vertical segment and the frame's left/right edge. */
private const val HEADER_FRAME_CLEARANCE_PX = 12f

/** The bend that leads back to the child lies this far below the header band (px). */
private const val HEADER_BAND_BEND_PX = 6f

/** Room (px) the re-routed edge needs between its bend and the child it enters. */
private const val HEADER_CHILD_ROOM_PX = 10f

/** The detour leaves the original line this far above the frame (px). */
private const val HEADER_DETOUR_ABOVE_PX = 8f

/** Height (px) of the title strip, equal to the renderer's header height. */
private const val HEADER_TITLE_STRIP_PX = 28f

private const val HEADER_CORNER_RADIUS_PX = 6f

/** Upper bound of composites examined (hostile-input cap; the pass is O(edges x composites)). */
private const val MAX_HEADER_COMPOSITES = 64

/**
 * Edges that enter a child of a state-machine composite from above run straight down through the frame's header band
 * (title and entry/exit lines). For vertical flow, such a vertical segment is moved to the nearest free x right of
 * (or, without action lines, left of) the header texts: the edge leaves its line above the frame, runs down beside the
 * texts and returns to the child's x below the band. The end point at the child is unchanged. Without a free x (or
 * without room between the band and the child) the edge stays as it is. Texts are placed by estimated widths
 * ([LayoutGroup.headerTextWidths]), so the pass is font independent. Other directions are not touched: there the
 * edges enter beside the header. Edges that start inside the frame, end on the frame and side entries are left alone.
 * O(edges x composites), capped.
 */
internal fun LayoutResult.avoidCompositeHeaderBand(
    graph: LayoutGraph,
    direction: LayoutDirection,
): LayoutResult {
    if (direction != LayoutDirection.TopToBottom) return this
    val composites = graph.groups.filter { it.headerTextWidths != null && it.id in groups }.take(MAX_HEADER_COMPOSITES)
    if (composites.isEmpty()) return this
    var changed: MutableMap<EdgeId, EdgeRoute>? = null
    for (e in graph.edges) {
        val route = edges[e.id] ?: continue
        val sourceBox = nodes[e.source.nodeId]?.bounds ?: continue
        val targetBox = nodes[e.target.nodeId]?.bounds ?: continue
        for (g in composites) {
            val frame = groups.getValue(g.id).bounds
            if (contains(frame = frame, inner = targetBox) && !contains(frame = frame, inner = sourceBox)) {
                val moved = rerouted(route = route, group = g, frame = frame, child = targetBox) ?: continue
                if (changed == null) changed = edges.toMutableMap()
                changed[e.id] = moved
                break
            }
        }
    }
    return changed?.let { copy(edges = it) } ?: this
}

private fun contains(
    frame: Rect,
    inner: Rect,
): Boolean =
    inner.origin.x >= frame.origin.x &&
        inner.origin.y >= frame.origin.y &&
        inner.origin.x + inner.size.width <= frame.origin.x + frame.size.width &&
        inner.origin.y + inner.size.height <= frame.origin.y + frame.size.height

private fun routePoints(route: EdgeRoute): List<Point>? =
    when (route) {
        is EdgeRoute.Direct -> listOf(route.source, route.target)
        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + route.target
        else -> null
    }

private fun rerouted(
    route: EdgeRoute,
    group: LayoutGroup,
    frame: Rect,
    child: Rect,
): EdgeRoute? {
    val widths = group.headerTextWidths ?: return null
    val pts = routePoints(route) ?: return null
    val top = frame.origin.y
    val left = frame.origin.x
    val right = left + frame.size.width
    val bandBottom = top + group.padding.top
    val centre = left + frame.size.width / 2f
    val titleLo = centre - widths.title / 2f - HEADER_TEXT_CLEARANCE_PX
    val titleHi = centre + widths.title / 2f + HEADER_TEXT_CLEARANCE_PX
    val hasActions = widths.actions > 0f && group.padding.top > STM_BASE_TOP_PADDING_PX
    val actionsHi = left + 4f + minOf(widths.actions, frame.size.width - 8f) + HEADER_TEXT_CLEARANCE_PX

    for (k in 0 until pts.size - 1) {
        val a = pts[k]
        val b = pts[k + 1]
        val vertical = kotlin.math.abs(a.x - b.x) < 0.5f && b.y > a.y
        if (!vertical || b.y <= top || a.y >= bandBottom) continue
        val x = a.x
        val crossesTitle = x in titleLo..titleHi && a.y < top + HEADER_TITLE_STRIP_PX
        val crossesActions =
            hasActions && x in (left + 4f - HEADER_TEXT_CLEARANCE_PX)..actionsHi && b.y > top + HEADER_TITLE_STRIP_PX
        if (!crossesTitle && !crossesActions) continue
        val blockedHi = maxOf(titleHi, if (hasActions) actionsHi else titleHi)
        val candidates = mutableListOf<Float>()
        if (blockedHi <= right - HEADER_FRAME_CLEARANCE_PX) candidates += blockedHi
        if (!hasActions && titleLo >= left + HEADER_FRAME_CLEARANCE_PX) candidates += titleLo
        val free = candidates.minByOrNull { kotlin.math.abs(it - x) } ?: return null
        val detourY = maxOf(a.y, top - HEADER_DETOUR_ABOVE_PX)
        val bendY = bandBottom + HEADER_BAND_BEND_PX
        val lead = buildList { if (a.y < detourY - 0.5f) add(Point(x = x, y = detourY)) }
        val all: List<Point> =
            if (bendY <= b.y - HEADER_CHILD_ROOM_PX) {
                // Room below the band: run down beside the texts, return to the child's x, end point unchanged.
                pts.subList(0, k + 1) + lead + listOf(Point(x = free, y = detourY), Point(x = free, y = bendY), Point(x = x, y = bendY)) +
                    pts.subList(k + 1, pts.size)
            } else if (k + 1 == pts.size - 1 &&
                free in (child.origin.x + HEADER_TEXT_CLEARANCE_PX)..(child.origin.x + child.size.width - HEADER_TEXT_CLEARANCE_PX)
            ) {
                // The child touches the band: no room for a bend. Enter the same top edge of the child at the free x.
                pts.subList(0, k + 1) + lead + listOf(Point(x = free, y = detourY), Point(x = free, y = b.y))
            } else {
                return null
            }
        return EdgeRoute.OrthogonalRounded(
            source = all.first(),
            target = all.last(),
            waypoints = all.subList(1, all.size - 1),
            cornerRadiusPx = HEADER_CORNER_RADIUS_PX,
        )
    }
    return null
}

private const val STM_BASE_TOP_PADDING_PX = 36f
