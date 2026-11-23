package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** A side entry leaves this much room (px) between the frame's side line and the detour. */
private const val SIDE_ENTRY_OUTSIDE_PX = 10f

/** The side entry crosses the frame this far below the header band (px). */
private const val SIDE_ENTRY_BELOW_BAND_PX = 8f

/** Minimum room (px) between the side entry's horizontal run and the child's top edge. */
private const val SIDE_ENTRY_CHILD_ROOM_PX = 10f

/** Canvas margin (px) a side entry detour has to keep. */
private const val SIDE_ENTRY_CANVAS_MARGIN_PX = 4f

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
    val planned = mutableListOf<SideEntryPlan>()
    for (e in graph.edges) {
        val route = edges[e.id] ?: continue
        val sourceBox = nodes[e.source.nodeId]?.bounds ?: continue
        val targetBox = nodes[e.target.nodeId]?.bounds ?: continue
        for (g in composites) {
            val frame = groups.getValue(g.id).bounds
            if (contains(frame = frame, inner = targetBox) && !contains(frame = frame, inner = sourceBox)) {
                val plan =
                    sideEntryPlan(edgeId = e.id, route = route, group = g, frame = frame, child = targetBox, canvasWidth = canvas.width)
                if (plan != null) {
                    planned += plan
                } else {
                    val moved = rerouted(route = route, group = g, frame = frame, child = targetBox)
                    if (moved != null) {
                        if (changed == null) changed = edges.toMutableMap()
                        changed[e.id] = moved
                    } else {
                        val pts = routePoints(route)
                        if (pts != null && crossesHeader(pts = pts, header = headerRect(group = g, frame = frame))) {
                            logger.debug { "Edge ${e.id.value} crosses the header of ${g.id.value}; no re-route found" }
                        }
                    }
                }
                break
            }
        }
    }
    for ((_, lane) in planned.groupBy { it.group.id to it.useLeft }) {
        // Left entries: the child furthest left takes the lowest lane and the outermost detour, so lanes never cross.
        val ordered = if (lane.first().useLeft) lane.sortedBy { it.last.x } else lane.sortedByDescending { it.last.x }
        for ((rank, plan) in ordered.withIndex()) {
            val moved = buildSideEntry(plan = plan, rank = rank, count = ordered.size, result = this)
            val fallback =
                moved ?: rerouted(route = plan.route, group = plan.group, frame = plan.frame, child = plan.childBox)
            if (fallback == null) {
                logger.debug { "Edge ${plan.edgeId.value} crosses the header of ${plan.group.id.value}; no re-route found" }
                continue
            }
            if (changed == null) changed = edges.toMutableMap()
            changed[plan.edgeId] = fallback
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

private fun routePoints(route: EdgeRoute): List<Point>? = polylinePoints(route)

/** The whole header strip of a composite: frame top down to the bottom of title and entry/exit lines. */
internal fun headerRect(
    group: LayoutGroup,
    frame: Rect,
): Rect =
    Rect(
        origin = frame.origin,
        size = Size(width = frame.size.width, height = group.padding.top - (group.headerTextWidths?.sideEntryGap ?: 0f)),
    )

/** An edge that has to enter its composite frame from the side, see [buildSideEntry]. */
private class SideEntryPlan(
    val edgeId: EdgeId,
    val route: EdgeRoute,
    val group: LayoutGroup,
    val frame: Rect,
    val childBox: Rect,
    val k: Int,
    val pts: List<Point>,
    val useLeft: Boolean,
) {
    val last: Point get() = pts.last()
}

/** Vertical distance (px) between two side-entry lanes, and the first lane's distance below the header band. */
private const val SIDE_ENTRY_LANE_PX = 16f

/** Horizontal distance (px) between two outside detours. */
private const val SIDE_ENTRY_OUTSIDE_LANE_PX = 16f

private fun sideEntryPlan(
    edgeId: EdgeId,
    route: EdgeRoute,
    group: LayoutGroup,
    frame: Rect,
    child: Rect,
    canvasWidth: Float,
): SideEntryPlan? {
    val pts = routePoints(route) ?: return null
    val header = headerRect(group = group, frame = frame)
    val k = pts.zipWithNext().indexOfFirst { (a, b) -> segmentIntersectsRect(a = a, b = b, r = header) }
    if (k < 0) return null
    val a = pts[k]
    val gap = group.headerTextWidths?.sideEntryGap ?: 0f
    if (a.y > frame.origin.y || gap <= 0f) return null
    val left = frame.origin.x
    val right = left + frame.size.width
    val leftFits = left - SIDE_ENTRY_OUTSIDE_PX >= SIDE_ENTRY_CANVAS_MARGIN_PX
    val rightFits = right + SIDE_ENTRY_OUTSIDE_PX <= canvasWidth - SIDE_ENTRY_CANVAS_MARGIN_PX
    if (!leftFits && !rightFits) return null
    return SideEntryPlan(
        edgeId = edgeId,
        route = route,
        group = group,
        frame = frame,
        childBox = child,
        k = k,
        pts = pts,
        useLeft = if (leftFits && rightFits) a.x - left <= right - a.x else leftFits,
    )
}

/**
 * Re-routes an edge that runs through the header strip of its group so that it enters the frame from the side,
 * below the header band: down on the outside of the frame, across the side line in its own lane, then straight above
 * the child. The end point at the child is unchanged. `null` when the horizontal run would cross a node or the
 * detour leaves the canvas.
 */
private fun buildSideEntry(
    plan: SideEntryPlan,
    rank: Int,
    count: Int,
    result: LayoutResult,
): EdgeRoute? {
    val frame = plan.frame
    val pts = plan.pts
    val k = plan.k
    val a = pts[k]
    val last = plan.last
    val top = frame.origin.y
    val headerH = headerRect(group = plan.group, frame = frame).size.height
    val lanes = ((plan.group.headerTextWidths?.sideEntryGap ?: 0f) - 12f) / SIDE_ENTRY_LANE_PX
    val laneFromBand = minOf(count - 1 - rank, maxOf(lanes.toInt() - 1, 0))
    val entryY = top + headerH + SIDE_ENTRY_BELOW_BAND_PX + laneFromBand * SIDE_ENTRY_LANE_PX
    if (last.y - entryY < SIDE_ENTRY_CHILD_ROOM_PX) return null
    val outsideX =
        if (plan.useLeft) {
            frame.origin.x - SIDE_ENTRY_OUTSIDE_PX - (count - 1 - rank) * SIDE_ENTRY_OUTSIDE_LANE_PX
        } else {
            frame.origin.x + frame.size.width + SIDE_ENTRY_OUTSIDE_PX + (count - 1 - rank) * SIDE_ENTRY_OUTSIDE_LANE_PX
        }
    if (outsideX < SIDE_ENTRY_CANVAS_MARGIN_PX || outsideX > result.canvas.width - SIDE_ENTRY_CANVAS_MARGIN_PX) return null
    val run =
        Rect(
            origin = Point(x = minOf(outsideX, last.x), y = entryY - 0.5f),
            size = Size(width = kotlin.math.abs(outsideX - last.x), height = 1f),
        )
    if (result.nodes.values.any { it.bounds.overlaps(run) && it.bounds != plan.childBox }) return null
    val detourY = maxOf(a.y, top - HEADER_DETOUR_ABOVE_PX)
    val all =
        buildList {
            addAll(pts.subList(0, k + 1))
            if (a.y < detourY - 0.5f) add(Point(x = a.x, y = detourY))
            add(Point(x = outsideX, y = detourY))
            add(Point(x = outsideX, y = entryY))
            add(Point(x = last.x, y = entryY))
            add(last)
        }
    return EdgeRoute.OrthogonalRounded(
        source = all.first(),
        target = all.last(),
        waypoints = all.subList(1, all.size - 1),
        cornerRadiusPx = HEADER_CORNER_RADIUS_PX,
    )
}

private fun Rect.overlaps(o: Rect): Boolean =
    origin.x < o.origin.x + o.size.width &&
        origin.x + size.width > o.origin.x &&
        origin.y < o.origin.y + o.size.height &&
        origin.y + size.height > o.origin.y

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
    val gap = widths.sideEntryGap
    val bandBottom = top + group.padding.top - gap
    val centre = left + frame.size.width / 2f
    val titleLo = centre - widths.title / 2f - HEADER_TEXT_CLEARANCE_PX
    val titleHi = centre + widths.title / 2f + HEADER_TEXT_CLEARANCE_PX
    val hasActions = widths.actions > 0f && group.padding.top - gap > STM_BASE_TOP_PADDING_PX
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
