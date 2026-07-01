package dev.kuml.io.svg.blueprint.edge

import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.blueprint.f

/**
 * Draws a [StepConnection] as a straight line between two cell centres, with an
 * arrowhead. SOLID for same-stage flow, DASHED for cross-layer support flow.
 *
 * The arrowhead marker (`id="bp-arrow"`) must be declared once in the SVG root
 * `<defs>` section — see [renderBlueprintJourney] in BlueprintGridSvg.kt.
 * It is intentionally NOT emitted here to avoid duplicate `<defs>` blocks and
 * duplicate `id` declarations when multiple connections exist.
 *
 * V3.1.24
 */
internal fun SvgBuilder.renderConnection(
    from: Pair<Double, Double>,
    to: Pair<Double, Double>,
    style: ConnectionStyle,
) {
    val (x1, y1) = from
    val (x2, y2) = to
    val dash = if (style == ConnectionStyle.DASHED) """ stroke-dasharray="6,4"""" else ""
    rawXml(
        """<line x1="${f(x1)}" y1="${f(y1)}" x2="${f(x2)}" y2="${f(y2)}" """ +
            """stroke="#555" stroke-width="1.5"$dash marker-end="url(#bp-arrow)"/>""",
    )
}
