package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.layout.polylinePoints
import kotlin.math.abs
import kotlin.math.max

/**
 * Grid layout of requirement groups (wave 2E-a).
 *
 * ELK ignores `wrapColumns` / `sameLayer` for nodes inside compound groups, so a requirement diagram with groups would
 * lose every layout hint. This object lays the diagram out in its own way, but only when the author asks for it
 * (`wrapColumns`, `aspectRatio`, `maxWidth` or a `sameLayer` of groups, see [applies]):
 *
 *  1. every group packs its cards (in declaration order) followed by its child groups row by row into a grid of its own -
 *     `wrapColumns` works on every level and the `include` order is the reading order;
 *  2. the top-level groups (and one implicit cell for ungrouped cards) become the cells of a grid - column count from
 *     `wrapColumns`, otherwise chosen so that the canvas gets close to the aspect target, `sameLayer` groups share a row;
 *  3. ungrouped blocks stand on a **rail** next to the grid, each at the mean position of the requirements it satisfies;
 *  4. all edges are routed around the final cards (orthogonal, obstacle aware, inside the content box).
 *
 * Everything is deterministic: only lists and insertion-ordered maps are iterated, ties break on the declaration index.
 */
internal object ReqGroupGrid {
    private const val STUB_PX = 12f
    private const val BORDER_MARGIN_PX = 20f
    private const val CORNER_RADIUS_PX = 8f
    private const val ATTACH_MARGIN_PX = 12f

    /** True when the diagram has groups and the spec asks for a grid. */
    fun applies(
        graph: LayoutGraph,
        spec: LayoutSpec?,
    ): Boolean {
        if (spec == null || graph.groups.isEmpty()) return false
        val groupIds = graph.groups.map { it.id.value }.toSet()
        return spec.wrapColumns != null ||
            spec.aspectRatio != null ||
            spec.maxWidth != null ||
            spec.sameLayer.any { g -> g.any { it in groupIds } }
    }

    /** Result of [pack]. */
    data class Packing(
        val positions: List<Point>,
        val size: Size,
        /** A `sameLayer` row holds more cells than the column count. */
        val widened: Boolean,
    )

    /**
     * Row-major packing of [cells] into [cols] columns, aligned top-left, with a gutter of [LayoutLimits.GRID_GUTTER_PX].
     * [horizontal] transposes the grid (cells are filled column by column, [cols] then counts rows). Cells listed
     * together in [sameLayerRows] start a new row together; if that row is longer than [cols] it simply gets wider.
     * [flipRows] / [flipCols] mirror the grid for the Up / Left flow.
     */
    fun pack(
        cells: List<Size>,
        cols: Int,
        horizontal: Boolean,
        sameLayerRows: List<List<Int>>,
        flipRows: Boolean = false,
        flipCols: Boolean = false,
        gutterX: Float = LayoutLimits.GRID_GUTTER_PX,
        gutterY: Float = LayoutLimits.GRID_GUTTER_PX,
    ): Packing {
        if (cells.isEmpty()) return Packing(positions = emptyList(), size = Size(width = 0f, height = 0f), widened = false)
        val perRow = max(1, cols)
        val placed = BooleanArray(cells.size)
        val rows = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var widened = false

        fun flush() {
            if (current.isNotEmpty()) rows += current
            current = mutableListOf()
        }
        for (i in cells.indices) {
            if (placed[i]) continue
            val set = sameLayerRows.firstOrNull { i in it }
            if (set != null) {
                flush()
                val row = set.filter { it in cells.indices && !placed[it] }.distinct().sorted()
                row.forEach { placed[it] = true }
                if (row.size > perRow) widened = true
                rows += row
            } else {
                current += i
                placed[i] = true
                if (current.size == perRow) flush()
            }
        }
        flush()

        // logical (row, col) -> visual (row, col)
        val visual = arrayOfNulls<IntArray>(cells.size)
        val logicalRows = rows.size
        val logicalCols = rows.maxOf { it.size }
        val visualRows = if (horizontal) logicalCols else logicalRows
        val visualCols = if (horizontal) logicalRows else logicalCols
        for ((r, row) in rows.withIndex()) {
            for ((c, idx) in row.withIndex()) {
                var vr = if (horizontal) c else r
                var vc = if (horizontal) r else c
                if (flipRows) vr = visualRows - 1 - vr
                if (flipCols) vc = visualCols - 1 - vc
                visual[idx] = intArrayOf(vr, vc)
            }
        }
        val colW = FloatArray(visualCols)
        val rowH = FloatArray(visualRows)
        for (idx in cells.indices) {
            val v = visual[idx] ?: continue
            colW[v[1]] = max(colW[v[1]], cells[idx].width)
            rowH[v[0]] = max(rowH[v[0]], cells[idx].height)
        }
        val xs = FloatArray(visualCols)
        val ys = FloatArray(visualRows)
        for (c in 1 until visualCols) xs[c] = xs[c - 1] + colW[c - 1] + gutterX
        for (r in 1 until visualRows) ys[r] = ys[r - 1] + rowH[r - 1] + gutterY
        val positions =
            cells.indices.map { idx ->
                val v = visual[idx] ?: intArrayOf(0, 0)
                Point(x = xs[v[1]], y = ys[v[0]])
            }
        val width = xs.last() + colW.last()
        val height = ys.last() + rowH.last()
        return Packing(positions = positions, size = Size(width = width, height = height), widened = widened)
    }

    /**
     * Column count of the grid: `wrapColumns` if set (capped at the cell count); otherwise the count whose packed width / height
     * is closest to the aspect target (`aspectRatio`, default [LayoutLimits.GRID_DEFAULT_ASPECT]) among those that respect
     * `maxWidth`. Ties go to the smaller count.
     */
    fun chooseColumns(
        cells: List<Size>,
        spec: LayoutSpec,
        horizontal: Boolean,
        extra: Size = Size(width = 0f, height = 0f),
    ): Int {
        val n = cells.size
        if (n <= 1) return 1
        spec.wrapColumns?.let { return it.coerceIn(1, n) }
        val target = spec.aspectRatio ?: LayoutLimits.GRID_DEFAULT_ASPECT
        var best = -1
        var bestDist = Double.MAX_VALUE
        var narrowest = 1
        var narrowestWidth = Float.MAX_VALUE
        for (c in 1..n) {
            val p = pack(cells = cells, cols = c, horizontal = horizontal, sameLayerRows = emptyList())
            if (p.size.height <= 0f) continue
            val width = p.size.width + extra.width
            val height = p.size.height + extra.height
            if (width < narrowestWidth) {
                narrowestWidth = width
                narrowest = c
            }
            if (spec.maxWidth != null && width > spec.maxWidth!!) continue
            val dist = abs(width.toDouble() / height - target)
            if (dist < bestDist) {
                bestDist = dist
                best = c
            }
        }
        return if (best > 0) best else narrowest
    }

    /** Lays out [graph] as a group grid. [hints] must already carry the flow direction of the spec. */
    fun layout(
        engine: KumlLayoutEngine,
        graph: LayoutGraph,
        hints: LayoutHints,
        spec: LayoutSpec,
        findings: MutableList<LayoutSpecFinding>,
    ): LayoutResult {
        val horizontal = hints.direction == LayoutDirection.LeftToRight || hints.direction == LayoutDirection.RightToLeft
        val flipRows = hints.direction == LayoutDirection.BottomToTop
        val flipCols = hints.direction == LayoutDirection.RightToLeft
        val ctx = Ctx(graph = graph, spec = spec, horizontal = horizontal, flipRows = flipRows, flipCols = flipCols)

        val topGroups = graph.groups.filter { it.parent == null || graph.groups.none { p -> p.id == it.parent } }
        val cells = ArrayList<Cell>()
        for (g in topGroups) cells += Cell(group = g, packed = ctx.packGroup(g))
        val looseCards = graph.nodes.filter { it.groupId == null && !it.hints.rail }
        if (looseCards.isNotEmpty()) {
            val loose = ctx.packFlat(cards = looseCards, children = emptyList())
            if (loose.size.width > 0f) cells += Cell(group = null, packed = loose)
        }

        val sizes = cells.map { it.packed.size }
        val cellOfGroup =
            cells.withIndex().filter { it.value.group != null }.associate {
                it.value.group!!
                    .id.value to it.index
            }
        val rows =
            spec.sameLayer
                .take(LayoutLimits.GROUPS)
                .map { g -> g.mapNotNull { cellOfGroup[it] }.distinct() }
                .filter { it.size >= 2 }
        val railNodes = graph.nodes.filter { it.groupId == null && it.hints.rail }
        val railExtra =
            if (railNodes.isEmpty()) {
                Size(width = 0f, height = 0f)
            } else if (horizontal) {
                Size(width = 0f, height = railNodes.maxOf { it.intrinsicSize.height } + LayoutLimits.RAIL_GAP_PX)
            } else {
                Size(width = railNodes.maxOf { it.intrinsicSize.width } + LayoutLimits.RAIL_GAP_PX, height = 0f)
            }
        val cols = chooseColumns(cells = sizes, spec = spec, horizontal = horizontal, extra = railExtra)
        val packing =
            pack(cells = sizes, cols = cols, horizontal = horizontal, sameLayerRows = rows, flipRows = flipRows, flipCols = flipCols)
        if (packing.widened) {
            findings +=
                LayoutSpecFinding(
                    code = LayoutCodes.SAME_LAYER_WIDENS_ROW,
                    message = "layout.sameLayer: a row of groups is wider than the column count $cols; the row was widened.",
                )
        }

        val nodes = LinkedHashMap<NodeId, Rect>()
        val groups = LinkedHashMap<GroupId, Rect>()
        for ((i, cell) in cells.withIndex()) {
            val at = packing.positions[i]
            cell.packed.nodes.forEach { (id, r) -> nodes[id] = r.moved(dx = at.x, dy = at.y) }
            cell.packed.groups.forEach { (id, r) -> groups[id] = r.moved(dx = at.x, dy = at.y) }
        }

        placeRail(graph = graph, nodes = nodes, horizontal = horizontal)

        // Edges: every edge is routed around the final cards.
        val content = union(nodes.values + groups.values)
        val borders = borderStrips(content)
        val plans = planAttachments(graph = graph, nodes = nodes)
        val routes = LinkedHashMap<EdgeId, EdgeRoute>()
        for (e in graph.edges) {
            val s = nodes[e.source.nodeId] ?: continue
            val t = nodes[e.target.nodeId] ?: continue
            val others =
                nodes.entries
                    .filter { it.key != e.source.nodeId && it.key != e.target.nodeId }
                    .map { it.value }
            val plan = plans.getValue(e.id)
            routes[e.id] = routeBetween(source = s, target = t, plan = plan, obstacles = others + borders)
        }

        // Canvas: origin at 0/0, everything inside.
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
            edges = routes.entries.associateTo(LinkedHashMap()) { (id, r) -> id to r.moved(dx = -minX, dy = -minY) },
            groups = groups.entries.associateTo(LinkedHashMap()) { (id, r) -> id to GroupLayout(bounds = r.moved(dx = -minX, dy = -minY)) },
        )
    }

    // ── rail ───────────────────────────────────────────────────────────────

    /**
     * Ungrouped blocks stand left of the grid (flow Down/Up) or above it (Right/Left), each at the mean position of the
     * requirements it satisfies. Overlaps are resolved in target order with a minimum gap; blocks without `satisfy`
     * follow at the end.
     */
    private fun placeRail(
        graph: LayoutGraph,
        nodes: MutableMap<NodeId, Rect>,
        horizontal: Boolean,
    ) {
        val rail = graph.nodes.withIndex().filter { it.value.groupId == null && it.value.hints.rail }
        if (rail.isEmpty()) return
        val gridBounds = union(nodes.values.toList())
        val targets = LinkedHashMap<NodeId, MutableList<Float>>()
        for (e in graph.edges) {
            if (!e.id.value.startsWith("satisfy:")) continue
            val req = nodes[e.target.nodeId] ?: continue
            if (graph.nodes.none { it.id == e.source.nodeId && it.hints.rail && it.groupId == null }) continue
            targets.getOrPut(e.source.nodeId) { ArrayList() } += if (horizontal) req.centerX() else req.centerY()
        }
        val sizes = rail.associate { it.value.id to it.value.intrinsicSize }
        val ordered =
            rail.sortedWith(
                compareBy(
                    { if (targets[it.value.id].isNullOrEmpty()) 1 else 0 },
                    { targets[it.value.id]?.average() ?: 0.0 },
                    { it.index },
                ),
            )
        var cursor = Float.NEGATIVE_INFINITY
        val maxOther = rail.maxOf { if (horizontal) it.value.intrinsicSize.height else it.value.intrinsicSize.width }
        for (item in ordered) {
            val size = sizes.getValue(item.value.id)
            val along = if (horizontal) size.width else size.height
            val target = targets[item.value.id]?.average()?.toFloat()
            var start = if (target != null) target - along / 2f else (if (cursor.isInfinite()) 0f else cursor)
            if (!cursor.isInfinite()) start = max(start, cursor)
            cursor = start + along + LayoutLimits.RAIL_BLOCK_GAP_PX
            nodes[item.value.id] =
                if (horizontal) {
                    Rect(
                        origin = Point(x = start, y = gridBounds.origin.y - LayoutLimits.RAIL_GAP_PX - maxOther),
                        size = size,
                    )
                } else {
                    Rect(
                        origin = Point(x = gridBounds.origin.x - LayoutLimits.RAIL_GAP_PX - maxOther, y = start),
                        size = size,
                    )
                }
        }
    }

    // ── routing ────────────────────────────────────────────────────────────

    /** Which side of source and target an edge uses and where exactly it attaches (distinct points per side). */
    internal class Plan(
        val vertical: Boolean,
        val down: Boolean,
        val sourceAt: Point,
        val targetAt: Point,
    )

    private fun sideOf(
        source: Rect,
        target: Rect,
    ): Pair<Boolean, Boolean> {
        val gapX = max(target.origin.x - source.right(), source.origin.x - target.right())
        val gapY = max(target.origin.y - source.bottom(), source.origin.y - target.bottom())
        val vertical = gapY > 0f && gapY >= gapX || gapX <= 0f
        val forward = if (vertical) target.centerY() >= source.centerY() else target.centerX() >= source.centerX()
        return vertical to forward
    }

    /**
     * Chooses the facing sides and spreads the attachment points of all edges that meet one side of one card evenly
     * along that side (margin [ATTACH_MARGIN_PX] to the corners), ordered by the position of the other end.
     */
    internal fun planAttachments(
        graph: LayoutGraph,
        nodes: Map<NodeId, Rect>,
    ): Map<EdgeId, Plan> {
        class End(
            val edge: EdgeId,
            val atSource: Boolean,
            val key: Float,
            val order: Int,
        )

        val sides = LinkedHashMap<EdgeId, Pair<Boolean, Boolean>>()
        val ends = LinkedHashMap<Triple<NodeId, Boolean, Boolean>, MutableList<End>>()
        for ((order, e) in graph.edges.withIndex()) {
            val s = nodes[e.source.nodeId] ?: continue
            val t = nodes[e.target.nodeId] ?: continue
            val (vertical, forward) = sideOf(source = s, target = t)
            sides[e.id] = vertical to forward
            // source leaves on the forward side, target arrives on the opposite side
            val sourceSideForward = forward
            ends.getOrPut(Triple(e.source.nodeId, vertical, sourceSideForward)) { ArrayList() } +=
                End(e.id, true, if (vertical) t.centerX() else t.centerY(), order)
            ends.getOrPut(Triple(e.target.nodeId, vertical, !sourceSideForward)) { ArrayList() } +=
                End(e.id, false, if (vertical) s.centerX() else s.centerY(), order)
        }
        val at = LinkedHashMap<Pair<EdgeId, Boolean>, Point>()
        for ((k, list) in ends) {
            val box = nodes.getValue(k.first)
            val (_, vertical, forward) = k
            val sorted = list.sortedWith(compareBy({ it.key }, { it.order }, { if (it.atSource) 0 else 1 }))
            val len = if (vertical) box.size.width else box.size.height
            val usable = max(0f, len - 2 * ATTACH_MARGIN_PX)
            for ((i, end) in sorted.withIndex()) {
                val along = if (sorted.size == 1 || usable <= 0f) len / 2f else ATTACH_MARGIN_PX + (i + 0.5f) * usable / sorted.size
                at[end.edge to end.atSource] =
                    if (vertical) {
                        Point(x = box.origin.x + along, y = if (forward) box.bottom() else box.origin.y)
                    } else {
                        Point(x = if (forward) box.right() else box.origin.x, y = box.origin.y + along)
                    }
            }
        }
        val out = LinkedHashMap<EdgeId, Plan>()
        for ((id, side) in sides) {
            out[id] =
                Plan(vertical = side.first, down = side.second, sourceAt = at.getValue(id to true), targetAt = at.getValue(id to false))
        }
        return out
    }

    internal fun routeBetween(
        source: Rect,
        target: Rect,
        plan: Plan,
        obstacles: List<Rect>,
    ): EdgeRoute {
        val sOut: Point
        val tOut: Point
        if (plan.vertical) {
            sOut = Point(x = 0f, y = if (plan.down) 1f else -1f)
            tOut = Point(x = 0f, y = -sOut.y)
        } else {
            sOut = Point(x = if (plan.down) 1f else -1f, y = 0f)
            tOut = Point(x = -sOut.x, y = 0f)
        }
        val sAttach = plan.sourceAt
        val tAttach = plan.targetAt
        val from = Point(x = sAttach.x + sOut.x * STUB_PX, y = sAttach.y + sOut.y * STUB_PX)
        val to = Point(x = tAttach.x + tOut.x * STUB_PX, y = tAttach.y + tOut.y * STUB_PX)
        val poly =
            IbdObstacleRouter.route(
                from = from,
                fromDir = sOut,
                to = to,
                toDir = tOut,
                obstacles = obstacles,
                endObstacles = listOf(source, target),
            )
        return EdgeRoute.OrthogonalRounded(
            source = sAttach,
            target = tAttach,
            waypoints = simplify(poly),
            cornerRadiusPx = CORNER_RADIUS_PX,
        )
    }

    /** Drops duplicate and collinear interior points. */
    private fun simplify(points: List<Point>): List<Point> {
        val out = ArrayList<Point>()
        for (p in points) if (out.lastOrNull() != p) out += p
        var i = 1
        while (i < out.size - 1) {
            val a = out[i - 1]
            val b = out[i]
            val c = out[i + 1]
            val sameX = abs(a.x - b.x) < 0.01f && abs(b.x - c.x) < 0.01f
            val sameY = abs(a.y - b.y) < 0.01f && abs(b.y - c.y) < 0.01f
            if (sameX || sameY) out.removeAt(i) else i++
        }
        return out
    }

    /** Four thin walls around the content box; routes cannot leave the content by more than a small margin. */
    internal fun borderStrips(content: Rect): List<Rect> {
        val m = BORDER_MARGIN_PX
        val thick = 40f
        val l = content.origin.x - m
        val t = content.origin.y - m
        val w = content.size.width + 2 * m
        val h = content.size.height + 2 * m
        return listOf(
            Rect(origin = Point(x = l - thick, y = t - thick), size = Size(width = w + 2 * thick, height = thick)),
            Rect(origin = Point(x = l - thick, y = t + h), size = Size(width = w + 2 * thick, height = thick)),
            Rect(origin = Point(x = l - thick, y = t), size = Size(width = thick, height = h)),
            Rect(origin = Point(x = l + w, y = t), size = Size(width = thick, height = h)),
        )
    }

    // ── per-group packing ──────────────────────────────────────────────────

    private class Cell(
        val group: LayoutGroup?,
        val packed: Packed,
    )

    /** Contents of one block relative to its own origin (0/0). */
    private class Packed(
        val size: Size,
        val nodes: LinkedHashMap<NodeId, Rect> = LinkedHashMap(),
        val groups: LinkedHashMap<GroupId, Rect> = LinkedHashMap(),
    )

    private class Ctx(
        val graph: LayoutGraph,
        val spec: LayoutSpec,
        val horizontal: Boolean,
        val flipRows: Boolean,
        val flipCols: Boolean,
    ) {
        /** Node ids of the whole subtree of each group (declaration order). */
        private val deepMembers: Map<GroupId, Set<String>> by lazy {
            val direct = graph.groups.associate { g -> g.id to graph.nodes.filter { it.groupId == g.id }.map { it.id.value } }
            val result = LinkedHashMap<GroupId, Set<String>>()

            fun collect(g: GroupId): Set<String> {
                result[g]?.let { return it }
                val acc = LinkedHashSet<String>(direct[g].orEmpty())
                for (c in graph.groups.filter { it.parent == g }) acc += collect(c.id)
                result[g] = acc
                return acc
            }
            graph.groups.forEach { collect(it.id) }
            result
        }

        fun packGroup(g: LayoutGroup): Packed {
            val cards = graph.nodes.filter { it.groupId == g.id }
            val children = graph.groups.filter { it.parent == g.id }.map { it to packGroup(it) }
            val content = packFlat(cards = cards, children = children)
            val pad = g.padding
            val minW = g.minSize?.width ?: 0f
            val minH = g.minSize?.height ?: 0f
            val frame =
                Size(
                    width = max(minW, content.size.width + pad.left + pad.right),
                    height = max(minH, content.size.height + pad.top + pad.bottom),
                )
            val out = Packed(size = frame)
            content.nodes.forEach { (id, r) -> out.nodes[id] = r.moved(dx = pad.left, dy = pad.top) }
            content.groups.forEach { (id, r) -> out.groups[id] = r.moved(dx = pad.left, dy = pad.top) }
            out.groups[g.id] = Rect(origin = Point(x = 0f, y = 0f), size = frame)
            return out
        }

        /**
         * Packs [cards] followed by the already packed [children] row by row into a grid (same rule as the top level:
         * `wrapColumns` or the aspect target decide the column count). The declaration order is the reading order.
         * Edges play no part here - they are routed afterwards around the final cards.
         */
        fun packFlat(
            cards: List<LayoutNode>,
            children: List<Pair<LayoutGroup, Packed>>,
        ): Packed {
            if (cards.isEmpty() && children.isEmpty()) return Packed(size = Size(width = 0f, height = 0f))
            val sizes = cards.map { it.intrinsicSize } + children.map { it.second.size }
            val cols = chooseColumns(cells = sizes, spec = spec, horizontal = horizontal)
            val packing =
                pack(
                    cells = sizes,
                    cols = cols,
                    horizontal = horizontal,
                    sameLayerRows = emptyList(),
                    flipRows = flipRows,
                    flipCols = flipCols,
                )
            val out = Packed(size = packing.size)
            for ((i, c) in cards.withIndex()) {
                val at = packing.positions[i]
                out.nodes[c.id] = Rect(origin = at, size = c.intrinsicSize)
            }
            for ((j, child) in children.withIndex()) {
                val at = packing.positions[cards.size + j]
                val p = child.second
                p.nodes.forEach { (nid, nr) -> out.nodes[nid] = nr.moved(dx = at.x, dy = at.y) }
                p.groups.forEach { (gid, gr) -> out.groups[gid] = gr.moved(dx = at.x, dy = at.y) }
            }
            return out
        }
    }

    // ── geometry helpers ───────────────────────────────────────────────────

    internal fun Rect.moved(
        dx: Float,
        dy: Float,
    ): Rect = Rect(origin = Point(x = origin.x + dx, y = origin.y + dy), size = size)

    internal fun Rect.centerX(): Float = origin.x + size.width / 2f

    internal fun Rect.centerY(): Float = origin.y + size.height / 2f

    internal fun Rect.right(): Float = origin.x + size.width

    internal fun Rect.bottom(): Float = origin.y + size.height

    internal fun corners(r: Rect): List<Point> = listOf(r.origin, Point(x = r.right(), y = r.bottom()))

    internal fun union(rects: List<Rect>): Rect {
        if (rects.isEmpty()) return Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 0f, height = 0f))
        val l = rects.minOf { it.origin.x }
        val t = rects.minOf { it.origin.y }
        val r = rects.maxOf { it.right() }
        val b = rects.maxOf { it.bottom() }
        return Rect(origin = Point(x = l, y = t), size = Size(width = r - l, height = b - t))
    }

    internal fun EdgeRoute.moved(
        dx: Float,
        dy: Float,
    ): EdgeRoute {
        fun p(pt: Point) = Point(x = pt.x + dx, y = pt.y + dy)
        return when (this) {
            is EdgeRoute.Direct -> EdgeRoute.Direct(source = p(source), target = p(target))
            is EdgeRoute.OrthogonalRounded -> copy(source = p(source), target = p(target), waypoints = waypoints.map(::p))
            is EdgeRoute.TreeRounded -> copy(source = p(source), target = p(target), waypoints = waypoints.map(::p))
            is EdgeRoute.Bezier -> copy(source = p(source), target = p(target), controlPoints = controlPoints.map(::p))
        }
    }
}
