package dev.kuml.io.svg.erm

import dev.kuml.io.svg.EdgeLabelGeometry
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
import kotlin.math.abs

/** The texts an ERM relationship edge carries as labels (null = none). */
internal data class ErmEdgeLabelSpec(
    val name: String?,
    val sourceRole: String?,
    val targetRole: String?,
)

/** Keys of the labels one ERM edge carries in a label plan. */
internal object ErmLabelKeys {
    const val CENTER: String = "center"
    const val SOURCE_ROLE: String = "srcRole"
    const val TARGET_ROLE: String = "tgtRole"
}

/**
 * Plans the relationship-name and role labels of ERM edges (Martin, Bachman, IDEF1X) with the shared [LabelPlacer]
 * (PAL wave 2B-c). The cardinality glyphs (crow's foot, bar, circle, arrow, dot) are obstacles that are not moved.
 * Everything is in drawn coordinates (canvas padding applied) and the plan is built once per diagram, in the order
 * nodes, routes, glyphs, plan, drawing.
 */
internal object ErmEdgeLabelPlan {
    private const val CHAR_PX = 6.6f
    private const val LINE_PX = 15f
    private const val PAD_PX = 8f
    private const val ROLE_CHAR_PX = 6f
    private const val ROLE_H_PX = 13f
    private const val ROLE_PAD_PX = 6f

    /** Length along the line and half width across it that a cardinality glyph occupies at an entity border. */
    private const val GLYPH_ALONG_PX = 30f
    private const val GLYPH_HALF_PX = 8f

    /**
     * @param routes drawn routes by relationship id (all edges of the diagram, note links included, act as foreign lines)
     * @param nodes drawn node rectangles by id
     * @param labels relationship ids with their label texts
     * @return per relationship id: label key to placed label
     */
    fun compute(
        routes: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        labels: Map<String, ErmEdgeLabelSpec>,
    ): Map<String, Map<String, PlacedLabel>> {
        if (labels.values.none { !it.name.isNullOrBlank() || it.sourceRole != null || it.targetRole != null }) return emptyMap()
        val index = ObstacleIndex()
        for ((id, r) in nodes.entries.sortedBy { it.key }) index.addRect(kind = ObstacleKind.NODE, r = r, ownerId = id)
        val polylines = routes.entries.sortedBy { it.key }.associate { it.key to polylineOf(it.value) }
        for ((id, pts) in polylines) index.addPolyline(kind = ObstacleKind.EDGE, pts = pts, ownerId = id)
        val requests = ArrayList<LabelRequest>()
        for ((id, spec) in labels.entries.sortedBy { it.key }) {
            val route = routes[id] ?: continue
            val pts = polylines.getValue(id)
            if (pts.size < 2) continue
            val srcDir = EdgeLabelGeometry.sourceSegmentTangent(route)
            val tgtInto = EdgeLabelGeometry.targetSegmentTangent(route)
            val tgtDir = -tgtInto.first to -tgtInto.second
            index.addRect(kind = ObstacleKind.MULTIPLICITY, r = glyphRect(anchor = route.source, dir = srcDir), ownerId = id)
            index.addRect(kind = ObstacleKind.MULTIPLICITY, r = glyphRect(anchor = route.target, dir = tgtDir), ownerId = id)
            spec.name?.takeIf { it.isNotBlank() }?.let {
                requests +=
                    LabelRequest(
                        id = "$id#${ErmLabelKeys.CENTER}",
                        role = LabelRole.EDGE_CENTER,
                        ownerId = id,
                        size = Size(width = it.length * CHAR_PX + PAD_PX, height = LINE_PX),
                        polyline = pts,
                    )
            }
            spec.sourceRole?.let {
                requests +=
                    role(id = "$id#${ErmLabelKeys.SOURCE_ROLE}", edge = id, text = it, end = LabelEnd.SOURCE, pts = pts)
            }
            spec.targetRole?.let {
                requests +=
                    role(id = "$id#${ErmLabelKeys.TARGET_ROLE}", edge = id, text = it, end = LabelEnd.TARGET, pts = pts)
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

    /** The box a cardinality glyph at [anchor], extending along the unit vector [dir] away from the entity, occupies. */
    fun glyphRect(
        anchor: Point,
        dir: Pair<Float, Float>,
    ): Rect {
        val (dx, dy) = dir
        val px = -dy
        val py = dx
        val xs =
            listOf(
                anchor.x + px * GLYPH_HALF_PX,
                anchor.x - px * GLYPH_HALF_PX,
                anchor.x + dx * GLYPH_ALONG_PX + px * GLYPH_HALF_PX,
                anchor.x + dx * GLYPH_ALONG_PX - px * GLYPH_HALF_PX,
            )
        val ys =
            listOf(
                anchor.y + py * GLYPH_HALF_PX,
                anchor.y - py * GLYPH_HALF_PX,
                anchor.y + dy * GLYPH_ALONG_PX + py * GLYPH_HALF_PX,
                anchor.y + dy * GLYPH_ALONG_PX - py * GLYPH_HALF_PX,
            )
        val left = xs.min()
        val top = ys.min()
        return Rect(origin = Point(x = left, y = top), size = Size(width = abs(xs.max() - left), height = abs(ys.max() - top)))
    }

    private fun role(
        id: String,
        edge: String,
        text: String,
        end: LabelEnd,
        pts: List<Point>,
    ) = LabelRequest(
        id = id,
        role = LabelRole.ROLE,
        ownerId = edge,
        size = Size(width = text.length * ROLE_CHAR_PX + ROLE_PAD_PX, height = ROLE_H_PX),
        polyline = pts,
        end = end,
    )

    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }
}
