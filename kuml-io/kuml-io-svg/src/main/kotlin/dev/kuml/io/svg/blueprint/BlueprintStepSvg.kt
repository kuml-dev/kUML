package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent

/**
 * Draws a single step card (rounded rect, title, optional pain/opportunity marker)
 * inside its (phase × layer) cell.
 *
 * V3.1.23
 */
internal fun SvgBuilder.renderStepCard(
    step: JourneyStep,
    cellX: Double,
    cellY: Double,
    cellW: Double,
    cellH: Double,
) {
    val m = 8.0
    val x = cellX + m
    val y = cellY + m
    val w = cellW - 2 * m
    val h = cellH - 2 * m
    tag("g", mapOf("id" to step.id)) {
        rawXml(
            """<rect x="${f(x)}" y="${f(y)}" width="${f(w)}" height="${f(h)}" rx="6" """ +
                """fill="#fff" stroke="#b0b8c4" stroke-width="1"/>""",
        )
        tag(
            "text",
            mapOf(
                "x" to f(x + w / 2),
                "y" to f(y + 20),
                "text-anchor" to "middle",
                "class" to "kuml-body",
                "font-size" to "12",
            ),
        ) { text(step.name ?: step.id) }
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
