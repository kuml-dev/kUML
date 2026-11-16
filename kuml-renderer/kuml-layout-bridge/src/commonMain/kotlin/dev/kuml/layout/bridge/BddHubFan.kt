package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Hub fans of a block definition diagram (Nacharbeit 86, P-BDD-01/02).
 *
 * A block that carries [MIN_EDGES] or more plain edges (`refPart`, `part`, association, ...; no generalisation, no
 * allocation with an anchor row) to blocks on one side of it is a hub. The layered engine routes such a fan as a tight
 * bundle (parallel lanes about 10 px apart) and sends the edges to the blocks of a later row around the outside of
 * the whole diagram. This pass redraws the fan orthogonally:
 *
 *  - every edge leaves the hub at its own point on the facing side, as close to the target as the side allows, with at
 *    least [EXIT_SPACING_PX] between two ends (an edge whose target lies straight ahead and is not hidden runs straight);
 *  - the horizontal run lies in the gap in front of the target row (or in the gap behind the hub), one lane per
 *    overlapping edge, lanes [LANE_SPACING_PX] apart;
 *  - an edge to a block of a later row drops through the nearest gap between two columns of the rows in front of
 *    it, never across a box and never around the diagram;
 *  - several edges into one block end at distinct points of its side.
 *
 * Works for all four flow directions (the flow axis is mapped to `a`, the cross axis to `c`). The pass is
 * deterministic and leaves a fan alone when the hub side is too short to hold the ends. Edges between blocks of one
 * layer and edges the engine drew in another way (generalisations, anchored allocations) keep their routes.
 */
internal object BddHubFan {
    const val MIN_EDGES = 4
    const val EXIT_SPACING_PX = 14f
    const val LANE_SPACING_PX = 16f
    private const val MIN_LANE_SPACING_PX = 7f
    private const val SIDE_MARGIN_PX = 12f
    private const val MIN_EXIT_SPACING_PX = 6f
    private const val CORRIDOR_MARGIN_PX = 9f
    private const val CORRIDOR_SPREAD_PX = 8f
    private const val OUTER_CORRIDOR_PX = 22f
    private const val LANE_CLEARANCE_PX = 10f
    private const val TOL = 1.5f

    /** One axis-aligned box in flow (`a`) and cross (`c`) coordinates. */
    private data class Box(
        val a0: Float,
        val a1: Float,
        val c0: Float,
        val c1: Float,
    ) {
        val centre: Float get() = (c0 + c1) / 2f
    }

    private class Axes(
        direction: LayoutDirection,
        private val flip: Boolean,
    ) {
        private val vertical = direction == LayoutDirection.TopToBottom || direction == LayoutDirection.BottomToTop
        private val sign =
            (if (direction == LayoutDirection.BottomToTop || direction == LayoutDirection.RightToLeft) -1f else 1f) *
                (if (flip) -1f else 1f)

        fun a(p: Point): Float = sign * (if (vertical) p.y else p.x)

        fun c(p: Point): Float = if (vertical) p.x else p.y

        fun point(
            a: Float,
            c: Float,
        ): Point = if (vertical) Point(x = c, y = sign * a) else Point(x = sign * a, y = c)

        fun box(r: Rect): Box {
            val p0 = r.origin
            val p1 = Point(x = r.origin.x + r.size.width, y = r.origin.y + r.size.height)
            val aa = a(p0)
            val ab = a(p1)
            return Box(a0 = min(aa, ab), a1 = max(aa, ab), c0 = min(c(p0), c(p1)), c1 = max(c(p0), c(p1)))
        }
    }

    private class Item(
        val edge: LayoutEdge,
        val other: NodeId,
        val box: Box,
    ) {
        var exit = 0f
        var entry = 0f
        var corridor: Float? = null
        var corridorHalf = 0f
        var lane1 = 0f
        var lane2 = 0f
    }

    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
        direction: LayoutDirection,
    ): LayoutResult {
        val plain =
            graph.edges.filter { e ->
                !e.id.value.startsWith("gen:") &&
                    e.hints.targetAnchorOffset == null &&
                    e.source.nodeId != e.target.nodeId &&
                    e.source.nodeId in result.nodes &&
                    e.target.nodeId in result.nodes
            }
        val degree = HashMap<NodeId, Int>()
        for (e in plain) {
            degree[e.source.nodeId] = (degree[e.source.nodeId] ?: 0) + 1
            degree[e.target.nodeId] = (degree[e.target.nodeId] ?: 0) + 1
        }
        val hubs = degree.filter { it.value >= MIN_EDGES }.keys.sortedBy { it.value }
        if (hubs.isEmpty()) return result
        val edges = LinkedHashMap(result.edges)
        val claimed = HashSet<EdgeId>()
        var changed = false
        for (hub in hubs) {
            for (flip in listOf(false, true)) {
                val axes = Axes(direction = direction, flip = flip)
                val hubBox = axes.box(result.nodes.getValue(hub).bounds)
                val items =
                    plain
                        .filter { it.id !in claimed && (it.source.nodeId == hub || it.target.nodeId == hub) }
                        .mapNotNull { e ->
                            val other = if (e.source.nodeId == hub) e.target.nodeId else e.source.nodeId
                            val box = axes.box(result.nodes.getValue(other).bounds)
                            if (box.a0 >= hubBox.a1 - TOL) Item(edge = e, other = other, box = box) else null
                        }.sortedWith(compareBy({ it.box.centre }, { it.edge.id.value }))
                if (items.size < MIN_EDGES) continue
                val others =
                    result.nodes
                        .filterKeys { it != hub }
                        .map { (id, n) -> id to axes.box(n.bounds) }
                val routes = route(hub = hub, hubBox = hubBox, items = items, others = others, axes = axes) ?: continue
                for ((id, r) in routes) {
                    edges[id] = r
                    claimed += id
                }
                changed = true
            }
        }
        if (!changed) return result
        return BddGeneralizationBus.tighten(original = result, nodes = result.nodes, edges = edges)
    }

    private fun route(
        hub: NodeId,
        hubBox: Box,
        items: List<Item>,
        others: List<Pair<NodeId, Box>>,
        axes: Axes,
    ): Map<EdgeId, EdgeRoute>? {
        val n = items.size
        val lo = hubBox.c0 + SIDE_MARGIN_PX
        val hi = hubBox.c1 - SIDE_MARGIN_PX
        if (hi - lo < (n - 1) * MIN_EXIT_SPACING_PX) return null
        // entries: one point per edge, several edges into one block fan out around its centre
        val byOther = items.groupBy { it.other }
        for (group in byOther.values) {
            val box = group.first().box
            val step = min(EXIT_SPACING_PX, (box.c1 - box.c0 - 2f * 6f) / max(1, group.size - 1).toFloat())
            group.forEachIndexed { k, it -> it.entry = box.centre + (k - (group.size - 1) / 2f) * (if (group.size > 1) step else 0f) }
        }
        // exits: as close to the entry as the hub side allows, kept apart
        val spacing = min(EXIT_SPACING_PX, (hi - lo) / max(1, n - 1).toFloat())
        val desired = items.map { it.entry.coerceIn(lo, hi) }
        val pos = FloatArray(n)
        for (i in 0 until n) pos[i] = if (i == 0) desired[i] else max(desired[i], pos[i - 1] + spacing)
        if (pos[n - 1] > hi) {
            pos[n - 1] = hi
            for (i in n - 2 downTo 0) pos[i] = min(pos[i], pos[i + 1] - spacing)
        }
        items.forEachIndexed { i, it -> it.exit = pos[i] }

        val hubEnd = hubBox.a1
        val firstAhead = others.filter { it.second.a0 >= hubEnd - TOL }.minOfOrNull { it.second.a0 } ?: return null
        val hubBand = ArrayList<Item>()
        val bands = HashMap<Float, MutableList<Item>>()
        for (it in items) {
            val t0 = it.box.a0
            val blockers = others.filter { (id, b) -> id != it.other && b.a1 > hubEnd + TOL && b.a0 < t0 - TOL }
            val straightFree =
                blockers.none { (_, b) ->
                    it.exit > b.c0 - CORRIDOR_MARGIN_PX / 2f &&
                        it.exit < b.c1 + CORRIDOR_MARGIN_PX / 2f
                }
            val inHubBand = t0 <= firstAhead + TOL
            when {
                abs(it.exit - it.entry) < 0.75f && straightFree -> {
                    it.exit = it.entry
                }
                inHubBand -> {
                    hubBand += it
                }
                else -> {
                    val gap = corridorFor(item = it, blockers = blockers) ?: return null
                    it.corridor = gap.first
                    it.corridorHalf = gap.second
                    hubBand += it
                    bands.getOrPut(t0) { ArrayList() } += it
                }
            }
        }
        // edges sharing a gap fan out in the order of their entries, so they do not cross each other
        for (group in items.filter { it.corridor != null }.groupBy { it.corridor to it.corridorHalf }.values) {
            if (group.size < 2) continue
            val step = min(CORRIDOR_SPREAD_PX, group.first().corridorHalf * 2f / group.size)
            val base = group.first().corridor!!
            group.sortedWith(compareBy({ it.entry }, { it.edge.id.value })).forEachIndexed { k, it ->
                it.corridor = base + (k - (group.size - 1) / 2f) * step
            }
        }
        // lanes in the band behind the hub
        assignLanes(
            members = hubBand,
            aStart = hubEnd,
            aEnd = firstAhead,
            span = { if (it.corridor != null) it.exit to it.corridor!! else it.exit to it.entry },
            set = { item, a -> item.lane1 = a },
        )
        // lanes in the band in front of each later row
        for ((t0, members) in bands.entries.sortedBy { it.key }) {
            val rowAbove = others.filter { it.second.a1 <= t0 - TOL }.maxOfOrNull { it.second.a1 } ?: continue
            assignLanes(
                members = members,
                aStart = max(rowAbove, hubEnd),
                aEnd = t0,
                span = { it.corridor!! to it.entry },
                set = { item, a -> item.lane2 = a },
            )
        }
        val out = LinkedHashMap<EdgeId, EdgeRoute>()
        for (it in items) {
            val startA = hubEnd
            val endA = it.box.a0
            val corridor = it.corridor
            val pts = ArrayList<Point>()
            val straight = abs(it.exit - it.entry) < 0.75f && corridor == null
            if (!straight) {
                if (corridor == null) {
                    pts += axes.point(a = it.lane1, c = it.exit)
                    pts += axes.point(a = it.lane1, c = it.entry)
                } else {
                    pts += axes.point(a = it.lane1, c = it.exit)
                    pts += axes.point(a = it.lane1, c = corridor)
                    pts += axes.point(a = it.lane2, c = corridor)
                    pts += axes.point(a = it.lane2, c = it.entry)
                }
            }
            var points = listOf(axes.point(a = startA, c = it.exit)) + pts + listOf(axes.point(a = endA, c = it.entry))
            // an edge of the original graph runs from its own source to its own target
            if (it.edge.source.nodeId != hub) points = points.reversed()
            val deduped = dedupe(points)
            if (deduped.size < 2) return null
            out[it.edge.id] =
                if (deduped.size == 2 && (deduped[0].x == deduped[1].x || deduped[0].y == deduped[1].y)) {
                    EdgeRoute.Direct(source = deduped.first(), target = deduped.last())
                } else {
                    EdgeRoute.OrthogonalRounded(
                        source = deduped.first(),
                        target = deduped.last(),
                        waypoints = deduped.subList(1, deduped.size - 1),
                        cornerRadiusPx = 0f,
                    )
                }
        }
        return out
    }

    /**
     * The vertical (flow-direction) channel through which [item] reaches a later row: the free gap between two columns
     * of the blocks in front of it that is nearest to the way from its exit to its entry; several edges in one gap
     * fan out.
     */
    private fun corridorFor(
        item: Item,
        blockers: List<Pair<NodeId, Box>>,
    ): Pair<Float, Float>? {
        val spans =
            blockers
                .map { (it.second.c0 - CORRIDOR_MARGIN_PX) to (it.second.c1 + CORRIDOR_MARGIN_PX) }
                .sortedBy { it.first }
        if (spans.isEmpty()) return item.entry to 0f
        val merged = ArrayList<Pair<Float, Float>>()
        for (s in spans) {
            if (merged.isNotEmpty() && s.first <= merged.last().second) {
                merged[merged.size - 1] = merged.last().first to max(merged.last().second, s.second)
            } else {
                merged += s
            }
        }
        val gaps = ArrayList<Triple<Float, Float, Float>>() // centre, left limit, right limit
        gaps += Triple(merged.first().first - OUTER_CORRIDOR_PX, merged.first().first - OUTER_CORRIDOR_PX, merged.first().first)
        for (i in 0 until merged.size - 1) {
            val l = merged[i].second
            val r = merged[i + 1].first
            if (r - l >= 2f) gaps += Triple((l + r) / 2f, l, r)
        }
        gaps += Triple(merged.last().second + OUTER_CORRIDOR_PX, merged.last().second, merged.last().second + OUTER_CORRIDOR_PX)
        val index = gaps.indices.minBy { abs(gaps[it].first - item.exit) + abs(gaps[it].first - item.entry) }
        val g = gaps[index]
        return g.first to max(0f, (g.third - g.second) / 2f - 1f)
    }

    /**
     * Gives every member a lane position in the band `aStart..aEnd`. Members whose cross extents overlap get different
     * lanes; the member that reaches furthest from its start takes the lane nearest to `aStart` (so the fan does not
     * cross itself). Lanes are centred in the band, at most [LANE_SPACING_PX] apart.
     */
    private fun assignLanes(
        members: List<Item>,
        aStart: Float,
        aEnd: Float,
        span: (Item) -> Pair<Float, Float>,
        set: (Item, Float) -> Unit,
    ) {
        if (members.isEmpty()) return
        val order = members.sortedWith(compareByDescending<Item> { abs(span(it).second - span(it).first) }.thenBy { it.edge.id.value })
        val lanes = ArrayList<MutableList<Pair<Float, Float>>>()
        val laneOf = HashMap<Item, Int>()
        for (m in order) {
            val (x0, x1) = span(m).let { min(it.first, it.second) to max(it.first, it.second) }
            var idx = lanes.indexOfFirst { lane -> lane.none { (l0, l1) -> x0 < l1 + LANE_CLEARANCE_PX && x1 > l0 - LANE_CLEARANCE_PX } }
            if (idx < 0) {
                lanes.add(mutableListOf())
                idx = lanes.size - 1
            }
            lanes[idx] += x0 to x1
            laneOf[m] = idx
        }
        val gap = aEnd - aStart
        val count = lanes.size
        val spacing = min(LANE_SPACING_PX, max(MIN_LANE_SPACING_PX, gap / (count + 1)))
        val usable = spacing * (count - 1)
        val first = aStart + (gap - usable) / 2f
        for (m in members) set(m, first + (laneOf.getValue(m)) * spacing)
    }

    private fun dedupe(points: List<Point>): List<Point> {
        val out = ArrayList<Point>()
        for (p in points) if (out.lastOrNull() != p) out += p
        return out
    }
}
