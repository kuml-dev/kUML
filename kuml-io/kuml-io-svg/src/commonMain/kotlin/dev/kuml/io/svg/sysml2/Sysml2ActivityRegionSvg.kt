package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.Rect
import dev.kuml.sysml2.ACT_MAX_REGION_DEPTH
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.InterruptibleRegionDefinition

/** Padding of an interruptible-region frame around its members (px). */
internal const val ACT_REGION_PADDING: Float = 12f

/** Extra top padding when the region name is shown inside the frame (px). */
internal const val ACT_REGION_TAB_TOP: Float = 28f

/** Dash pattern of a region frame. */
internal const val ACT_REGION_DASH: String = "4 3"

/** A region together with its computed frame rectangle (absolute, padding already applied). */
internal data class ActRegionFrame(
    val region: InterruptibleRegionDefinition,
    val rect: Rect,
)

private fun InterruptibleRegionDefinition.labelVisible(): Boolean = showLabel && name.isNotBlank()

/**
 * Computes the frame of every non-empty [regions] entry as the bounding box of its member nodes
 * ([nodeBounds], keyed by node id) and of its nested regions, plus [ACT_REGION_PADDING] (and
 * [ACT_REGION_TAB_TOP] at the top when the name is shown). The layout engine is not involved, so
 * regions never influence node placement; a node of another region that happens to lie inside the
 * box is not claimed by it.
 *
 * Bounded: nesting deeper than [ACT_MAX_REGION_DEPTH] and parent cycles are ignored (the
 * affected region is framed without its ancestors) so rendering never loops. The result is
 * ordered outermost first.
 */
internal fun computeRegionFrames(
    regions: List<InterruptibleRegionDefinition>,
    actions: List<ActionDefinition>,
    nodeBounds: Map<String, Rect>,
): List<ActRegionFrame> {
    // Wave 2F: the geometry lives in the bridge so the layout pass and this renderer share one function.
    val byId = regions.associateBy { it.id }
    val members = actions.filter { it.interruptibleRegionId != null }.associate { it.id to it.interruptibleRegionId!! }
    return dev.kuml.layout.bridge.ActRegionFrames
        .compute(regions = regions, members = members, nodeBounds = nodeBounds)
        .mapNotNull { f -> byId[f.regionId]?.let { ActRegionFrame(region = it, rect = f.rect) } }
}

/** Draws the dashed rounded region frames (theme edge colour, no fill), outermost first. */
internal fun renderRegionFrames(
    frames: List<ActRegionFrame>,
    builder: SvgBuilder,
) {
    for (f in frames) {
        val r = f.rect
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr("region:${f.region.id}"), "class" to "kuml-act-region")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(r.origin.x),
                        "y" to fmt3(r.origin.y),
                        "width" to fmt3(r.size.width),
                        "height" to fmt3(r.size.height),
                        "rx" to "8",
                        "ry" to "8",
                        "class" to "kuml-edge",
                        "stroke-width" to "1",
                        "stroke-dasharray" to ACT_REGION_DASH,
                    ),
            )
        }
    }
}

/**
 * Draws the region names (italic 11 px, inside the frame at its top left) on an opaque backing
 * rectangle; called after the edges so no edge covers a name.
 */
internal fun renderRegionLabels(
    frames: List<ActRegionFrame>,
    builder: SvgBuilder,
) {
    for (f in frames) {
        if (!f.region.labelVisible()) continue
        val r = f.rect
        val maxChars = ((r.size.width - 20f) / 6.2f).toInt().coerceAtLeast(3)
        val name = if (f.region.name.length <= maxChars) f.region.name else f.region.name.take(maxChars - 1) + "…"
        val backW = minOf(r.size.width - 8f, name.length * 6.2f + 12f)
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr("regionlabel:${f.region.id}"), "class" to "kuml-act-region-label")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(r.origin.x + 4f),
                        "y" to fmt3(r.origin.y + 3f),
                        "width" to fmt3(backW),
                        "height" to fmt3(16f),
                        "class" to "kuml-class",
                        "style" to "stroke:none",
                        "fill-opacity" to "1",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt3(r.origin.x + 8f),
                        "y" to fmt3(r.origin.y + 15f),
                        "text-anchor" to "start",
                        "font-style" to "italic",
                        "font-size" to "11",
                    ),
            ) { text(name) }
        }
    }
}
