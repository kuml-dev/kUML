package dev.kuml.io.svg.uml

import dev.kuml.core.model.KumlElement
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.LabelEnd
import dev.kuml.layout.bridge.label.LabelPlacer
import dev.kuml.layout.bridge.label.LabelRequest
import dev.kuml.layout.bridge.label.LabelRole
import dev.kuml.layout.bridge.label.PlacedLabel
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization

/** Keys of the labels one association edge carries in a label plan. */
public object UmlLabelKeys {
    public const val CENTER: String = "center"
    public const val SOURCE_MULT: String = "srcMult"
    public const val SOURCE_ROLE: String = "srcRole"
    public const val TARGET_MULT: String = "tgtMult"
    public const val TARGET_ROLE: String = "tgtRole"
}

/**
 * Plans the labels of UML associations (name / stereotype in the middle, multiplicity and role at both ends) with the
 * shared [LabelPlacer] (PAL wave 2B-b): one cost-based placement instead of fixed offsets, so labels keep clear of
 * nodes, other labels and foreign edges. Sizes are estimated (font independent), as for notes.
 */
public object UmlAssociationLabelPlan {
    private const val CENTER_CHAR_PX = 6.6f
    private const val CENTER_LINE_PX = 15f
    private const val CENTER_PAD_PX = 8f
    private const val SMALL_CHAR_PX = 6f
    private const val SMALL_H_PX = 13f
    private const val SMALL_PAD_PX = 6f

    /**
     * The plan for a UML [diagram] laid out as [layout] (no canvas padding); the same code path the SVG renderer uses.
     */
    public fun planFor(
        diagram: dev.kuml.core.model.KumlDiagram,
        layout: dev.kuml.layout.LayoutResult,
        theme: KumlTheme,
    ): Map<String, Map<String, PlacedLabel>> =
        planFor(
            layout = layout,
            elements =
                dev.kuml.io.svg.KumlSvgRenderer
                    .buildKumlElementIndex(diagram.elements),
            theme = theme,
            padding = 0f,
        )

    /** Plan for the edges of [layout] as drawn with canvas [padding]; keys of the result are edge ids. */
    internal fun planFor(
        layout: dev.kuml.layout.LayoutResult,
        elements: Map<String, KumlElement>,
        theme: KumlTheme,
        padding: Float,
    ): Map<String, Map<String, PlacedLabel>> {
        val nodeLookup: (String) -> dev.kuml.layout.NodeLayout? = { id -> layout.nodes[dev.kuml.layout.NodeId(id)] }
        val drawn = LinkedHashMap<String, EdgeRoute>()
        for ((edgeId, route) in layout.edges) {
            val el = elements[edgeId.value] ?: continue
            drawn[edgeId.value] =
                dev.kuml.io.svg.KumlSvgRenderer.shiftRoute(
                    route =
                        dev.kuml.io.svg.SelfLoopRouter
                            .adjust(element = el, originalRoute = route, nodeLookup = nodeLookup),
                    dx = padding,
                )
        }
        val drawnNodes =
            layout.nodes.entries.associate { (id, nl) ->
                id.value to
                    nl.bounds.copy(origin = nl.bounds.origin.copy(x = nl.bounds.origin.x + padding, y = nl.bounds.origin.y + padding))
            }
        return compute(routes = drawn, nodes = drawnNodes, elements = elements, theme = theme)
    }

    /**
     * @param routes the edges as drawn (padding already applied), by edge id
     * @param nodes node rectangles as drawn, by node id
     * @param elements the diagram's elements by id
     * @return placed labels per edge id (only edges with at least one label)
     */
    internal fun compute(
        routes: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        elements: Map<String, KumlElement>,
        theme: KumlTheme,
    ): Map<String, Map<String, PlacedLabel>> {
        val index = ObstacleIndex()
        for ((id, r) in nodes.entries.sortedBy { it.key }) index.addRect(kind = ObstacleKind.NODE, r = r, ownerId = id)
        val polylines = routes.entries.sortedBy { it.key }.associate { it.key to polylineOf(it.value) }
        for ((id, pts) in polylines) index.addPolyline(kind = ObstacleKind.EDGE, pts = pts, ownerId = id)

        val requests = ArrayList<LabelRequest>()
        for ((edgeId, pts) in polylines) {
            if (pts.size < 2) continue
            val lines: List<String>
            var ends: List<UmlAssociationEnd> = emptyList()
            when (val el = elements[edgeId]) {
                is UmlAssociation -> {
                    lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes), el.name)
                    ends = el.ends
                }
                is UmlAssociationClass -> {
                    lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes), el.name)
                    ends = el.ends
                }
                is UmlDependency -> lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes), el.name)
                is UmlConnector -> lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes), el.name)
                is UmlGeneralization -> lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes))
                is UmlInterfaceRealization -> lines = listOfNotNull(StereotypeHelper.headerLabel(element = el, theme = theme.stereotypes))
                is UmlInclude -> lines = listOf("«include»")
                is UmlExtend -> lines = listOf("«extend»")
                else -> continue
            }
            if (lines.isNotEmpty()) {
                requests +=
                    LabelRequest(
                        id = "$edgeId#${UmlLabelKeys.CENTER}",
                        role = LabelRole.EDGE_CENTER,
                        ownerId = edgeId,
                        size =
                            Size(
                                width = lines.maxOf { it.length } * CENTER_CHAR_PX + CENTER_PAD_PX,
                                height = lines.size * CENTER_LINE_PX,
                            ),
                        polyline = pts,
                    )
            }
            if (ends.size >= 2) {
                requests += endRequests(edgeId = edgeId, end = ends[0], side = LabelEnd.SOURCE, pts = pts)
                requests += endRequests(edgeId = edgeId, end = ends[1], side = LabelEnd.TARGET, pts = pts)
            }
        }
        if (requests.isEmpty()) return emptyMap()
        val placed = LabelPlacer.place(requests = requests, index = index)
        val byEdge = LinkedHashMap<String, MutableMap<String, PlacedLabel>>()
        for ((id, label) in placed) {
            val (edgeId, key) = id.split('#', limit = 2).let { it[0] to it[1] }
            byEdge.getOrPut(edgeId) { LinkedHashMap() }[key] = label
        }
        return byEdge
    }

    private fun endRequests(
        edgeId: String,
        end: UmlAssociationEnd,
        side: LabelEnd,
        pts: List<Point>,
    ): List<LabelRequest> {
        val out = ArrayList<LabelRequest>(2)
        val mult = end.multiplicity.toLabel()
        val prefix = if (side == LabelEnd.SOURCE) "src" else "tgt"
        if (mult != null) {
            out += small(id = "$edgeId#${prefix}Mult", role = LabelRole.MULTIPLICITY, edgeId = edgeId, text = mult, side = side, pts = pts)
        }
        end.role?.let {
            out +=
                small(id = "$edgeId#${prefix}Role", role = LabelRole.ROLE, edgeId = edgeId, text = it, side = side, pts = pts)
        }
        return out
    }

    private fun small(
        id: String,
        role: LabelRole,
        edgeId: String,
        text: String,
        side: LabelEnd,
        pts: List<Point>,
    ) = LabelRequest(
        id = id,
        role = role,
        ownerId = edgeId,
        size = Size(width = text.length * SMALL_CHAR_PX + SMALL_PAD_PX, height = SMALL_H_PX),
        polyline = pts,
        end = side,
    )

    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }

    /** The rectangle's centre. */
    internal fun center(r: Rect): Point = Point(x = r.origin.x + r.size.width / 2f, y = r.origin.y + r.size.height / 2f)
}
