package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StateRegion
import dev.kuml.sysml2.Sysml2Model

/** Height of the name header strip of a composite state frame (px). */
internal const val STM_COMPOSITE_HEADER_HEIGHT: Float = 28f

/**
 * Draws the frames of all composite states (wave A1) that the layout bridge turned into
 * compound groups: a rounded rectangle with a bold name header, optional `entry / exit / do`
 * lines under a hairline, and — for orthogonal regions — dashed separators plus italic region
 * names. Frames are painted outermost first so nested frames and their children layer on top.
 *
 * Which orientation the separators take is read off the layout result (regions stacked
 * vertically get horizontal separators, regions side by side get vertical ones), so the renderer
 * does not depend on the layout direction. Separators always span the full width/height of the
 * composite, which is what keeps the regions visually equally wide.
 *
 * All text goes through the builder's escaping; ids go through [xmlEscapeAttr].
 */
internal fun renderCompositeStateFrames(
    model: Sysml2Model,
    visibleIds: Set<String>,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    val composites =
        model.definitions
            .filterIsInstance<StateDefinition>()
            .filter { it.id in visibleIds && it.effectiveKind == StateKind.State }
            .mapNotNull { def -> layoutResult.groups[GroupId(def.id)]?.let { def to it.bounds } }
            .sortedByDescending { (_, b) -> b.size.width * b.size.height }
    for ((def, raw) in composites) {
        val b = Rect(origin = raw.origin.copy(x = raw.origin.x + paddingPx, y = raw.origin.y + paddingPx), size = raw.size)
        val x = b.origin.x
        val y = b.origin.y
        val w = b.size.width
        val h = b.size.height
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(def.id), "class" to "kuml-composite-state")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(x),
                        "y" to fmt3(y),
                        "width" to fmt3(w),
                        "height" to fmt3(h),
                        "rx" to "10",
                        "ry" to "10",
                        "class" to "kuml-class",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to fmt3(x + w / 2f),
                        "y" to fmt3(y + 18f),
                        "text-anchor" to "middle",
                        "font-size" to "13",
                        "font-weight" to "bold",
                    ),
            ) { text(def.name) }
            val actions =
                listOfNotNull(
                    def.entryAction?.takeIf { it.isNotEmpty() }?.let { "entry / $it" },
                    def.exitAction?.takeIf { it.isNotEmpty() }?.let { "exit / $it" },
                    def.doAction?.takeIf { it.isNotEmpty() }?.let { "do / $it" },
                )
            val headerBottom = y + STM_COMPOSITE_HEADER_HEIGHT
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to fmt3(x),
                        "y1" to fmt3(headerBottom),
                        "x2" to fmt3(x + w),
                        "y2" to fmt3(headerBottom),
                        "class" to "kuml-divider",
                        "stroke-width" to "1",
                    ),
            )
            actions.forEachIndexed { i, line ->
                tag(
                    name = "text",
                    attrs = mapOf("class" to "kuml-body", "x" to fmt3(x + 8f), "y" to fmt3(headerBottom + 13f + i * 13f)),
                ) { text(line) }
            }

            val regions: List<Pair<StateRegion, Rect>> =
                def.regions.mapNotNull { r ->
                    layoutResult.groups[GroupId(r.id)]?.let { g ->
                        r to
                            Rect(origin = Point(x = g.bounds.origin.x + paddingPx, y = g.bounds.origin.y + paddingPx), size = g.bounds.size)
                    }
                }
            for ((region, rect) in regions) {
                region.name?.takeIf { it.isNotEmpty() }?.let { name ->
                    tag(
                        name = "text",
                        attrs =
                            mapOf(
                                "class" to "kuml-body",
                                "x" to fmt3(rect.origin.x + 6f),
                                "y" to fmt3(rect.origin.y + 13f),
                                "font-size" to "11",
                                "font-style" to "italic",
                            ),
                    ) { text(name) }
                }
            }
            if (regions.size >= 2) {
                val rects = regions.map { it.second }
                val byY = rects.sortedBy { it.origin.y }
                val stacked = byY.zipWithNext().all { (a, c) -> a.origin.y + a.size.height <= c.origin.y + 0.5f }
                if (stacked) {
                    for ((a, c) in byY.zipWithNext()) {
                        val sy = ((a.origin.y + a.size.height) + c.origin.y) / 2f
                        separator(x1 = x, y1 = sy, x2 = x + w, y2 = sy)
                    }
                } else {
                    for ((a, c) in rects.sortedBy { it.origin.x }.zipWithNext()) {
                        val sx = ((a.origin.x + a.size.width) + c.origin.x) / 2f
                        separator(x1 = sx, y1 = headerBottom, x2 = sx, y2 = y + h)
                    }
                }
            }
        }
    }
}

private fun SvgBuilder.separator(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
) {
    tag(
        name = "line",
        attrs =
            mapOf(
                "x1" to fmt3(x1),
                "y1" to fmt3(y1),
                "x2" to fmt3(x2),
                "y2" to fmt3(y2),
                "class" to "kuml-divider",
                "stroke-dasharray" to "6 4",
                "stroke-width" to "1",
            ),
    )
}
