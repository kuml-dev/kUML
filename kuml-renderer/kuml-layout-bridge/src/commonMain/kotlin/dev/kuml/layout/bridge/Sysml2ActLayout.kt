package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.LayoutWarning
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ExceptionHandlerUsage
import dev.kuml.sysml2.InterruptibleRegionDefinition
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Model

/** Pin-label room (px) kept inside a lane next to a box with pins on that side. */
private const val ACT_PIN_LABEL_ROOM = 56f

/** Maximum rounds of the region displacement (DoS guard, plan wave 2F). */
internal const val ACT_REGION_ROUNDS = 8

/**
 * Wave 2F post-pass for activity diagrams: swimlanes as columns, `sameLayer` as deepest layer, interruptible regions
 * kept clear of foreign nodes, orthogonal re-routing of every flow.
 *
 * Runs only for top-to-bottom diagrams with at least one swimlane; everything else keeps the engine's result
 * unchanged (the horizontal direction is documented as not covered). Called by the SVG renderer before notes and
 * label overhang are placed; deliberately not part of [LayoutSpecRunner], the engine abstraction stays untouched.
 */
public fun Sysml2LayoutBridge.normalizeActLayout(
    model: Sysml2Model,
    diagram: ActDiagram,
    layoutResult: LayoutResult,
): LayoutResult {
    val flow = diagram.layout?.direction
    if (flow != null && flow != Sysml2LayoutFlow.Down) return layoutResult
    val visible = diagram.elementIds.toSet()
    val actions = model.definitions.filterIsInstance<ActionDefinition>().filter { it.id in visible && NodeId(it.id) in layoutResult.nodes }
    if (actions.isEmpty()) return layoutResult
    val partitions = model.definitions.filterIsInstance<ActivityPartitionDefinition>()
    val usedLaneIds = actions.mapNotNull { it.partitionId }.toSet()
    val lanes = partitions.filter { it.id in usedLaneIds }.map { ActLane(id = it.id, title = it.name) }
    if (lanes.isEmpty()) return layoutResult
    val laneIds = lanes.map { it.id }.toSet()

    val infos =
        actions.map { a ->
            val sides = a.pins.map { it.effectiveSide }.toSet()
            ActNodeInfo(
                id = a.id,
                laneId = a.partitionId?.takeIf { it in laneIds },
                leftExtra = if (PinSide.Left in sides) ACT_PIN_LABEL_ROOM else 0f,
                rightExtra = if (PinSide.Right in sides) ACT_PIN_LABEL_ROOM else 0f,
            )
        }
    val ids = actions.map { it.id }.toSet()
    val flows = ArrayList<ActFlow>()
    for (f in model.usages.filterIsInstance<ControlFlowUsage>()) {
        if (f.sourceNodeId in ids && f.targetNodeId in ids) flows += ActFlow(id = f.id, source = f.sourceNodeId, target = f.targetNodeId)
    }
    for (f in model.usages.filterIsInstance<ObjectFlowUsage>()) {
        if (f.sourceNodeId in ids && f.targetNodeId in ids) flows += ActFlow(id = f.id, source = f.sourceNodeId, target = f.targetNodeId)
    }
    for (h in model.usages.filterIsInstance<ExceptionHandlerUsage>()) {
        if (h.protectedNodeId in ids && h.handlerNodeId in ids && h.protectedNodeId != h.handlerNodeId) {
            flows += ActFlow(id = h.id, source = h.protectedNodeId, target = h.handlerNodeId)
        }
    }
    val sameLayer = diagram.layout?.sameLayer.orEmpty()
    val regions = model.definitions.filterIsInstance<InterruptibleRegionDefinition>()
    val members = actions.filter { it.interruptibleRegionId != null }.associate { it.id to it.interruptibleRegionId!! }

    // Region displacement: push non-members horizontally out of a frame, re-place, repeat (bounded).
    val dx = HashMap<String, Float>()
    var outcome =
        ActLaneColumnPass.apply(
            result = layoutResult,
            nodes = infos,
            flows = flows,
            lanes = lanes,
            sameLayerGroups = sameLayer,
            dx = dx,
        )
    var unresolved: List<String> = emptyList()
    if (regions.isNotEmpty()) {
        for (round in 0 until ACT_REGION_ROUNDS) {
            val violations = regionViolations(result = outcome.result, regions = regions, members = members)
            unresolved = violations.map { it.first }
            if (violations.isEmpty()) break
            for ((nodeId, frame) in violations) {
                val b =
                    outcome.result.nodes
                        .getValue(NodeId(nodeId))
                        .bounds
                val toLeft = (b.origin.x + b.size.width) - (frame.origin.x - REGION_CLEARANCE)
                val toRight = (frame.origin.x + frame.size.width + REGION_CLEARANCE) - b.origin.x
                dx[nodeId] = (dx[nodeId] ?: 0f) + if (toLeft <= toRight) -toLeft else toRight
            }
            outcome =
                ActLaneColumnPass.apply(
                    result = layoutResult,
                    nodes = infos,
                    flows = flows,
                    lanes = lanes,
                    sameLayerGroups = sameLayer,
                    dx = dx,
                )
        }
        unresolved = regionViolations(result = outcome.result, regions = regions, members = members).map { it.first }
    }

    val nodeRects =
        outcome.result.nodes.entries
            .associate { it.key.value to it.value.bounds }
    val kinds = actions.associate { it.id to it.kind }
    val laneOfNode = infos.mapNotNull { i -> i.laneId?.let { i.id to it } }.toMap()
    val routes =
        ActOrthogonalRouter.routeAll(
            nodes = nodeRects,
            kinds = kinds,
            edges = flows.map { ActEdgeEnds(id = it.id, source = it.source, target = it.target) },
            backEdges = outcome.backEdges,
            laneOfNode = laneOfNode,
            corridorX = outcome.corridorX,
        )
    val edges = LinkedHashMap(outcome.result.edges)
    for ((id, r) in ActCorridorSeparation.apply(routes)) edges[id] = r
    val warnings = outcome.result.warnings.toMutableList()
    if (unresolved.isNotEmpty()) {
        warnings +=
            LayoutWarning(
                code = LayoutCodes.REGION_OVERLAP,
                message = "region frame still overlaps a non-member: ${unresolved.sorted().joinToString(", ")}",
                affectedNodes = unresolved.sorted().map { NodeId(it) },
            )
    }
    return outcome.result.copy(edges = edges, warnings = warnings)
}

private const val REGION_CLEARANCE = 8f

private fun regionViolations(
    result: LayoutResult,
    regions: List<InterruptibleRegionDefinition>,
    members: Map<String, String>,
): List<Pair<String, Rect>> {
    val bounds = result.nodes.entries.associate { it.key.value to it.value.bounds }
    val frames = ActRegionFrames.compute(regions = regions, members = members, nodeBounds = bounds)
    val out = ArrayList<Pair<String, Rect>>()
    val seen = HashSet<String>()
    for (f in frames.sortedBy { it.regionId }) {
        val inside = ActRegionFrames.closure(regionId = f.regionId, regions = regions)
        for ((nodeId, b) in bounds.entries.sortedBy { it.key }) {
            if (members[nodeId] in inside) continue
            if (nodeId in seen) continue
            val r = f.rect
            val hit =
                b.origin.x < r.origin.x + r.size.width + REGION_CLEARANCE &&
                    b.origin.x + b.size.width > r.origin.x - REGION_CLEARANCE &&
                    b.origin.y < r.origin.y + r.size.height + REGION_CLEARANCE &&
                    b.origin.y + b.size.height > r.origin.y - REGION_CLEARANCE
            if (hit) {
                out += nodeId to r
                seen += nodeId
            }
        }
    }
    return out
}
