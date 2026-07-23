package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.Touchpoint
import dev.kuml.io.svg.SvgBuilder

/**
 * Avg width of a 9pt legend-label character (px). Mirrors the private
 * `LEGEND_CHAR_WIDTH_PX` in
 * [dev.kuml.blueprint.model.BlueprintGridConstants] — duplicated here
 * because the wrap *decision* (which chips land in which row) is computed
 * once in the shared metamodel module (consumed by both this renderer and
 * `BlueprintLayoutBridge`), but the actual per-chip x-advance while
 * *drawing* a row is only needed here. MUST stay in sync with that constant.
 */
private const val LEGEND_CHAR_WIDTH_PX = 5.4

/**
 * Draws the touchpoint legend band below the grid: one row per entry in
 * [rows] (already wrapped to fit the content width by
 * [BlueprintGridConstants.wrapLegendEntries]), each row a left-to-right
 * sequence of "numbered badge + touchpoint name" chips.
 *
 * Fix: touchpoint icons in the grid only ever showed the channel glyph —
 * the touchpoint's own name was never rendered anywhere, so two touchpoints
 * sharing a channel (e.g. two different push notifications) were
 * indistinguishable. Each chip's badge number matches the numbered circle
 * [dev.kuml.io.svg.blueprint.renderTouchpoint] draws on that touchpoint's
 * icon in the grid above.
 */
internal fun SvgBuilder.renderBlueprintLegend(
    rows: List<List<Pair<Int, Touchpoint>>>,
    x: Double,
    y: Double,
) {
    if (rows.isEmpty()) return
    val rowHeight = BlueprintGridConstants.LEGEND_ROW_HEIGHT
    val badgeDiameter = BlueprintGridConstants.LEGEND_BADGE_DIAMETER
    val badgeTextGap = BlueprintGridConstants.LEGEND_BADGE_TEXT_GAP
    val chipGap = BlueprintGridConstants.LEGEND_CHIP_GAP
    val badgeR = badgeDiameter / 2.0

    rows.forEachIndexed { rowIdx, row ->
        val rowCy = y + rowIdx * rowHeight + rowHeight / 2.0
        var cursorX = x
        for ((badge, tp) in row) {
            val badgeCx = cursorX + badgeR
            rawXml(
                """<circle cx="${f(badgeCx)}" cy="${f(rowCy)}" r="${f(badgeR)}" """ +
                    """fill="#333" stroke="white" stroke-width="1"/>""" +
                    """<text x="${f(badgeCx)}" y="${f(rowCy + 3.0)}" text-anchor="middle" """ +
                    """font-size="9" font-weight="700" fill="white">$badge</text>""",
            )
            val label = tp.name ?: tp.id
            val textX = cursorX + badgeDiameter + badgeTextGap
            tag(
                "text",
                mapOf(
                    "x" to f(textX),
                    "y" to f(rowCy + 3.0),
                    "class" to "kuml-small",
                    "font-size" to "9",
                ),
            ) { text(label) }
            cursorX = textX + label.length * LEGEND_CHAR_WIDTH_PX + chipGap
        }
    }
}
