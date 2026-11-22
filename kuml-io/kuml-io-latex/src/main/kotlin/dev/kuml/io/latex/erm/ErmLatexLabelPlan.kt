package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SelfLoopPlacement
import dev.kuml.layout.bridge.SelfLoopRouter
import dev.kuml.layout.bridge.SelfLoopSide
import dev.kuml.layout.bridge.label.ErmEdgeLabelPlan
import dev.kuml.layout.bridge.label.ErmEdgeLabelSpec
import dev.kuml.layout.bridge.label.PlacedLabel

/**
 * The label plan of an ERM diagram in LaTeX (Nacharbeit 130): the same [ErmEdgeLabelPlan] the SVG renderer uses places the
 * relationship names and role labels of Martin, Bachman and IDEF1X lines so they avoid entity boxes, foreign lines and the
 * cardinality glyphs. The rectangles use the SVG text metrics, a conservative estimate (the TeX fonts are narrower). All
 * coordinates are layout space (y down, no padding), like the drawn TeX coordinates.
 */
internal object ErmLatexLabelPlan {
    /** @property placed label key to placed label, per relationship id; @property selfLoopRects the estimated self-loop label boxes (obstacles, not planned). */
    internal data class Result(
        val placed: Map<String, Map<String, PlacedLabel>>,
        val selfLoopRects: List<Rect>,
        val routes: Map<String, EdgeRoute>,
    )

    /** True for a relationship whose label layout is the self-loop one (right/left-hand loop, see `appendSelfLoopLabels`). */
    fun isSelfLoop(
        rel: ErmRelationship,
        route: EdgeRoute,
    ): Boolean = SelfLoopRouter.selfLoopNodeId(rel) != null && route is EdgeRoute.OrthogonalRounded && route.waypoints.isNotEmpty()

    fun compute(
        layout: LayoutResult,
        relationships: Map<String, ErmRelationship>,
        selfLoopPlan: Map<String, SelfLoopPlacement>,
    ): Result {
        val routes = LinkedHashMap<String, EdgeRoute>()
        val labels = LinkedHashMap<String, ErmEdgeLabelSpec>()
        val fixed = ArrayList<Rect>()
        for ((edgeId, route) in layout.edges) {
            val rel = relationships[edgeId.value]
            if (rel == null) {
                routes[edgeId.value] = route
                continue
            }
            val routed =
                SelfLoopRouter.adjust(
                    element = rel,
                    originalRoute = route,
                    nodeLookup = { id -> layout.nodes[NodeId(id)] },
                    plan = selfLoopPlan,
                    edgeId = edgeId.value,
                )
            routes[edgeId.value] = routed
            if (isSelfLoop(rel = rel, route = routed)) {
                val node = SelfLoopRouter.selfLoopNodeId(rel)?.let { layout.nodes[NodeId(it)] }
                if (node != null) {
                    fixed +=
                        selfLoopLabelRects(
                            rel = rel,
                            route = routed as EdgeRoute.OrthogonalRounded,
                            node = node,
                            side = SelfLoopRouter.sideOf(plan = selfLoopPlan, edgeId = edgeId.value),
                        )
                }
                // Only the glyph rectangles of the loop enter the plan; its labels are laid out separately.
                labels[edgeId.value] = ErmEdgeLabelSpec(name = null, sourceRole = null, targetRole = null)
            } else {
                labels[edgeId.value] =
                    ErmEdgeLabelSpec(
                        name = rel.name?.takeIf { it.isNotBlank() },
                        sourceRole = rel.sourceRole?.takeIf { it.isNotBlank() },
                        targetRole = rel.targetRole?.takeIf { it.isNotBlank() },
                    )
            }
        }
        val nodes = layout.nodes.entries.associate { (id, nl) -> id.value to nl.bounds }
        val placed = ErmEdgeLabelPlan.compute(routes = routes, nodes = nodes, labels = labels, fixedObstacles = fixed)
        return Result(placed = placed, selfLoopRects = fixed, routes = routes)
    }

    /**
     * Estimated boxes of the self-loop labels that `ErmLatexRenderer.appendSelfLoopLabels` draws (SVG metrics): name beside
     * the loop, source role above and target role below the loop ends. LEFT mirrors the x ranges outwards.
     */
    fun selfLoopLabelRects(
        rel: ErmRelationship,
        route: EdgeRoute.OrthogonalRounded,
        node: NodeLayout,
        side: SelfLoopSide,
    ): List<Rect> {
        val left = side == SelfLoopSide.LEFT
        val borderX = if (left) node.bounds.origin.x else node.bounds.origin.x + node.bounds.size.width
        val outerX = route.waypoints[0].x
        val rects = ArrayList<Rect>()
        rel.name?.takeIf { it.isNotBlank() }?.let {
            val w = it.length * NAME_CHAR_PX + NAME_PAD_PX
            val cy = node.bounds.origin.y + node.bounds.size.height / 2f
            val x = if (left) outerX - NAME_GAP_PX - w else outerX + NAME_GAP_PX
            rects += rect(x = x, y = cy - NAME_H_PX / 2f, w = w, h = NAME_H_PX)
        }
        rel.sourceRole?.takeIf { it.isNotBlank() }?.let {
            val w = it.length * ROLE_CHAR_PX + ROLE_PAD_PX
            val x = if (left) borderX - ROLE_INSET_PX - w else borderX + ROLE_INSET_PX
            rects += rect(x = x, y = route.source.y - ROLE_GAP_PX - ROLE_H_PX, w = w, h = ROLE_H_PX)
        }
        rel.targetRole?.takeIf { it.isNotBlank() }?.let {
            val w = it.length * ROLE_CHAR_PX + ROLE_PAD_PX
            val x = if (left) borderX - ROLE_INSET_PX - w else borderX + ROLE_INSET_PX
            rects += rect(x = x, y = route.target.y + ROLE_GAP_PX, w = w, h = ROLE_H_PX)
        }
        return rects
    }

    private fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

    private const val NAME_CHAR_PX = 6.6f
    private const val NAME_PAD_PX = 8f
    private const val NAME_H_PX = 15f
    private const val NAME_GAP_PX = 4f
    private const val ROLE_CHAR_PX = 6f
    private const val ROLE_PAD_PX = 6f
    private const val ROLE_H_PX = 13f
    private const val ROLE_INSET_PX = 2f
    private const val ROLE_GAP_PX = 10f
}
