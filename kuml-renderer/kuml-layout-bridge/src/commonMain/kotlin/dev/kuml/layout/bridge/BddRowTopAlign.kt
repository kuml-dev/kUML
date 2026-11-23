package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** A node starts a new row when its top is more than this share of the row's smallest node height below the row's top. */
private const val ROW_SPLIT_SHARE = 0.5f

/** Upper bound of nodes examined (hostile-input cap). */
private const val ROW_ALIGN_MAX_NODES = 2_000

/**
 * PAL wave E1 (13a): after ELK's wrapped layout, the blocks of one grid row of a block definition diagram share
 * the smallest top edge of that row (the row stays aligned at the top, the row spacing is unchanged). Rows are formed
 * from the nodes' tops: a node belongs to the row of the first node whose top lies within half of the row's smallest
 * height. Only rows without attached edges are moved: an edge endpoint cannot be shifted without re-routing, so a
 * row whose moved blocks carry edges stays as the engine placed it (logged). Only top-to-bottom flow is handled.
 */
internal object BddRowTopAlign {
    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
        direction: LayoutDirection,
    ): LayoutResult {
        if (direction != LayoutDirection.TopToBottom) return result
        val ids =
            graph.nodes
                .map { it.id }
                .filter { it in result.nodes }
                .take(ROW_ALIGN_MAX_NODES)
        if (ids.size < 2) return result
        val withEdges: Set<NodeId> = graph.edges.flatMap { listOf(it.source.nodeId, it.target.nodeId) }.toSet()
        val sorted =
            ids.sortedBy {
                result.nodes
                    .getValue(it)
                    .bounds.origin.y
            }
        val rows = mutableListOf<MutableList<NodeId>>()
        var rowTop = 0f
        var rowMinH = 0f
        for (id in sorted) {
            val b = result.nodes.getValue(id).bounds
            if (rows.isEmpty() || b.origin.y - rowTop > ROW_SPLIT_SHARE * rowMinH) {
                rows += mutableListOf(id)
                rowTop = b.origin.y
                rowMinH = b.size.height
            } else {
                rows.last() += id
                rowMinH = minOf(rowMinH, b.size.height)
            }
        }
        var nodes = result.nodes
        for (row in rows) {
            if (row.size < 2) continue
            val top =
                row.minOf {
                    result.nodes
                        .getValue(it)
                        .bounds.origin.y
                }
            val moving =
                row.filter {
                    result.nodes
                        .getValue(it)
                        .bounds.origin.y - top > 0.5f
                }
            if (moving.isEmpty()) continue
            if (moving.any { it in withEdges }) {
                logger.debug { "BDD row at y=$top keeps ragged tops: a block to move carries edges" }
                continue
            }
            val moved = nodes.toMutableMap()
            for (id in moving) {
                val n = nodes.getValue(id)
                val dy = top - n.bounds.origin.y
                moved[id] =
                    n.copy(
                        bounds = n.bounds.copy(origin = Point(x = n.bounds.origin.x, y = top)),
                        ports = n.ports.mapValues { (_, p) -> Point(x = p.x, y = p.y + dy) },
                        labels = n.labels.mapValues { (_, r) -> r.copy(origin = Point(x = r.origin.x, y = r.origin.y + dy)) },
                    )
            }
            nodes = moved
        }
        return if (nodes === result.nodes) result else result.copy(nodes = nodes)
    }
}
