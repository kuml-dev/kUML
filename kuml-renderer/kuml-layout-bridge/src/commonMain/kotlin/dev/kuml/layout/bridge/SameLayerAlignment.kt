package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.note.translated
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Two bounds closer than this (px) count as aligned. */
private const val ALIGN_EPSILON = 0.01f

/**
 * Centres the members of every `sameLayer` group on the flow axis (PAL wave 2D-b).
 *
 * The layer index decides *which* layer the members share; ELK however aligns the members of a layer at their start
 * edge, so boxes of different size end up with different centres. This post-pass moves each member along the flow axis
 * (y for top-to-bottom / bottom-to-top, x for left-to-right / right-to-left) until its centre equals the centre of the
 * largest member of the group. Because every member stays inside the band of that largest member, no box leaves the
 * layer's band and nothing collides with its lane neighbours. Edges that end at a moved node are pulled along.
 *
 * A group whose members do not share a layer (their bands on the flow axis do not overlap, e.g. because a `first`
 * constraint or a cycle won over the group) is left untouched and reported with a warning. At most
 * [dev.kuml.core.model.layout.LayoutLimits] groups with a bounded number of ids reach this function, and every group is
 * visited once, so the pass is linear in the number of members and edges.
 */
internal fun LayoutResult.alignSameLayerCenters(
    groups: List<List<String>>,
    direction: LayoutDirection,
    graph: LayoutGraph,
    internalEdges: Set<EdgeId> = emptySet(),
): LayoutResult {
    if (groups.isEmpty()) return this
    val vertical = direction == LayoutDirection.TopToBottom || direction == LayoutDirection.BottomToTop
    val nodes = nodes.toMutableMap()
    val delta = HashMap<NodeId, Float>()

    fun start(r: Rect) = if (vertical) r.origin.y else r.origin.x

    fun extent(r: Rect) = if (vertical) r.size.height else r.size.width

    for (group in groups) {
        val members = group.map { NodeId(it) }.distinct().filter { it in nodes }
        if (members.size < 2) continue
        val anchor = members.maxBy { extent(nodes.getValue(it).bounds) }
        val anchorBounds = nodes.getValue(anchor).bounds
        val centre = start(anchorBounds) + extent(anchorBounds) / 2f
        val sharesLayer =
            members.all { m ->
                val b = nodes.getValue(m).bounds
                start(b) < start(anchorBounds) + extent(anchorBounds) && start(b) + extent(b) > start(anchorBounds)
            }
        if (!sharesLayer) {
            logger.warn { "sameLayer(${group.joinToString(", ")}) could not be honoured: the members do not share a layer." }
            continue
        }
        for (m in members) {
            val n = nodes.getValue(m)
            val d = centre - (start(n.bounds) + extent(n.bounds) / 2f)
            if (kotlin.math.abs(d) < ALIGN_EPSILON) continue
            val dx = if (vertical) 0f else d
            val dy = if (vertical) d else 0f
            nodes[m] = n.movedBy(dx = dx, dy = dy)
            delta[m] = (delta[m] ?: 0f) + d
        }
    }

    val edges = edges.toMutableMap()
    // Edges between two members of one group were withheld from the engine (it cannot route inside a layer): draw
    // them straight between the facing sides of the now aligned boxes.
    for (e in graph.edges) {
        if (e.id !in internalEdges) continue
        val a = nodes[e.source.nodeId]?.bounds ?: continue
        val b = nodes[e.target.nodeId]?.bounds ?: continue
        edges[e.id] = facingRoute(from = a, to = b, vertical = vertical)
    }
    if (delta.isEmpty() && internalEdges.isEmpty()) return this
    for (e in graph.edges) {
        if (e.id in internalEdges) continue
        val route = edges[EdgeId(e.id.value)] ?: continue
        val ds = delta[e.source.nodeId] ?: 0f
        val dt = delta[e.target.nodeId] ?: 0f
        if (ds == 0f && dt == 0f) continue
        edges[e.id] = route.withEndsMoved(sourceD = ds, targetD = dt, vertical = vertical)
    }
    val moved = copy(nodes = nodes, edges = edges)
    return moved.normalised()
}

/** Straight line between the sides of [from] and [to] that face each other across the lane axis. */
private fun facingRoute(
    from: Rect,
    to: Rect,
    vertical: Boolean,
): EdgeRoute {
    fun cx(r: Rect) = r.origin.x + r.size.width / 2f

    fun cy(r: Rect) = r.origin.y + r.size.height / 2f
    return if (vertical) {
        // lane axis is x: leave through the right side when the target lies to the right
        val right = cx(to) >= cx(from)
        EdgeRoute.Direct(
            source = Point(x = if (right) from.origin.x + from.size.width else from.origin.x, y = cy(from)),
            target = Point(x = if (right) to.origin.x else to.origin.x + to.size.width, y = cy(to)),
        )
    } else {
        val below = cy(to) >= cy(from)
        EdgeRoute.Direct(
            source = Point(x = cx(from), y = if (below) from.origin.y + from.size.height else from.origin.y),
            target = Point(x = cx(to), y = if (below) to.origin.y else to.origin.y + to.size.height),
        )
    }
}

private fun NodeLayout.movedBy(
    dx: Float,
    dy: Float,
): NodeLayout =
    NodeLayout(
        bounds = Rect(origin = Point(x = bounds.origin.x + dx, y = bounds.origin.y + dy), size = bounds.size),
        ports = ports.mapValues { Point(x = it.value.x + dx, y = it.value.y + dy) },
        labels = labels.mapValues { Rect(origin = Point(x = it.value.origin.x + dx, y = it.value.origin.y + dy), size = it.value.size) },
    )

private fun Point.moved(
    d: Float,
    vertical: Boolean,
): Point = if (vertical) Point(x = x, y = y + d) else Point(x = x + d, y = y)

/**
 * Moves the end points of this route along the flow axis by [sourceD] / [targetD]. An orthogonal route whose first
 * (last) segment runs across the flow axis has the neighbouring waypoint pulled along so that the segment stays
 * straight.
 */
private fun EdgeRoute.withEndsMoved(
    sourceD: Float,
    targetD: Float,
    vertical: Boolean,
): EdgeRoute {
    fun pull(
        wps: List<Point>,
        endpoint: Point,
        d: Float,
        atStart: Boolean,
    ): List<Point> {
        if (d == 0f || wps.isEmpty()) return wps
        val idx = if (atStart) 0 else wps.lastIndex
        val w = wps[idx]
        // A segment across the flow axis keeps the endpoint's flow coordinate; pull the waypoint along.
        val across = if (vertical) kotlin.math.abs(w.y - endpoint.y) < ALIGN_EPSILON else kotlin.math.abs(w.x - endpoint.x) < ALIGN_EPSILON
        if (!across) return wps
        return wps.toMutableList().also { it[idx] = w.moved(d = d, vertical = vertical) }
    }
    return when (this) {
        is EdgeRoute.Direct ->
            copy(source = source.moved(d = sourceD, vertical = vertical), target = target.moved(d = targetD, vertical = vertical))
        is EdgeRoute.OrthogonalRounded ->
            copy(
                source = source.moved(d = sourceD, vertical = vertical),
                target = target.moved(d = targetD, vertical = vertical),
                waypoints =
                    pull(pull(waypoints, source, sourceD, atStart = true), target, targetD, atStart = false),
            )
        is EdgeRoute.TreeRounded ->
            copy(
                source = source.moved(d = sourceD, vertical = vertical),
                target = target.moved(d = targetD, vertical = vertical),
                waypoints =
                    pull(pull(waypoints, source, sourceD, atStart = true), target, targetD, atStart = false),
            )
        is EdgeRoute.Bezier -> {
            val cps = controlPoints.toMutableList()
            if (cps.isNotEmpty()) {
                if (sourceD != 0f) cps[0] = cps[0].moved(d = sourceD, vertical = vertical)
                if (targetD != 0f) cps[cps.lastIndex] = cps[cps.lastIndex].moved(d = targetD, vertical = vertical)
            }
            copy(
                source = source.moved(d = sourceD, vertical = vertical),
                target = target.moved(d = targetD, vertical = vertical),
                controlPoints = cps,
            )
        }
    }
}

/** Shifts everything so no node, group or edge point has a negative coordinate and grows the canvas to fit. */
internal fun LayoutResult.normalised(): LayoutResult {
    var minX = 0f
    var minY = 0f
    var maxX = canvas.width
    var maxY = canvas.height

    fun see(p: Point) {
        minX = minOf(minX, p.x)
        minY = minOf(minY, p.y)
        maxX = maxOf(maxX, p.x)
        maxY = maxOf(maxY, p.y)
    }

    fun see(r: Rect) {
        see(r.origin)
        see(Point(x = r.origin.x + r.size.width, y = r.origin.y + r.size.height))
    }
    nodes.values.forEach { see(it.bounds) }
    groups.values.forEach { see(it.bounds) }
    edges.values.forEach { r ->
        see(r.source)
        see(r.target)
        when (r) {
            is EdgeRoute.OrthogonalRounded -> r.waypoints.forEach(::see)
            is EdgeRoute.TreeRounded -> r.waypoints.forEach(::see)
            else -> Unit
        }
    }
    val grown = copy(canvas = dev.kuml.layout.Size(width = maxX, height = maxY))
    return if (minX < 0f || minY < 0f) grown.translated(dx = -minOf(0f, minX), dy = -minOf(0f, minY)) else grown
}
