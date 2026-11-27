package dev.kuml.layout.bridge.label

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex

/**
 * Plans the edge labels of SysML 2 IBD and BDD diagrams with the shared [LabelPlacer] (PAL wave 2B-c). The plan is
 * made on the layout, before the notes are placed: notes then avoid the rectangles the renderer will really draw,
 * and the renderer draws the labels at exactly these rectangles. Nodes, composite titles, group borders, port
 * labels (already placed by the layout bridge) and foreign edges are obstacles; the label keeps next to its own line.
 */
public object Sysml2EdgeLabelPlan {
    /**
     * @param layout the layout the labels belong to (no canvas padding, no notes yet)
     * @param labelSizes estimated size of the label of an edge id (edges without an entry carry no label)
     * @param configure adds more obstacles (drawn port squares, owner ports)
     * @return placed label per edge id; ids are processed sorted, so the result is deterministic
     */
    public fun compute(
        layout: LayoutResult,
        labelSizes: Map<String, Size>,
        configure: (ObstacleIndex) -> Unit = {},
        costs: LabelCostModel = LabelCostModel(lineClearance = 2f),
        portLabels: List<PortLabelSpec> = emptyList(),
        titleRects: List<dev.kuml.layout.Rect> = emptyList(),
        keepInsideFrames: Boolean = false,
    ): Map<String, PlacedLabel> =
        Sysml2DiagramLabelPlan.compute(
            layout = layout,
            edgeLabelSizes = labelSizes,
            portLabels = portLabels,
            titleRects = titleRects,
            configure = configure,
            costs = costs,
            keepInsideFrames = keepInsideFrames,
        )

    /** PAL Welle X1: [LabelCostModel] of a block definition diagram with the nearest offset 6 px instead of 4 px. */
    public val BDD_COSTS_X1: LabelCostModel = LabelCostModel(lineClearance = 2f, edgeOffsets = listOf(6f, 8f, 12f))

    /**
     * PAL Welle X1 gate for block definition diagrams: plans with the default costs and with [BDD_COSTS_X1] and keeps the
     * 6 px variant only when it leaves strictly fewer labels outside the 6 to 16 px band from their own line and no more
     * unclean labels. Otherwise the result is the plan of the default costs, unchanged.
     */
    public fun computeGated(
        layout: LayoutResult,
        labelSizes: Map<String, Size>,
        configure: (ObstacleIndex) -> Unit = {},
        keepInsideFrames: Boolean = false,
    ): Map<String, PlacedLabel> {
        val original = compute(layout = layout, labelSizes = labelSizes, configure = configure, keepInsideFrames = keepInsideFrames)
        if (original.isEmpty()) return original
        val trial =
            compute(
                layout = layout,
                labelSizes = labelSizes,
                configure = configure,
                costs = BDD_COSTS_X1,
                keepInsideFrames = keepInsideFrames,
            )
        val polys = layout.edges.entries.associate { it.key.value to Sysml2DiagramLabelPlan.stmPolylineOf(it.value) }
        val better =
            Sysml2DiagramLabelPlan.edgeGapViolations(labels = trial, polys = polys) <
                Sysml2DiagramLabelPlan.edgeGapViolations(labels = original, polys = polys) &&
                trial.count { !it.value.clean } <= original.count { !it.value.clean }
        return if (better) trial else original
    }
}
