@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.Point

/**
 * Bachman cardinality glyphs as TikZ primitives with the offsets of the SVG renderer (`ErmBachmanEdgeSvg`):
 * a filled arrowhead for "many" (apex on the entity border), then an open circle for "optional" or a filled
 * circle for "mandatory". Geometry is in layout coordinates (y down); [ErmTikz.pt] flips y on emission.
 */
internal object BachmanTikz {
    /** Distance from the entity border to the arrowhead base. */
    const val ARROW_LEN_PX: Float = 12f

    /** Half-spread of the arrowhead base. */
    const val ARROW_HALF_PX: Float = 5f

    /** Gap between the arrowhead base (or the entity border) and the circle marker. */
    const val MARKER_GAP_PX: Float = 6f

    /** Radius of the circle marker. */
    const val CIRCLE_RADIUS_PX: Float = 4f

    /** Appends the glyph of [cardinality] at [anchor] (a node border point) extending along [dir] (away from the node). */
    fun appendGlyph(
        anchor: Point,
        dir: Pair<Float, Float>,
        cardinality: Cardinality,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val (dx, dy) = dir
        val px = -dy
        val py = dx
        val ind = options.indent
        var innerOffset = 0f
        if (cardinality.many) {
            val bx = anchor.x + dx * ARROW_LEN_PX
            val by = anchor.y + dy * ARROW_LEN_PX
            out.appendLine(
                "$ind\\filldraw[line width=0.6pt] ${ErmTikz.pt(anchor.x, anchor.y)} -- " +
                    "${ErmTikz.pt(bx + px * ARROW_HALF_PX, by + py * ARROW_HALF_PX)} -- " +
                    "${ErmTikz.pt(bx - px * ARROW_HALF_PX, by - py * ARROW_HALF_PX)} -- cycle;",
            )
            innerOffset = ARROW_LEN_PX
        }
        val offset = innerOffset + MARKER_GAP_PX + CIRCLE_RADIUS_PX
        val centre = ErmTikz.pt(anchor.x + dx * offset, anchor.y + dy * offset)
        if (cardinality.optional) {
            out.appendLine("$ind\\draw[line width=0.6pt, fill=white] $centre circle (${fmtCoord(CIRCLE_RADIUS_PX)}pt);")
        } else {
            out.appendLine("$ind\\fill $centre circle (${fmtCoord(CIRCLE_RADIUS_PX)}pt);")
        }
    }
}
