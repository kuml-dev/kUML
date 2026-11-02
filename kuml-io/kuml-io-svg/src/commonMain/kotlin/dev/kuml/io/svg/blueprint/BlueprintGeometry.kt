package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLabels
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
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
    private val captionWidth: Double =
        BlueprintGridConstants.captionWidth(labels = BlueprintLabels.ENGLISH, lines = BlueprintLine.entries),
) {
    val labelColumnWidth = BlueprintGridConstants.LABEL_COLUMN_WIDTH
    val columnWidth = BlueprintGridConstants.COLUMN_WIDTH

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

    /**
     * Content-aware height of every layer band (wave 2G-c): tall enough for the
     * tallest cell stack of the layer, at least [BlueprintGridConstants.ROW_HEIGHT].
     */
    private val layerHeights: Map<BlueprintLayer, Double> = BlueprintGridConstants.layerHeights(model = model, layers = layers)

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

    /** Height of the band of [layer]. */
    fun bandHeight(layer: BlueprintLayer): Double = layerHeights.getValue(layer)

    /** Y range [top, bottom] of a layer band. */
    fun bandY(layer: BlueprintLayer): ClosedFloatingPointRange<Double> {
        val idx = layers.indexOf(layer)
        require(idx >= 0) { "layer $layer not visible" }
        val top = gridTop + layers.take(idx).sumOf { bandHeight(it) }
        return top..(top + bandHeight(layer))
    }

    val contentRight = contentLeft + columnWidth * phases.size

    /** Bottom Y of the last layer band — where the touchpoint legend (if any) starts. */
    val gridBottom = gridTop + layers.sumOf { bandHeight(it) }

    /** Placed step cards (stacked per cell), by step id. */
    private val cardRects: Map<String, CardRect> = placeCards()

    /** Rectangle of [stepId]'s card, `null` when its phase or layer is not shown. */
    fun cardRect(stepId: String): CardRect? = cardRects[stepId]

    /** Cards of one cell, top to bottom. */
    fun cardsIn(
        phaseId: String,
        layer: BlueprintLayer,
    ): List<CardRect> = model.stepsIn(phaseId = phaseId, layer = layer).mapNotNull { cardRects[it.id] }

    private fun placeCards(): Map<String, CardRect> {
        val out = LinkedHashMap<String, CardRect>()
        phases.forEachIndexed { i, phase ->
            for (layer in layers) {
                val (cellX, cellY) = cellOrigin(phaseIndex = i, layer = layer)
                val stacked =
                    BlueprintGridConstants.stackedCards(model = model, phaseId = phase.id, layer = layer, layerHeight = bandHeight(layer))
                for (c in stacked) {
                    out[c.step.id] =
                        CardRect(
                            step = c.step,
                            x = cellX + CARD_MARGIN_SIDE,
                            y = cellY + c.yOffset,
                            w = columnWidth - 2 * CARD_MARGIN_SIDE,
                            h = c.height,
                        )
                }
            }
        }
        return out
    }

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

    /** Gutter between the lanes and the note column; grows with the widest multi-anchor note. */
    private val noteGutter =
        BlueprintNoteRouter.gutterFor(anchors = model.notes.maxOfOrNull { n -> model.noteLinks.count { it.noteId == n.id } } ?: 1)

    /** x of the note column (a gutter right of the lanes). */
    val noteX = contentRight + noteGutter

    /** Notes with their anchor routes, in declaration order; empty when the model has no notes. */
    val placedNotes: List<PlacedBlueprintNote> = placeNotes()

    val totalWidth = contentRight + BlueprintGridConstants.noteColumnWidth(model) + (noteGutter - NOTE_GUTTER) + padding
    val totalHeight =
        maxOf(
            gridBottom + legendHeight,
            placedNotes.maxOfOrNull { it.y + it.h } ?: 0.0,
            // a line in the outer channel below the lanes must stay inside the canvas
            placedNotes.maxOfOrNull { n -> n.routes.maxOfOrNull { (_, pts) -> pts.maxOf { it.second } } ?: 0.0 } ?: 0.0,
        ) + padding

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
        val laneSlots = HashMap<Double, Int>()
        val cardEntries = HashMap<Pair<Double, Double>, Int>()
        val claimedTracks = ArrayList<Double>()
        val obstacles = horizontalObstacles()
        val blocks = cardRects.values.map { BlueprintNoteRouter.Box(left = it.x, top = it.y, right = it.x + it.w, bottom = it.y + it.h) }
        for (n in order) {
            val size =
                dev.kuml.layout.bridge.NoteLayout
                    .sizeOf(body = n.body)
            val w = size.width.toDouble()
            val links = model.noteLinks.filter { it.noteId == n.id && stepById[it.targetId]?.let { s -> cellOf(s) } != null }
            val h = maxOf(size.height.toDouble(), BlueprintNoteRouter.minNoteHeight(anchors = links.size.coerceAtLeast(1)))
            val steps = anchored.getValue(n.id)
            val first = steps.firstOrNull()?.let { cellOf(it) }
            val desired = if (first != null) first.second + bandHeight(steps.first().layer) / 2 - h / 2 else bottom
            val y = maxOf(desired, bottom)
            bottom = y + h + NOTE_GAP
            val targets =
                links.map { link ->
                    val step = stepById.getValue(link.targetId)
                    val (_, cellY) = cellOf(step) ?: error("filtered above")
                    val card = cardRects.getValue(step.id)
                    val siblings = cardsIn(phaseId = step.phaseRef, layer = step.layer)
                    val idx = siblings.indexOfFirst { it.step.id == step.id }
                    BlueprintNoteRouter.Target(
                        linkId = link.id,
                        laneTop = cellY,
                        cardLeft = card.x,
                        cardTop = card.y,
                        cardRight = card.x + card.w,
                        cardBottom = card.y + card.h,
                        laneBottom = cellY + bandHeight(step.layer),
                        // In a stack the margin zone above/below a card is the gap to its neighbour card.
                        topStart =
                            if (idx ==
                                0
                            ) {
                                cellY + BlueprintNoteRouter.LANE_LINE_CLEARANCE
                            } else {
                                siblings[idx - 1].let { it.y + it.h } +
                                    BlueprintNoteRouter.TRACK_CLEARANCE
                            },
                        bottomStart =
                            if (idx == siblings.lastIndex) {
                                cellY + bandHeight(step.layer) - BlueprintNoteRouter.LANE_LINE_CLEARANCE
                            } else {
                                siblings[idx + 1].y - BlueprintNoteRouter.TRACK_CLEARANCE
                            },
                    )
                }
            val routes =
                BlueprintNoteRouter.route(
                    noteX = noteX,
                    noteY = y,
                    noteH = h,
                    targets = targets,
                    laneSlots = laneSlots,
                    cardEntries = cardEntries,
                    horizontalObstacles = obstacles,
                    claimedTracks = claimedTracks,
                    blocks = blocks,
                )
            out += PlacedBlueprintNote(id = n.id, body = n.body, x = noteX, y = y, w = w, h = h, routes = routes)
        }
        return out
    }

    /** Lane boundaries and the top/bottom edges of all step cards: the horizontals an anchor track keeps clear of. */
    private fun horizontalObstacles(): List<BlueprintNoteRouter.HLine> {
        val out = ArrayList<BlueprintNoteRouter.HLine>()
        for (layer in layers) {
            val band = bandY(layer)
            for (y in listOf(band.start, band.endInclusive)) {
                out +=
                    BlueprintNoteRouter.HLine(
                        y = y,
                        xFrom = contentLeft,
                        xTo = contentRight,
                        clearance = BlueprintNoteRouter.LANE_LINE_CLEARANCE,
                    )
            }
        }
        // The captions of the separator lines sit right-aligned just above each lane boundary; a track must not cross them.
        for (layer in if (captionWidth > 0.0) layers.dropLast(1) else emptyList()) {
            val bottom = bandY(layer).endInclusive
            out +=
                BlueprintNoteRouter.HLine(
                    y = bottom - SEPARATOR_CAPTION_CENTER_PX,
                    xFrom = contentRight - captionWidth,
                    xTo = contentRight,
                    clearance = SEPARATOR_CAPTION_CLEARANCE_PX,
                )
        }
        for (card in cardRects.values) {
            out += BlueprintNoteRouter.HLine(y = card.y, xFrom = card.x, xTo = card.x + card.w)
            out += BlueprintNoteRouter.HLine(y = card.y + card.h, xFrom = card.x, xTo = card.x + card.w)
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

/** A step card placed in the grid: [x]/[y] top-left, [w]/[h] size. */
internal class CardRect(
    val step: dev.kuml.blueprint.model.JourneyStep,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    val cx: Double get() = x + w / 2.0
    val cy: Double get() = y + h / 2.0
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

/** Distance of the caption text centre above its lane boundary and the clearance a track keeps. */
private const val SEPARATOR_CAPTION_CENTER_PX: Double = 16.0
private const val SEPARATOR_CAPTION_CLEARANCE_PX: Double = 9.0
private const val NOTE_GUTTER: Double = 16.0
private const val NOTE_GAP: Double = 12.0
private val CARD_MARGIN_SIDE: Double = BlueprintGridConstants.CARD_MARGIN_SIDE
