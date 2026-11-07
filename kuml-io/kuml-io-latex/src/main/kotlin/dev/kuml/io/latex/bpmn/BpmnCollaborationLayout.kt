package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventPosition

/**
 * Deterministic, absolute layout of a BPMN collaboration for the LaTeX renderer (no TikZ library, no layout engine).
 *
 * Pools are stacked top to bottom with equal width. A pool is split into horizontal lane bands (one per leaf lane,
 * nested lanes wrap their children); the flow nodes of the referenced process are placed left to right in
 * declaration order, each in the band of the lane that lists it. All widths are estimates taken from the TikZ
 * styles of [BpmnLatexRenderer.appendBpmnTikzStyles]; a node whose text is wider than estimated can overlap its
 * neighbour, which is why the node gap is generous. Every length is capped well below TeX's 16383pt limit.
 *
 * Node names are claimed once in a fixed order (pools, lanes, flow nodes) so that no `\node` name is defined
 * twice; a flow node whose sanitised id is empty or already taken is left out and `omittedNodes` is set.
 * Y values grow downwards here; the renderer negates them for TikZ.
 */
internal object BpmnCollaborationLayout {
    const val POOL_HEADER_W = 25f
    const val LANE_HEADER_W = 20f
    const val POOL_GAP = 20f
    const val BAND_H = 80f
    const val MIN_POOL_W = 200f
    private const val CONTENT_PAD = 20f
    private const val NODE_GAP = 40f

    // Half widths follow the styles: task minimum width 80pt, event minimum size 28pt (intermediate: double line),
    // gateway minimum size 36pt with a safety margin; label text under an event/gateway is estimated per character.
    private const val TASK_HALF_W = 40f
    private const val CALL_ACTIVITY_EXTRA = 2f
    private const val EVENT_HALF_W = 14f
    private const val INTERMEDIATE_HALF_W = 16f
    private const val GATEWAY_HALF_W = 25f
    private const val LABEL_CHAR_PT = 3.4f
    private const val LABEL_HALF_MAX = 60f

    /** TeX stops at 16383.99pt; stay far below it (notes stack below the pools, see [BpmnNoteTikz.MAX_STACK_PT]). */
    const val MAX_EXTENT_PT = 12000f
    const val MAX_POOL_HEIGHT_PT = 6000f
    private const val MAX_LANE_DEPTH = 8

    class PlacedNode(
        val node: BpmnFlowNode,
        val name: String,
        val x: Float,
        val y: Float,
    )

    class LaneBox(
        val lane: BpmnLane,
        val depth: Int,
        val top: Float,
        val height: Float,
        val name: String,
        val headerName: String,
    )

    class PoolPlan(
        val participant: BpmnParticipant,
        val name: String,
        val headerName: String,
        val top: Float,
        val height: Float,
        val lanes: List<LaneBox>,
        val nodes: List<PlacedNode>,
        val process: BpmnProcess?,
        val omittedNodes: Boolean,
        val contentRight: Float,
    )

    enum class TargetKind { POOL, LANE, NODE }

    /** Where a drawn name sits; `centerY` is absolute and grows downwards. */
    class Target(
        val kind: TargetKind,
        val centerY: Float,
    )

    class Plan(
        val pools: List<PoolPlan>,
        val width: Float,
        val height: Float,
        val targets: Map<String, Target>,
        val omittedPools: Boolean,
    ) {
        /** Every TikZ name the collaboration picture defines (pools, headers, lanes, lane headers, flow nodes). */
        val definedNames: Set<String> by lazy {
            buildSet {
                for (pool in pools) {
                    add(pool.name)
                    add(pool.headerName)
                    for (lane in pool.lanes) {
                        add(lane.name)
                        add(lane.headerName)
                    }
                    for (node in pool.nodes) add(node.name)
                }
            }
        }
    }

    private class Names {
        private val used = mutableSetOf<String>()

        /** The sanitised id, or null when it is empty or already taken. */
        fun claim(raw: String): String? = BpmnLatexRenderer.sanitizeTikzId(raw).takeIf { it.isNotEmpty() && used.add(it) }

        /** A name that is certainly new: [base] with a numeric suffix when needed. */
        fun unique(
            base: String,
            fallback: String,
        ): String {
            val start = base.ifEmpty { fallback }
            var candidate = start
            var n = 2
            while (!used.add(candidate)) candidate = "${start}_${n++}"
            return candidate
        }
    }

    private class LaneLayout(
        val boxes: List<LaneBox>,
        val bandTops: List<Float>,
        val height: Float,
    )

    private fun halfWidthOf(node: BpmnFlowNode): Float {
        val label = minOf((node.name?.length ?: 0) * LABEL_CHAR_PT / 2f, LABEL_HALF_MAX)
        return when (node) {
            is BpmnEvent -> maxOf(if (node.position == EventPosition.INTERMEDIATE) INTERMEDIATE_HALF_W else EVENT_HALF_W, label)
            is BpmnGateway -> maxOf(GATEWAY_HALF_W, label)
            is BpmnTask, is BpmnSubProcess -> TASK_HALF_W
            is BpmnCallActivity -> TASK_HALF_W + CALL_ACTIVITY_EXTRA
        }
    }

    /** Lanes depth-first, parents before children; only leaf lanes own a band. Returns the total height. */
    private fun placeLanes(
        lanes: List<BpmnLane>,
        depth: Int,
        top: Float,
        names: Names,
        boxes: MutableList<LaneBox>,
        bandTops: MutableList<Float>,
    ): Float {
        var y = top
        for (lane in lanes) {
            if (y > MAX_POOL_HEIGHT_PT - BAND_H) break
            val index = boxes.size
            val name = names.unique(base = BpmnLatexRenderer.sanitizeTikzId(lane.id), fallback = "kumllane")
            val header = names.unique(base = "lh_$name", fallback = "lh_kumllane")
            boxes += LaneBox(lane = lane, depth = depth, top = y, height = 0f, name = name, headerName = header)
            val children = if (depth < MAX_LANE_DEPTH) lane.childLanes else emptyList()
            val height =
                if (children.isEmpty()) {
                    bandTops += y
                    BAND_H
                } else {
                    placeLanes(lanes = children, depth = depth + 1, top = y, names = names, boxes = boxes, bandTops = bandTops)
                }
            boxes[index] = LaneBox(lane = lane, depth = depth, top = y, height = height, name = name, headerName = header)
            y += height
        }
        return y - top
    }

    fun plan(
        model: BpmnModel,
        collab: BpmnCollaboration,
    ): Plan {
        val names = Names()
        val participants = collab.participants

        // 1. pool names first, so a pool keeps its id even when a flow node of another pool carries the same one
        val poolNames =
            participants.map { pt ->
                val name = names.unique(base = BpmnLatexRenderer.sanitizeTikzId(pt.id), fallback = "kumlpool")
                name to names.unique(base = "ph_$name", fallback = "ph_kumlpool")
            }

        // 2. lanes: relative geometry per pool
        val layouts =
            participants.map { pt ->
                val boxes = mutableListOf<LaneBox>()
                val bandTops = mutableListOf<Float>()
                val height = placeLanes(lanes = pt.lanes, depth = 0, top = 0f, names = names, boxes = boxes, bandTops = bandTops)
                if (bandTops.isEmpty()) {
                    LaneLayout(boxes = emptyList(), bandTops = listOf(0f), height = BAND_H)
                } else {
                    LaneLayout(boxes = boxes, bandTops = bandTops, height = height)
                }
            }

        // 3. flow nodes, then absolute pool positions
        val drawnProcesses = mutableSetOf<String>()
        val pools = mutableListOf<PoolPlan>()
        var top = 0f
        var omittedPools = false
        participants.forEachIndexed { i, pt ->
            val layout = layouts[i]
            if (top + layout.height > MAX_POOL_HEIGHT_PT) {
                omittedPools = true
                return@forEachIndexed
            }
            val columns = (layout.boxes.maxOfOrNull { it.depth } ?: -1) + 1
            val contentStart = POOL_HEADER_W + LANE_HEADER_W * columns + CONTENT_PAD
            // a process drawn in an earlier pool is not drawn twice (its node names are taken)
            val referenced = pt.processRef?.let { ref -> model.processes.firstOrNull { it.id == ref } }
            val process = referenced?.takeIf { drawnProcesses.add(it.id) }
            // node id -> band index; a deeper (later) lane wins over its parent
            val bandOf = HashMap<String, Int>()
            for (box in layout.boxes) {
                val band = layout.bandTops.indexOfFirst { it >= box.top }.coerceAtLeast(0)
                box.lane.flowNodeRefs.forEach { ref -> bandOf[ref] = band }
            }
            val nodes = mutableListOf<PlacedNode>()
            var omitted = referenced != null && process == null
            var right = contentStart - CONTENT_PAD
            for (node in process?.flowNodes.orEmpty()) {
                val half = halfWidthOf(node)
                val x = if (nodes.isEmpty()) contentStart + half else right + NODE_GAP + half
                if (x + half > MAX_EXTENT_PT) {
                    omitted = true
                    break
                }
                val name = names.claim(node.id)
                if (name == null) {
                    omitted = true
                    continue
                }
                val band = (bandOf[node.id] ?: 0).coerceIn(0, layout.bandTops.lastIndex)
                nodes +=
                    PlacedNode(
                        node = node,
                        name = name,
                        x = x,
                        y = layout.bandTops[band] + BAND_H / 2f,
                    )
                right = x + half
            }
            pools +=
                PoolPlan(
                    participant = pt,
                    name = poolNames[i].first,
                    headerName = poolNames[i].second,
                    top = top,
                    height = layout.height,
                    lanes = layout.boxes,
                    nodes = nodes,
                    process = process,
                    omittedNodes = omitted,
                    contentRight = right + CONTENT_PAD,
                )
            top += layout.height + POOL_GAP
        }

        val width = maxOf(MIN_POOL_W, pools.maxOfOrNull { it.contentRight } ?: MIN_POOL_W)
        val targets = linkedMapOf<String, Target>()
        for (pool in pools) {
            targets[pool.name] = Target(kind = TargetKind.POOL, centerY = pool.top + pool.height / 2f)
            for (lane in pool.lanes) {
                targets[lane.name] = Target(kind = TargetKind.LANE, centerY = pool.top + lane.top + lane.height / 2f)
            }
            for (node in pool.nodes) {
                targets[node.name] = Target(kind = TargetKind.NODE, centerY = pool.top + node.y)
            }
        }
        val height = if (pools.isEmpty()) 0f else top - POOL_GAP
        return Plan(pools = pools, width = width, height = height, targets = targets, omittedPools = omittedPools)
    }
}
