package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Title bands of IBD part boxes (Nacharbeit 43). A part box draws its stereotype line and its name at the top, over the
 * whole width. Port labels and connector labels that fall back to the inside of the box must not cover that text, so
 * the band counts as a hard obstacle of the label plan (the same kind as a composite title). Without it a label inside
 * a box without nested frames was drawn through the box title.
 *
 * The band starts at the top edge of the box and spans its full width. Its height is the drawn header
 * (`STEREOTYPE_LINE_H` for the stereotype, `NAME_LINE_H` for the name, the same constants the box sizing uses) plus
 * [TITLE_BAND_MARGIN_PX]. The result is in layout coordinates, ordered by node id, so it is deterministic.
 *
 * @param nodeIds the part boxes that carry a title (owner ports and other nodes are left out)
 * @param hasStereotype whether the box draws a stereotype line above its name
 */
internal fun partTitleBands(
    layout: LayoutResult,
    nodeIds: Collection<NodeId>,
    hasStereotype: (NodeId) -> Boolean,
): List<Rect> {
    val out = ArrayList<Rect>(nodeIds.size)
    for (id in nodeIds.sortedBy { it.value }) {
        val b = layout.nodes[id]?.bounds ?: continue
        val header = (if (hasStereotype(id)) Sysml2LayoutBridge.STEREOTYPE_LINE_H else 0f) + Sysml2LayoutBridge.NAME_LINE_H
        val h = minOf(header + TITLE_BAND_MARGIN_PX, b.size.height)
        out += Rect(origin = Point(x = b.origin.x, y = b.origin.y), size = Size(width = b.size.width, height = h))
    }
    return out
}

/** Free space below the drawn header text that still counts as title (px). */
internal const val TITLE_BAND_MARGIN_PX: Float = 4f
