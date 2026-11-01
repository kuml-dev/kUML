package dev.kuml.layout.bridge

import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/** A swimlane in declaration order. */
internal data class ActLane(
    val id: String,
    val title: String,
)

/** What the lane pass needs to know about one activity node. */
internal data class ActNodeInfo(
    val id: String,
    val laneId: String?,
    /** Extra room left / right of the node that must stay inside its lane (pin labels), px. */
    val leftExtra: Float = 0f,
    val rightExtra: Float = 0f,
)

/** A flow between two nodes (control, object, exception handler). */
internal data class ActFlow(
    val id: String,
    val source: String,
    val target: String,
)

/** Result of [ActLaneColumnPass.apply]: node boxes and lane bands, edges are routed later. */
internal data class ActLaneOutcome(
    val result: LayoutResult,
    val layerOf: Map<String, Int>,
    val backEdges: Set<String>,
    val laneRects: Map<String, Rect>,
    /** Lane id -> x of the right-hand back-edge corridor (absolute), only lanes that have one. */
    val corridorX: Map<String, Float>,
    val rowTop: Map<Int, Float>,
)

/**
 * Lays an activity diagram out as swimlane *columns* (PAL wave 2F, P-ACT-01).
 *
 * ELK delivers a flat flow graph (the lanes are no longer compounds). This pass derives the layers from the flow graph
 * itself (depth-first back-edge detection, longest path; engine independent, works with the grid engine as well), honours `sameLayer` by moving every member
 * to the deepest layer of its group, orders the nodes inside a lane and layer by their ELK x, sizes every lane
 * to its widest row and draws all lanes as bands of equal height. Rows are as high as their tallest node plus a gap;
 * every node is centred vertically in its row, so the centres of a layer match.
 *
 * Deterministic: only sorted / list-ordered collections are iterated. Bounded: at most `nodes + 2` relaxation rounds.
 */
internal object ActLaneColumnPass {
    const val LANE_PAD = 24f
    const val NODE_GAP = 24f
    const val MIN_LANE_WIDTH = 160f
    const val CORRIDOR_WIDTH = 16f
    const val ROW_GAP = 40f
    const val HEADER_HEIGHT = 28f
    const val HEADER_LINE_HEIGHT = 16f
    const val HEADER_TOP_GAP = 24f
    const val BOTTOM_GAP = 24f

    fun laneHeaderHeight(
        lanes: List<ActLane>,
        laneWidths: Map<String, Float>,
    ): Float =
        lanes.maxOfOrNull { l -> ActTextWrap.laneHeaderHeight(title = l.title, laneWidthPx = laneWidths[l.id] ?: MIN_LANE_WIDTH) } ?: 0f

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun apply(
        result: LayoutResult,
        nodes: List<ActNodeInfo>,
        flows: List<ActFlow>,
        lanes: List<ActLane>,
        sameLayerGroups: List<List<String>>,
        /** Extra x shift per node (lane-local), used by the region displacement. */
        dx: Map<String, Float> = emptyMap(),
    ): ActLaneOutcome {
        val infoById = nodes.associateBy { it.id }
        val boxes =
            nodes
                .mapNotNull { n -> result.nodes[NodeId(n.id)]?.let { n.id to it.bounds } }
                .toMap(LinkedHashMap())
        // Back edges and layers come from the flow graph itself, not from the engine: with a cycle the engine may
        // reverse any edge of it, which would turn the main flow upside down. A depth-first search from the sources
        // in declaration order marks the edges that return to a node still on the stack as back edges; the layers are
        // the longest paths over the remaining acyclic graph.
        val order = boxes.keys.toList()
        val valid = flows.filter { it.source != it.target && it.source in boxes && it.target in boxes }
        val outOf = LinkedHashMap<String, MutableList<ActFlow>>()
        for (f in valid) outOf.getOrPut(f.source) { ArrayList() } += f
        val hasIn = valid.map { it.target }.toSet()
        val state = HashMap<String, Int>()
        val backEdges = LinkedHashSet<String>()
        for (root in order.filter { it !in hasIn } + order) {
            if (state[root] != null) continue
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(root to 0)
            state[root] = 1
            while (stack.isNotEmpty()) {
                val (n, idx) = stack.removeLast()
                val outs = outOf[n].orEmpty()
                if (idx >= outs.size) {
                    state[n] = 2
                    continue
                }
                stack.addLast(n to idx + 1)
                val f = outs[idx]
                when (state[f.target]) {
                    1 -> backEdges += f.id
                    null -> {
                        state[f.target] = 1
                        stack.addLast(f.target to 0)
                    }
                    else -> Unit
                }
            }
        }
        val forward = valid.filter { it.id !in backEdges }
        val layer = HashMap<String, Int>()
        run {
            val indeg = HashMap<String, Int>()
            for (f in forward) indeg[f.target] = (indeg[f.target] ?: 0) + 1
            val ready = ArrayDeque(order.filter { (indeg[it] ?: 0) == 0 })
            for (n in ready) layer[n] = 0
            val outsF = forward.groupBy { it.source }
            while (ready.isNotEmpty()) {
                val n = ready.removeFirst()
                for (f in outsF[n].orEmpty()) {
                    layer[f.target] = maxOf(layer[f.target] ?: 0, layer.getValue(n) + 1)
                    indeg[f.target] = indeg.getValue(f.target) - 1
                    if (indeg.getValue(f.target) == 0) ready.addLast(f.target)
                }
            }
            for (n in order) if (n !in layer) layer[n] = 0
            // pure sources sit directly above their earliest successor instead of at the very top
            val targets = forward.map { it.target }.toSet()
            for (n in order) {
                if (n in targets) continue
                val succ = outsF[n].orEmpty().map { layer.getValue(it.target) }
                if (succ.isNotEmpty()) layer[n] = maxOf(layer.getValue(n), succ.min() - 1)
            }
        }

        // sameLayer: deepest layer of the group, then push successors down (bounded relaxation).
        val groups = sameLayerGroups.map { g -> g.filter { it in layer } }.filter { it.size >= 2 }
        for (round in 0..(boxes.size + 2)) {
            var changed = false
            for (g in groups) {
                val top = g.maxOf { layer.getValue(it) }
                for (m in g) {
                    if (layer.getValue(m) != top) {
                        layer[m] = top
                        changed = true
                    }
                }
            }
            for (f in forward) {
                val s = layer.getValue(f.source)
                if (layer.getValue(f.target) <= s) {
                    layer[f.target] = s + 1
                    changed = true
                }
            }
            if (!changed) break
        }

        val layerCount = (layer.values.maxOrNull() ?: -1) + 1
        val laneIds = lanes.map { it.id }
        val columnIds = (laneIds + listOf(NO_LANE)).filter { c -> nodes.any { (it.laneId ?: NO_LANE) == c && it.id in boxes } }

        fun colOf(id: String): String = infoById.getValue(id).laneId?.takeIf { it in laneIds } ?: NO_LANE

        // Per column and layer: nodes ordered by ELK x.
        val cells = HashMap<Pair<String, Int>, List<String>>()
        for (c in columnIds) {
            for (l in 0 until layerCount) {
                val ids =
                    boxes.keys
                        .filter { colOf(it) == c && layer.getValue(it) == l }
                        .sortedWith(compareBy({ boxes.getValue(it).origin.x + boxes.getValue(it).size.width / 2f }, { it }))
                if (ids.isNotEmpty()) cells[c to l] = ids
            }
        }

        // Lane-local x of every node (0 = lane centre), rows centred, region dx applied, overlaps swept away.
        val localX = HashMap<String, Float>()

        fun foot(id: String): Float = boxes.getValue(id).size.width + infoById.getValue(id).leftExtra + infoById.getValue(id).rightExtra
        for ((key, ids) in cells.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val total = ids.sumOf { foot(it).toDouble() }.toFloat() + NODE_GAP * (ids.size - 1)
            var x = -total / 2f
            val xs = ArrayList<Pair<String, Float>>()
            for (id in ids) {
                val i = infoById.getValue(id)
                xs += id to (x + i.leftExtra + (dx[id] ?: 0f))
                x += foot(id) + NODE_GAP
            }
            // sweep: keep order, enforce the gap after dx moved nodes
            val ordered = xs.sortedWith(compareBy({ it.second }, { it.first }))
            var prevRight = Float.NEGATIVE_INFINITY
            for ((id, px) in ordered) {
                val i = infoById.getValue(id)
                val w = boxes.getValue(id).size.width
                val left = maxOf(px, prevRight + NODE_GAP + i.leftExtra)
                localX[id] = left
                prevRight = left + w + i.rightExtra
            }
        }

        // Lane extents (local), widths, corridor.
        val laneLeft = HashMap<String, Float>()
        val laneRight = HashMap<String, Float>()
        val laneWidth = LinkedHashMap<String, Float>()
        val corridorLanes = HashSet<String>()
        for (f in flows) if (f.id in backEdges) corridorLanes += colOf(f.source)
        for (c in columnIds) {
            val members = boxes.keys.filter { colOf(it) == c }
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (id in members) {
                val i = infoById.getValue(id)
                lo = minOf(lo, localX.getValue(id) - i.leftExtra)
                hi = maxOf(hi, localX.getValue(id) + boxes.getValue(id).size.width + i.rightExtra)
            }
            val corridor = if (c in corridorLanes) CORRIDOR_WIDTH else 0f
            var l = lo - LANE_PAD
            var r = hi + LANE_PAD + corridor
            if (r - l < MIN_LANE_WIDTH) {
                val grow = (MIN_LANE_WIDTH - (r - l)) / 2f
                l -= grow
                r += grow
            }
            laneLeft[c] = l
            laneRight[c] = r
            laneWidth[c] = r - l
        }

        val headerLanes = lanes.filter { it.id in columnIds }
        val headerH = laneHeaderHeight(lanes = headerLanes, laneWidths = laneWidth)

        // Row geometry.
        val rowH = FloatArray(layerCount)
        for (id in boxes.keys) {
            val l = layer.getValue(id)
            rowH[l] = maxOf(rowH[l], boxes.getValue(id).size.height)
        }
        val rowTop = HashMap<Int, Float>()
        var y = headerH + HEADER_TOP_GAP
        for (l in 0 until layerCount) {
            rowTop[l] = y
            y += rowH[l] + ROW_GAP
        }
        val totalH = (if (layerCount == 0) headerH else y - ROW_GAP + BOTTOM_GAP).coerceAtLeast(headerH + BOTTOM_GAP)

        // Absolute geometry.
        var cursor = 0f
        val laneRects = LinkedHashMap<String, Rect>()
        val corridorX = LinkedHashMap<String, Float>()
        val colX0 = HashMap<String, Float>()
        for (c in columnIds) {
            colX0[c] = cursor
            laneRects[c] = Rect(origin = Point(x = cursor, y = 0f), size = Size(width = laneWidth.getValue(c), height = totalH))
            if (c in corridorLanes) corridorX[c] = cursor + laneWidth.getValue(c) - CORRIDOR_WIDTH / 2f
            cursor += laneWidth.getValue(c)
        }
        val newNodes = LinkedHashMap(result.nodes)
        for (id in boxes.keys) {
            val c = colOf(id)
            val b = boxes.getValue(id)
            val x = colX0.getValue(c) + (localX.getValue(id) - laneLeft.getValue(c))
            val l = layer.getValue(id)
            val yy = rowTop.getValue(l) + (rowH[l] - b.size.height) / 2f
            val old = result.nodes.getValue(NodeId(id))
            newNodes[NodeId(id)] =
                NodeLayout(bounds = Rect(origin = Point(x = x, y = yy), size = b.size), ports = old.ports, labels = old.labels)
        }
        val groupsOut = LinkedHashMap<GroupId, GroupLayout>()
        for (l in headerLanes) laneRects[l.id]?.let { groupsOut[GroupId(l.id)] = GroupLayout(it) }
        val out =
            result.copy(
                nodes = newNodes,
                groups = groupsOut,
                canvas = Size(width = maxOf(cursor, 1f), height = totalH),
            )
        return ActLaneOutcome(
            result = out,
            layerOf = layer,
            backEdges = backEdges,
            laneRects = laneRects,
            corridorX = corridorX,
            rowTop = rowTop,
        )
    }

    const val NO_LANE: String = "\u0000none"
}
