package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteLayout
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Measures the box of a note. Injectable so tests can prove placement does not depend on the font. */
public interface NoteMeasure {
    /** Size of the note box for [body] when the box may be at most [maxWidth] wide. */
    public fun measure(
        body: String,
        maxWidth: Float,
    ): Size
}

/** The sizing contract shared with the renderer ([NoteLayout.sizeOf]). */
public object DefaultNoteMeasure : NoteMeasure {
    override fun measure(
        body: String,
        maxWidth: Float,
    ): Size = NoteLayout.sizeOf(body = body)
}

/** All numbers of the placement engine; the placement code contains no size constants of its own. */
public data class NotePlacementConfig(
    /** Maximum note width. 260 matches [NoteLayout.MAX_W], so existing notes keep their size. */
    val maxNoteWidth: Float = NoteLayout.MAX_W,
    /** Minimum distance between the exit points of two anchor lines of one note. */
    val minExitSpacing: Float = 12f,
    /** Gap between an anchor and a candidate note position. */
    val candidateGap: Float = 24f,
    /** Anchors beyond this count per note are dropped (first ones win). */
    val maxAnchors: Int = 8,
    /** Notes beyond this count per diagram are dropped (first ones win). */
    val maxNotes: Int = 200,
    /** Hard cap on obstacle tests per run; when spent, remaining routes fall back to straight lines. */
    val workBudget: Long = 4_000_000L,
    /** Maximum interior waypoints of an anchor route. */
    val maxRouteWaypoints: Int = 6,
    /** Gap between notes of the free-standing grid. */
    val gridGap: Float = 16f,
    /** Inset of exit points from the corners of the note border. */
    val exitCornerInset: Float = 10f,
    val costs: NoteCostModel = NoteCostModel(),
)

/** One anchor link of a note: [linkId] names the drawn line, [targetId] the annotated element. */
public data class NoteAnchorRef(
    val linkId: String,
    val targetId: String,
)

/** A note to place. */
public data class NotePlacementInput(
    val noteId: String,
    val body: String,
    val anchors: List<NoteAnchorRef>,
)

/** An anchor whose target the layout does not show. */
public data class UnresolvedAnchor(
    val noteId: String,
    val anchorId: String,
)

/**
 * Result of [NotePlacer.place]: [layout] now contains the notes as nodes (id = note id) and the anchor lines
 * as edges (id = link id). [truncated] lists notes cut by a limit (too many anchors, or beyond the note cap).
 */
public data class NotePlacementResult(
    val layout: LayoutResult,
    val unresolved: List<UnresolvedAnchor>,
    val truncated: List<String>,
)

/**
 * Places notes after the layout: near their anchors, away from nodes, labels and other notes, with one
 * orthogonal anchor route per anchor. Deterministic: notes are processed in declaration order, candidates in a
 * fixed order and ties go to the earlier candidate.
 */
public object NotePlacer {
    private enum class Side { E, W, S, N }

    private class Exit(
        val point: Point,
        val side: Side,
    )

    private class Anchor(
        val ref: NoteAnchorRef,
        val geometry: AnchorGeometry,
        obstacles: ObstacleIndex,
    ) {
        val rep: Point =
            when (geometry) {
                is AnchorGeometry.At -> geometry.p
                is AnchorGeometry.Along -> freePointOn(pts = geometry.pts, obstacles = obstacles)
                is AnchorGeometry.Around -> Point(x = geometry.r.cx, y = geometry.r.cy)
            }

        // A point anchor (port, lifeline stem) sits ON a box or line: that box must still not be crossed on the way.
        val exempt: Set<String> = if (geometry is AnchorGeometry.At) emptySet() else AnchorGeometryResolver.aliasesOf(ref.targetId)

        fun attach(from: Point): Point =
            when (geometry) {
                is AnchorGeometry.At -> geometry.p
                is AnchorGeometry.Along -> rep
                is AnchorGeometry.Around -> nearestBorderPoint(r = geometry.r, p = from)
            }
    }

    private class Plan(
        val rect: Rect,
        val routes: List<List<Point>>,
        val cost: Float,
    )

    /** Places [notes] into [layout]; [obstacles] is extended with the placed notes and routes. */
    public fun place(
        layout: LayoutResult,
        notes: List<NotePlacementInput>,
        resolver: AnchorGeometryResolver,
        obstacles: ObstacleIndex,
        measure: NoteMeasure = DefaultNoteMeasure,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val truncated = mutableListOf<String>()
        val unresolved = mutableListOf<UnresolvedAnchor>()
        if (notes.size > config.maxNotes) truncated += notes.drop(config.maxNotes).map { it.noteId }
        val limited = notes.take(config.maxNotes)
        val budget = ObstacleIndex.Budget(config.workBudget)

        val nodes = LinkedHashMap(layout.nodes)
        val edges = LinkedHashMap(layout.edges)
        var extent = contentExtent(layout)
        val loose = mutableListOf<Pair<NotePlacementInput, Size>>()
        val placedRects = mutableListOf<Rect>()

        for (note in limited) {
            val size = measure.measure(body = note.body, maxWidth = config.maxNoteWidth)
            if (note.anchors.size > config.maxAnchors) truncated += note.noteId
            val resolved = mutableListOf<Anchor>()
            for (ref in note.anchors.take(config.maxAnchors)) {
                val g = resolver.resolve(ref.targetId)
                if (g ==
                    null
                ) {
                    unresolved += UnresolvedAnchor(noteId = note.noteId, anchorId = ref.targetId)
                } else {
                    resolved +=
                        Anchor(ref = ref, geometry = g, obstacles = obstacles)
                }
            }
            if (resolved.isEmpty()) {
                loose += note to size
                continue
            }
            val plan = choosePlan(size = size, anchors = resolved, extent = extent, obstacles = obstacles, config = config, budget = budget)
            nodes[NodeId(note.noteId)] = NodeLayout(bounds = plan.rect)
            obstacles.addRect(kind = ObstacleKind.NOTE, r = plan.rect, ownerId = note.noteId)
            placedRects += plan.rect
            extent = extent.union(plan.rect)
            resolved.forEachIndexed { i, a ->
                val pts = plan.routes[i]
                edges[EdgeId(a.ref.linkId)] = routeOf(pts)
                obstacles.addPolyline(kind = ObstacleKind.ANCHOR_ROUTE, pts = pts, ownerId = a.ref.linkId)
                pts.forEach { extent = extent.union(rectOf(left = it.x, top = it.y, right = it.x, bottom = it.y)) }
            }
        }

        // Free-standing and unanchorable notes: a grid right of everything else.
        if (loose.isNotEmpty()) {
            val cols = min(4, ceil(sqrt(loose.size.toFloat())).toInt()).coerceAtLeast(1)
            val rows = loose.chunked(cols)
            val colWidths = (0 until cols).map { c -> rows.maxOf { r -> r.getOrNull(c)?.second?.width ?: 0f } }
            var y = 0f
            val originX = extent.right + config.candidateGap
            for (row in rows) {
                var x = originX
                row.forEachIndexed { c, (note, size) ->
                    val rect = Rect(origin = Point(x = x, y = y), size = size)
                    nodes[NodeId(note.noteId)] = NodeLayout(bounds = rect)
                    obstacles.addRect(kind = ObstacleKind.NOTE, r = rect, ownerId = note.noteId)
                    extent = extent.union(rect)
                    x += colWidths[c] + config.gridGap
                }
                y += row.maxOf { it.second.height } + config.gridGap
            }
        }

        val raw =
            layout.copy(
                canvas = Size(width = max(layout.canvas.width, extent.right), height = max(layout.canvas.height, extent.bottom)),
                nodes = nodes,
                edges = edges,
            )
        val dx = if (extent.left < 0f) -extent.left else 0f
        val dy = if (extent.top < 0f) -extent.top else 0f
        return NotePlacementResult(layout = raw.translated(dx = dx, dy = dy), unresolved = unresolved, truncated = truncated)
    }

    /** Midpoint of [pts], or the nearest of a few other points along it that lies in no label rectangle. */
    private fun freePointOn(
        pts: List<Point>,
        obstacles: ObstacleIndex,
    ): Point {
        val total = polylineLength(pts)
        if (total <= 0f || pts.size < 2) return polylineMidpoint(pts)
        for (t in ALONG_FRACTIONS) {
            val p = polylineAt(pts = pts, at = total * t)
            if (!obstacles.insideLabel(p)) return p
        }
        return polylineMidpoint(pts)
    }

    private fun contentExtent(layout: LayoutResult): Rect {
        var r = rectOf(left = 0f, top = 0f, right = layout.canvas.width, bottom = layout.canvas.height)
        layout.nodes.values.forEach { r = r.union(it.bounds) }
        layout.groups.values.forEach { r = r.union(it.bounds) }
        return r
    }

    private fun routeOf(pts: List<Point>): EdgeRoute =
        if (pts.size == 2) {
            EdgeRoute.Direct(source = pts.first(), target = pts.last())
        } else {
            EdgeRoute.OrthogonalRounded(
                source = pts.first(),
                target = pts.last(),
                waypoints = pts.subList(1, pts.size - 1),
                cornerRadiusPx = 0f,
            )
        }

    // ---------------------------------------------------------------- candidate search

    private fun choosePlan(
        size: Size,
        anchors: List<Anchor>,
        extent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        val diag = diagonalOf(anchors)
        val full =
            bestPlan(
                size = size,
                anchors = anchors,
                candidates = candidatePositions(size = size, anchors = anchors, extent = extent, config = config),
                obstacles = obstacles,
                config = config,
                budget = budget,
            )
        if (anchors.size > 1) {
            val total = full.routes.sumOf { polylineLength(it).toDouble() }.toFloat()
            if (total > diag * LONG_ROUTE_FACTOR + LONG_ROUTE_SLACK) {
                // Too long: put the note at the anchor nearest to the centroid instead.
                val cx = anchors.map { it.rep.x }.average().toFloat()
                val cy = anchors.map { it.rep.y }.average().toFloat()
                val nearest = anchors.minBy { distance(a = it.rep, b = Point(x = cx, y = cy)) }
                val near =
                    bestPlan(
                        size = size,
                        anchors = anchors,
                        candidates = candidatePositions(size = size, anchors = listOf(nearest), extent = extent, config = config),
                        obstacles = obstacles,
                        config = config,
                        budget = budget,
                    )
                return near
            }
        }
        return full
    }

    private fun diagonalOf(anchors: List<Anchor>): Float {
        var r =
            rectOf(left = anchors.first().rep.x, top = anchors.first().rep.y, right = anchors.first().rep.x, bottom = anchors.first().rep.y)
        anchors.forEach { r = r.union(rectOf(left = it.rep.x, top = it.rep.y, right = it.rep.x, bottom = it.rep.y)) }
        return sqrt(r.size.width * r.size.width + r.size.height * r.size.height)
    }

    private fun candidatePositions(
        size: Size,
        anchors: List<Anchor>,
        extent: Rect,
        config: NotePlacementConfig,
    ): List<Rect> {
        val out = ArrayList<Rect>()
        val w = size.width
        val h = size.height
        val gap = config.candidateGap

        fun around(b: Rect) {
            val ring =
                listOf(
                    b.right + gap to b.cy - h / 2f, // E
                    b.right + gap to b.top - gap - h, // NE
                    b.cx - w / 2f to b.top - gap - h, // N
                    b.left - gap - w to b.top - gap - h, // NW
                    b.left - gap - w to b.cy - h / 2f, // W
                    b.left - gap - w to b.bottom + gap, // SW
                    b.cx - w / 2f to b.bottom + gap, // S
                    b.right + gap to b.bottom + gap, // SE
                )
            ring.forEach { (x, y) -> out += Rect(origin = Point(x = x, y = y), size = size) }
        }

        fun boxOf(a: Anchor): Rect =
            when (val g = a.geometry) {
                is AnchorGeometry.Around -> g.r
                else -> rectOf(left = a.rep.x, top = a.rep.y, right = a.rep.x, bottom = a.rep.y)
            }
        var bbox = boxOf(anchors.first())
        anchors.forEach { bbox = bbox.union(boxOf(it)) }
        around(bbox)
        // Margin strips: right, bottom, left, top of the existing content.
        val cx = bbox.cx
        val cy = bbox.cy
        out += Rect(origin = Point(x = extent.right + gap, y = cy - h / 2f), size = size)
        out += Rect(origin = Point(x = cx - w / 2f, y = extent.bottom + gap), size = size)
        out += Rect(origin = Point(x = extent.left - gap - w, y = cy - h / 2f), size = size)
        out += Rect(origin = Point(x = cx - w / 2f, y = extent.top - gap - h), size = size)
        if (anchors.size > 1) anchors.forEach { around(boxOf(it)) }
        return out
    }

    private fun bestPlan(
        size: Size,
        anchors: List<Anchor>,
        candidates: List<Rect>,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        var best: Plan? = null
        for (rect in candidates) {
            val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
            if (best == null || plan.cost < best.cost) best = plan
            if (budget.exhausted && best != null) break
        }
        return best
            ?: planFor(
                rect = Rect(origin = Point(x = 0f, y = 0f), size = size),
                anchors = anchors,
                obstacles = obstacles,
                config = config,
                budget = budget,
            )
    }

    private fun planFor(
        rect: Rect,
        anchors: List<Anchor>,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        var cost = obstacles.rectCost(r = rect, costs = config.costs, budget = budget)
        val exits = assignExits(rect = rect, anchors = anchors, config = config)
        val routes = ArrayList<List<Point>>()
        for ((i, a) in anchors.withIndex()) {
            val exit = exits[i]
            val q = a.attach(exit.point)
            val (pts, c) =
                bestRoute(
                    exit = exit,
                    q = q,
                    noteRect = rect,
                    anchor = a,
                    own = routes,
                    obstacles = obstacles,
                    config = config,
                    budget = budget,
                )
            routes += pts
            cost += c
        }
        return Plan(rect = rect, routes = routes, cost = cost)
    }

    // ---------------------------------------------------------------- exits

    private fun assignExits(
        rect: Rect,
        anchors: List<Anchor>,
        config: NotePlacementConfig,
    ): List<Exit> {
        val w = rect.size.width
        val h = rect.size.height
        val capacity = { s: Side ->
            val len = if (s == Side.E || s == Side.W) h else w
            max(1, ((len - 2f * config.exitCornerInset) / config.minExitSpacing).toInt() + 1)
        }
        val used = HashMap<Side, MutableList<Int>>()
        val sideOf = IntArray(anchors.size)
        val sides = Side.values()
        val ranking =
            anchors.map { a ->
                val dx = (a.rep.x - rect.cx) / w
                val dy = (a.rep.y - rect.cy) / h
                sides.sortedByDescending {
                    when (it) {
                        Side.E -> dx
                        Side.W -> -dx
                        Side.S -> dy
                        Side.N -> -dy
                    }
                }
            }
        // Strongest affinity first, so weakly aligned anchors give way when a side is full.
        val order =
            anchors.indices.sortedByDescending { i ->
                val a = anchors[i]
                val dx = (a.rep.x - rect.cx) / w
                val dy = (a.rep.y - rect.cy) / h
                max(kotlin.math.abs(dx), kotlin.math.abs(dy))
            }
        val chosen = arrayOfNulls<Side>(anchors.size)
        for (i in order) {
            val s = ranking[i].first { used.getOrPut(it) { mutableListOf() }.size < capacity(it) }
            used.getValue(s) += i
            chosen[i] = s
            sideOf[i] = s.ordinal
        }
        val result = arrayOfNulls<Exit>(anchors.size)
        for (s in sides) {
            val members = used[s].orEmpty()
            if (members.isEmpty()) continue
            val horizontal = s == Side.E || s == Side.W
            val lo = (if (horizontal) rect.top else rect.left) + config.exitCornerInset
            val hi =
                (if (horizontal) rect.bottom else rect.right) - config.exitCornerInset - (if (s == Side.N) NoteLayout.DOG_EAR_SIZE else 0f)
            val ideal = members.associateWith { i -> (if (horizontal) anchors[i].rep.y else anchors[i].rep.x).coerceIn(lo, max(lo, hi)) }
            val sorted = members.sortedBy { ideal.getValue(it) }
            val pos = FloatArray(sorted.size) { ideal.getValue(sorted[it]) }
            for (k in 1 until pos.size) if (pos[k] < pos[k - 1] + config.minExitSpacing) pos[k] = pos[k - 1] + config.minExitSpacing
            val overflow = pos.last() - max(lo, hi)
            if (overflow > 0f) for (k in pos.indices) pos[k] -= overflow
            for (k in 1 until pos.size) if (pos[k] < pos[k - 1] + config.minExitSpacing) pos[k] = pos[k - 1] + config.minExitSpacing
            sorted.forEachIndexed { k, i ->
                val p =
                    when (s) {
                        Side.E -> Point(x = rect.right, y = pos[k])
                        Side.W -> Point(x = rect.left, y = pos[k])
                        Side.S -> Point(x = pos[k], y = rect.bottom)
                        Side.N -> Point(x = pos[k], y = rect.top)
                    }
                result[i] = Exit(point = p, side = s)
            }
        }
        return result.map { it!! }
    }

    // ---------------------------------------------------------------- routing

    private fun bestRoute(
        exit: Exit,
        q: Point,
        noteRect: Rect,
        anchor: Anchor,
        own: List<List<Point>>,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Pair<List<Point>, Float> {
        val p = exit.point
        val candidates =
            routeCandidates(p = p, q = q, exit = exit, obstacles = obstacles, exempt = anchor.exempt, config = config, budget = budget)
        val shrunk = noteRect.inflated(-1f)
        var best: List<Point> = listOf(p, q)
        var bestCost = Float.MAX_VALUE
        for (cand in candidates) {
            if (cand.size - 2 > config.maxRouteWaypoints) continue
            // The route must leave the note, not run back through it.
            if ((0 until cand.size - 1).any { segmentIntersectsRect(a = cand[it], b = cand[it + 1], r = shrunk) }) continue
            var cost = polylineLength(cand) + (cand.size - 2) * config.costs.bend
            for (i in 0 until cand.size - 1) {
                cost +=
                    obstacles.segmentCost(a = cand[i], b = cand[i + 1], exemptOwners = anchor.exempt, costs = config.costs, budget = budget)
                for (o in own) {
                    for (j in 0 until o.size - 1) {
                        if (segmentsCross(a = cand[i], b = cand[i + 1], c = o[j], d = o[j + 1])) cost += config.costs.anchorRouteCrossing
                        if (collinearOverlap(a = cand[i], b = cand[i + 1], c = o[j], d = o[j + 1]) >
                            OWN_OVERLAP
                        ) {
                            cost += config.costs.anchorRouteOverlap
                        }
                    }
                }
            }
            if (cost < bestCost) {
                bestCost = cost
                best = cand
            }
            if (budget.exhausted) break
        }
        if (bestCost == Float.MAX_VALUE) bestCost = polylineLength(best)
        return best to bestCost
    }

    private fun routeCandidates(
        p: Point,
        q: Point,
        exit: Exit,
        obstacles: ObstacleIndex,
        exempt: Set<String>,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): List<List<Point>> {
        val list = ArrayList<List<Point>>()
        list += listOf(p, q)
        if (budget.exhausted) return list
        val mx = (p.x + q.x) / 2f
        val my = (p.y + q.y) / 2f
        list += listOf(p, Point(x = q.x, y = p.y), q)
        list += listOf(p, Point(x = p.x, y = q.y), q)
        list += listOf(p, Point(x = mx, y = p.y), Point(x = mx, y = q.y), q)
        list += listOf(p, Point(x = p.x, y = my), Point(x = q.x, y = my), q)
        // A short stub away from the note first, then the Z shape (keeps lines from hugging the border).
        val stub = stubPoint(p = p, side = exit.side, d = config.candidateGap / 2f)
        list += listOf(p, stub, Point(x = stub.x, y = q.y), q)
        list += listOf(p, stub, Point(x = q.x, y = stub.y), q)
        // Detours around the rectangles that block the straight line and the L shapes.
        val blockers = LinkedHashSet<Rect>()
        blockers += obstacles.blockingRects(a = p, b = q, exemptOwners = exempt, limit = DETOUR_BLOCKERS)
        blockers += obstacles.blockingRects(a = p, b = Point(x = q.x, y = p.y), exemptOwners = exempt, limit = DETOUR_BLOCKERS)
        blockers += obstacles.blockingRects(a = Point(x = q.x, y = p.y), b = q, exemptOwners = exempt, limit = DETOUR_BLOCKERS)
        blockers += obstacles.blockingRects(a = p, b = Point(x = p.x, y = q.y), exemptOwners = exempt, limit = DETOUR_BLOCKERS)
        blockers += obstacles.blockingRects(a = Point(x = p.x, y = q.y), b = q, exemptOwners = exempt, limit = DETOUR_BLOCKERS)
        for (r in blockers.take(DETOUR_BLOCKERS * 2)) {
            val g = r.inflated(DETOUR_MARGIN)
            for (c in listOf(
                Point(x = g.left, y = g.top),
                Point(x = g.right, y = g.top),
                Point(x = g.right, y = g.bottom),
                Point(x = g.left, y = g.bottom),
            )) {
                list += listOf(p, c, q)
                list += listOf(p, Point(x = c.x, y = p.y), c, q)
                list += listOf(p, Point(x = p.x, y = c.y), c, q)
                list += listOf(p, c, Point(x = c.x, y = q.y), q)
                list += listOf(p, c, Point(x = q.x, y = c.y), q)
            }
        }
        return list.map { simplify(it) }.filter { it.size >= 2 }.distinct()
    }

    private fun stubPoint(
        p: Point,
        side: Side,
        d: Float,
    ): Point =
        when (side) {
            Side.E -> Point(x = p.x + d, y = p.y)
            Side.W -> Point(x = p.x - d, y = p.y)
            Side.S -> Point(x = p.x, y = p.y + d)
            Side.N -> Point(x = p.x, y = p.y - d)
        }

    /** Drops duplicate points and merges collinear axis-aligned runs. */
    private fun simplify(pts: List<Point>): List<Point> {
        val dedup = ArrayList<Point>()
        for (pt in pts) if (dedup.isEmpty() || distance(a = dedup.last(), b = pt) > 0.5f) dedup += pt
        if (dedup.size < 3) return dedup
        val out = ArrayList<Point>()
        out += dedup.first()
        for (i in 1 until dedup.size - 1) {
            val a = out.last()
            val b = dedup[i]
            val c = dedup[i + 1]
            val sameX = kotlin.math.abs(a.x - b.x) < 0.5f && kotlin.math.abs(b.x - c.x) < 0.5f
            val sameY = kotlin.math.abs(a.y - b.y) < 0.5f && kotlin.math.abs(b.y - c.y) < 0.5f
            if (!sameX && !sameY) out += b
        }
        out += dedup.last()
        return out
    }

    private val ALONG_FRACTIONS: List<Float> = listOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f, 0.1f, 0.9f)
    private const val LONG_ROUTE_FACTOR: Float = 1.5f
    private const val LONG_ROUTE_SLACK: Float = 200f
    private const val DETOUR_BLOCKERS: Int = 3
    private const val DETOUR_MARGIN: Float = 8f
    private const val OWN_OVERLAP: Float = 8f
}
