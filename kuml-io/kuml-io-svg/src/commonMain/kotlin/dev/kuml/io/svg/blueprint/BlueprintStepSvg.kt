package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLabels
import dev.kuml.blueprint.model.Channel
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Touchpoint
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent

/**
 * Wraps [text] into lines that fit within [maxWidthPx], splitting only at
 * word boundaries. A single word wider than the column is kept on its own
 * line (never truncated). Delegates to the shared
 * [BlueprintGridConstants.wrapWords], the one wrap algorithm.
 */
internal fun wrapText(
    text: String,
    maxWidthPx: Double,
): List<String> =
    BlueprintGridConstants.wrapWords(
        text = text,
        maxChars = (maxWidthPx / BlueprintGridConstants.AVG_CHAR_WIDTH_PX).toInt().coerceAtLeast(1),
    )

/**
 * Truncates [text] to fit within [maxWidthPx] on a single line, appending an
 * ellipsis when it doesn't fit. Deliberately never wraps to a second line —
 * the marker row (pain/opportunity) is a fixed-height reserve
 * ([BlueprintGridConstants.MARKER_ROW_RESERVE]), so unbounded pain text would
 * either overflow or require unbounded card growth. The full, untruncated text
 * stays available via the pain-dot's `<title>` tooltip.
 */
internal fun truncateOneLine(
    text: String,
    maxWidthPx: Double,
    charWidthPx: Double = BlueprintGridConstants.SMALL_CHAR_WIDTH_PX,
): String {
    val maxChars = (maxWidthPx / charWidthPx).toInt().coerceAtLeast(1)
    if (text.length <= maxChars) return text
    if (maxChars <= 1) return "…"
    return text.take(maxChars - 1) + "…"
}

/** A touchpoint drawn as a badge in a card's footer: its legend number is [badge]. */
internal class FooterTouchpoint(
    val tp: Touchpoint,
    val channel: Channel?,
    val badge: Int?,
)

/**
 * Draws a single step card inside its (phase x layer) cell: rounded rect, title,
 * optional pain/opportunity marker row and an optional footer row.
 *
 * V3.1.23 base card; V3.1.27 automatic title wrapping; V3.1.28 asymmetric
 * top/bottom cell margins. Wave 2G-c: the card rectangle ([x], [y], [w], [h]) is
 * decided by [BlueprintGeometry] (cards of one cell are stacked, the height is
 * content-aware). The title uses the full inner width; the actor lives in the
 * footer row, left (role icon + name, truncated with an ellipsis, full name in
 * the tooltip) and the touchpoints right (numbered badges, step 20 px; when they
 * do not fit beside the actor name the last ones collapse into a "+n" chip).
 * Card text, icon and badges never overlap because they sit in separate rows /
 * columns of the same rectangle.
 */
internal fun SvgBuilder.renderStepCard(
    step: JourneyStep,
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    labels: BlueprintLabels = BlueprintLabels.ENGLISH,
    actor: Actor? = null,
    accent: String = "#b0b8c4",
    touchpoints: List<FooterTouchpoint> = emptyList(),
) {
    val inset = BlueprintGridConstants.CARD_TEXT_INSET
    val textWidth = w - 2 * inset
    val textCenterX = x + w / 2.0
    val hasFooter = actor != null || touchpoints.isNotEmpty()
    val footerHeight = BlueprintGridConstants.CARD_FOOTER_HEIGHT
    val footerBottomInset = BlueprintGridConstants.CARD_FOOTER_BOTTOM_INSET
    val footerCy = y + h - footerBottomInset - footerHeight / 2.0
    tag(name = "g", attrs = mapOf("id" to step.id)) {
        rawXml(
            """<rect x="${f(x)}" y="${f(y)}" width="${f(w)}" height="${f(h)}" rx="6" """ +
                """fill="#fff" stroke="$accent" stroke-width="1.5"/>""",
        )

        // Title: full inner width, wrapped by the shared algorithm (the height was computed from the same lines).
        val lines = BlueprintGridConstants.cardTitleLines(step)
        // First-line baseline: always y+20, regardless of line count.
        val firstLineY = y + BlueprintGridConstants.FIRST_LINE_TOP_OFFSET
        if (lines.size == 1) {
            tag(
                name = "text",
                attrs =
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
                        val dy = if (idx == 0) "0" else f(BlueprintGridConstants.TITLE_LINE_HEIGHT)
                        append("""<tspan x="${f(textCenterX)}" dy="$dy">${xmlEscapeContent(line)}</tspan>""")
                    }
                    append("</text>")
                },
            )
        }

        // Marker row (pain + opportunity) sits above the footer, or at the bottom when there is none.
        if (BlueprintGridConstants.hasMarkerRow(step)) {
            val markerY =
                if (hasFooter) {
                    y + h - footerBottomInset - footerHeight - BlueprintGridConstants.CARD_FOOTER_GAP - 5.0
                } else {
                    y + h - 10.0
                }
            val opportunityWidth = if (step.opportunity != null) 16.0 else 0.0
            val painPoint = step.painPoint
            if (painPoint != null) {
                rawXml(
                    """<circle cx="${f(x + inset + 2)}" cy="${f(markerY)}" r="5" fill="#d00080"/>""" +
                        """<title>${xmlEscapeContent(labels.painPrefix + painPoint)}</title>""",
                )
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "x" to f(x + inset + 12),
                            "y" to f(markerY + 3.0),
                            "class" to "kuml-small",
                            "font-size" to "9",
                        ),
                ) { text(truncateOneLine(text = painPoint, maxWidthPx = textWidth - 12.0 - opportunityWidth)) }
            }
            if (step.opportunity != null) {
                rawXml(
                    """<circle cx="${f(x + w - inset - 5)}" cy="${f(markerY)}" r="5" fill="#186cb4"/>""" +
                        """<title>${xmlEscapeContent(labels.opportunityPrefix + step.opportunity)}</title>""",
                )
            }
        }

        if (hasFooter) renderFooter(actor = actor, touchpoints = touchpoints, x = x, w = w, cy = footerCy)
    }
}

/** Footer row: actor role icon + name on the left, numbered touchpoint badges right-aligned. */
private fun SvgBuilder.renderFooter(
    actor: Actor?,
    touchpoints: List<FooterTouchpoint>,
    x: Double,
    w: Double,
    cy: Double,
) {
    val inset = BlueprintGridConstants.CARD_TEXT_INSET
    val iconSize = BlueprintGridConstants.FOOTER_ICON_SIZE
    val left = x + inset
    val right = x + w - inset
    val actorReserve =
        if (actor != null) iconSize + BlueprintGridConstants.FOOTER_ICON_GAP + BlueprintGridConstants.FOOTER_NAME_MIN_WIDTH else 0.0
    val badges =
        BlueprintGridConstants.footerBadgeLayout(
            badgeCount = touchpoints.size,
            availableWidth = right - left,
            reservedWidth = actorReserve,
        )
    val badgesWidth = badges.slots * BlueprintGridConstants.BADGE_STEP

    if (actor != null) {
        val icon = BlueprintActorIcons.fragmentFor(actor.role)
        val fullName = actor.name ?: actor.id
        rawXml(
            """<g transform="translate(${f(left)},${f(cy - iconSize / 2.0)})" color="#1d2968">$icon""" +
                """<title>${xmlEscapeContent("$fullName (${actor.role})")}</title></g>""",
        )
        val nameX = left + iconSize + BlueprintGridConstants.FOOTER_ICON_GAP
        val nameWidth = (right - badgesWidth - nameX).coerceAtLeast(0.0)
        val shown =
            truncateOneLine(
                text = BlueprintGridConstants.clipText(text = fullName, max = BlueprintGridConstants.MAX_ACTOR_NAME_CHARS),
                maxWidthPx = nameWidth,
            )
        rawXml(
            """<text x="${f(nameX)}" y="${f(cy + 3.5)}" class="kuml-small" font-size="10">""" +
                """${xmlEscapeContent(shown)}<title>${xmlEscapeContent(fullName)}</title></text>""",
        )
    }

    // Badges right-aligned; slot k spans [right - (slots - k) * step, right - (slots - k - 1) * step].
    val step = BlueprintGridConstants.BADGE_STEP
    for (k in 0 until badges.visible) {
        val t = touchpoints[k]
        val bcx = right - (badges.slots - k) * step + step / 2.0
        rawXml("<g>")
        rawXml("""<title>${xmlEscapeContent(touchpointTooltip(t))}</title>""")
        renderTouchpoint(tp = t.tp, channel = t.channel, cx = bcx, cy = cy, size = iconSize, centerLabel = t.badge?.toString() ?: "")
        rawXml("</g>")
    }
    if (badges.overflow > 0) {
        val bcx = right - step / 2.0
        val hidden = touchpoints.drop(badges.visible)
        rawXml(
            """<g><title>${xmlEscapeContent(hidden.joinToString(", ") { touchpointTooltip(it) })}</title>""" +
                """<rect x="${f(bcx - step / 2.0 + 1)}" y="${f(cy - 8)}" width="${f(step - 2)}" height="16" rx="8" """ +
                """fill="#333"/><text x="${f(bcx)}" y="${f(cy + 3.0)}" text-anchor="middle" font-size="8" """ +
                """font-weight="700" fill="white">+${badges.overflow}</text></g>""",
        )
    }
}

private fun touchpointTooltip(t: FooterTouchpoint): String {
    val name = t.tp.name ?: t.tp.id
    val channel = t.channel?.let { " (" + (it.name ?: it.kind.name) + ")" } ?: ""
    return (t.badge?.let { "$it: " } ?: "") + name + channel
}
