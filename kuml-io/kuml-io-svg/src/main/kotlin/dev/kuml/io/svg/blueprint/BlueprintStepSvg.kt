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
 * clear 24 px zone between the card border and the separator line.
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
        val textBlockH = (lines.size - 1) * TITLE_LINE_HEIGHT
        // First-line baseline: y+20 for single line; shift up by half the
        // extra block height so multi-line titles stay in the upper third.
        val firstLineY = y + 20.0 - textBlockH / 2.0

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
        if (step.painPoint != null) {
            rawXml(
                """<circle cx="${f(x + 10)}" cy="${f(y + h - 10)}" r="5" fill="#d00080"/>""" +
                    """<title>${xmlEscapeContent("Pain: " + step.painPoint)}</title>""",
            )
        }
        if (step.opportunity != null) {
            rawXml(
                """<circle cx="${f(x + w - 10)}" cy="${f(y + h - 10)}" r="5" fill="#186cb4"/>""" +
                    """<title>${xmlEscapeContent("Chance: " + step.opportunity)}</title>""",
            )
        }
    }
}
