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
    ): Map<String, PlacedLabel> =
        Sysml2DiagramLabelPlan.compute(layout = layout, edgeLabelSizes = labelSizes, configure = configure, costs = costs)
}
