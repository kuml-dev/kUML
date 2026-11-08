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
 * @property flow layout flow axis (Nacharbeit 38): a note beside its anchor along the flow costs [offAxisPenaltyPx]
 *   extra (a soft preference, never a hard rule); [FlowAxis.NONE] switches the preference off
 * @property hardAnchorLines a note rectangle keeps 6 px from the anchor lines of notes placed before it
 * @property anchorNearPenalty soft cost of an anchor line running 6..12 px past a foreign node or port; 0 switches it off
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
    val flow: FlowAxis = FlowAxis.NONE,
    val offAxisPenaltyPx: Float = DEFAULT_OFF_AXIS_PENALTY,
    val anchorNearPenalty: Float = 0f,
    val hardAnchorLines: Boolean = false,
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
            flowAxis = flow,
            axisPenaltyPx = offAxisPenaltyPx,
            anchorNearPenalty = anchorNearPenalty,
            hardAnchorLines = hardAnchorLines,
        )

    public companion object {
        public const val DEFAULT_EDGE_CLEARANCE: Float = 16f
        public const val DEFAULT_WIDTH_FACTOR: Float = 2.0f
        public const val DEFAULT_OFF_AXIS_PENALTY: Float = 40f
    }
}

/** The axis the diagram's edges flow along (Nacharbeit 38); notes prefer the sides across it. */
public enum class FlowAxis { NONE, HORIZONTAL, VERTICAL }
