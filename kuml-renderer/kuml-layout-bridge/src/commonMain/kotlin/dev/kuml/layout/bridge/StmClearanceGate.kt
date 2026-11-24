package dev.kuml.layout.bridge

import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult

/** Spacing (px, base units) a gated second layout asks for between parallel edges (PAL wave J). */
private const val RELAYOUT_EDGE_EDGE_PX = 24f

/** Padding (px) a composite frame gets on its left, right and bottom side in the gated second layout. */
private const val RELAYOUT_GROUP_PADDING_PX = 24f

/**
 * Public view of the clearance detector for tests and tools: the number of edge clearance violations (parallel edges
 * closer than 16 px, edges closer than 12 px to a foreign node, frame or header band) of a state-machine layout.
 */
public object StmClearance {
    /** Number of clearance violations of [result] on [graph]; `0` for a clean layout. */
    public fun violationCount(
        result: LayoutResult,
        graph: LayoutGraph,
    ): Int = detectViolations(result = result, graph = graph).count

    /** One readable line per violation of [result] (for diagnostics and inventory output). */
    public fun describe(
        result: LayoutResult,
        graph: LayoutGraph,
    ): List<String> {
        val v = detectViolations(result = result, graph = graph)
        return v.parallel.map { "parallel ${it.first.value} / ${it.second.value}: ${it.gap} px" } +
            v.edgeNode.map { "edge ${it.edge.value} near ${it.obstacle}: ${it.gap} px" }
    }
}

/** Hints of the gated second layout: more room between parallel edges; never lower than the original values. */
internal fun LayoutHints.roomier(): LayoutHints =
    copy(
        spacing =
            spacing.copy(
                edgeToEdge = maxOf(spacing.edgeToEdge, RELAYOUT_EDGE_EDGE_PX),
                edgeToEdgeBetweenLayers = maxOf(spacing.edgeToEdgeBetweenLayers.takeUnless { it.isNaN() } ?: 0f, RELAYOUT_EDGE_EDGE_PX),
            ),
    )

/** Composite frames (groups with a header) get at least [RELAYOUT_GROUP_PADDING_PX] on the sides and the bottom; the top keeps the header band. */
internal fun LayoutGraph.withRoomyComposites(): LayoutGraph =
    copy(
        groups =
            groups.map { g ->
                if (g.headerTextWidths == null) {
                    g
                } else {
                    g.copy(
                        padding =
                            Insets(
                                top = g.padding.top,
                                right = maxOf(g.padding.right, RELAYOUT_GROUP_PADDING_PX),
                                bottom = maxOf(g.padding.bottom, RELAYOUT_GROUP_PADDING_PX),
                                left = maxOf(g.padding.left, RELAYOUT_GROUP_PADDING_PX),
                            ),
                    )
                }
            },
    )
