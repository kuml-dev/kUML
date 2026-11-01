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
