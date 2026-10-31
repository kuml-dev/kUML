package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayerPin
import dev.kuml.layout.LayoutGraph
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Diagram

/**
 * Applies the diagram's `layout { }` hints (wave C3) to a layout graph as node hints:
 * `first` / `last` become [LayerPin]s; `wrapColumns` and `sameLayer` become explicit layer indices computed
 * by [wrapLayers]. Nodes that are not in the graph (hidden, unknown ids) are ignored — the checker
 * reports them. SEQ diagrams are unaffected (lifeline order is the declaration order).
 *
 * Note: ELK honours layer hints only for flat (non-compound) nodes; for nodes inside compound groups it
 * emits the warning `hint.ignored.layerInCompound`.
 */
internal fun LayoutGraph.withLayoutSpec(diagram: Sysml2Diagram): LayoutGraph {
    if (diagram is SeqDiagram) return this
    return withSpec(spec = diagram.layout)
}

/**
 * Diagram-family neutral form of [withLayoutSpec] (PAL wave 2B): applies `wrapColumns`, `sameLayer`,
 * `first` and `last` of [spec] to this graph. `null` / empty specs return the graph unchanged.
 */
internal fun LayoutGraph.withSpec(spec: LayoutSpec?): LayoutGraph {
    if (spec == null || spec.isEmpty) return this
    val known = nodes.map { it.id.value }.toSet()
    val first =
        spec.first
            .filter { it in known }
            .take(LayoutLimits.IDS_PER_GROUP)
            .toSet()
    val last =
        spec.last
            .filter { it in known && it !in first }
            .take(LayoutLimits.IDS_PER_GROUP)
            .toSet()
    val groups =
        spec.sameLayer
            .take(LayoutLimits.GROUPS)
            .map { g -> g.take(LayoutLimits.IDS_PER_GROUP).filter { it in known } }
            .filter { it.size >= 2 }
    val wrap = spec.wrapColumns?.takeIf { it in 1..LayoutLimits.GROUPS }

    var layers: Map<String, Int>? = null
    if (wrap != null || groups.isNotEmpty()) {
        val base =
            wrapLayers(
                nodes = nodes.map { it.id.value },
                edges = edges.map { it.source.nodeId.value to it.target.nodeId.value },
                columns = wrap,
            )
        val adjusted = base.toMutableMap()
        for (g in groups) {
            val target = g.maxOf { adjusted.getValue(it) }
            g.forEach { adjusted[it] = target }
        }
        // first/last combined with explicit layers are expressed as layer indices (ELK's own FIRST/LAST
        // constraints do not combine with partitions): all other layers shift by one, `last` gets max + 1.
        if (first.isNotEmpty() || last.isNotEmpty()) {
            val shift = if (first.isNotEmpty()) 1 else 0
            adjusted.keys.toList().forEach { adjusted[it] = adjusted.getValue(it) + shift }
            first.forEach { adjusted[it] = 0 }
            val top = (adjusted.values.maxOrNull() ?: 0) + 1
            last.forEach { adjusted[it] = top }
        }
        layers = adjusted
    }
    if (layers == null && first.isEmpty() && last.isEmpty()) return this
    return copy(
        nodes =
            nodes.map { n ->
                val id = n.id.value
                val pin =
                    if (id in first) {
                        LayerPin.First
                    } else if (id in last) {
                        LayerPin.Last
                    } else {
                        null
                    }
                if (layers == null && pin == null) {
                    n
                } else {
                    n.copy(hints = n.hints.copy(layerIndex = layers?.get(id), layerPin = pin ?: n.hints.layerPin))
                }
            },
    )
}

/**
 * Layer index per node: longest-path layering over the edges (back edges found by an iterative DFS are
 * ignored, so cycles are fine), optionally wrapped so that no layer holds more than [columns] nodes
 * (the nodes of an over-full layer, in declaration order, spill into additional layers and shift all
 * following layers). O(V + E).
 */
internal fun wrapLayers(
    nodes: List<String>,
    edges: List<Pair<String, String>>,
    columns: Int?,
): Map<String, Int> {
    val index = nodes.withIndex().associate { it.value to it.index }
    val out = Array(nodes.size) { mutableListOf<Int>() }
    for ((s, t) in edges) {
        val si = index[s] ?: continue
        val ti = index[t] ?: continue
        if (si != ti) out[si].add(ti)
    }
    // iterative DFS: classify back edges, record post-order
    val state = IntArray(nodes.size) // 0 new, 1 on stack, 2 done
    val back = HashSet<Long>()
    val post = ArrayList<Int>(nodes.size)
    for (root in nodes.indices) {
        if (state[root] != 0) continue
        val stack = ArrayDeque<IntArray>() // (node, next child position)
        stack.addLast(intArrayOf(root, 0))
        state[root] = 1
        while (stack.isNotEmpty()) {
            val top = stack.last()
            val v = top[0]
            if (top[1] < out[v].size) {
                val w = out[v][top[1]++]
                when (state[w]) {
                    0 -> {
                        state[w] = 1
                        stack.addLast(intArrayOf(w, 0))
                    }
                    1 -> back.add(v.toLong() * nodes.size + w)
                    else -> Unit
                }
            } else {
                state[v] = 2
                post.add(v)
                stack.removeLast()
            }
        }
    }
    val layer = IntArray(nodes.size)
    for (v in post.asReversed()) {
        for (w in out[v]) {
            if (v.toLong() * nodes.size + w in back) continue
            if (layer[w] < layer[v] + 1) layer[w] = layer[v] + 1
        }
    }
    if (columns == null) return nodes.withIndex().associate { it.value to layer[it.index] }

    val byLayer =
        nodes.indices
            .groupBy { layer[it] }
            .entries
            .sortedBy { it.key }
    val result = HashMap<String, Int>()
    var offset = 0
    for ((l, members) in byLayer.map { it.key to it.value }) {
        for ((i, v) in members.withIndex()) result[nodes[v]] = l + offset + i / columns
        offset += (members.size - 1) / columns
    }
    return result
}
