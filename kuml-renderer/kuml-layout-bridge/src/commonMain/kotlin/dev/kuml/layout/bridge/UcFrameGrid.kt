package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.ActorSide
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.ReqGroupGrid.corners
import dev.kuml.layout.bridge.ReqGroupGrid.moved
import dev.kuml.layout.bridge.ReqGroupGrid.union
import dev.kuml.layout.polylinePoints
import kotlin.math.ceil
import kotlin.math.max

/**
 * Grid layout of use-case diagrams with a subject frame (wave 2E-b).
 *
 *  1. The use cases of every subject frame are packed in declaration order into a grid: `wrapColumns` gives the column
 *     count, otherwise the smallest count whose picture (frame plus the actor columns) is at most
 *     [LayoutLimits.GRID_DEFAULT_ASPECT] wide.
 *  2. Actors stand outside the frames, stacked in declaration order and centred vertically: [ActorSide.Auto] puts up to
 *     two actors on the left and splits more of them (the first half left); `Left` / `Right` / `Both` are explicit.
 *  3. All edges are routed orthogonally around the final nodes (shared with the requirement grid), their attachment
 *     points are spread along the node sides.
 *
 * Diagrams without a subject frame keep the layered engine. Everything is deterministic (declaration order).
 */
internal object UcFrameGrid {
    private const val ACTOR_GAP_PX = 48f
    private const val ACTOR_FRAME_GAP_PX = 120f

    /** Gap between two rows of use cases: room for the lines (with their labels) that run between neighbours of one row. */
    private const val ROW_GUTTER_PX = 72f
    private const val LANE_PITCH_PX = 14f

    /** True when the diagram has at least one subject frame. */
    fun applies(graph: LayoutGraph): Boolean = graph.groups.isNotEmpty()

    /** Number of actors on the left side for [n] actors. */
    fun leftCount(
        n: Int,
        side: ActorSide?,
    ): Int =
        when (side ?: ActorSide.Auto) {
            ActorSide.Left -> n
            ActorSide.Right -> 0
            ActorSide.Auto -> if (n <= 2) n else ceil(n / 2.0).toInt()
            ActorSide.Both -> if (n <= 1) n else ceil(n / 2.0).toInt()
        }

    fun layout(
        engine: KumlLayoutEngine,
        graph: LayoutGraph,
        hints: LayoutHints,
        spec: LayoutSpec,
    ): LayoutResult {
        // The UC bridge marks actors with the `rail` node hint (only this grid reads it).
        fun isActor(n: LayoutNode): Boolean = n.hints.rail
        val actors = graph.nodes.filter { isActor(it) && it.groupId == null }
        val leftN = leftCount(n = actors.size, side = spec.actorSide)
        val left = actors.take(leftN)
        val right = actors.drop(leftN)
        val leftW = left.maxOfOrNull { it.intrinsicSize.width } ?: 0f
        val rightW = right.maxOfOrNull { it.intrinsicSize.width } ?: 0f
        val extraW = (if (left.isEmpty()) 0f else leftW + ACTOR_FRAME_GAP_PX) + (if (right.isEmpty()) 0f else rightW + ACTOR_FRAME_GAP_PX)

        // frames, one cell each; loose use cases (no frame) form one more cell
        class Cell(
            val group: dev.kuml.layout.LayoutGroup?,
            val size: Size,
            val nodes: LinkedHashMap<NodeId, Rect>,
        )
        val cells = ArrayList<Cell>()
        val frameCells = graph.groups.filter { it.parent == null }
        val asplit = Size(width = extraW, height = 0f)
        for (g in frameCells) {
            val members = graph.nodes.filter { it.groupId == g.id }
            val sizes = members.map { it.intrinsicSize }
            val cols =
                ReqGroupGrid.chooseColumns(
                    cells = sizes,
                    spec = spec,
                    horizontal = false,
                    extra = Size(width = extraW + g.padding.left + g.padding.right, height = g.padding.top + g.padding.bottom),
                )
            val pk = ReqGroupGrid.pack(cells = sizes, cols = cols, horizontal = false, sameLayerRows = emptyList(), gutterY = ROW_GUTTER_PX)
            val pad = g.padding
            val frame =
                Size(
                    width = max(g.minSize?.width ?: 0f, pk.size.width + pad.left + pad.right),
                    height = max(g.minSize?.height ?: 0f, pk.size.height + pad.top + pad.bottom),
                )
            val nodes = LinkedHashMap<NodeId, Rect>()
            members.forEachIndexed { i, m ->
                nodes[m.id] =
                    Rect(
                        origin = Point(x = pk.positions[i].x + pad.left, y = pk.positions[i].y + pad.top),
                        size = m.intrinsicSize,
                    )
            }
            cells += Cell(g, frame, nodes)
        }
        val loose = graph.nodes.filter { it.groupId == null && !isActor(it) }
        if (loose.isNotEmpty()) {
            val sizes = loose.map { it.intrinsicSize }
            val cols = ReqGroupGrid.chooseColumns(cells = sizes, spec = spec, horizontal = false, extra = asplit)
            val pk = ReqGroupGrid.pack(cells = sizes, cols = cols, horizontal = false, sameLayerRows = emptyList(), gutterY = ROW_GUTTER_PX)
            val nodes = LinkedHashMap<NodeId, Rect>()
            loose.forEachIndexed { i, m -> nodes[m.id] = Rect(origin = pk.positions[i], size = m.intrinsicSize) }
            cells += Cell(null, pk.size, nodes)
        }
        val row =
            ReqGroupGrid.pack(
                cells =
                    cells.map {
                        it.size
                    },
                cols = cells.size.coerceAtLeast(1),
                horizontal = false,
                sameLayerRows = emptyList(),
            )
        val bodyH = row.size.height
        val bodyX0 = if (left.isEmpty()) 0f else leftW + ACTOR_FRAME_GAP_PX

        val nodes = LinkedHashMap<NodeId, Rect>()
        val groups = LinkedHashMap<GroupId, Rect>()
        for ((i, c) in cells.withIndex()) {
            val dx = bodyX0 + row.positions[i].x
            val dy = row.positions[i].y
            c.nodes.forEach { (id, r) -> nodes[id] = r.moved(dx = dx, dy = dy) }
            c.group?.let { groups[it.id] = Rect(origin = Point(x = dx, y = dy), size = c.size) }
        }
        val bodyRight = bodyX0 + row.size.width

        fun stack(
            list: List<LayoutNode>,
            x: (LayoutNode) -> Float,
        ) {
            if (list.isEmpty()) return
            val total = list.sumOf { it.intrinsicSize.height.toDouble() }.toFloat() + ACTOR_GAP_PX * (list.size - 1)
            var y = (bodyH - total) / 2f
            for (a in list) {
                nodes[a.id] = Rect(origin = Point(x = x(a), y = y), size = a.intrinsicSize)
                y += a.intrinsicSize.height + ACTOR_GAP_PX
            }
        }

        // Within one side the actors are ordered by the mean height of the use cases they touch (ties: declaration
        // order), so that most lines run roughly horizontally.
        fun barycenter(a: LayoutNode): Double {
            val ys =
                graph.edges.mapNotNull { e ->
                    val other =
                        if (e.source.nodeId == a.id) {
                            e.target.nodeId
                        } else if (e.target.nodeId == a.id) {
                            e.source.nodeId
                        } else {
                            null
                        }
                    other?.let { nodes[it] }?.let { it.origin.y + it.size.height / 2f }
                }
            return if (ys.isEmpty()) Double.MAX_VALUE else ys.average()
        }
        val order = actors.withIndex().associate { it.value.id to it.index }
        val byHeight = compareBy<LayoutNode>({ barycenter(it) }, { order.getValue(it.id) })
        stack(left.sortedWith(byHeight)) { leftW - it.intrinsicSize.width } // right-aligned to the actor column
        stack(right.sortedWith(byHeight)) { bodyRight + ACTOR_FRAME_GAP_PX }

        val content = union(nodes.values + groups.values)
        val borders = ReqGroupGrid.borderStrips(content)
        val plans = ReqGroupGrid.planAttachments(graph = graph, nodes = nodes)
        val routes = LinkedHashMap<EdgeId, EdgeRoute>()
        for (e in graph.edges) {
            val s = nodes[e.source.nodeId] ?: continue
            val t = nodes[e.target.nodeId] ?: continue
            val others = nodes.entries.filter { it.key != e.source.nodeId && it.key != e.target.nodeId }.map { it.value }
            routes[e.id] = ReqGroupGrid.routeBetween(source = s, target = t, plan = plans.getValue(e.id), obstacles = others + borders)
        }

        // Lines between two use cases of one row run as a U through the gap below the row (the label then sits in the
        // free gap instead of on the neighbouring ellipses). Every line of a row gets its own lane.
        val useCaseIds = nodes.keys.filter { id -> graph.nodes.none { it.id == id && it.hints.rail } }.toSet()
        val lanes = HashMap<Float, Int>()
        for (e in graph.edges) {
            val s = nodes[e.source.nodeId] ?: continue
            val t = nodes[e.target.nodeId] ?: continue
            if (e.source.nodeId !in useCaseIds || e.target.nodeId !in useCaseIds) continue
            if (kotlin.math.abs(s.origin.y - t.origin.y) > 1f) continue // same grid row (rows are top aligned)
            val rowBottom = max(s.origin.y + s.size.height, t.origin.y + t.size.height)
            val k = (lanes[rowBottom] ?: 0)
            lanes[rowBottom] = k + 1
            val laneY = rowBottom + 18f + k * LANE_PITCH_PX
            if (laneY > rowBottom + ROW_GUTTER_PX - 12f) continue
            val dx = (k % 3 - 1) * 8f
            val sp = Point(x = s.origin.x + s.size.width / 2f + dx, y = s.origin.y + s.size.height)
            val tp = Point(x = t.origin.x + t.size.width / 2f + dx, y = t.origin.y + t.size.height)
            routes[e.id] =
                EdgeRoute.OrthogonalRounded(
                    source = sp,
                    target = tp,
                    waypoints = listOf(Point(x = sp.x, y = laneY), Point(x = tp.x, y = laneY)),
                    cornerRadiusPx = 0f,
                )
        }

        val all = ArrayList<Point>()
        nodes.values.forEach { all += corners(it) }
        groups.values.forEach { all += corners(it) }
        routes.values.forEach { all += it.polylinePoints() }
        val minX = all.minOf { it.x }
        val minY = all.minOf { it.y }
        val maxX = all.maxOf { it.x }
        val maxY = all.maxOf { it.y }
        return LayoutResult(
            engineId = engine.id,
            seed = hints.deterministicSeed,
            canvas = Size(width = maxX - minX, height = maxY - minY),
            nodes = nodes.entries.associateTo(LinkedHashMap()) { (id, r) -> id to NodeLayout(bounds = r.moved(dx = -minX, dy = -minY)) },
            edges = routes.entries.associateTo(LinkedHashMap()) { (id, r) -> id to with(ReqGroupGrid) { r.moved(dx = -minX, dy = -minY) } },
            groups = groups.entries.associateTo(LinkedHashMap()) { (id, r) -> id to GroupLayout(bounds = r.moved(dx = -minX, dy = -minY)) },
        )
    }
}
