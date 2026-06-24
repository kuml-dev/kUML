package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.io.svg.SvgBuilder

/**
 * Draws the three Shostack separator lines that sit *on the boundary* between
 * two adjacent layer bands (V3.1.24):
 *
 * - [BlueprintLine.INTERACTION]          — CUSTOMER_ACTIONS │ FRONTSTAGE  (solid)
 * - [BlueprintLine.VISIBILITY]           — FRONTSTAGE       │ BACKSTAGE   (dashed)
 * - [BlueprintLine.INTERNAL_INTERACTION] — BACKSTAGE        │ SUPPORT     (dotted)
 *
 * Each line is only drawn when **both** adjacent layers are visible — otherwise
 * the boundary it represents is not present in the rendered grid. The line sits
 * on the *lower* edge of the upper band (= upper edge of the lower band) and
 * carries a small right-aligned caption.
 *
 * Geometry note: the y-coordinate is the band boundary, so off-by-one-band
 * errors are avoided by deriving it from [BlueprintGeometry.bandY] of the upper
 * layer (`.endInclusive`).
 */
internal fun SvgBuilder.renderBlueprintLines(
    lines: Set<BlueprintLine>,
    geo: BlueprintGeometry,
) {
    // boundary spec: line, upper layer, lower layer, dash pattern, caption
    data class Boundary(
        val line: BlueprintLine,
        val upper: BlueprintLayer,
        val lower: BlueprintLayer,
        val dash: String?,
        val caption: String,
    )

    val boundaries =
        listOf(
            Boundary(
                BlueprintLine.INTERACTION,
                BlueprintLayer.CUSTOMER_ACTIONS,
                BlueprintLayer.FRONTSTAGE,
                null,
                "Line of Interaction",
            ),
            Boundary(
                BlueprintLine.VISIBILITY,
                BlueprintLayer.FRONTSTAGE,
                BlueprintLayer.BACKSTAGE,
                "8,4",
                "Line of Visibility",
            ),
            Boundary(
                BlueprintLine.INTERNAL_INTERACTION,
                BlueprintLayer.BACKSTAGE,
                BlueprintLayer.SUPPORT_PROCESSES,
                "2,4",
                "Line of Internal Interaction",
            ),
        )

    boundaries.forEach { bd ->
        if (bd.line !in lines) return@forEach
        // Both adjacent layers must be present for the boundary to exist.
        if (bd.upper !in geo.layers || bd.lower !in geo.layers) return@forEach

        val y = geo.bandY(bd.upper).endInclusive
        val dashAttr = bd.dash?.let { """ stroke-dasharray="$it"""" } ?: ""
        rawXml(
            """<line x1="${f(geo.contentLeft)}" y1="${f(y)}" x2="${f(geo.contentRight)}" y2="${f(y)}" """ +
                """stroke="#1d2968" stroke-width="1.5"$dashAttr/>""",
        )
        // Right-aligned caption sitting just above the boundary line.
        tag(
            "text",
            mapOf(
                "x" to f(geo.contentRight - 6),
                "y" to f(y - 4),
                "text-anchor" to "end",
                "class" to "kuml-body",
                "font-size" to "10",
                "font-style" to "italic",
                "fill" to "#1d2968",
            ),
        ) { text(bd.caption) }
    }
}
