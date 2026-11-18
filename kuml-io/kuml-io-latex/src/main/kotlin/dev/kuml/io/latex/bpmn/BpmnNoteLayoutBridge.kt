package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.BpmnNotePlacement
import dev.kuml.layout.bridge.note.NotePlacementResult
import kotlin.math.abs

/**
 * Builds a [LayoutResult] from the estimated [BpmnCollaborationLayout.Plan] so that the LaTeX renderer can reuse
 * the SVG note rule ([BpmnNotePlacement.placeCollaboration]) for the placement hint. The plan already grows
 * downwards like the SVG layout, so no coordinate conversion is needed here; the pools start at the origin.
 *
 * The geometry is an estimate (node widths, 25pt/20pt title bands against the 30px/24px of the SVG rule), which only
 * ever makes the placer more conservative.
 */
internal object BpmnNoteLayoutBridge {
    /** Origin of a note node that the placer has not moved (no resolvable anchor); no real position is ever this far out. */
    const val UNPLACED = -99999f

    class Input(
        val id: String,
        val size: Size,
    )

    /**
     * The layout of [plan]: a group per pool and lane, a node per drawn flow node, the sequence flows as straight
     * edges, [obstacles] (notes that stay in their default row) as fixed nodes and every hinted note in [hinted] as
     * an unplaced node of its real size.
     */
    fun syntheticLayout(
        plan: BpmnCollaborationLayout.Plan,
        obstacles: Map<String, Rect>,
        hinted: List<Input>,
    ): LayoutResult {
        val groups = LinkedHashMap<GroupId, GroupLayout>()
        val nodes = LinkedHashMap<NodeId, NodeLayout>()
        val edges = LinkedHashMap<EdgeId, EdgeRoute>()
        for (pool in plan.pools) {
            groups[GroupId(pool.participant.id)] =
                GroupLayout(bounds = Rect(origin = Point(x = 0f, y = pool.top), size = Size(width = plan.width, height = pool.height)))
            for (lane in pool.lanes) {
                val x = BpmnCollaborationLayout.POOL_HEADER_W + BpmnCollaborationLayout.LANE_HEADER_W * lane.depth
                groups[GroupId(lane.lane.id)] =
                    GroupLayout(
                        Rect(
                            origin = Point(x = x, y = pool.top + lane.top),
                            size = Size(width = maxOf(0f, plan.width - x), height = lane.height),
                        ),
                    )
            }
            for (placed in pool.nodes) {
                val (w, h) = BpmnCollaborationLayout.rawSizeOf(placed.node)
                nodes.putIfAbsent(
                    NodeId(placed.node.id),
                    NodeLayout(
                        bounds =
                            Rect(
                                origin = Point(x = placed.x - w / 2f, y = pool.top + placed.y - h / 2f),
                                size = Size(width = w, height = h),
                            ),
                    ),
                )
            }
            val centres = pool.nodes.associate { it.node.id to Point(x = it.x, y = pool.top + it.y) }
            for (flow in pool.process?.sequenceFlows.orEmpty()) {
                val from = centres[flow.sourceRef] ?: continue
                val to = centres[flow.targetRef] ?: continue
                edges.putIfAbsent(EdgeId(flow.id), EdgeRoute.Direct(source = from, target = to))
            }
        }
        for ((id, rect) in obstacles) nodes.putIfAbsent(NodeId(id), NodeLayout(bounds = rect))
        for (n in hinted) {
            nodes.putIfAbsent(
                NodeId(n.id),
                NodeLayout(bounds = Rect(origin = Point(x = UNPLACED, y = UNPLACED), size = n.size)),
            )
        }
        return LayoutResult(
            engineId = LayoutEngineId("latex-estimate"),
            seed = null,
            canvas = Size(width = plan.width, height = plan.height),
            nodes = nodes,
            edges = edges,
            groups = groups,
        )
    }

    /** A synthetic diagram of [collab]: [BpmnNotePlacement] only needs the collaboration id. */
    fun syntheticDiagram(collab: BpmnCollaboration): CollaborationDiagram =
        CollaborationDiagram(name = collab.name ?: collab.id, collaborationId = collab.id)

    /** Runs the SVG note rule on [layout]; the placer reports unhonoured hints itself. */
    fun place(
        model: BpmnModel,
        collab: BpmnCollaboration,
        layout: LayoutResult,
    ): NotePlacementResult = BpmnNotePlacement.placeCollaboration(model = model, diagram = syntheticDiagram(collab), layout = layout)

    /**
     * The rectangle the placer gave note [id] in plan coordinates, or null when it left the note unplaced. The placer
     * translates the whole layout when a note lands at negative coordinates; that shift is taken back here.
     */
    fun placedRect(
        result: NotePlacementResult,
        id: String,
    ): Rect? {
        val r = result.layout.nodes[NodeId(id)]?.bounds ?: return null
        if (abs(r.origin.x - (UNPLACED + result.dx)) < 0.5f && abs(r.origin.y - (UNPLACED + result.dy)) < 0.5f) return null
        return Rect(origin = Point(x = r.origin.x - result.dx, y = r.origin.y - result.dy), size = r.size)
    }
}
