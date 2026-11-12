package dev.kuml.blueprint.model

/**
 * Shared grid-geometry constants for the Journey-Map / Service-Blueprint view.
 *
 * Single source of truth consumed by both the SVG renderer ([BlueprintGeometry]
 * in `kuml-io-svg`) and the layout bridge ([BlueprintLayoutBridge] in
 * `kuml-layout-bridge`). Before V3.1.24 both classes carried identical
 * hardcoded values independently — a silent divergence risk.
 *
 * V3.1.28: [ROW_HEIGHT] increased from 96 → 112 to create a 24 px clearance
 * zone beneath each step card.  The card inner height stays at 80 px
 * ([CARD_MARGIN_TOP] 8 + inner 80 + [CARD_MARGIN_BOTTOM] 24 = 112).
 * [SEPARATOR_LABEL_OFFSET] controls how far the Shostack-line captions sit
 * above the separator line — 12 px places the text comfortably inside the
 * 24 px clearance zone, clear of both the card border and the line itself.
 *
 * V3.1.24
 */
public object BlueprintGridConstants {
    public const val LABEL_COLUMN_WIDTH: Double = 132.0
    public const val COLUMN_WIDTH: Double = 200.0

    /** Width of the note column right of the lanes: a 260 px note plus a 16 px gutter. Shared by the layout bridge and the SVG renderer. */
    public const val NOTE_COLUMN_WIDTH: Double = 276.0

    /** Width reserved right of the grid for notes (0 when the model has none). */
    public fun noteColumnWidth(model: BlueprintModel): Double = if (model.notes.isEmpty()) 0.0 else NOTE_COLUMN_WIDTH

    /** Total height of one layer-band row (card + margins). */
    public const val ROW_HEIGHT: Double = 112.0

    /** Top margin between band edge and step-card rect. */
    public const val CARD_MARGIN_TOP: Double = 8.0

    /**
     * Bottom margin between step-card rect and the band's lower edge.
     *
     * Larger than [CARD_MARGIN_TOP] so Shostack separator-line captions have
     * 24 px of clear space between the card border and the separator line.
     */
    public const val CARD_MARGIN_BOTTOM: Double = 24.0

    /**
     * Baseline distance (px) of a Shostack separator-line caption above the
     * separator line.  12 px places the text at roughly mid-height of the
     * [CARD_MARGIN_BOTTOM] clearance zone.
     */
    public const val SEPARATOR_LABEL_OFFSET: Double = 12.0

    public const val EMOTION_BAND_HEIGHT: Double = 110.0
    public const val HEADER_HEIGHT: Double = 36.0
    public const val PADDING: Double = 16.0

    // ── Step-card metrics (PAL wave 2G-c) ──────────────────────────────────
    //
    // Single source of truth for the card metrics: the SVG renderer
    // (`BlueprintStepSvg`/`BlueprintGeometry`, kuml-io-svg) and the layout bridge
    // (`BlueprintLayoutBridge`, kuml-layout-bridge) both read them from here;
    // kuml-io-svg deliberately does not depend on kuml-layout-bridge, and this
    // metamodel module is the one dependency both already share.

    /** Approximate average character width for font-size 12 sans-serif (px). */
    public const val AVG_CHAR_WIDTH_PX: Double = 6.5

    /** Line height of a wrapped step-card title (px). */
    public const val TITLE_LINE_HEIGHT: Double = 14.0

    /** Horizontal card margin (left + right each) inside a column cell. */
    public const val CARD_MARGIN_SIDE: Double = 8.0

    /** Inner padding between the card border and its text/footer (left + right each). */
    public const val CARD_TEXT_INSET: Double = 8.0

    /** First-line baseline offset from the card top. */
    public const val FIRST_LINE_TOP_OFFSET: Double = 20.0

    /** Clearance below the last title line's baseline (descenders + breathing room). */
    public const val BOTTOM_TEXT_CLEARANCE: Double = 10.0

    /** Extra height of the marker row (pain/opportunity dot + caption) between title and footer. */
    public const val MARKER_ROW_RESERVE: Double = 22.0

    /** Height of the footer row (actor icon + name left, touchpoint badges right). */
    public const val CARD_FOOTER_HEIGHT: Double = 22.0

    /** Gap between the content above and the footer row. */
    public const val CARD_FOOTER_GAP: Double = 6.0

    /** Gap between the footer row and the card's bottom border. */
    public const val CARD_FOOTER_BOTTOM_INSET: Double = 4.0

    /** Smallest card height. */
    public const val CARD_MIN_HEIGHT: Double = 80.0

    /**
     * Vertical gap between two cards stacked in the same cell. 14 px leaves two
     * candidate rows for a note anchor track between the cards (4 px clearance each side).
     */
    public const val CARD_STACK_GAP: Double = 14.0

    /** Horizontal advance from one footer touchpoint badge to the next. */
    public const val BADGE_STEP: Double = 20.0

    /** Edge length of the actor icon and of a footer touchpoint badge. */
    public const val FOOTER_ICON_SIZE: Double = 16.0

    /** Gap between the actor icon and the actor name. */
    public const val FOOTER_ICON_GAP: Double = 4.0

    /** Smallest width the actor name keeps before touchpoint badges collapse into a "+n" chip. */
    public const val FOOTER_NAME_MIN_WIDTH: Double = 60.0

    /** Avg width of a 9pt `kuml-small` character (px), used for the footer actor name and the pain caption. */
    public const val SMALL_CHAR_WIDTH_PX: Double = 5.4

    /** Inner padding of the swimlane header text inside the label column (left + right each). */
    public const val LABEL_COLUMN_TEXT_INSET: Double = 6.0

    /** Line height of a wrapped swimlane header. */
    public const val LABEL_LINE_HEIGHT: Double = 15.0

    /** Approximate char width of the 12px bold swimlane header (px). */
    public const val LABEL_CHAR_WIDTH_PX: Double = 7.0

    /** Approximate char width of the 10px italic separator-line caption (px). */
    public const val CAPTION_CHAR_WIDTH_PX: Double = 7.5

    // Upper bounds against hostile/oversized inputs (a model is untrusted input).

    /** Longest step title that is laid out; the rest is replaced by an ellipsis. */
    public const val MAX_TITLE_CHARS: Int = 300

    /** Most title lines a card shows; a longer title ends in an ellipsis on the last line. */
    public const val MAX_TITLE_LINES: Int = 12

    /** Longest swimlane header / line caption that is laid out. */
    public const val MAX_LABEL_CHARS: Int = 60

    /** Most lines of a wrapped swimlane header. */
    public const val MAX_LABEL_LINES: Int = 6

    /** Longest actor name shown in the card footer (the tooltip keeps the full name). */
    public const val MAX_ACTOR_NAME_CHARS: Int = 80

    /** Cards per cell above which the constraint checker warns (the layout itself stays linear in the card count). */
    public const val MAX_CARDS_PER_CELL: Int = 12

    /** Width available to text and footer inside a card. */
    public fun cardTextWidth(): Double = COLUMN_WIDTH - 2 * CARD_MARGIN_SIDE - 2 * CARD_TEXT_INSET

    /** Clips [text] to [max] characters, ending in an ellipsis when it was longer. */
    public fun clipText(
        text: String,
        max: Int,
    ): String = if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"

    /**
     * Wraps [text] into lines of at most [maxChars] characters, splitting at
     * spaces. A word longer than a line stays on its own line, or, with
     * [splitLongWords], is cut with a trailing hyphen so no text runs out of its
     * box. The one wrap algorithm for card titles and swimlane headers — height
     * computation and drawing must agree on it.
     */
    public fun wrapWords(
        text: String,
        maxChars: Int,
        splitLongWords: Boolean = false,
    ): List<String> {
        val limit = maxChars.coerceAtLeast(2)
        val lines = mutableListOf<String>()
        var current = ""
        for (raw in text.split(" ")) {
            var word = raw
            while (splitLongWords && word.length > limit) {
                if (current.isNotEmpty()) {
                    lines += current
                    current = ""
                }
                lines += word.take(limit - 1) + "-"
                word = word.drop(limit - 1)
            }
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= limit) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    /** The wrapped title lines of [step]'s card, bounded by [MAX_TITLE_CHARS] and [MAX_TITLE_LINES]. */
    public fun cardTitleLines(step: JourneyStep): List<String> {
        val maxChars = (cardTextWidth() / AVG_CHAR_WIDTH_PX).toInt()
        val lines =
            wrapWords(text = clipText(text = step.name ?: step.id, max = MAX_TITLE_CHARS), maxChars = maxChars, splitLongWords = true)
        if (lines.size <= MAX_TITLE_LINES) return lines
        return lines.take(MAX_TITLE_LINES - 1) + clipText(text = lines[MAX_TITLE_LINES - 1] + "…", max = maxChars)
    }

    /** The wrapped swimlane header lines of [text]. */
    public fun laneLabelLines(text: String): List<String> {
        val maxChars = ((LABEL_COLUMN_WIDTH - 2 * LABEL_COLUMN_TEXT_INSET) / LABEL_CHAR_WIDTH_PX).toInt()
        val lines = wrapWords(text = clipText(text = text, max = MAX_LABEL_CHARS), maxChars = maxChars, splitLongWords = true)
        if (lines.size <= MAX_LABEL_LINES) return lines
        return lines.take(MAX_LABEL_LINES - 1) + clipText(text = lines[MAX_LABEL_LINES - 1] + "…", max = maxChars)
    }

    /** Estimated width of the widest separator-line caption shown (the notes router keeps clear of it). */
    public fun captionWidth(
        labels: BlueprintLabels,
        lines: Collection<BlueprintLine>,
    ): Double = (lines.maxOfOrNull { clipText(text = labels.line(it), max = MAX_LABEL_CHARS).length } ?: 0) * CAPTION_CHAR_WIDTH_PX

    /** Whether [step]'s card carries a footer row (an actor and/or touchpoints). */
    public fun hasFooter(step: JourneyStep): Boolean = step.actorRef != null || step.touchpointRefs.isNotEmpty()

    /** Whether [step]'s card carries a marker row (pain point and/or opportunity). */
    public fun hasMarkerRow(step: JourneyStep): Boolean = step.painPoint != null || step.opportunity != null

    /** Most lines the actor name in the card footer wraps to; a longer name ends in an ellipsis on the last line. */
    public const val MAX_ACTOR_NAME_LINES: Int = 3

    /** Line height of the wrapped actor name in the card footer (px). */
    public const val ACTOR_LINE_HEIGHT: Double = 12.0

    /** Font size of the actor name in the card footer (px). */
    public const val ACTOR_FONT_SIZE: Double = 10.0

    /**
     * Badge slots of a footer with [badgeCount] touchpoint badges: how many fit beside the actor
     * (when [hasActor]) and how many collapse into a "+n" chip. Shared by the renderer and the
     * height computation so both agree on the actor name's width.
     */
    public fun footerBadgeSlots(
        badgeCount: Int,
        hasActor: Boolean,
    ): FooterBadges =
        footerBadgeLayout(
            badgeCount = badgeCount,
            availableWidth = cardTextWidth(),
            reservedWidth = if (hasActor) FOOTER_ICON_SIZE + FOOTER_ICON_GAP + FOOTER_NAME_MIN_WIDTH else 0.0,
        )

    /**
     * The wrapped footer lines of the actor [name] on a card with [badgeCount] touchpoint badges:
     * at most [MAX_ACTOR_NAME_LINES] lines, the last ending in an ellipsis when the name is longer.
     * An empty list when [name] is null. The one line source for drawing and height computation.
     */
    public fun actorNameLines(
        name: String?,
        badgeCount: Int,
    ): List<String> {
        if (name == null) return emptyList()
        val slots = footerBadgeSlots(badgeCount = badgeCount, hasActor = true).slots
        val width = cardTextWidth() - FOOTER_ICON_SIZE - FOOTER_ICON_GAP - slots * BADGE_STEP
        val maxChars = (width / SMALL_CHAR_WIDTH_PX).toInt().coerceAtLeast(4)
        val lines =
            wrapWords(text = clipText(text = name, max = MAX_ACTOR_NAME_CHARS), maxChars = maxChars, splitLongWords = true)
        if (lines.size <= MAX_ACTOR_NAME_LINES) return lines
        return lines.take(MAX_ACTOR_NAME_LINES - 1) + clipText(text = lines[MAX_ACTOR_NAME_LINES - 1] + "…", max = maxChars)
    }

    /** Height (px) of the footer row for [lineCount] actor-name lines; one line keeps [CARD_FOOTER_HEIGHT]. */
    public fun footerHeight(lineCount: Int): Double = maxOf(CARD_FOOTER_HEIGHT, ACTOR_FONT_SIZE + ACTOR_LINE_HEIGHT * lineCount)

    /**
     * Natural height (px) of [step]'s card: title, marker row and footer, at least [CARD_MIN_HEIGHT].
     * [actorName] is the resolved actor name (wrapped in the footer, which grows with its line count);
     * [badgeCount] the number of touchpoint badges actually drawn.
     */
    public fun cardHeight(
        step: JourneyStep,
        actorName: String? = null,
        badgeCount: Int = step.touchpointRefs.size,
    ): Double {
        val lines = cardTitleLines(step).size
        val title = FIRST_LINE_TOP_OFFSET + (lines - 1) * TITLE_LINE_HEIGHT + BOTTOM_TEXT_CLEARANCE
        val marker = if (hasMarkerRow(step)) MARKER_ROW_RESERVE else 0.0
        val nameLines = actorNameLines(name = actorName, badgeCount = badgeCount).size.coerceAtLeast(1)
        val footer = if (hasFooter(step)) CARD_FOOTER_GAP + footerHeight(lineCount = nameLines) else 0.0
        return maxOf(CARD_MIN_HEIGHT, title + marker + footer)
    }

    private fun actorNameOf(
        model: BlueprintModel,
        step: JourneyStep,
    ): String? = step.actorRef?.let { ref -> model.actors.firstOrNull { it.id == ref } }?.let { it.name ?: it.id }

    private fun cardHeightIn(
        model: BlueprintModel,
        step: JourneyStep,
    ): Double {
        val known = model.touchpoints.mapTo(HashSet()) { it.id }
        return cardHeight(
            step = step,
            actorName = actorNameOf(model = model, step = step),
            badgeCount = step.touchpointRefs.count { it in known },
        )
    }

    /** A card placed inside its cell: [yOffset] is relative to the cell's top edge. */
    public data class StackedCard(
        val step: JourneyStep,
        val yOffset: Double,
        val height: Double,
    )

    private fun naturalStackHeight(
        steps: List<JourneyStep>,
        model: BlueprintModel,
    ): Double = steps.sumOf { cardHeightIn(model = model, step = it) } + (steps.size - 1).coerceAtLeast(0) * CARD_STACK_GAP

    /**
     * Height (px) of every layer band in [layers]: tall enough for the tallest
     * cell stack of that layer (cards in declaration order, [CARD_STACK_GAP]
     * apart), at least [ROW_HEIGHT]. Each layer has its own height.
     */
    public fun layerHeights(
        model: BlueprintModel,
        layers: List<BlueprintLayer>,
    ): Map<BlueprintLayer, Double> {
        val stepsByCell = model.steps.groupBy { it.layer to it.phaseRef }
        return layers.associateWith { layer ->
            val tallest =
                stepsByCell
                    .filterKeys { it.first == layer }
                    .values
                    .maxOfOrNull { naturalStackHeight(steps = it, model = model) } ?: 0.0
            maxOf(ROW_HEIGHT, CARD_MARGIN_TOP + tallest + CARD_MARGIN_BOTTOM)
        }
    }

    /**
     * The cards of cell ([phaseId], [layer]) stacked top to bottom in declaration
     * order inside a band of height [layerHeight]. Spare room is shared evenly,
     * so a lone card fills the band's card area and the cards of one layer line
     * up at the bottom.
     */
    public fun stackedCards(
        model: BlueprintModel,
        phaseId: String,
        layer: BlueprintLayer,
        layerHeight: Double,
    ): List<StackedCard> {
        val steps = model.stepsIn(phaseId = phaseId, layer = layer)
        if (steps.isEmpty()) return emptyList()
        val inner = layerHeight - CARD_MARGIN_TOP - CARD_MARGIN_BOTTOM
        val extra = ((inner - naturalStackHeight(steps = steps, model = model)) / steps.size).coerceAtLeast(0.0)
        var y = CARD_MARGIN_TOP
        return steps.map { step ->
            val h = cardHeightIn(model = model, step = step) + extra
            StackedCard(step = step, yOffset = y, height = h).also { y += h + CARD_STACK_GAP }
        }
    }

    /** Result of [footerBadgeLayout]: [visible] badges are drawn, [overflow] more collapse into one "+n" chip. */
    public data class FooterBadges(
        val visible: Int,
        val overflow: Int,
    ) {
        /** Number of [BADGE_STEP] slots taken, including the "+n" chip. */
        val slots: Int get() = visible + if (overflow > 0) 1 else 0
    }

    /**
     * How many of [badgeCount] footer touchpoint badges fit into [availableWidth]
     * after [reservedWidth] (actor icon + minimum name width) is kept free; the
     * rest collapses into a trailing "+n" chip. At least one slot always remains.
     */
    public fun footerBadgeLayout(
        badgeCount: Int,
        availableWidth: Double,
        reservedWidth: Double,
    ): FooterBadges {
        if (badgeCount <= 0) return FooterBadges(visible = 0, overflow = 0)
        val slots = ((availableWidth - reservedWidth) / BADGE_STEP).toInt().coerceAtLeast(1)
        if (badgeCount <= slots) return FooterBadges(visible = badgeCount, overflow = 0)
        val visible = slots - 1
        return FooterBadges(visible = visible, overflow = badgeCount - visible)
    }

    // ── Touchpoint legend (fix: touchpoint names were never rendered) ──────
    //
    // Touchpoint icons only ever drew the channel glyph, never the
    // touchpoint's own name — readers had no way to tell two same-channel
    // touchpoints apart (e.g. two different push notifications) without
    // reading the source DSL. Each touchpoint actually referenced by a step
    // gets a small numbered badge on its icon; the legend below the grid
    // maps badge number -> touchpoint name, keyed by individual touchpoint
    // (not just channel/symbol), so distinct touchpoints stay distinguishable.

    /** Diameter of a legend badge circle (mirrors the badge drawn on touchpoint icons). */
    public const val LEGEND_BADGE_DIAMETER: Double = 16.0

    /** Gap between a legend badge and its label text. */
    public const val LEGEND_BADGE_TEXT_GAP: Double = 4.0

    /** Edge length of the channel glyph between a legend badge and its label (wave 2G-c: the card badges carry only the number). */
    public const val LEGEND_ICON_SIZE: Double = 12.0

    /** Gap between the channel glyph and the label text. */
    public const val LEGEND_ICON_GAP: Double = 4.0

    /** Gap between one legend chip and the next. */
    public const val LEGEND_CHIP_GAP: Double = 20.0

    /** Vertical height per wrapped legend row. */
    public const val LEGEND_ROW_HEIGHT: Double = 22.0

    /** Gap between the last diagram row and the legend's first row. */
    public const val LEGEND_TOP_PADDING: Double = 12.0

    /** Avg width of a 9pt legend-label character (mirrors kuml-small typography). */
    private const val LEGEND_CHAR_WIDTH_PX: Double = 5.4

    /**
     * Touchpoints actually referenced by at least one *visible* step, in
     * declaration order, each paired with a stable 1-based badge number.
     * Touchpoints declared but never referenced by a visible step — either
     * unused entirely, or only referenced by a step whose layer isn't in
     * [visibleLayers] — are silently omitted (no orphaned legend entries).
     *
     * Bug this fixes: the first version scanned `model.steps` unfiltered by
     * layer, so a step in a hidden layer (e.g. a `JourneyDiagram`'s default
     * `visibleLayers = {CUSTOMER_ACTIONS}` hiding a frontstage/backstage/
     * support step) could still add its touchpoint to the legend — an entry
     * with no matching badge anywhere in the actually-drawn diagram.
     */
    public fun legendEntries(
        model: BlueprintModel,
        visibleLayers: Set<BlueprintLayer>,
    ): List<Pair<Int, Touchpoint>> {
        val usedIds =
            model.steps
                .filter { it.layer in visibleLayers }
                .flatMap { it.touchpointRefs }
                .toSet()
        return model.touchpoints
            .filter { it.id in usedIds }
            .mapIndexed { index, tp -> (index + 1) to tp }
    }

    /** Estimated rendered width of one legend chip (badge + gap + label + trailing gap). */
    private fun legendChipWidth(touchpoint: Touchpoint): Double {
        val label = touchpoint.name ?: touchpoint.id
        val textWidth = label.length * LEGEND_CHAR_WIDTH_PX
        return LEGEND_BADGE_DIAMETER + LEGEND_BADGE_TEXT_GAP + LEGEND_ICON_SIZE + LEGEND_ICON_GAP + textWidth + LEGEND_CHIP_GAP
    }

    /**
     * Greedily wraps [entries] into rows that fit within [contentWidth] —
     * same left-to-right fill strategy as [wrapWords],
     * except the unit is a whole chip (badge + label) rather than a word,
     * since a chip can't be split across rows.
     */
    public fun wrapLegendEntries(
        entries: List<Pair<Int, Touchpoint>>,
        contentWidth: Double,
    ): List<List<Pair<Int, Touchpoint>>> {
        if (entries.isEmpty()) return emptyList()
        val rows = mutableListOf<List<Pair<Int, Touchpoint>>>()
        var currentRow = mutableListOf<Pair<Int, Touchpoint>>()
        var currentWidth = 0.0
        for (entry in entries) {
            val w = legendChipWidth(entry.second)
            if (currentRow.isNotEmpty() && currentWidth + w > contentWidth) {
                rows += currentRow
                currentRow = mutableListOf()
                currentWidth = 0.0
            }
            currentRow += entry
            currentWidth += w
        }
        if (currentRow.isNotEmpty()) rows += currentRow
        return rows
    }

    /**
     * Height (px) of the touchpoint legend band for [model] at [contentWidth]
     * — `0.0` when no *visible* step (see [legendEntries]) references any
     * touchpoint, so diagrams without touchpoints render exactly as before
     * (no empty legend band).
     */
    public fun legendHeight(
        model: BlueprintModel,
        contentWidth: Double,
        visibleLayers: Set<BlueprintLayer>,
    ): Double {
        val entries = legendEntries(model = model, visibleLayers = visibleLayers)
        if (entries.isEmpty()) return 0.0
        val rows = wrapLegendEntries(entries = entries, contentWidth = contentWidth)
        return LEGEND_TOP_PADDING + rows.size * LEGEND_ROW_HEIGHT
    }
}
