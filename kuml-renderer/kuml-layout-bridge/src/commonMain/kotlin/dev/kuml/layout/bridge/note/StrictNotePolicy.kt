package dev.kuml.layout.bridge.note

import dev.kuml.layout.Rect

/** One swimlane (activity partition) in final layout coordinates. */
public data class LaneRect(
    val id: String,
    val rect: Rect,
)

/**
 * The strict note placement rules (Nacharbeit 25 for state machines, Nacharbeit 29 for activity diagrams): a note keeps
 * [edgeClearance] px away from edges, nodes and other notes, prefers anchor lines that cross nothing, and goes into a
 * strip below the content as the last resort. State machines use the defaults; activity diagrams additionally set
 * [lanes], [hardLabelsAndTitles] and [sortByAnchorY].
 *
 * @property widthFactor the diagram including its notes may be at most this factor wider than without (emergency brake)
 * @property lanes swimlanes: a note lies fully inside one lane (inset by [laneInset]) or fully outside all of them
 *   (at least [laneOuterGap] px away); empty for diagrams without lanes
 * @property hardLabelsAndTitles pin / edge labels and composite titles become hard obstacles like nodes
 * @property sortByAnchorY notes are placed in the order of their anchors' height, so stacked notes keep that order
 */
public data class StrictNotePolicy(
    val edgeClearance: Float = DEFAULT_EDGE_CLEARANCE,
    val widthFactor: Float = DEFAULT_WIDTH_FACTOR,
    val costs: NoteCostModel = NoteCostModel(titleRect = 80_000f, lineThroughRect = 15_000f),
    val lanes: List<LaneRect> = emptyList(),
    val hardLabelsAndTitles: Boolean = false,
    val sortByAnchorY: Boolean = false,
    val laneInset: Float = 16f,
    val laneOuterGap: Float = 24f,
) {
    /** The placer configuration these rules produce on top of [base]. */
    public fun toConfig(base: NotePlacementConfig): NotePlacementConfig =
        base.copy(
            maxWidthFactor = widthFactor,
            edgeClearance = edgeClearance,
            costs = costs,
            lanes = lanes,
            hardLabelsAndTitles = hardLabelsAndTitles,
            sortByAnchorY = sortByAnchorY,
            laneInset = laneInset,
            laneOuterGap = laneOuterGap,
        )

    public companion object {
        public const val DEFAULT_EDGE_CLEARANCE: Float = 16f
        public const val DEFAULT_WIDTH_FACTOR: Float = 2.0f
    }
}
