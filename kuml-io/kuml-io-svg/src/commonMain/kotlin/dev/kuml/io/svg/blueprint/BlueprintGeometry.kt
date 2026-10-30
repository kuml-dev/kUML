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

    /**
     * Content-aware — grows past [BlueprintGridConstants.ROW_HEIGHT] when a
     * step title wraps onto enough lines to need more room (fix: cards used
     * to overflow their fixed-height border for 3+ line titles).
     */
    val rowHeight = BlueprintGridConstants.contentAwareRowHeight(model)
    val cardMarginTop = BlueprintGridConstants.CARD_MARGIN_TOP
    val cardMarginBottom = BlueprintGridConstants.CARD_MARGIN_BOTTOM
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

    /** Bottom Y of the last layer band — where the touchpoint legend (if any) starts. */
    val gridBottom = gridTop + rowHeight * layers.size

    /**
     * Touchpoints actually referenced by a visible step, badge-numbered —
     * see [BlueprintGridConstants.legendEntries]. Empty when no step
     * references a touchpoint, so diagrams without touchpoints get no
     * legend band at all.
     */
    val legendEntries = BlueprintGridConstants.legendEntries(model = model, visibleLayers = layers.toSet())

    /** [legendEntries] wrapped into rows that fit within the grid's content width. */
    val legendRows = BlueprintGridConstants.wrapLegendEntries(entries = legendEntries, contentWidth = contentRight - contentLeft)

    /** Height of the touchpoint legend band, `0.0` when [legendEntries] is empty. */
    val legendHeight =
        BlueprintGridConstants.legendHeight(
            model = model,
            contentWidth = contentRight - contentLeft,
            visibleLayers = layers.toSet(),
        )

    /** x of the note column (a gutter right of the lanes). */
    val noteX = contentRight + NOTE_GUTTER

    /** Notes with their anchor routes, in declaration order; empty when the model has no notes. */
    val placedNotes: List<PlacedBlueprintNote> = placeNotes()

    val totalWidth = contentRight + BlueprintGridConstants.noteColumnWidth(model) + padding
    val totalHeight = maxOf(gridBottom + legendHeight, placedNotes.maxOfOrNull { it.y + it.h } ?: 0.0) + padding

    private fun placeNotes(): List<PlacedBlueprintNote> {
        if (model.notes.isEmpty()) return emptyList()
        val stepById = model.steps.associateBy { it.id }
        val out = ArrayList<PlacedBlueprintNote>()
        var bottom = gridTop
        // Anchored notes first (top to bottom by the first anchor), then the free-standing ones.
        val anchored = LinkedHashMap<String, List<dev.kuml.blueprint.model.JourneyStep>>()
        for (n in model.notes) {
            val steps =
                model.noteLinks
                    .filter { it.noteId == n.id }
                    .mapNotNull { stepById[it.targetId] }
                    .filter { cellOf(it) != null }
            anchored[n.id] = steps
        }
        val order = model.notes.sortedBy { n -> anchored.getValue(n.id).firstOrNull()?.let { cellOf(it)?.second } ?: Double.MAX_VALUE }
        for (n in order) {
            val size =
                dev.kuml.layout.bridge.NoteLayout
                    .sizeOf(body = n.body)
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            val steps = anchored.getValue(n.id)
            val first = steps.firstOrNull()?.let { cellOf(it) }
            val desired = if (first != null) first.second + rowHeight / 2 - h / 2 else bottom
            val y = maxOf(desired, bottom)
            bottom = y + h + NOTE_GAP
            val ny = y + h / 2
            val gx = noteX - NOTE_GUTTER / 2
            val routes =
                model.noteLinks.filter { it.noteId == n.id }.mapNotNull { link ->
                    val step = stepById[link.targetId] ?: return@mapNotNull null
                    val (cellX, cellY) = cellOf(step) ?: return@mapNotNull null
                    val cardCx = cellX + columnWidth / 2
                    val marginY = cellY + cardMarginTop / 2
                    link.id to
                        listOf(noteX to ny, gx to ny, gx to marginY, cardCx to marginY, cardCx to cellY + cardMarginTop)
                }
            out += PlacedBlueprintNote(id = n.id, body = n.body, x = noteX, y = y, w = w, h = h, routes = routes)
        }
        return out
    }

    private fun cellOf(step: dev.kuml.blueprint.model.JourneyStep): Pair<Double, Double>? {
        val phaseIdx = phases.indexOfFirst { it.id == step.phaseRef }
        if (phaseIdx < 0 || step.layer !in layers) return null
        return cellOrigin(phaseIndex = phaseIdx, layer = step.layer)
    }

    /** Cell origin (x,y) for a (phase, layer) cell. */
    fun cellOrigin(
        phaseIndex: Int,
        layer: BlueprintLayer,
    ): Pair<Double, Double> = (contentLeft + columnWidth * phaseIndex) to bandY(layer).start
}

/** A note placed in the note column; [routes] are the anchor polylines (link id to points), note edge first, card edge last. */
internal class PlacedBlueprintNote(
    val id: String,
    val body: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val routes: List<Pair<String, List<Pair<Double, Double>>>>,
)

private const val NOTE_GUTTER: Double = 16.0
private const val NOTE_GAP: Double = 12.0
