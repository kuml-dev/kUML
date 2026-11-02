package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.PkgCardElement
import dev.kuml.layout.bridge.PkgCardKind
import dev.kuml.layout.bridge.Sysml2PackageLayout

/** Characters per px of the 11 px bold tab / 13 px card title (same estimate the layout bridge sizes with). */
private const val TAB_CHAR_PX: Float = 7.8f
private const val TAB_H: Float = 20f
private const val CARD_LINE_H: Float = 14f

/**
 * Draws the frames of a package diagram (wave 2I-a): 1 px rectangle, **no fill** (the frame must not hide the arrows that
 * run through it), outermost first. The `style` attribute is deliberate: a CSS rule of the theme (`.kuml-class`) would
 * override a `fill` attribute.
 */
internal fun renderPackageFrames(
    plan: Sysml2PackageLayout.PkgPlan,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    for ((frame, b) in framesWithBounds(plan = plan, layoutResult = layoutResult, paddingPx = paddingPx)) {
        builder.tag(name = "g", attrs = mapOf("id" to frame.id, "class" to "kuml-package-frame")) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(b.origin.x),
                        "y" to fmt3(b.origin.y),
                        "width" to fmt3(b.size.width),
                        "height" to fmt3(b.size.height),
                        "class" to "kuml-class",
                        "stroke-width" to "1",
                        "style" to "fill:none",
                    ),
            )
        }
    }
}

/**
 * Draws the name tabs and the `import X::*` text lines of the package frames after all edges (opaque tab, so no arrow
 * runs across a name). The tab slides along the top band until no edge crosses it.
 */
internal fun renderPackageTabs(
    plan: Sysml2PackageLayout.PkgPlan,
    layoutResult: LayoutResult,
    paddingPx: Float,
    builder: SvgBuilder,
    edges: List<List<Point>> = emptyList(),
) {
    for ((frame, b) in framesWithBounds(plan = plan, layoutResult = layoutResult, paddingPx = paddingPx)) {
        val maxChars = ((b.size.width - 16f) / TAB_CHAR_PX).toInt().coerceAtLeast(3)
        val name = clip(s = frame.pkg.name, max = maxChars)
        val tabW = minOf(b.size.width, name.length * TAB_CHAR_PX + 16f)
        val placement = placeFrameLabel(frame = b, labelW = tabW, bandH = TAB_H, edges = edges)
        val attrs =
            buildMap {
                put("id", "tab:${frame.id}")
                put("class", "kuml-package-tab")
                if (placement.fallbackOccluded) put("data-kuml-fallback", "occluded")
            }
        builder.tag(name = "g", attrs = attrs) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(placement.x),
                        "y" to fmt3(b.origin.y),
                        "width" to fmt3(tabW),
                        "height" to fmt3(TAB_H),
                        "class" to "kuml-class",
                        "stroke-width" to "1",
                        "fill-opacity" to "1",
                    ),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to fmt3(placement.x + 8f),
                        "y" to fmt3(b.origin.y + 14f),
                        "text-anchor" to "start",
                        "font-size" to "11",
                        "font-weight" to "bold",
                    ),
            ) { text(name) }
            frame.textLines.forEachIndexed { i, line ->
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body kuml-package-import-line",
                            "x" to fmt3(b.origin.x + 8f),
                            "y" to fmt3(b.origin.y + TAB_H + 4f + 11f + CARD_LINE_H * i),
                            "font-size" to "11",
                            "font-style" to "italic",
                        ),
                ) { text(clip(s = line, max = ((b.size.width - 16f) / 6.6f).toInt().coerceAtLeast(8))) }
            }
        }
    }
}

private fun framesWithBounds(
    plan: Sysml2PackageLayout.PkgPlan,
    layoutResult: LayoutResult,
    paddingPx: Float,
): List<Pair<Sysml2PackageLayout.FramePlan, Rect>> =
    plan.frames
        .mapNotNull { f ->
            layoutResult.groups[GroupId(f.id)]?.let { g ->
                f to Rect(origin = Point(x = g.bounds.origin.x + paddingPx, y = g.bounds.origin.y + paddingPx), size = g.bounds.size)
            }
        }.sortedByDescending { (_, b) -> b.size.width * b.size.height }

/** Draws a definition, view, viewpoint or collapsed-package card. */
internal fun renderPackageCard(
    element: PkgCardElement,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    builder.tag(
        name = "g",
        attrs = mapOf("id" to element.id, "transform" to "translate(${fmt3(x)},${fmt3(y)})", "class" to "kuml-package-card"),
    ) {
        if (element.kind == PkgCardKind.CollapsedPackage) {
            val tabW = minOf(w, element.title.length * TAB_CHAR_PX + 16f)
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to "0",
                        "y" to fmt3(TAB_H),
                        "width" to fmt3(w),
                        "height" to fmt3(h - TAB_H),
                        "class" to "kuml-class",
                    ),
            )
            tag(
                name = "rect",
                attrs = mapOf("x" to "0", "y" to "0", "width" to fmt3(tabW), "height" to fmt3(TAB_H), "class" to "kuml-class"),
            )
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-title",
                        "x" to "8",
                        "y" to "14",
                        "text-anchor" to "start",
                        "font-size" to "11",
                        "font-weight" to "bold",
                    ),
            ) { text(element.title) }
            var baseline = TAB_H + 16f
            for (section in element.sections) {
                for (line in section.lines) {
                    bodyLine(text = line.text, italic = line.italic, x = 8f, y = baseline)
                    baseline += CARD_LINE_H
                }
            }
            return@tag
        }
        tag(name = "rect", attrs = mapOf("width" to fmt3(w), "height" to fmt3(h), "class" to "kuml-class"))
        tag(
            name = "text",
            attrs = mapOf("class" to "kuml-stereotype", "x" to fmt3(w / 2f), "y" to "16", "text-anchor" to "middle", "font-size" to "11"),
        ) { text(element.keyword) }
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-title",
                    "x" to fmt3(w / 2f),
                    "y" to "33",
                    "text-anchor" to "middle",
                    "font-size" to "13",
                    "font-weight" to "bold",
                ),
        ) { text(element.title) }
        var cursor = 44f
        for (section in element.sections) {
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to "0",
                        "y1" to fmt3(cursor),
                        "x2" to fmt3(w),
                        "y2" to fmt3(cursor),
                        "class" to "kuml-divider",
                    ),
            )
            var baseline = cursor + 6f + 11f
            section.heading?.let { heading ->
                bodyLine(text = heading, italic = true, x = 8f, y = baseline, weight = "bold")
                baseline += CARD_LINE_H
            }
            for (line in section.lines) {
                bodyLine(text = line.text, italic = line.italic, x = 16f, y = baseline)
                baseline += CARD_LINE_H
            }
            cursor += 6f + CARD_LINE_H * (1 + section.lines.size)
        }
    }
}

private fun SvgBuilder.bodyLine(
    text: String,
    italic: Boolean,
    x: Float,
    y: Float,
    weight: String? = null,
) {
    val attrs =
        buildMap {
            put("class", "kuml-body")
            put("x", fmt3(x))
            put("y", fmt3(y))
            put("font-size", "11")
            if (italic) put("font-style", "italic")
            if (weight != null) put("font-weight", weight)
        }
    tag(name = "text", attrs = attrs) { text(text) }
}

private fun clip(
    s: String,
    max: Int,
): String {
    val m = max.coerceAtLeast(4)
    return if (s.length <= m) s else s.substring(0, m - 1) + "…"
}
