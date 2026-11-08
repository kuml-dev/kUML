package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutGraph

/**
 * Marks the forward edges of a state machine ([dev.kuml.layout.EdgeHints.keepDirection]): a depth-first walk from the
 * initial pseudo-states first, then from the other nodes in declaration order, classifies every edge that points back
 * to a node still on the stack as the one that closes a cycle. All other edges are marked, so a layered engine that has
 * to break a cycle reverses a closing edge and not the edge out of the initial pseudo-state. Graphs without an initial
 * pseudo-state stay unchanged. O(V + E), iterative.
 */
internal fun LayoutGraph.withForwardEdgePriority(): LayoutGraph {
    if (nodes.none { it.hints.initialState }) return this
    val ids = nodes.map { it.id }
    val isInitial = nodes.filter { it.hints.initialState }.map { it.id }.toSet()
    val order = ids.filter { it in isInitial } + ids.filterNot { it in isInitial }
    val index = HashMap<dev.kuml.layout.NodeId, Int>()
    order.forEachIndexed { i, id -> index[id] = i }
    val out = Array(order.size) { mutableListOf<Pair<Int, Int>>() } // (target index, edge position)
    edges.forEachIndexed { pos, e ->
        val s = index[e.source.nodeId] ?: return@forEachIndexed
        val t = index[e.target.nodeId] ?: return@forEachIndexed
        out[s].add(t to pos)
    }
    val state = IntArray(order.size)
    val back = HashSet<Int>()
    for (root in order.indices) {
        if (state[root] != 0) continue
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(root, 0))
        state[root] = 1
        while (stack.isNotEmpty()) {
            val top = stack.last()
            val v = top[0]
            if (top[1] < out[v].size) {
                val (w, pos) = out[v][top[1]++]
                when (state[w]) {
                    0 -> {
                        state[w] = 1
                        stack.addLast(intArrayOf(w, 0))
                    }
                    1 -> back.add(pos)
                    else -> Unit
                }
            } else {
                state[v] = 2
                stack.removeLast()
            }
        }
    }
    return copy(
        edges = edges.mapIndexed { pos, e -> if (pos in back) e else e.copy(hints = e.hints.copy(keepDirection = true)) },
    )
}
