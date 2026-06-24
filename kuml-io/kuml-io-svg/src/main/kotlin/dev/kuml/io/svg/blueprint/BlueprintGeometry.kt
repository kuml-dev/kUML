package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.Phase

/**
 * Self-contained grid geometry for the Journey-Map / Blueprint view.
 *
 * The blueprint is a strict table (phases = columns, layers = rows), so the
 * geometry is computed directly here with a simple deterministic algorithm —
 * no ELK. The full [dev.kuml.layout] LayoutGraph bridge
 * (`BlueprintLayoutBridge`) wraps these same numbers; this class keeps the SVG
 * renderer self-sufficient and snapshot-stable.
 *
 * Layout (Journey-Map view):
 * ```
 *  [labelW] | emotion band (optional)              |
 *  ---------+--------------------------------------
 *  Layer A  | phase0 col | phase1 col | phase2 col |
 *  Layer B  |    …       |    …       |    …       |
 * ```
 *
 * V3.1.23
 */
internal class BlueprintGeometry(
    private val model: BlueprintModel,
    visibleLayers: Set<BlueprintLayer>,
    private val showEmotionCurve: Boolean,
) {
    val labelColumnWidth = BlueprintGridConstants.LABEL_COLUMN_WIDTH
    val columnWidth = BlueprintGridConstants.COLUMN_WIDTH
    val rowHeight = BlueprintGridConstants.ROW_HEIGHT
    val emotionBandHeight = BlueprintGridConstants.EMOTION_BAND_HEIGHT
    val headerHeight = BlueprintGridConstants.HEADER_HEIGHT
    val padding = BlueprintGridConstants.PADDING

    /** Phases as columns, in canonical order. */
    val phases: List<Phase> = model.orderedPhases()

    /** Visible layers in canonical Shostack order (NOT set-iteration order). */
    val layers: List<BlueprintLayer> =
        BlueprintLayer.entries.filter { it in visibleLayers }

    /** X of the left edge of the phase content area (after the label column). */
    val contentLeft = padding + labelColumnWidth

    /** X centre of each phase column, index-aligned with [phases]. */
    val columnCenters: List<Double> =
        phases.indices.map { i -> contentLeft + columnWidth * i + columnWidth / 2.0 }

    /** Top Y of the emotion band (0-height when not shown). */
    val emotionTop = padding + headerHeight
    val emotionHeight = if (showEmotionCurve) emotionBandHeight else 0.0

    /** Top Y of the first layer band. */
    val gridTop = emotionTop + emotionHeight

    /** Y range [top, bottom] of a layer band. */
    fun bandY(layer: BlueprintLayer): ClosedFloatingPointRange<Double> {
        val idx = layers.indexOf(layer)
        require(idx >= 0) { "layer $layer not visible" }
        val top = gridTop + rowHeight * idx
        return top..(top + rowHeight)
    }

    val contentRight = contentLeft + columnWidth * phases.size
    val totalWidth = contentRight + padding
    val totalHeight = gridTop + rowHeight * layers.size + padding

    /** Cell origin (x,y) for a (phase, layer) cell. */
    fun cellOrigin(
        phaseIndex: Int,
        layer: BlueprintLayer,
    ): Pair<Double, Double> = (contentLeft + columnWidth * phaseIndex) to bandY(layer).start
}
