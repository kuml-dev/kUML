package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent

/** Approximate average character width for font-size 12 sans-serif (px). */
private const val AVG_CHAR_WIDTH_PX = 6.5

/** Line height for wrapped step-card titles (px). */
private const val TITLE_LINE_HEIGHT = 14.0

/** Approximate average character width for the 9pt `kuml-small` pain caption (px). */
private const val SMALL_CHAR_WIDTH_PX = 5.4

/**
 * Wraps [text] into lines that fit within [maxWidthPx], splitting only at
 * word boundaries. A single word wider than the column is kept on its own
 * line (never truncated).
 */
internal fun wrapText(
    text: String,
    maxWidthPx: Double,
): List<String> {
    val maxChars = (maxWidthPx / AVG_CHAR_WIDTH_PX).toInt().coerceAtLeast(1)
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (candidate.length <= maxChars) {
            current.clear()
            current.append(candidate)
        } else {
            if (current.isNotEmpty()) lines += current.toString()
            current.clear()
            current.append(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}

/**
 * Truncates [text] to fit within [maxWidthPx] on a single line, appending an
 * ellipsis when it doesn't fit. Deliberately never wraps to a second line —
 * the pain-point caption sits in a fixed-height reserve
 * ([dev.kuml.blueprint.model.BlueprintGridConstants.contentAwareRowHeight]
 * only reserves one extra line), so unbounded pain text would either
 * overflow or require unbounded card growth. The full, untruncated text
 * stays available via the pain-dot's `<title>` tooltip.
 */
internal fun truncateOneLine(
    text: String,
    maxWidthPx: Double,
): String {
    val maxChars = (maxWidthPx / SMALL_CHAR_WIDTH_PX).toInt().coerceAtLeast(1)
    if (text.length <= maxChars) return text
    if (maxChars <= 1) return "…"
    return text.take(maxChars - 1) + "…"
}

/**
 * Draws a single step card (rounded rect, title, optional pain/opportunity
 * marker, optional actor-role icon) inside its (phase × layer) cell.
 *
 * V3.1.23 — base card. V3.1.24 adds the [actor]-role icon in the top-right
 * corner (so backstage/support steps show *who* performs them) and a per-layer
 * accent stroke passed in as [accent]. V3.1.25 shifts text anchor left when
 * an icon is present. V3.1.27 adds automatic title text wrapping via
 * [wrapText] + `<tspan>` so long titles like "Beschließt Aufnahme im
 * Vorstand" no longer overflow the card. V3.1.28 uses an asymmetric top/bottom
 * margin (8 px top, 24 px bottom) so Shostack separator-line captions have a
 * clear 24 px zone between the card border and the separator line. Fix:
 * the title block used to be vertically re-centered by shifting the first
 * line up for 2+ line titles, which pushed 3+ line titles above the card's
 * top border; the first line is now always anchored at y+20, and
 * [BlueprintGridConstants.contentAwareRowHeight] grows [cellH] so the card
 * always has room below for however many lines a title wraps to.
 */
internal fun SvgBuilder.renderStepCard(
    step: JourneyStep,
    cellX: Double,
    cellY: Double,
    cellW: Double,
    cellH: Double,
    actor: Actor? = null,
    accent: String = "#b0b8c4",
) {
    val mSide = 8.0
    val mTop = BlueprintGridConstants.CARD_MARGIN_TOP
    val mBottom = BlueprintGridConstants.CARD_MARGIN_BOTTOM
    val x = cellX + mSide
    val y = cellY + mTop
    val w = cellW - 2 * mSide
    val h = cellH - mTop - mBottom
    tag("g", mapOf("id" to step.id)) {
        rawXml(
            """<rect x="${f(x)}" y="${f(y)}" width="${f(w)}" height="${f(h)}" rx="6" """ +
                """fill="#fff" stroke="$accent" stroke-width="1.5"/>""",
        )
        // When an actor icon is present, reserve space on the right so the
        // title text does not bleed under it (iconSize=16 + 4px right margin +
        // 4px gap = 24px reserve).
        val iconSize = 16.0
        val iconRightReserve = if (actor != null) iconSize + 8 else 0.0
        val textWidth = w - iconRightReserve
        val textCenterX = x + textWidth / 2.0

        // Wrap title text to avoid horizontal overflow (V3.1.27).
        val lines = wrapText(step.name ?: step.id, textWidth)
        // First-line baseline: always y+20, regardless of line count (fix).
        // The previous version shifted this up by half the wrapped block's
        // extra height to "center" multi-line titles — for 3+ lines that
        // pushed the first baseline above the card's top border. Top-
        // anchoring is safe now that the card height itself is content-aware
        // (see BlueprintGridConstants.contentAwareRowHeight), so it always
        // has room below for however many lines a title wraps to.
        val firstLineY = y + 20.0

        if (lines.size == 1) {
            tag(
                "text",
                mapOf(
                    "x" to f(textCenterX),
                    "y" to f(firstLineY),
                    "text-anchor" to "middle",
                    "class" to "kuml-body",
                    "font-size" to "12",
                ),
            ) { text(lines[0]) }
        } else {
            rawXml(
                buildString {
                    append("""<text x="${f(textCenterX)}" y="${f(firstLineY)}" """)
                    append("""text-anchor="middle" class="kuml-body" font-size="12">""")
                    lines.forEachIndexed { idx, line ->
                        val dy = if (idx == 0) "0" else f(TITLE_LINE_HEIGHT)
                        append("""<tspan x="${f(textCenterX)}" dy="$dy">${xmlEscapeContent(line)}</tspan>""")
                    }
                    append("</text>")
                },
            )
        }
        // Actor-role icon (top-right corner), V3.1.24.
        if (actor != null) {
            val ix = x + w - iconSize - 4
            val iy = y + 4
            val icon = BlueprintActorIcons.fragmentFor(actor.role)
            rawXml(
                """<g transform="translate(${f(ix)},${f(iy)})" color="#1d2968">$icon""" +
                    """<title>${xmlEscapeContent((actor.name ?: actor.id) + " (" + actor.role + ")")}</title></g>""",
            )
        }
        val painPoint = step.painPoint
        if (painPoint != null) {
            // Dot + caption share their own row above the touchpoint-icon
            // row (fix: the dot used to sit in the touchpoint row itself,
            // which only had horizontal room for the dot — a caption there
            // would collide with the icons; moving both up onto a row of
            // their own means the touchpoint row no longer needs any
            // pain-specific offset either, see BlueprintGridSvg's tpStartX).
            // Single line, truncated: the full text remains in the tooltip.
            val painRowY = y + h - 24
            rawXml(
                """<circle cx="${f(x + 10)}" cy="${f(painRowY)}" r="5" fill="#d00080"/>""" +
                    """<title>${xmlEscapeContent("Pain: $painPoint")}</title>""",
            )
            tag(
                "text",
                mapOf(
                    "x" to f(x + 20),
                    "y" to f(painRowY + 3.0),
                    "class" to "kuml-small",
                    "font-size" to "9",
                ),
            ) { text(truncateOneLine(painPoint, w - 26.0)) }
        }
        if (step.opportunity != null) {
            rawXml(
                """<circle cx="${f(x + w - 10)}" cy="${f(y + h - 10)}" r="5" fill="#186cb4"/>""" +
                    """<title>${xmlEscapeContent("Chance: " + step.opportunity)}</title>""",
            )
        }
    }
}
