@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Wrapped generalisation fans of UML class diagrams (PAL wave 2G-a).
 *
 * A general classifier with a wide fan of specialisations is drawn with its specialisations in a grid of `columns`
 * columns in declaration order (row by row). All boxes of one column share the same left edge. Every
 * generalisation edge is routed orthogonally and individually: it leaves the specialisation at the near side,
 * runs through the horizontal channel below its row to a vertical gutter between two columns (at least
 * [MIN_GUTTER_PX] wide, one lane per edge), down the gutter to a collector channel above the general classifier, and ends
 * at its own point of the general classifier. The lanes are ordered so that no two edges cross, and no edge touches
 * a box. The general classifier keeps its position; the grid is centred above (flow `Up`: below) it.
 *
 * The pass is deterministic and skips a fan it cannot handle (an edge other than a comment link on a specialisation,
 * a horizontal flow, a fan with generalisation edges that were given an explicit id). Foreign boxes the grid would
 * overlap are moved sideways, and their edges are redrawn as plain lines. Horizontal flows keep the layered engine's
 * routes.
 */
internal object UmlWrapGrid {
    /** A fan qualifies once it has more specialisations than one grid row holds, but never fewer than this. */
    const val MIN_FAN = 4
    const val MIN_GUTTER_PX = 32f
    private const val LANE_PX = 8f
    private const val GUTTER_PAD_PX = 20f
    private const val CHANNEL_MIN_PX = 40f
    private const val CHANNEL_PER_COLUMN_PX = 8f
    private const val COLLECTOR_PAD_PX = 16f
    private const val ARRIVAL_PX = 16f
    private const val ROW_TO_COLLECTOR_PX = 16f
    private const val TARGET_SPACING_PX = 14f
    private const val TARGET_MARGIN_PX = 8f
    private const val FOREIGN_CLEARANCE_PX = 40f
    private const val MAX_FAN = 400

    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
        direction: LayoutDirection,
        columns: Int,
    ): LayoutResult {
        if (direction != LayoutDirection.TopToBottom && direction != LayoutDirection.BottomToTop) return result
        if (columns < 2) return result
        val gen = graph.edges.filter { it.id.value.startsWith(GEN_PREFIX) && it.source.nodeId != it.target.nodeId }
        val byGeneral = gen.groupBy { it.target.nodeId }
        val general =
            byGeneral.entries
                .filter { e ->
                    e.value
                        .map { it.source.nodeId }
                        .distinct()
                        .size >= max(MIN_FAN, columns + 1)
                }.maxWithOrNull(compareBy({ it.value.size }, { it.key.value }))
                ?.key ?: return result
        val fan = byGeneral.getValue(general)
        val order = graph.nodes.map { it.id }
        val specIds =
            fan
                .map { it.source.nodeId }
                .distinct()
                .sortedBy { order.indexOf(it) }
        if (specIds.size > MAX_FAN) return result
        val specSet = specIds.toSet()
        // every edge touching a specialisation must be a fan edge or a comment link
        val foreignEdgeOnSpec =
            graph.edges.any { e ->
                e !in fan &&
                    (e.source.nodeId in specSet || e.target.nodeId in specSet) &&
                    !e.id.value.startsWith(COMMENT_LINK_PREFIX)
            }
        if (foreignEdgeOnSpec) return result
        val fanBySpec = fan.associateBy { it.source.nodeId }
        if (specIds.any { fanBySpec[it] == null }) return result
        val gLayout = result.nodes[general] ?: return result
        val specLayouts = specIds.map { result.nodes[it] ?: return result }
        val gCenterY = gLayout.bounds.origin.y + gLayout.bounds.size.height / 2f
        val specMeanY = specLayouts.map { it.bounds.origin.y + it.bounds.size.height / 2f }.average().toFloat()
        val gBelow = gCenterY > specMeanY
        val m = Mirror(active = !gBelow)

        val c = min(columns, specIds.size)
        val n = specIds.size
        val rowCount = (n + c - 1) / c
        val sizes = specLayouts.map { it.bounds.size }

        // ---- gutters ---------------------------------------------------------------------------------------------
        fun gutterOf(k: Int): Int = if (k <= (c - 1) / 2) k else k + 1

        fun leftSide(gutter: Int): Boolean = gutter <= (c - 1) / 2 // the edges come from the box right of the gutter
        val lanesIn = IntArray(c + 1)
        for (i in 0 until n) if (i / c < rowCount - 1) lanesIn[gutterOf(i % c)]++
        val gutterW = FloatArray(c + 1) { g -> max(MIN_GUTTER_PX, lanesIn[g] * LANE_PX + GUTTER_PAD_PX) }
        val colW = FloatArray(c) { k -> (0 until n).filter { it % c == k }.maxOf { sizes[it].width } }
        val rowH = FloatArray(rowCount) { r -> (0 until n).filter { it / c == r }.maxOf { sizes[it].height } }
        val gutterLeft = FloatArray(c + 1)
        val colX = FloatArray(c)
        var x = 0f
        for (g in 0..c) {
            gutterLeft[g] = x
            x += gutterW[g]
            if (g < c) {
                colX[g] = x
                x += colW[g]
            }
        }
        val totalW = x
        val gBounds = m.rect(gLayout.bounds)
        val gcx = gBounds.origin.x + gBounds.size.width / 2f
        val dx = gcx - totalW / 2f

        // ---- rows (normalised: the general classifier is below) --------------------------------------------------------
        val channel = max(CHANNEL_MIN_PX, 2f * LANE_PX + c * CHANNEL_PER_COLUMN_PX)
        // collector lanes need the split of the fan into left / right of the target points, so first the descent x
        val descentX = FloatArray(n)
        val laneIdxInGutter = IntArray(n)
        for (i in 0 until n) {
            val r = i / c
            val k = i % c
            val colLeft = colX[k] + dx
            descentX[i] =
                if (r < rowCount - 1) {
                    val g = gutterOf(k)
                    val gl = gutterLeft[g] + dx
                    laneIdxInGutter[i] = r
                    if (leftSide(g)) gl + GUTTER_PAD_PX / 2f + r * LANE_PX else gl + gutterW[g] - GUTTER_PAD_PX / 2f - r * LANE_PX
                } else {
                    colLeft + sizes[i].width / 2f
                }
        }
        val order2 = (0 until n).sortedWith(compareBy({ descentX[it] }, { it }))
        val rank = IntArray(n)
        order2.forEachIndexed { pos, i -> rank[i] = pos }
        val spacing = min(TARGET_SPACING_PX, max(4f, (gBounds.size.width - 2f * TARGET_MARGIN_PX) / max(1, n - 1)))
        val targetX = FloatArray(n) { i -> gcx + (rank[i] - (n - 1) / 2f) * spacing }
        val left = (0 until n).filter { descentX[it] < targetX[it] - 0.5f }.sortedByDescending { descentX[it] }
        val right = (0 until n).filter { descentX[it] > targetX[it] + 0.5f }.sortedBy { descentX[it] }
        val laneCount = max(left.size, right.size)
        val collectorH = COLLECTOR_PAD_PX + laneCount * LANE_PX
        val collectorBottom = gBounds.origin.y - ARRIVAL_PX
        val collectorTop = collectorBottom - collectorH
        val rowBottom = FloatArray(rowCount)
        val rowTop = FloatArray(rowCount)
        var cursor = collectorTop - ROW_TO_COLLECTOR_PX
        for (r in rowCount - 1 downTo 0) {
            rowBottom[r] = cursor
            rowTop[r] = cursor - rowH[r]
            cursor = rowTop[r] - channel
        }
        val laneY = FloatArray(n) { Float.NaN }
        left.forEachIndexed { k, i -> laneY[i] = collectorTop + COLLECTOR_PAD_PX / 2f + k * LANE_PX }
        right.forEachIndexed { k, i -> laneY[i] = collectorTop + COLLECTOR_PAD_PX / 2f + k * LANE_PX }

        // ---- build the new layout ---------------------------------------------------------------------------------
        val newBounds = LinkedHashMap<NodeId, Rect>()
        val routes = LinkedHashMap<EdgeId, EdgeRoute>()
        val gTop = gBounds.origin.y
        for (i in 0 until n) {
            val r = i / c
            val k = i % c
            val sz = sizes[i]
            val rect = Rect(origin = Point(x = colX[k] + dx, y = rowTop[r]), size = sz)
            newBounds[specIds[i]] = m.rect(rect)
            val cx = rect.origin.x + sz.width / 2f
            val bottom = rect.origin.y + sz.height
            val pts = ArrayList<Point>()
            if (r < rowCount - 1) {
                val yCh = rowBottom[r] + channel / 2f + (k - (c - 1) / 2f) * 6f
                pts += Point(cx, yCh)
                pts += Point(descentX[i], yCh)
            }
            if (!laneY[i].isNaN()) {
                pts += Point(descentX[i], laneY[i])
                pts += Point(targetX[i], laneY[i])
            } else {
                pts += Point(targetX[i], collectorTop + COLLECTOR_PAD_PX / 2f)
            }
            val src = Point(cx, bottom)
            val tgt = Point(targetX[i], gTop)
            routes[fanBySpec.getValue(specIds[i]).id] =
                EdgeRoute.OrthogonalRounded(
                    source = m.pt(src),
                    target = m.pt(tgt),
                    waypoints = cornerPoints(listOf(src) + pts + tgt).map(m::pt),
                    cornerRadiusPx = 0f,
                )
        }

        // ---- foreign boxes inside the grid are moved sideways ----------------------------------------------------------
        val gridBox =
            Rect(
                origin = Point(x = dx - FOREIGN_CLEARANCE_PX / 2f, y = rowTop[0] - FOREIGN_CLEARANCE_PX / 2f),
                size = Size(width = totalW + FOREIGN_CLEARANCE_PX, height = gTop - rowTop[0] + FOREIGN_CLEARANCE_PX / 2f),
            )
        val nodes = LinkedHashMap(result.nodes)
        val edges = LinkedHashMap(result.edges)
        newBounds.forEach { (id, r) -> nodes[id] = nodes.getValue(id).copy(bounds = r) }
        routes.forEach { (id, r) -> edges[id] = r }
        val moved = HashSet<NodeId>()
        for ((id, nl) in result.nodes) {
            if (id == general || id in specSet) continue
            val b = m.rect(nl.bounds)
            if (!overlaps(b, gridBox)) continue
            val cxF = b.origin.x + b.size.width / 2f
            val nx =
                if (cxF <
                    dx + totalW / 2f
                ) {
                    gridBox.origin.x - FOREIGN_CLEARANCE_PX - b.size.width
                } else {
                    gridBox.origin.x + gridBox.size.width +
                        FOREIGN_CLEARANCE_PX
                }
            nodes[id] = nl.copy(bounds = m.rect(Rect(origin = Point(nx, b.origin.y), size = b.size)))
            moved += id
        }
        // edges whose end moved (comment links of specialisations, edges of a moved foreign box) become plain lines
        for (e in graph.edges) {
            if (e in fan) continue
            val a = e.source.nodeId
            val b = e.target.nodeId
            if (a !in specSet && b !in specSet && a !in moved && b !in moved) continue
            val ra = nodes[a]?.bounds ?: continue
            val rb = nodes[b]?.bounds ?: continue
            edges[e.id] = EdgeRoute.Direct(source = clipToward(ra, rb), target = clipToward(rb, ra))
        }
        return BddGeneralizationBus.tighten(original = result, nodes = nodes, edges = edges)
    }

    private fun cornerPoints(points: List<Point>): List<Point> {
        // points = source + waypoints + target; keep only the inner corner points (collinear / duplicate ones removed)
        val all = ArrayList<Point>()
        for (p in points) if (all.lastOrNull() != p) all += p
        val inner = ArrayList<Point>()
        for (i in 1 until all.size - 1) {
            val a = all[i - 1]
            val b = all[i]
            val c = all[i + 1]
            val collinear = (a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)
            if (!collinear) inner += b
        }
        return inner
    }

    private fun overlaps(
        a: Rect,
        b: Rect,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width &&
            a.origin.x + a.size.width > b.origin.x &&
            a.origin.y < b.origin.y + b.size.height &&
            a.origin.y + a.size.height > b.origin.y

    /** The point where the line from the centre of [from] to the centre of [to] leaves [from]. */
    internal fun clipToward(
        from: Rect,
        to: Rect,
    ): Point {
        val cx = from.origin.x + from.size.width / 2f
        val cy = from.origin.y + from.size.height / 2f
        val tx = to.origin.x + to.size.width / 2f
        val ty = to.origin.y + to.size.height / 2f
        val dxv = tx - cx
        val dyv = ty - cy
        if (dxv == 0f && dyv == 0f) return Point(cx, cy)
        val sx = if (dxv == 0f) Float.MAX_VALUE else (from.size.width / 2f) / abs(dxv)
        val sy = if (dyv == 0f) Float.MAX_VALUE else (from.size.height / 2f) / abs(dyv)
        val s = min(sx, sy)
        return Point(cx + dxv * s, cy + dyv * s)
    }

    /** Mirrors the flow axis, so the algorithm can always assume the general classifier is below its fan. */
    private class Mirror(
        val active: Boolean,
    ) {
        fun pt(p: Point): Point = if (active) Point(p.x, -p.y) else p

        fun rect(r: Rect): Rect = if (active) Rect(origin = Point(r.origin.x, -(r.origin.y + r.size.height)), size = r.size) else r
    }

    private const val GEN_PREFIX = "gen::"
    private const val COMMENT_LINK_PREFIX = "noteanchor::"
}

/**
 * The band of edgeless classifiers of a UML class diagram (PAL wave 2G-a).
 *
 * Classes, interfaces and enumerations without any edge (a comment link counts as an edge) are withheld from the layered
 * engine and placed in a band under the graph: left-aligned at the graph's left edge, [BAND_GAP_PX] below it, in
 * declaration order, wrapping at the graph's width but with at least [MIN_PER_ROW] boxes per row.
 */
internal object UmlEdgelessBand {
    const val BAND_GAP_PX = 40f
    const val MIN_PER_ROW = 3
    private const val CELL_GAP_PX = 24f

    /** The graph without its edgeless classifiers and those classifiers (declaration order), or `null` if there is nothing to move. */
    fun split(graph: LayoutGraph): Pair<LayoutGraph, List<LayoutNode>>? {
        val touched = HashSet<NodeId>()
        for (e in graph.edges) {
            touched += e.source.nodeId
            touched += e.target.nodeId
        }
        val edgeless =
            graph.nodes.filter { it.hints.bandable && it.groupId == null && it.ports.isEmpty() && it.id !in touched }
        if (edgeless.isEmpty() || edgeless.size == graph.nodes.size) return null
        val keep = graph.nodes.filter { n -> edgeless.none { it.id == n.id } }
        return graph.copy(nodes = keep) to edgeless
    }

    fun place(
        result: LayoutResult,
        band: List<LayoutNode>,
    ): LayoutResult {
        if (result.nodes.isEmpty() || band.isEmpty()) return result
        val minX = result.nodes.values.minOf { it.bounds.origin.x }
        val maxX = result.nodes.values.maxOf { it.bounds.origin.x + it.bounds.size.width }
        var maxY = result.nodes.values.maxOf { it.bounds.origin.y + it.bounds.size.height }
        result.edges.values.forEach { r ->
            maxY = max(maxY, max(r.source.y, r.target.y))
            if (r is EdgeRoute.OrthogonalRounded) r.waypoints.forEach { maxY = max(maxY, it.y) }
        }
        val rightMargin = result.canvas.width - maxX
        val nodeBottom = result.nodes.values.maxOf { it.bounds.origin.y + it.bounds.size.height }
        val bottomMargin = result.canvas.height - nodeBottom
        val limit = max(maxX - minX, 1f)
        val placed = LinkedHashMap<NodeId, NodeLayout>()
        var x = minX
        var y = maxY + BAND_GAP_PX
        var rowH = 0f
        var inRow = 0
        var bandMaxX = minX
        for (n in band) {
            val w = n.intrinsicSize.width
            val h = n.intrinsicSize.height
            if (inRow >= MIN_PER_ROW && x + w > minX + limit) {
                x = minX
                y += rowH + CELL_GAP_PX
                rowH = 0f
                inRow = 0
            }
            placed[n.id] = NodeLayout(bounds = Rect(origin = Point(x, y), size = Size(w, h)))
            x += w + CELL_GAP_PX
            rowH = max(rowH, h)
            inRow++
            bandMaxX = max(bandMaxX, x - CELL_GAP_PX)
        }
        val bandBottom = y + rowH
        return result.copy(
            nodes = result.nodes + placed,
            canvas =
                Size(
                    width = max(result.canvas.width, bandMaxX + rightMargin),
                    height = max(result.canvas.height, bandBottom + bottomMargin),
                ),
        )
    }

    /** Rounds up [value]: the auto-wrap column count for [nodes] nodes in one layer. */
    fun autoColumns(nodes: Int): Int = ceil(kotlin.math.sqrt(nodes.toDouble())).toInt()
}
