package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.Sysml2NotePlacement
import dev.kuml.layout.bridge.note.rectOf

/** A port label to place: the label of the port square [anchorRect] of the owner [ownerId] (PAL wave 2C-b2). */
public data class PortLabelSpec(
    val id: String,
    val ownerId: String,
    val size: Size,
    val anchorRect: Rect,
    /** The side of the port square where an edge or the owner box docks; the label stays off it. */
    val excludedSide: LabelSide?,
    /** The side of the owner box (or nested frame) the port sits on; enables the fixed outside / inside candidate ranking. */
    val outward: LabelSide? = null,
    /** The owner box; obstacles owned by it do not count against the label. */
    val parentId: String? = null,
)

/** One pin label of an activity action: the pin square [pinRect] on the [side] of the action box [ownerId] (PAL wave 2C-b2b). */
public data class ActPinLabelSpec(
    val id: String,
    val text: String,
    val pinRect: Rect,
    val side: LabelSide,
    /** The side of the pin where its own edge docks (the label does not stand in front of that edge); `null` without an edge. */
    val dockSide: LabelSide?,
    val ownerId: String = "",
)

/** The text lines of the label of edge [id] (activity diagrams). */
public data class EdgeLabelSpec(
    val id: String,
    val lines: List<String>,
)

/** One state-machine transition label to place: [lines] drawn in a box of [size] (PAL wave 2D-a). */
public data class StmLabelSpec(
    val id: String,
    val lines: List<String>,
    val size: Size,
)

/**
 * A composite state as obstacle of a state-machine label plan: its [frame], the bottom of its header band
 * ([headerBottom]), the [dividers] between orthogonal regions and the rectangles of the region names.
 */
public data class StmCompositeObstacle(
    val id: String,
    val frame: Rect,
    val headerBottom: Float,
    val dividers: List<Pair<Point, Point>> = emptyList(),
    val regionNameRects: List<Rect> = emptyList(),
)

/** Result of a label plan: the placed rectangles and the lines each label is drawn in, both keyed by label id. */
public data class Sysml2LabelPlanResult(
    val labels: Map<String, PlacedLabel>,
    val lines: Map<String, List<String>>,
)

/**
 * One label plan for a whole SysML 2 diagram (PAL wave 2C-b2): port labels and edge labels go through the same
 * [LabelPlacer] run with one shared obstacle index, so a port label and an edge label can no longer be planned
 * independently of each other. Arrow heads enter the index as [ObstacleKind.ARROWHEAD] squares. The result is keyed
 * by label id (edge id or [PortLabelSpec.id]) and deterministic.
 */
public object Sysml2DiagramLabelPlan {
    /** Side length of the square a label keeps clear around an arrow tip. */
    public const val ARROWHEAD_PX: Float = 12f

    /**
     * @param edgeLabelSizes estimated size per edge id (edges without an entry carry no label)
     * @param portLabels port labels to place together with the edge labels
     * @param arrowheads arrow-tip points (the [ARROWHEAD_PX] square around each becomes an obstacle)
     * @param titleRects estimated rectangles of texts inside boxes (part titles, nested frame headers): composite titles
     * @param configure adds more obstacles (drawn port squares, owner ports)
     */
    public fun compute(
        layout: LayoutResult,
        edgeLabelSizes: Map<String, Size>,
        portLabels: List<PortLabelSpec> = emptyList(),
        arrowheads: List<Point> = emptyList(),
        titleRects: List<Rect> = emptyList(),
        configure: (ObstacleIndex) -> Unit = {},
        costs: LabelCostModel = LabelCostModel(lineClearance = 2f),
    ): Map<String, PlacedLabel> {
        if (edgeLabelSizes.isEmpty() && portLabels.isEmpty()) return emptyMap()
        val index = Sysml2NotePlacement.obstaclesOf(layout = layout)
        registerNestedFrames(index = index, layout = layout)
        for (t in titleRects) index.addRect(kind = ObstacleKind.COMPOSITE_TITLE, r = t)
        configure(index)
        for (tip in arrowheads) {
            val h = ARROWHEAD_PX / 2f
            index.addRect(
                kind = ObstacleKind.ARROWHEAD,
                r = rectOf(left = tip.x - h, top = tip.y - h, right = tip.x + h, bottom = tip.y + h),
            )
        }
        val requests = ArrayList<LabelRequest>()
        for (p in portLabels.sortedBy { it.id }) {
            requests +=
                LabelRequest(
                    id = p.id,
                    role = LabelRole.PORT,
                    ownerId = p.ownerId,
                    size = p.size,
                    anchorRect = p.anchorRect,
                    excludedSide = p.excludedSide,
                    outward = p.outward,
                    parentId = p.parentId,
                )
        }
        for ((id, route) in layout.edges.entries.sortedBy { it.key.value }) {
            val size = edgeLabelSizes[id.value] ?: continue
            val pts = polylineOf(route)
            if (pts.size < 2) continue
            requests += LabelRequest(id = id.value, role = LabelRole.EDGE_CENTER, ownerId = id.value, size = size, polyline = pts)
        }
        if (requests.isEmpty()) return emptyMap()
        return LabelPlacer.place(requests = requests, index = index, costs = costs)
    }

    /** Cost model of the parametric diagram: 2 px free space all round, search outwards up to 24 px (PAL wave 2C-b3). */
    public val PAR_COSTS: LabelCostModel =
        LabelCostModel(
            lineClearance = 2f,
            nodeClearance = 2f,
            edgeOffsets = listOf(4f, 8f, 12f, 16f, 20f, 24f),
            maxDistancePx = 24f,
        )

    /**
     * Plans the binding labels of a parametric diagram with the one shared [LabelPlacer] (PAL wave 2C-b3). Obstacles:
     * every node and nested constraint frame, the pin squares, the text rectangles of the constraint boxes, all binding
     * lines and the 12 px squares around the line ends. A label keeps [PAR_COSTS] clearance to all of them. There are no
     * leader lines; when no free spot exists the label sits on its line (halo) and is reported as not clean.
     *
     * @param layout enriched layout, canvas coordinates without padding
     * @param pinRects all pin squares, absolute
     * @param textRects estimated rectangles of the texts in the constraint boxes (stereotype, name, expression, parameters)
     */
    public fun planParLabels(
        layout: LayoutResult,
        edgeLabelSizes: Map<String, Size>,
        pinRects: List<Rect>,
        textRects: List<Rect>,
    ): Sysml2LabelPlanResult {
        val ends = ArrayList<Point>()
        for ((_, route) in layout.edges.entries.sortedBy { it.key.value }) {
            val pts = polylineOf(route)
            if (pts.size >= 2) {
                ends += pts.first()
                ends += pts.last()
            }
        }
        val labels =
            compute(
                layout = layout,
                edgeLabelSizes = edgeLabelSizes,
                arrowheads = ends,
                titleRects = textRects,
                configure = { index -> for (r in pinRects) index.addRect(kind = ObstacleKind.PORT, r = r) },
                costs = PAR_COSTS,
            )
        val lines = LinkedHashMap<String, List<String>>()
        return Sysml2LabelPlanResult(labels = labels, lines = lines)
    }

    /**
     * Plans the pin labels and edge labels of an activity diagram in one [LabelPlacer] run (PAL wave 2C-b2b). All
     * coordinates are canvas coordinates of the drawn diagram. Obstacles: the action boxes, every edge segment, the
     * arrow tips ([ARROWHEAD_PX] squares), the pin squares and [titleRects] (action names, composite titles). Pin labels go
     * first (outside the box, clockwise behind the pin first, inside only when every outside position collides), then
     * the edge labels beside their line.
     *
     * @param nodes action box rectangles by node id
     * @param titleRects estimated rectangles of texts drawn inside the boxes (they count as composite titles)
     */
    public fun planActLabels(
        pins: List<ActPinLabelSpec>,
        edges: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        arrowTips: List<Point>,
        edgeLabels: List<EdgeLabelSpec>,
        titleRects: List<Rect> = emptyList(),
        costs: LabelCostModel = LabelCostModel(lineClearance = 2f),
        glyphRects: List<Rect> = emptyList(),
    ): Sysml2LabelPlanResult {
        if (pins.isEmpty() && edgeLabels.isEmpty()) return Sysml2LabelPlanResult(labels = emptyMap(), lines = emptyMap())
        val index = ObstacleIndex()
        for ((id, r) in nodes.entries.sortedBy { it.key }) index.addRect(kind = ObstacleKind.NODE, r = r, ownerId = id)
        for (t in titleRects) index.addRect(kind = ObstacleKind.COMPOSITE_TITLE, r = t)
        for ((id, route) in edges.entries.sortedBy { it.key }) {
            index.addPolyline(
                kind = ObstacleKind.EDGE,
                pts = polylineOf(route),
                ownerId = id,
            )
        }
        for (tip in arrowTips) {
            val h = ARROWHEAD_PX / 2f
            index.addRect(
                kind = ObstacleKind.ARROWHEAD,
                r = rectOf(left = tip.x - h, top = tip.y - h, right = tip.x + h, bottom = tip.y + h),
            )
        }
        // Wave 2F: interruption glyphs are obstacles like arrow tips.
        for (g in glyphRects) index.addRect(kind = ObstacleKind.ARROWHEAD, r = g)
        val lines = LinkedHashMap<String, List<String>>()
        val requests = ArrayList<LabelRequest>()
        for (p in pins.sortedBy { it.id }) {
            index.addRect(kind = ObstacleKind.PORT, r = p.pinRect, ownerId = p.id)
            val wrapped = Sysml2LabelSizing.wrapPortLabel(text = p.text)
            lines[p.id] = wrapped
            requests +=
                LabelRequest(
                    id = p.id,
                    role = LabelRole.PORT,
                    ownerId = p.id,
                    size = Sysml2LabelSizing.sizeOf(wrapped),
                    anchorRect = p.pinRect,
                    excludedSide = p.dockSide,
                    outward = p.side,
                    parentId = p.ownerId,
                )
        }
        for (e in edgeLabels.sortedBy { it.id }) {
            val route = edges[e.id] ?: continue
            val pts = polylineOf(route)
            if (pts.size < 2 || e.lines.isEmpty()) continue
            lines[e.id] = e.lines
            requests +=
                LabelRequest(
                    id = e.id,
                    role = LabelRole.EDGE_CENTER,
                    ownerId = e.id,
                    size = Sysml2LabelSizing.sizeOf(e.lines),
                    polyline = pts,
                )
        }
        if (requests.isEmpty()) return Sysml2LabelPlanResult(labels = emptyMap(), lines = emptyMap())
        return Sysml2LabelPlanResult(labels = LabelPlacer.place(requests = requests, index = index, costs = costs), lines = lines)
    }

    /** Cost model of the state machine: 2 px free space, search along the whole line up to 14 px away (PAL wave 2D-a). */
    public val STM_COSTS: LabelCostModel =
        LabelCostModel(
            lineClearance = 2f,
            nodeClearance = 2f,
            edgeOffsets = listOf(3f, 6f, 10f, 14f),
            maxDistancePx = 14f,
            edgeCandidateMode = EdgeCandidateMode.ARC_LENGTH,
            arcStepPx = 8f,
            arcMaxFraction = 0.4f,
            leftSidePenalty = 400f,
            sharedSegmentPenalty = 600f,
            shortSegmentPenalty = 300f,
            foreignCloserPenalty = 20_000f,
            perRequestWorkBudget = 400_000L,
        )

    /** Segments shorter than this carry no label by preference (PAL wave 2D-a). */
    private const val STM_SHORT_SEGMENT_PX: Float = 16f

    /** Above this many edge segments in total the quadratic shared-segment test is skipped. */
    private const val STM_SHARED_CHECK_SEGMENT_LIMIT: Int = 4_000

    private const val STM_BEZIER_SAMPLES: Int = 16

    /**
     * Plans the transition labels of a state machine with the one shared [LabelPlacer] (PAL wave 2D-a).
     *
     * Obstacles: simple states, pseudo-states and notes ([nodes]); the frame of every composite state as a closed line
     * (a label may sit inside it but must not straddle it) together with its header band, its dividers and its region
     * names; all drawn routes ([edges], including note anchors); a 12 px square around each line end (arrow heads); and
     * a strip above [frameTop]. A label whose line does not run through a composite must not enter that composite.
     *
     * @param edges final drawn routes in canvas coordinates (after route transforms)
     * @param nodes rectangles of simple states, pseudo-states and notes by id
     * @param labels one entry per edge carrying a label (the size is the box of the wrapped lines)
     */
    public fun planStmLabels(
        edges: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        composites: List<StmCompositeObstacle>,
        labels: List<StmLabelSpec>,
        frameTop: Float,
        costs: LabelCostModel = STM_COSTS,
    ): Sysml2LabelPlanResult {
        if (labels.isEmpty()) return Sysml2LabelPlanResult(labels = emptyMap(), lines = emptyMap())
        val index = ObstacleIndex()
        for ((id, r) in nodes.entries.sortedBy { it.key }) index.addRect(kind = ObstacleKind.NODE, r = r, ownerId = id)
        index.addRect(
            kind = ObstacleKind.NODE,
            r = rectOf(left = -STM_STRIP_PX, top = frameTop - STM_STRIP_PX, right = STM_STRIP_PX, bottom = frameTop),
        )
        for (c in composites.sortedBy { it.id }) {
            val f = c.frame
            val l = f.origin.x
            val t = f.origin.y
            val r = l + f.size.width
            val b = t + f.size.height
            index.addPolyline(
                kind = ObstacleKind.FRAGMENT,
                pts = listOf(Point(x = l, y = t), Point(x = r, y = t), Point(x = r, y = b), Point(x = l, y = b), Point(x = l, y = t)),
                ownerId = c.id,
            )
            val header = rectOf(left = l, top = t, right = r, bottom = maxOf(t, c.headerBottom))
            if (header.size.height > 0f) index.addRect(kind = ObstacleKind.COMPOSITE_TITLE, r = header)
            for ((p, q) in c.dividers) index.addPolyline(kind = ObstacleKind.FRAGMENT, pts = listOf(p, q), ownerId = c.id)
            for (n in c.regionNameRects) index.addRect(kind = ObstacleKind.COMPOSITE_TITLE, r = n)
        }
        val polys = LinkedHashMap<String, List<Point>>()
        for ((id, route) in edges.entries.sortedBy { it.key }) {
            val pts = stmPolylineOf(route)
            polys[id] = pts
            index.addPolyline(kind = ObstacleKind.EDGE, pts = pts, ownerId = id)
        }
        for ((_, pts) in polys) {
            if (pts.size < 2) continue
            // Source end: a small square at the junction with the state; target end: the box of the drawn arrow head.
            val src = pts.first()
            val h = ARROWHEAD_PX / 2f
            index.addRect(
                kind = ObstacleKind.ARROWHEAD,
                r = rectOf(left = src.x - h, top = src.y - h, right = src.x + h, bottom = src.y + h),
            )
            index.addRect(kind = ObstacleKind.ARROWHEAD, r = arrowHeadBox(tip = pts.last(), from = pts[pts.size - 2]))
        }
        val requests = ArrayList<LabelRequest>()
        val lines = LinkedHashMap<String, List<String>>()
        // The shared-segment test is quadratic in the number of segments; above the cap it is skipped (DoS guard).
        val segmentTotal = polys.values.sumOf { maxOf(0, it.size - 1) }
        val checkShared = segmentTotal <= STM_SHARED_CHECK_SEGMENT_LIMIT
        for (spec in labels.sortedBy { it.id }) {
            val pts = polys[spec.id] ?: continue
            if (pts.size < 2) continue
            lines[spec.id] = spec.lines
            val mid = arcMidpoint(pts)
            val avoid =
                composites
                    .filter { c -> !c.frame.containsPoint(mid) }
                    .map { it.frame }
            val flags =
                (0 until pts.size - 1).map { i ->
                    var f = 0
                    if (segLen(a = pts[i], b = pts[i + 1]) < STM_SHORT_SEGMENT_PX) f = f or SEGMENT_SHORT
                    if (checkShared && sharesSegment(a = pts[i], b = pts[i + 1], owner = spec.id, all = polys)) f = f or SEGMENT_SHARED
                    f
                }
            requests +=
                LabelRequest(
                    id = spec.id,
                    role = LabelRole.EDGE_CENTER,
                    ownerId = spec.id,
                    size = spec.size,
                    polyline = pts,
                    avoid = avoid,
                    segmentFlags = flags,
                )
        }
        if (requests.isEmpty()) return Sysml2LabelPlanResult(labels = emptyMap(), lines = emptyMap())
        return Sysml2LabelPlanResult(labels = LabelPlacer.place(requests = requests, index = index, costs = costs), lines = lines)
    }

    private const val STM_STRIP_PX: Float = 100_000f

    /** Length and half width of a drawn transition arrow head in px. */
    private const val ARROW_LEN_PX: Float = 12f
    private const val ARROW_HALF_WIDTH_PX: Float = 5f

    /** Bounding box of the arrow head at [tip] of a line that arrives from [from] (a square around the tip for a zero-length end). */
    private fun arrowHeadBox(
        tip: Point,
        from: Point,
    ): Rect {
        val len = segLen(a = tip, b = from)
        if (len < 0.01f) {
            val h = ARROWHEAD_PX / 2f
            return rectOf(left = tip.x - h, top = tip.y - h, right = tip.x + h, bottom = tip.y + h)
        }
        val ux = (from.x - tip.x) / len
        val uy = (from.y - tip.y) / len
        val bx = tip.x + ux * ARROW_LEN_PX
        val by = tip.y + uy * ARROW_LEN_PX
        val nx = -uy * ARROW_HALF_WIDTH_PX
        val ny = ux * ARROW_HALF_WIDTH_PX
        val xs = listOf(tip.x, bx + nx, bx - nx)
        val ys = listOf(tip.y, by + ny, by - ny)
        return rectOf(left = xs.min(), top = ys.min(), right = xs.max(), bottom = ys.max())
    }

    private fun Rect.containsPoint(p: Point): Boolean =
        p.x >= origin.x && p.x <= origin.x + size.width && p.y >= origin.y && p.y <= origin.y + size.height

    private fun segLen(
        a: Point,
        b: Point,
    ): Float = kotlin.math.hypot(b.x - a.x, b.y - a.y)

    /** The point halfway along the arc length of [pts]. */
    private fun arcMidpoint(pts: List<Point>): Point {
        var total = 0f
        for (i in 0 until pts.size - 1) total += segLen(a = pts[i], b = pts[i + 1])
        var rest = total / 2f
        for (i in 0 until pts.size - 1) {
            val l = segLen(a = pts[i], b = pts[i + 1])
            if (rest <= l || i == pts.size - 2) {
                val f = if (l > 0.01f) (rest / l).coerceIn(0f, 1f) else 0f
                return Point(x = pts[i].x + (pts[i + 1].x - pts[i].x) * f, y = pts[i].y + (pts[i + 1].y - pts[i].y) * f)
            }
            rest -= l
        }
        return pts.first()
    }

    /** Collinear (within 1 px) and overlapping by more than 1 px with a segment of another edge. */
    private fun sharesSegment(
        a: Point,
        b: Point,
        owner: String,
        all: Map<String, List<Point>>,
    ): Boolean {
        val len = segLen(a = a, b = b)
        if (len < 0.001f) return false
        val dx = (b.x - a.x) / len
        val dy = (b.y - a.y) / len

        fun along(p: Point) = (p.x - a.x) * dx + (p.y - a.y) * dy

        fun off(p: Point) = kotlin.math.abs((p.x - a.x) * -dy + (p.y - a.y) * dx)
        for ((id, pts) in all) {
            if (id == owner) continue
            for (i in 0 until pts.size - 1) {
                val p = pts[i]
                val q = pts[i + 1]
                if (off(p) > 1f || off(q) > 1f) continue
                val lo = maxOf(0f, minOf(along(p), along(q)))
                val hi = minOf(len, maxOf(along(p), along(q)))
                if (hi - lo > 1f) return true
            }
        }
        return false
    }

    /** Polyline of the drawn route; Bezier curves are sampled (the generic [polylineOf] only joins their ends). */
    public fun stmPolylineOf(route: EdgeRoute): List<Point> {
        val raw =
            when (route) {
                is EdgeRoute.Direct -> listOf(route.source, route.target)
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.Bezier -> sampleBezier(route)
            }
        return raw.filter { it.x.isFinite() && it.y.isFinite() }
    }

    private fun sampleBezier(r: EdgeRoute.Bezier): List<Point> {
        val cp = r.controlPoints
        if (cp.isEmpty()) return listOf(r.source, r.target)
        val ctrl = if (cp.size >= 2) listOf(r.source, cp[0], cp[1], r.target) else listOf(r.source, cp[0], r.target)
        return (0..STM_BEZIER_SAMPLES).map { i ->
            var cur = ctrl
            val t = i.toFloat() / STM_BEZIER_SAMPLES
            while (cur.size > 1) cur = cur.zipWithNext { a, b -> Point(x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t) }
            cur.first()
        }
    }

    /**
     * Nested IBD frames (box in box): the border of each frame is a line a label must not straddle, its header a title
     * a label must not cover (PAL wave 2C-b2b).
     */
    private fun registerNestedFrames(
        index: ObstacleIndex,
        layout: LayoutResult,
    ) {
        for (f in layout.ibdNestedFrames) {
            val node = layout.nodes[dev.kuml.layout.NodeId(f.nodeId)] ?: continue
            val l = node.bounds.origin.x + f.bounds.origin.x
            val t = node.bounds.origin.y + f.bounds.origin.y
            val r = l + f.bounds.size.width
            val b = t + f.bounds.size.height
            index.addPolyline(
                kind = ObstacleKind.FRAGMENT,
                pts = listOf(Point(x = l, y = t), Point(x = r, y = t), Point(x = r, y = b), Point(x = l, y = b), Point(x = l, y = t)),
                ownerId = f.path,
            )
        }
    }

    /** The tip of an edge's arrow head, i.e. its target point. */
    public fun tipOf(route: EdgeRoute): Point = polylineOf(route).last()

    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }
}
