package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NoteKind
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

    /** Size for a note of [kind]; implementations that ignore the kind keep working unchanged. */
    public fun measure(
        body: String,
        maxWidth: Float,
        kind: NoteKind,
    ): Size = measure(body = body, maxWidth = maxWidth)
}

/** The sizing contract shared with the renderer ([NoteLayout.sizeOf]). */
public object DefaultNoteMeasure : NoteMeasure {
    override fun measure(
        body: String,
        maxWidth: Float,
    ): Size = NoteLayout.sizeOf(body = body)

    override fun measure(
        body: String,
        maxWidth: Float,
        kind: NoteKind,
    ): Size = NoteLayout.sizeOf(body = body, kind = kind)
}

/** Measures notes that wrap at [width] instead of [NoteLayout.MAX_W] (state machines: [NoteLayout.STM_MAX_W]). */
public class WidthLimitedNoteMeasure(
    private val width: Float,
) : NoteMeasure {
    override fun measure(
        body: String,
        maxWidth: Float,
    ): Size = NoteLayout.sizeOf(body = body, maxWidth = minOf(width, maxWidth))

    override fun measure(
        body: String,
        maxWidth: Float,
        kind: NoteKind,
    ): Size = NoteLayout.sizeOf(body = body, maxWidth = minOf(width, maxWidth), kind = kind)
}

/** All numbers of the placement engine; the placement code contains no size constants of its own. */
public data class NotePlacementConfig(
    /** Maximum note width. 260 matches [NoteLayout.MAX_W], so existing notes keep their size. */
    val maxNoteWidth: Float = NoteLayout.MAX_W,
    /** Minimum distance between the exit points of two anchor lines of one note. */
    val minExitSpacing: Float = 16f,
    /** Gap between an anchor and a candidate note position. */
    val candidateGap: Float = 24f,
    /** Anchors beyond this count per note are dropped (first ones win). */
    val maxAnchors: Int = 8,
    /** Notes beyond this count per diagram are dropped (first ones win). */
    val maxNotes: Int = 200,
    /** Hard cap on obstacle tests per run; when spent, remaining routes fall back to straight lines. */
    val workBudget: Long = 4_000_000L,
    /** Maximum interior waypoints of an anchor route. */
    val maxRouteWaypoints: Int = 2,
    /** Shortest stub a detour or emergency route leaves the note (or enters the anchor) with. */
    val stubMin: Float = 12f,
    /** Gap between a note and the lifeline stem or port square it is anchored to. */
    val anchorSideGap: Float = 16f,
    /** True if the layout's port positions are local to their node (IBD): they must not move with a translation. */
    val localPorts: Boolean = false,
    /** Gap between notes of the free-standing grid. */
    val gridGap: Float = 16f,
    /** Inset of exit points from the corners of the note border. */
    val exitCornerInset: Float = 10f,
    /** Minimum distance between a note and a part box (PAL wave 2C-b2b). */
    val minPartGap: Float = 8f,
    /** Minimum distance between two anchors' attach points on the same polyline. */
    val minAttachSpacing: Float = 16f,
    val costs: NoteCostModel = NoteCostModel(),
    /**
     * If set (PAL wave 2D-b, state machines), the diagram including its notes may be at most this factor wider than
     * without notes. Candidate positions that would widen it further are dropped; when none is left, the notes are
     * stacked vertically below the content instead.
     */
    val maxWidthFactor: Float? = null,
    /** Gap between vertically stacked notes (only used together with [maxWidthFactor]). */
    val stackGap: Float = 12f,
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
    val kind: NoteKind = NoteKind.NOTE,
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
    /** Shift the layout got because a note landed at negative coordinates; positions planned before placement must move by it. */
    val dx: Float = 0f,
    val dy: Float = 0f,
)

/**
 * Places notes after the layout: near their anchors, away from nodes, labels and other notes, with one
 * orthogonal anchor route per anchor. Deterministic: notes are processed in declaration order, candidates in a
 * fixed order and ties go to the earlier candidate.
 */
public object NotePlacer {
    private const val WIDTH_EPS = 0.5f

    private class Exit(
        val point: Point,
        val side: Side,
    )

    private class Anchor(
        val ref: NoteAnchorRef,
        val geometry: AnchorGeometry,
        obstacles: ObstacleIndex,
        used: List<Point> = emptyList(),
        spacing: Float = 0f,
    ) {
        val rep: Point =
            when (geometry) {
                is AnchorGeometry.At -> geometry.p
                is AnchorGeometry.Port -> outerMidpoint(box = geometry.box, side = geometry.outward)
                is AnchorGeometry.Stem -> Point(x = geometry.x, y = geometry.top + STEM_FIRST_OFFSET)
                is AnchorGeometry.Along -> freePointOn(pts = geometry.pts, obstacles = obstacles, used = used, spacing = spacing)
                is AnchorGeometry.Around -> Point(x = geometry.r.cx, y = geometry.r.cy)
            }

        // A point anchor (port, lifeline stem) sits ON a box or line: that box must still not be crossed on the way.
        val exempt: Set<String> =
            when (geometry) {
                is AnchorGeometry.At, is AnchorGeometry.Port -> emptySet()
                else -> AnchorGeometryResolver.aliasesOf(ref.targetId)
            }

        /** Where the anchor sits for a note occupying [rect]: a stem anchor follows the note's height. */
        fun repFor(rect: Rect): Point =
            when (geometry) {
                is AnchorGeometry.Stem -> Point(x = geometry.x, y = rect.cy.coerceIn(geometry.top, geometry.bottom))
                else -> rep
            }

        fun attach(from: Point): Point =
            when (geometry) {
                is AnchorGeometry.At -> geometry.p
                is AnchorGeometry.Port -> rep
                is AnchorGeometry.Stem -> Point(x = geometry.x, y = from.y.coerceIn(geometry.top, geometry.bottom))
                is AnchorGeometry.Along -> rep
                is AnchorGeometry.Around -> nearestBorderPoint(r = geometry.r, p = from)
            }

        /**
         * Points the line may end at. A port square: the middle of its outer side first, then 4.5 px beside it, for a
         * port whose own connection leaves through the middle (the anchor must not lie on that connection).
         */
        fun attachOptions(from: Point): List<Point> =
            if (geometry is AnchorGeometry.Port) {
                val horizontal = geometry.outward == Side.N || geometry.outward == Side.S
                val outer =
                    listOf(0f, PORT_SIDE_OFFSET, -PORT_SIDE_OFFSET).map { d ->
                        if (horizontal) Point(x = rep.x + d, y = rep.y) else Point(x = rep.x, y = rep.y + d)
                    }
                // PAL 2C-b2b: the lateral faces of the square, on the outward half (4.5 px off the border line, so the line does not run along it).
                val b = geometry.box
                val sign = if (geometry.outward == Side.S || geometry.outward == Side.E) 1f else -1f
                val lateral =
                    if (horizontal) {
                        listOf(
                            Point(x = b.left, y = b.cy + sign * PORT_SIDE_OFFSET),
                            Point(x = b.right, y = b.cy + sign * PORT_SIDE_OFFSET),
                        )
                    } else {
                        listOf(
                            Point(x = b.cx + sign * PORT_SIDE_OFFSET, y = b.top),
                            Point(x = b.cx + sign * PORT_SIDE_OFFSET, y = b.bottom),
                        )
                    }
                outer + lateral
            } else {
                listOf(attach(from))
            }

        /** A port line must arrive straight from outside the square, a stem line horizontally. */
        fun arrivalOk(route: List<Point>): Boolean {
            val prev = route[route.size - 2]
            val q = route.last()
            return when (geometry) {
                is AnchorGeometry.Port -> {
                    val b = geometry.box
                    val eps = AXIS_EPS
                    val face =
                        when {
                            kotlin.math.abs(q.y - b.bottom) < eps && q.x > b.left + eps && q.x < b.right - eps -> Side.S
                            kotlin.math.abs(q.y - b.top) < eps && q.x > b.left + eps && q.x < b.right - eps -> Side.N
                            kotlin.math.abs(q.x - b.right) < eps -> Side.E
                            kotlin.math.abs(q.x - b.left) < eps -> Side.W
                            else -> geometry.outward
                        }
                    when (face) {
                        Side.E -> kotlin.math.abs(prev.y - q.y) < AXIS_EPS && prev.x > q.x
                        Side.W -> kotlin.math.abs(prev.y - q.y) < AXIS_EPS && prev.x < q.x
                        Side.S -> kotlin.math.abs(prev.x - q.x) < AXIS_EPS && prev.y > q.y
                        Side.N -> kotlin.math.abs(prev.x - q.x) < AXIS_EPS && prev.y < q.y
                    } &&
                        // PAL 2C-b2: no bend within PORT_STRAIGHT_PX of the port: the last segment is straight for at least that far.
                        (route.size == 2 || distance(a = prev, b = q) >= PORT_STRAIGHT_PX)
                }
                is AnchorGeometry.Stem -> kotlin.math.abs(prev.y - q.y) < AXIS_EPS
                else -> true
            }
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
        val baseExtent = extent
        val loose = mutableListOf<Pair<NotePlacementInput, Size>>()
        val placedRects = mutableListOf<Rect>()
        val usedAlong = HashMap<String, MutableList<Point>>()

        for (note in limited) {
            val size = measure.measure(body = note.body, maxWidth = config.maxNoteWidth, kind = note.kind)
            if (note.anchors.size > config.maxAnchors) truncated += note.noteId
            val resolved = mutableListOf<Anchor>()
            for (ref in note.anchors.take(config.maxAnchors)) {
                val g = resolver.resolve(ref.targetId)
                if (g ==
                    null
                ) {
                    unresolved += UnresolvedAnchor(noteId = note.noteId, anchorId = ref.targetId)
                } else {
                    // PAL 2C-b2b: anchors on one connection get their own attach points, at least minAttachSpacing apart.
                    val used = usedAlong.getOrPut(ref.targetId) { mutableListOf() }
                    val anchor = Anchor(ref = ref, geometry = g, obstacles = obstacles, used = used, spacing = config.minAttachSpacing)
                    if (g is AnchorGeometry.Along) used += anchor.rep
                    resolved += anchor
                }
            }
            if (resolved.isEmpty()) {
                loose += note to size
                continue
            }
            val plan =
                choosePlan(
                    size = size,
                    anchors = resolved,
                    extent = extent,
                    baseExtent = baseExtent,
                    obstacles = obstacles,
                    config = config,
                    budget = budget,
                )
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
        return NotePlacementResult(
            layout = raw.translated(dx = dx, dy = dy, shiftPorts = !config.localPorts),
            unresolved = unresolved,
            truncated = truncated,
            dx = dx,
            dy = dy,
        )
    }

    /** Midpoint of [pts], or the nearest of a few other points along it that lies in no label rectangle. */
    private fun freePointOn(
        pts: List<Point>,
        obstacles: ObstacleIndex,
        used: List<Point> = emptyList(),
        spacing: Float = 0f,
    ): Point {
        val total = polylineLength(pts)
        if (total <= 0f || pts.size < 2) return polylineMidpoint(pts)
        for (t in ALONG_FRACTIONS) {
            val p = polylineAt(pts = pts, at = total * t)
            if (!obstacles.insideLabel(p) && used.none { distance(a = it, b = p) < spacing }) return p
        }
        for (t in ALONG_FRACTIONS) {
            val p = polylineAt(pts = pts, at = total * t)
            if (used.none { distance(a = it, b = p) < spacing }) return p
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
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        val diag = diagonalOf(anchors)
        val full =
            bestPlan(
                size = size,
                anchors = anchors,
                candidates =
                    withinWidthBudget(
                        candidates = candidatePositions(size = size, anchors = anchors, extent = extent, config = config),
                        size = size,
                        anchors = anchors,
                        extent = extent,
                        baseExtent = baseExtent,
                        config = config,
                    ),
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
                        candidates =
                            withinWidthBudget(
                                candidates = candidatePositions(size = size, anchors = listOf(nearest), extent = extent, config = config),
                                size = size,
                                anchors = listOf(nearest),
                                extent = extent,
                                baseExtent = baseExtent,
                                config = config,
                            ),
                        obstacles = obstacles,
                        config = config,
                        budget = budget,
                    )
                return near
            }
        }
        return full
    }

    /**
     * Keeps the candidates that do not widen the diagram beyond [NotePlacementConfig.maxWidthFactor] times the width
     * of [baseExtent]; without a factor all candidates pass. Always added: positions stacked vertically below the content
     * (3 horizontal positions inside the budget: centred on the anchor, left-aligned, right-aligned).
     */
    private fun withinWidthBudget(
        candidates: List<Rect>,
        size: Size,
        anchors: List<Anchor>,
        extent: Rect,
        baseExtent: Rect,
        config: NotePlacementConfig,
    ): List<Rect> {
        val factor = config.maxWidthFactor ?: return candidates
        val maxWidth = baseExtent.size.width * factor
        val fits = candidates.filter { extent.union(it).size.width <= maxWidth + WIDTH_EPS }
        val minX = extent.left
        val maxX = max(minX, extent.left + maxWidth - size.width)
        val y = extent.bottom + config.stackGap
        val anchorX = anchors.map { it.rep.x }.average().toFloat()
        val stacked =
            listOf(anchorX - size.width / 2f, minX, maxX)
                .map { it.coerceIn(minX, maxX) }
                .distinct()
                .map { x -> Rect(origin = Point(x = x, y = y), size = size) }
        // The cost model picks between the fitting side positions and the stacked ones, so a note is never forced
        // onto a title or a line just because the only other spots would be too wide.
        return fits + stacked
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

        fun around(
            b: Rect,
            gap: Float = config.candidateGap,
        ) {
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
                is AnchorGeometry.Port -> g.box
                else -> rectOf(left = a.rep.x, top = a.rep.y, right = a.rep.x, bottom = a.rep.y)
            }
        val only = anchors.singleOrNull()?.geometry
        if (only is AnchorGeometry.Stem) {
            // Beside the lifeline, the note's height on a free stretch of the stem; 24 px steps from the head down.
            val side = config.anchorSideGap
            var cy = only.top + h / 2f + STEM_FIRST_OFFSET / 2f
            var steps = 0
            while (cy - h / 2f < only.bottom && steps < MAX_STEM_STEPS) {
                out += Rect(origin = Point(x = only.x + side, y = cy - h / 2f), size = size)
                out += Rect(origin = Point(x = only.x - side - w, y = cy - h / 2f), size = size)
                cy += STEM_STEP
                steps++
            }
        }
        if (only is AnchorGeometry.Port) {
            // In the direction the port sticks out, then the other sides; always clear of the square.
            val b = only.box
            val g = config.anchorSideGap
            val order =
                listOf(only.outward) + Side.values().filter { it != only.outward && it != opposite(only.outward) } + opposite(only.outward)
            for (s in order) {
                for (shift in listOf(0f, -(h / 2f + g), h / 2f + g)) {
                    val r =
                        when (s) {
                            Side.E -> Rect(origin = Point(x = b.right + g, y = b.cy - h / 2f + shift), size = size)
                            Side.W -> Rect(origin = Point(x = b.left - g - w, y = b.cy - h / 2f + shift), size = size)
                            Side.S -> Rect(origin = Point(x = b.cx - w / 2f + shift, y = b.bottom + g), size = size)
                            Side.N -> Rect(origin = Point(x = b.cx - w / 2f + shift, y = b.top - g - h), size = size)
                        }
                    out += r
                }
            }
        }
        var bbox = boxOf(anchors.first())
        anchors.forEach { bbox = bbox.union(boxOf(it)) }
        around(bbox)
        // PAL 2C-b2b: with the 8 px part gap the first ring can be full; wider rings keep a free spot in reach.
        around(bbox, gap * 2f)
        around(bbox, gap * 3.5f)
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
        var cost = obstacles.rectCost(r = rect, costs = config.costs, budget = budget, partGap = config.minPartGap)
        val exits = assignExits(rect = rect, anchors = anchors, config = config)
        val routes = ArrayList<List<Point>>()
        for ((i, a) in anchors.withIndex()) {
            val exit = exits[i]
            var chosen: Pair<List<Point>, Float>? = null
            for (q in a.attachOptions(exit.point)) {
                val r =
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
                if (chosen == null || r.second < chosen.second) chosen = r
                if (r.second < NO_ROUTE_PENALTY) break
            }
            val (pts0, c) = chosen!!
            val pts = removeTinyJog(pts0)
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
                val ar = a.repFor(rect)
                val dx = (ar.x - rect.cx) / w
                val dy = (ar.y - rect.cy) / h
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
                val ar = anchors[i].repFor(rect)
                val dx = (ar.x - rect.cx) / w
                val dy = (ar.y - rect.cy) / h
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
            val ideal =
                members.associateWith { i ->
                    val ar = anchors[i].repFor(rect)
                    (if (horizontal) ar.y else ar.x).coerceIn(lo, max(lo, hi))
                }
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
        val shrunk = noteRect.inflated(-1f)

        fun cost(cand: List<Point>): Float {
            var cost = polylineLength(cand) + (cand.size - 2) * config.costs.bend
            for (i in 0 until cand.size - 1) {
                cost +=
                    obstacles.segmentCost(a = cand[i], b = cand[i + 1], exemptOwners = anchor.exempt, costs = config.costs, budget = budget)
                for (o in own) {
                    for (j in 0 until o.size - 1) {
                        if (segmentsCross(a = cand[i], b = cand[i + 1], c = o[j], d = o[j + 1])) cost += config.costs.anchorRouteCrossing
                    }
                }
            }
            return cost
        }

        /** Hard rules: leaves the note, few segments, arrives properly, never runs along a border or another line. */
        fun allowed(
            cand: List<Point>,
            maxSegments: Int,
        ): Boolean =
            cand.size - 1 <= maxSegments &&
                (0 until cand.size - 1).all { distance(a = cand[it], b = cand[it + 1]) > AXIS_EPS } &&
                (cand.size == 2 || (0 until cand.size - 1).all { axisAligned(a = cand[it], b = cand[it + 1]) }) &&
                (0 until cand.size - 1).none { segmentIntersectsRect(a = cand[it], b = cand[it + 1], r = shrunk) } &&
                anchor.arrivalOk(cand) &&
                !obstacles.routeCrossesBoxes(pts = cand, exemptOwners = anchor.exempt, budget = budget) &&
                // A port line must not end on the port's own connection: it would read as part of it.
                !(anchor.geometry is AnchorGeometry.Port && obstacles.nearEdge(p = cand.last(), tolerance = RUN_ALONG_TOLERANCE)) &&
                !obstacles.routeRunsAlong(pts = cand, extraRects = listOf(noteRect), extraLines = own, budget = budget)

        fun pick(
            list: List<List<Point>>,
            maxSegments: Int,
        ): Pair<List<Point>, Float>? {
            var best: List<Point>? = null
            var bestCost = Float.MAX_VALUE
            for (cand in list) {
                if (!allowed(cand = cand, maxSegments = maxSegments)) continue
                val c = cost(cand)
                if (c < bestCost) {
                    bestCost = c
                    best = cand
                }
                if (budget.exhausted) break
            }
            return best?.let { it to bestCost }
        }
        val regular =
            routeCandidates(p = p, q = q, exit = exit, obstacles = obstacles, exempt = anchor.exempt, config = config, budget = budget)
                .filter { it.size - 2 <= config.maxRouteWaypoints }
        // PAL 2C-b2: a straight anchor that crosses nothing wins outright; then at most one bend; more only as the last regular choice.
        val straight = regular.firstOrNull { it.size == 2 }
        if (straight != null && allowed(cand = straight, maxSegments = 1)) {
            val c = cost(straight)
            if (c - polylineLength(straight) < STRAIGHT_FREE_EPS) return straight to c
        }
        // One-bend tier only when the route is also crossing-free (cost = length + bends only); a crossing L must not pre-empt a free Z.
        pick(list = regular.filter { it.size - 2 <= 1 }, maxSegments = 2)?.let { (route, c) ->
            if (c - polylineLength(route) - (route.size - 2) * config.costs.bend < STRAIGHT_FREE_EPS) return route to c
        }
        pick(list = regular, maxSegments = config.maxRouteWaypoints + 1)?.let { return it }
        // Emergency: a stub away from the note and into the anchor, growing until nothing lies on top of anything.
        val emergency = emergencyCandidates(p = p, q = q, exit = exit, anchor = anchor, config = config)
        pick(list = emergency, maxSegments = config.maxRouteWaypoints + 1)?.let { return it }
        pick(list = emergency, maxSegments = EMERGENCY_SEGMENTS)?.let { return it }
        // Nothing passes the hard rules (hostile input): the cheapest emergency route that at least leaves the note.
        val fallback = emergency.filter { c -> (0 until c.size - 1).none { segmentIntersectsRect(a = c[it], b = c[it + 1], r = shrunk) } }
        val last = fallback.minByOrNull { cost(it) } ?: listOf(p, q)
        // No route obeys the rules from this note position: make the position lose against any that has one.
        return last to (cost(last) + NO_ROUTE_PENALTY)
    }

    /** Routes with a stub of growing length at the note exit and at the anchor end; always at most 4 segments. */
    private fun emergencyCandidates(
        p: Point,
        q: Point,
        exit: Exit,
        anchor: Anchor,
        config: NotePlacementConfig,
    ): List<List<Point>> {
        val out = ArrayList<List<Point>>()
        val geometry = anchor.geometry
        for (d in listOf(config.stubMin, config.stubMin * 2, config.stubMin * 4, config.stubMin * 8)) {
            val enter =
                when {
                    geometry is AnchorGeometry.Port -> stubPoint(p = q, side = geometry.outward, d = d)
                    geometry is AnchorGeometry.Stem -> Point(x = if (p.x < q.x) q.x - d else q.x + d, y = q.y)
                    else -> null
                }
            if (enter != null) {
                out += listOf(p, Point(x = enter.x, y = p.y), enter, q)
                out += listOf(p, Point(x = p.x, y = enter.y), enter, q)
                continue
            }
            val s = stubPoint(p = p, side = exit.side, d = d)
            out += listOf(p, s, Point(x = s.x, y = q.y), q)
            out += listOf(p, s, Point(x = q.x, y = s.y), q)
        }
        return out.map { simplify(it) }.filter { it.size >= 2 }.distinct()
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
        val stub = stubPoint(p = p, side = exit.side, d = maxOf(config.stubMin, config.candidateGap / 2f))
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

    private fun axisAligned(
        a: Point,
        b: Point,
    ): Boolean = kotlin.math.abs(a.x - b.x) < AXIS_EPS || kotlin.math.abs(a.y - b.y) < AXIS_EPS

    private fun outerMidpoint(
        box: Rect,
        side: Side,
    ): Point =
        when (side) {
            Side.E -> Point(x = box.right, y = box.cy)
            Side.W -> Point(x = box.left, y = box.cy)
            Side.S -> Point(x = box.cx, y = box.bottom)
            Side.N -> Point(x = box.cx, y = box.top)
        }

    private fun opposite(side: Side): Side =
        when (side) {
            Side.E -> Side.W
            Side.W -> Side.E
            Side.S -> Side.N
            Side.N -> Side.S
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

    /** A first segment shorter than 3 px (a jog of a few pixels at the note exit) is absorbed: the exit slides along the note border. */
    private fun removeTinyJog(pts: List<Point>): List<Point> {
        if (pts.size != 3) return pts
        val (p, w, q) = pts
        val tiny = distance(a = p, b = w)
        if (tiny >= 3f) return pts
        val straightAfter = kotlin.math.abs(w.x - q.x) < 0.5f || kotlin.math.abs(w.y - q.y) < 0.5f
        if (!straightAfter) return pts
        return simplify(listOf(w, q)).let {
            listOf(
                if (kotlin.math.abs(p.x - w.x) <
                    0.5f
                ) {
                    Point(x = p.x, y = w.y)
                } else {
                    Point(x = w.x, y = p.y)
                },
                q,
            )
        }
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
    private const val AXIS_EPS: Float = 0.5f
    private const val PORT_STRAIGHT_PX: Float = 8f
    private const val STRAIGHT_FREE_EPS: Float = 1f
    private const val PORT_SIDE_OFFSET: Float = 4.5f
    private const val NO_ROUTE_PENALTY: Float = 200_000f
    private const val STEM_FIRST_OFFSET: Float = 24f
    private const val STEM_STEP: Float = 24f
    private const val MAX_STEM_STEPS: Int = 80
    private const val EMERGENCY_SEGMENTS: Int = 4
}
