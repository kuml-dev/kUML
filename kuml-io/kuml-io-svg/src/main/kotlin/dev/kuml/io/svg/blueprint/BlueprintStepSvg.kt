package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent

/**
 * Draws a single step card (rounded rect, title, optional pain/opportunity
 * marker, optional actor-role icon) inside its (phase × layer) cell.
 *
 * V3.1.23 — base card. V3.1.24 adds the [actor]-role icon in the top-right
 * corner (so backstage/support steps show *who* performs them) and a per-layer
 * accent stroke passed in as [accent].
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
    val m = 8.0
    val x = cellX + m
    val y = cellY + m
    val w = cellW - 2 * m
    val h = cellH - 2 * m
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
        tag(
            "text",
            mapOf(
                "x" to f(x + (w - iconRightReserve) / 2),
                "y" to f(y + 20),
                "text-anchor" to "middle",
                "class" to "kuml-body",
                "font-size" to "12",
            ),
        ) { text(step.name ?: step.id) }
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
