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
     * stacked vertically below the content instead. With [edgeClearance] (Nacharbeit 26) this is only an emergency brake
     * for ring candidates and the strip, never below the width of two note columns; the columns beside the content
     * are exempt.
     */
    val maxWidthFactor: Float? = null,
    /** Gap between vertically stacked notes (only used together with [maxWidthFactor]). */
    val stackGap: Float = 12f,
    /**
     * If set (Nacharbeit 25, state machines), a hard rule replaces the soft edge penalty: a candidate whose rectangle
     * comes closer than this to a diagram edge or a part box (or closer than [gridGap] to another note) is excluded.
     * The anchor lines of a note are exempt by construction (they are planned after the rectangle is chosen). When
     * every nearby candidate is excluded, the note goes into a strip below all content (see `stripPlan`). Since
     * Nacharbeit 26 the notes first try columns beside the content, at the anchor's height, and prefer positions whose
     * anchor lines cross nothing; the strip is the last fallback.
     * Null keeps the behaviour of every other diagram type unchanged.
     */
    val edgeClearance: Float? = null,
    /** Nacharbeit 29: swimlanes (see [StrictNotePolicy.lanes]); only used together with [edgeClearance]. */
    val lanes: List<LaneRect> = emptyList(),
    /** Nacharbeit 29: labels and composite titles are hard obstacles (see [StrictNotePolicy.hardLabelsAndTitles]). */
    val hardLabelsAndTitles: Boolean = false,
    /** Nacharbeit 29: place the notes in the order of their anchors' height. */
    val sortByAnchorY: Boolean = false,
    /** Distance a note keeps from the border of its lane. */
    val laneInset: Float = 16f,
    /** Distance a note outside all lanes keeps from them. */
    val laneOuterGap: Float = 24f,
    /** Nacharbeit 38: the layout flow axis; a note placed along it pays [axisPenaltyPx] (only with [edgeClearance]). */
    val flowAxis: FlowAxis = FlowAxis.NONE,
    /** Nacharbeit 38: extra cost of a note beside its anchor along the flow axis. */
    val axisPenaltyPx: Float = StrictNotePolicy.DEFAULT_OFF_AXIS_PENALTY,
    /** Nacharbeit 38: soft cost of an anchor line within 6..12 px of a foreign node or port; 0 = off. */
    val anchorNearPenalty: Float = 0f,
    /** Nacharbeit 38: a note rectangle may not sit on the anchor line of an earlier note (6 px). */
    val hardAnchorLines: Boolean = false,
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
    /** Sequence diagrams (Nacharbeit 39): notes that fit no admissible column and went into the last-resort margin position. */
    val degraded: List<String> = emptyList(),
    /** Sequence diagrams (Nacharbeit 39): tolerated crossings of anchor lines with foreign stems, messages and frame edges. */
    val crossings: Int = 0,
)

/**
 * Places notes after the layout: near their anchors, away from nodes, labels and other notes, with one
 * orthogonal anchor route per anchor. Deterministic: notes are processed in declaration order, candidates in a
 * fixed order and ties go to the earlier candidate.
 */
public object NotePlacer {
    private const val WIDTH_EPS = 0.5f
    private const val TIE_EPS = 1f

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
                is AnchorGeometry.Ellipse -> Point(x = geometry.cx, y = geometry.cy)
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
                is AnchorGeometry.Ellipse -> EllipseGeometry.onRay(e = geometry, toward = from)
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
            } else if (geometry is AnchorGeometry.Ellipse) {
                // Axis projections first (they keep the last segment axis-parallel), the ray for a diagonal direct line.
                listOfNotNull(
                    EllipseGeometry.horizontalHit(e = geometry, y = from.y, fromLeft = from.x < geometry.cx),
                    EllipseGeometry.verticalHit(e = geometry, x = from.x, fromTop = from.y < geometry.cy),
                    attach(from),
                ).distinct()
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
                // The last segment must not run through the ellipse: it leaves the outline point outward.
                is AnchorGeometry.Ellipse -> EllipseGeometry.leavesOutward(e = geometry, q = q, prev = prev)
                else -> true
            }
        }
    }

    private class Plan(
        val rect: Rect,
        val routes: List<List<Point>>,
        val cost: Float,
        /** False if at least one anchor line obeys no routing rule from this position. */
        val routed: Boolean = true,
        /** True if the strip search hit [MAX_STRIP_ROWS] and this plan ignores the clearance rule (the note is reported as truncated). */
        val stripExhausted: Boolean = false,
        /** Nacharbeit 26: true if no anchor line crosses a node, a foreign note, a label or an edge (only evaluated with an edge clearance). */
        val clean: Boolean = false,
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
        val limited =
            notes.take(config.maxNotes).let { taken ->
                if (config.sortByAnchorY) taken.sortedBy { anchorHeight(note = it, resolver = resolver) } else taken
            }
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
            if (plan.stripExhausted) truncated += note.noteId
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
                extent = extent,
                baseExtent = baseExtent,
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
                        extent = extent,
                        baseExtent = baseExtent,
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
        val maxWidth = effectiveMaxWidth(baseExtent = baseExtent, config = config) ?: return candidates
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
                is AnchorGeometry.Ellipse -> g.bounds
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
        // Notes N2: still wider rings keep a free spot in reach when many notes share an anchor.
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
        extent: Rect,
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        // Notes N2: a position on top of another note is never an option; the cost model only ranks the free ones.
        val clearance = config.edgeClearance
        if (clearance != null) {
            return bestPlanWithClearance(
                size = size,
                anchors = anchors,
                candidates = candidates,
                extent = extent,
                baseExtent = baseExtent,
                obstacles = obstacles,
                config = config,
                budget = budget,
                clearance = clearance,
            )
        }
        var best: Plan? = null
        for (rect in candidates) {
            if (obstacles.overlapsNote(rect)) continue
            val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
            if (best == null || plan.cost < best.cost) best = plan
            if (budget.exhausted && best != null) break
        }
        if (best != null && best.routed) return best
        // No free position with a clean anchor line: the column fallback always finds room (rows below each other).
        val column =
            if (config.maxWidthFactor !=
                null
            ) {
                null
            } else {
                columnPlan(size = size, anchors = anchors, extent = extent, baseExtent = baseExtent, obstacles = obstacles, config = config)
            }
        if (column != null && (best == null || column.routed || column.cost < best.cost)) return column
        return best
            ?: planFor(
                rect = Rect(origin = Point(x = extent.right + config.candidateGap, y = extent.bottom + config.candidateGap), size = size),
                anchors = anchors,
                obstacles = obstacles,
                config = config,
                budget = ObstacleIndex.Budget(config.workBudget),
            )
    }

    /**
     * Nacharbeit 26 selection for [NotePlacementConfig.edgeClearance]: lexicographic, a crossing-free anchor line beats a
     * cheap one. A: columns beside the content; B: the ring candidates; C: the strip below; D: the cheapest of everything
     * found (the old cost model, crossings priced as before). Every stage only returns a plan that is clean and routed.
     */
    private fun bestPlanWithClearance(
        size: Size,
        anchors: List<Anchor>,
        candidates: List<Rect>,
        extent: Rect,
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
        clearance: Float,
    ): Plan {
        val laneMode = config.lanes.isNotEmpty()
        val laneExt = laneExtent(baseExtent = baseExtent, config = config)
        if (laneMode) {
            // Nacharbeit 29, stage 1: inside the anchor's own lane, beside the anchor.
            inLanePlans(
                size = size,
                anchors = anchors,
                candidates = candidates,
                obstacles = obstacles,
                config = config,
                clearance = clearance,
            ).let { pickClean(it) }?.let { return it }
        }
        // A: side columns derived from the content extent (with lanes: the near column first, then the far one).
        val columns =
            columnPlans(
                size = size,
                anchors = anchors,
                baseExtent = if (laneMode) laneExt else baseExtent,
                obstacles = obstacles,
                config = config,
                clearance = clearance,
            )
        val flowMode = config.flowAxis != FlowAxis.NONE && !laneMode
        if (flowMode) {
            // Nacharbeit 38: with a flow preference the columns and the ring compete on cost (axis penalty included).
            var bestFlow: Plan? = pickClean(columns)
            for (rect in candidates) {
                if (obstacles.overlapsNote(rect)) continue
                if (blocked(rect = rect, obstacles = obstacles, config = config, clearance = clearance, budget = budget)) continue
                val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
                if (plan.clean && (bestFlow == null || plan.cost < bestFlow.cost - TIE_EPS)) bestFlow = plan
                if (budget.exhausted) break
            }
            bestFlow?.let { return it }
        } else if (laneMode) {
            val nearRight = anchors.map { it.rep.x }.average().toFloat() >= laneExt.cx
            val right = columns.filter { it.rect.left > laneExt.cx }
            val left = columns.filter { it.rect.left <= laneExt.cx }
            val (near, far) = if (nearRight) right to left else left to right
            (pickClean(near) ?: pickClean(far))?.let { return it }
        } else {
            pickClean(columns)?.let { return it }
        }
        // B: ring candidates (inside the width budget).
        var bestClean: Plan? = null
        var bestAny: Plan? = null
        for (rect in candidates) {
            if (obstacles.overlapsNote(rect)) continue
            if (blocked(rect = rect, obstacles = obstacles, config = config, clearance = clearance, budget = budget)) continue
            val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
            if (bestAny == null || plan.cost < bestAny.cost) bestAny = plan
            if (plan.clean && (bestClean == null || plan.cost < bestClean.cost)) bestClean = plan
            if (budget.exhausted && bestAny != null) break
        }
        bestClean?.let { return it }
        // C: the strip below the content.
        val strip =
            stripPlan(
                size = size,
                anchors = anchors,
                extent = extent,
                baseExtent = baseExtent,
                obstacles = obstacles,
                config = config,
                clearance = clearance,
            )
        if (strip.clean) return strip
        // D: nothing is clean: the cheapest routed plan of all stages (old cost model), else the cheapest at all.
        val pool = (columns + listOfNotNull(bestAny, strip))
        val routed = pool.filter { it.routed }
        val chosen = (routed.ifEmpty { pool }).minBy { it.cost }
        // The strip is the last resort of the old behaviour: keep its exhausted flag when it wins.
        return chosen
    }

    /** The content extent together with every lane (lanes may extend beyond the nodes). */
    private fun laneExtent(
        baseExtent: Rect,
        config: NotePlacementConfig,
    ): Rect = config.lanes.fold(baseExtent) { acc, l -> acc.union(l.rect) }

    /** Hard exclusion of a note rectangle: clearance to edges/nodes/notes (and labels), plus the lane invariant. */
    private fun blocked(
        rect: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        clearance: Float,
        budget: ObstacleIndex.Budget,
    ): Boolean =
        obstacles.violatesClearance(
            r = rect,
            clearance = clearance,
            noteGap = config.gridGap,
            budget = budget,
            hardLabels = config.hardLabelsAndTitles,
            hardAnchorLines = config.hardAnchorLines,
        ) ||
            !laneAllows(rect = rect, config = config)

    /**
     * Lane invariant (Nacharbeit 29): a rectangle lies fully inside one lane (inset by [NotePlacementConfig.laneInset])
     * or fully outside the union of all lanes, [NotePlacementConfig.laneOuterGap] px away. Without lanes everything passes.
     */
    private fun laneAllows(
        rect: Rect,
        config: NotePlacementConfig,
    ): Boolean {
        if (config.lanes.isEmpty()) return true
        if (config.lanes.any { insideLane(rect = rect, lane = it.rect, inset = config.laneInset) }) return true
        return config.lanes.none { it.rect.inflated(config.laneOuterGap).intersects(rect) }
    }

    private fun insideLane(
        rect: Rect,
        lane: Rect,
        inset: Float,
    ): Boolean =
        rect.left >= lane.left + inset &&
            rect.right <= lane.right - inset &&
            rect.top >= lane.top + inset &&
            rect.bottom <= lane.bottom - inset

    /**
     * Stage 1 of the lane mode: positions inside the lane that holds the anchors' mean point, beside the anchors
     * (left and right of their box at the anchor's height, then up and down in rows) plus the ring candidates that lie
     * in that lane. Bounded by its own budget; only plans that obey the hard rules are produced.
     */
    private fun inLanePlans(
        size: Size,
        anchors: List<Anchor>,
        candidates: List<Rect>,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        clearance: Float,
    ): List<Plan> {
        val mx = anchors.map { it.rep.x }.average().toFloat()
        val my = anchors.map { it.rep.y }.average().toFloat()
        val lane = config.lanes.firstOrNull { it.rect.contains(Point(x = mx, y = my)) }?.rect ?: return emptyList()
        val gap = config.candidateGap
        val h = size.height
        val w = size.width
        var box =
            rectOf(left = anchors.first().rep.x, top = anchors.first().rep.y, right = anchors.first().rep.x, bottom = anchors.first().rep.y)
        for (a in anchors) {
            val b =
                when (val g = a.geometry) {
                    is AnchorGeometry.Around -> g.r
                    is AnchorGeometry.Ellipse -> g.bounds
                    is AnchorGeometry.Port -> g.box
                    else -> rectOf(left = a.rep.x, top = a.rep.y, right = a.rep.x, bottom = a.rep.y)
                }
            box = box.union(b)
        }
        val step = h + max(config.stackGap, config.gridGap)
        val rows = obstacles.noteCount() + 2
        val xs =
            listOf(
                box.right + gap,
                box.left - gap - w,
                lane.right - config.laneInset - w,
                lane.left + config.laneInset,
            ).distinct()
        val rects = ArrayList<Rect>()
        rects += candidates.filter { insideLane(rect = it, lane = lane, inset = config.laneInset) }
        for (k in 0..(2 * rows)) {
            val offset = if (k % 2 == 0) k / 2 else -(k + 1) / 2
            val y = my - h / 2f + offset * step
            for (x in xs) rects += Rect(origin = Point(x = x, y = y), size = size)
        }
        // A separate, bounded budget: this stage must be searched even when the main budget is spent.
        val budget = ObstacleIndex.Budget(config.workBudget / 4)
        val out = ArrayList<Plan>()
        for (rect in rects) {
            if (budget.exhausted) break
            if (!insideLane(rect = rect, lane = lane, inset = config.laneInset)) continue
            if (obstacles.overlapsNote(rect)) continue
            if (blocked(rect = rect, obstacles = obstacles, config = config, clearance = clearance, budget = budget)) continue
            out += planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
        }
        return out
    }

    /** Mean height of the anchors of [note] (large when none resolves), the sort key of [NotePlacementConfig.sortByAnchorY]. */
    private fun anchorHeight(
        note: NotePlacementInput,
        resolver: AnchorGeometryResolver,
    ): Float {
        val ys =
            note.anchors.mapNotNull { ref ->
                when (val g = resolver.resolve(ref.targetId)) {
                    null -> null
                    is AnchorGeometry.At -> g.p.y
                    is AnchorGeometry.Port -> g.box.cy
                    is AnchorGeometry.Stem -> g.top
                    is AnchorGeometry.Along -> polylineMidpoint(g.pts).y
                    is AnchorGeometry.Around -> g.r.cy
                    is AnchorGeometry.Ellipse -> g.cy
                }
            }
        return if (ys.isEmpty()) Float.MAX_VALUE else ys.average().toFloat()
    }

    /** The cheapest clean plan; ties within 1 px of cost go to the earlier plan (the right column is listed first). */
    private fun pickClean(plans: List<Plan>): Plan? {
        var best: Plan? = null
        for (p in plans) {
            if (!p.clean) continue
            if (best == null || p.cost < best.cost - TIE_EPS) best = p
        }
        return best
    }

    /**
     * Nacharbeit 26: candidate positions in a column right of the content and one left of it, both measured from
     * [baseExtent] (never from the extent that grows with every note, so notes form a column, not a staircase).
     * The preferred y centres the note on the mean anchor height; further rows alternate 0, +1, -1, +2, -2 ... steps
     * of one note height plus [NotePlacementConfig.stackGap]. Right side first so ties resolve to the right.
     */
    private fun columnPlans(
        size: Size,
        anchors: List<Anchor>,
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        clearance: Float,
    ): List<Plan> {
        val gap = config.candidateGap
        val h = size.height
        val y0 = anchors.map { it.rep.y }.average().toFloat() - h / 2f
        val step = h + max(config.stackGap, config.gridGap)
        val rows = obstacles.noteCount() + 1
        // Separate, bounded budget: the columns must be searched even when the main budget is spent.
        val budget = ObstacleIndex.Budget(config.workBudget / 4)
        val xs = listOf(baseExtent.right + gap, baseExtent.left - gap - size.width)
        val out = ArrayList<Plan>()
        for (x in xs) {
            for (k in 0..(2 * rows)) {
                if (budget.exhausted) break
                val offset = if (k % 2 == 0) k / 2 else -(k + 1) / 2
                val y = y0 + offset * step
                if (y < baseExtent.top - h) continue
                val rect = Rect(origin = Point(x = x, y = y), size = size)
                if (obstacles.overlapsNote(rect)) continue
                if (blocked(rect = rect, obstacles = obstacles, config = config, clearance = clearance, budget = budget)) continue
                out += planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
            }
        }
        return out
    }

    /**
     * The width budget for ring and strip candidates. Without an edge clearance: [NotePlacementConfig.maxWidthFactor]
     * times the base width. With one (Nacharbeit 26): at least what two note columns need, so the budget never forbids
     * what the columns already allow: `max(base * factor, base + 2 * (gap + maxNoteWidth))`.
     */
    private fun effectiveMaxWidth(
        baseExtent: Rect,
        config: NotePlacementConfig,
    ): Float? {
        val factor = config.maxWidthFactor ?: return null
        val plain = baseExtent.size.width * factor
        if (config.edgeClearance == null) return plain
        return max(plain, baseExtent.size.width + 2f * (config.candidateGap + config.maxNoteWidth))
    }

    /**
     * Nacharbeit 25 fallback for [NotePlacementConfig.edgeClearance]: rows one [STRIP_ROW_STEP] px lower each, starting
     * [NotePlacementConfig.candidateGap] below the content, so the note grows downwards and the width stays within the budget.
     * Within a row the x positions run left to right (centred on the anchor, flush left, flush right of the width budget).
     * The first row with a position that obeys the clearance rule wins (the cheapest of that row); `extent` already contains
     * every note placed so far, so that is the first row unless an obstacle lies below the content. After [MAX_STRIP_ROWS] rows
     * the search stops and the last row is used regardless (reported through [Plan.stripExhausted]), so the loop is bounded.
     */
    private fun stripPlan(
        size: Size,
        anchors: List<Anchor>,
        extent: Rect,
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        clearance: Float,
    ): Plan {
        val maxWidth = effectiveMaxWidth(baseExtent = baseExtent, config = config)
        val minX = extent.left
        val maxX = if (maxWidth == null) max(minX, extent.right - size.width) else max(minX, extent.left + maxWidth - size.width)
        val anchorX = anchors.map { it.rep.x }.average().toFloat()
        val xs =
            listOf(anchorX - size.width / 2f, minX, maxX)
                .map { it.coerceIn(minX, maxX) }
                .distinct()
                .sorted()
        // A separate, bounded budget (like the column fallback): the strip must be found even when the main budget is spent.
        val budget = ObstacleIndex.Budget(config.workBudget / 4)
        var lastRect = Rect(origin = Point(x = xs.first(), y = extent.bottom + config.candidateGap), size = size)
        for (row in 0 until MAX_STRIP_ROWS) {
            val y = extent.bottom + config.candidateGap + row * STRIP_ROW_STEP
            var best: Plan? = null
            for (x in xs) {
                val rect = Rect(origin = Point(x = x, y = y), size = size)
                lastRect = rect
                if (obstacles.overlapsNote(rect)) continue
                if (blocked(rect = rect, obstacles = obstacles, config = config, clearance = clearance, budget = budget)) continue
                val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
                if (best == null || plan.cost < best.cost) best = plan
            }
            if (best != null) return best
        }
        val plan = planFor(rect = lastRect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
        return Plan(rect = plan.rect, routes = plan.routes, cost = plan.cost, routed = plan.routed, stripExhausted = true)
    }

    /**
     * Notes N2 fallback: rows stacked below each other in a column right of the content (then right of the notes),
     * then the same on the left. Every row is free of notes by construction, so this terminates after at most
     * `notes + 1` rows per column; the first row whose anchor lines all obey the routing rules wins, else the cheapest.
     */
    private fun columnPlan(
        size: Size,
        anchors: List<Anchor>,
        extent: Rect,
        baseExtent: Rect,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
    ): Plan? {
        val rows = obstacles.noteCount() + 1
        val startY = anchors.minOf { it.rep.y } - size.height / 2f
        val step = size.height + config.gridGap
        // Right and left alternate row by row, so a crowd of notes grows in both directions instead of one tall column.
        val xs =
            listOf(
                baseExtent.right + config.candidateGap,
                baseExtent.left - config.candidateGap - size.width,
                extent.right + config.candidateGap,
                extent.left - config.candidateGap - size.width,
            ).distinct()
        // A separate, bounded budget: the fallback must run even when the main budget is spent.
        val budget = ObstacleIndex.Budget(config.workBudget / 4)
        var best: Plan? = null
        // First pass: beside the content (right and left alternating); second pass: beside the notes already placed there.
        for (pass in listOf(xs.take(2), xs.drop(2))) {
            for (k in 0..(2 * rows)) {
                val offset = if (k % 2 == 0) k / 2 else -(k + 1) / 2
                for (x in pass) {
                    val rect = Rect(origin = Point(x = x, y = startY + offset * step), size = size)
                    if (obstacles.overlapsNote(rect)) continue
                    val plan = planFor(rect = rect, anchors = anchors, obstacles = obstacles, config = config, budget = budget)
                    if (plan.routed) return plan
                    if (best == null || plan.cost < best.cost) best = plan
                    if (budget.exhausted) return best
                }
            }
        }
        return best
    }

    /**
     * Nacharbeit 38: [NotePlacementConfig.axisPenaltyPx] if the note lies beside its anchors along the flow axis (left/right
     * for a horizontal flow, above/below for a vertical one); a soft preference that a short anchor line can outweigh.
     */
    private fun axisPenalty(
        rect: Rect,
        anchors: List<Anchor>,
        config: NotePlacementConfig,
    ): Float {
        if (config.flowAxis == FlowAxis.NONE || config.edgeClearance == null || anchors.isEmpty()) return 0f
        var box =
            rectOf(left = anchors.first().rep.x, top = anchors.first().rep.y, right = anchors.first().rep.x, bottom = anchors.first().rep.y)
        for (a in anchors) {
            val b =
                when (val g = a.geometry) {
                    is AnchorGeometry.Around -> g.r
                    is AnchorGeometry.Ellipse -> g.bounds
                    is AnchorGeometry.Port -> g.box
                    else -> rectOf(left = a.rep.x, top = a.rep.y, right = a.rep.x, bottom = a.rep.y)
                }
            box = box.union(b)
        }
        val sepX = max(0f, max(rect.left - box.right, box.left - rect.right))
        val sepY = max(0f, max(rect.top - box.bottom, box.top - rect.bottom))
        val alongFlow =
            when (config.flowAxis) {
                FlowAxis.HORIZONTAL -> sepX > sepY
                FlowAxis.VERTICAL -> sepY > sepX
                FlowAxis.NONE -> false
            }
        return if (alongFlow) config.axisPenaltyPx else 0f
    }

    private fun planFor(
        rect: Rect,
        anchors: List<Anchor>,
        obstacles: ObstacleIndex,
        config: NotePlacementConfig,
        budget: ObstacleIndex.Budget,
    ): Plan {
        var cost = obstacles.rectCost(r = rect, costs = config.costs, budget = budget, partGap = config.minPartGap)
        cost += axisPenalty(rect = rect, anchors = anchors, config = config)
        val exits = assignExits(rect = rect, anchors = anchors, config = config)
        val routes = ArrayList<List<Point>>()
        var routed = true
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
            if (c >= NO_ROUTE_PENALTY) routed = false
            val pts = removeTinyJog(pts0)
            routes += pts
            cost += c
        }
        val clean =
            config.edgeClearance != null &&
                routed &&
                routes.indices.all { obstacles.routeIsClean(pts = routes[it], exemptOwners = anchors[it].exempt, budget = budget) }
        return Plan(rect = rect, routes = routes, cost = cost, routed = routed, clean = clean)
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
                    obstacles.segmentCost(
                        a = cand[i],
                        b = cand[i + 1],
                        exemptOwners = anchor.exempt,
                        costs = config.costs,
                        budget = budget,
                        nearPenalty = config.anchorNearPenalty,
                    )
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

    /** Rows of the strip fallback (Nacharbeit 25): distance between two rows and the cap of the search. */
    private const val STRIP_ROW_STEP: Float = 24f
    private const val MAX_STRIP_ROWS: Int = 80
    private const val EMERGENCY_SEGMENTS: Int = 4
}
