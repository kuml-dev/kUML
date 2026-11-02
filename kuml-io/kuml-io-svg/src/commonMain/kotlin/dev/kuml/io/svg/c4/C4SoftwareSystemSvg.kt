package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.c4TypeTag
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert ein [C4SoftwareSystem] als gerundetes Rechteck mit dickem Rahmen.
 *
 * Zeigt `[Software System]`-Header, Name (fett) und optionale Beschreibung —
 * Letztere mehrzeilig per `<tspan>`, sodass lange Texte nicht aus der Box
 * laufen. Die Box-Höhe wird vom `C4ContentSizeProvider` so dimensioniert,
 * dass alle Wrap-Zeilen Platz finden.
 */
internal fun renderC4SoftwareSystem(
    element: C4SoftwareSystem,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val r = theme.borders.cornerRadiusPx
    val ext = element.external

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmt(w),
                    "height" to fmt(h),
                    "rx" to fmt(r),
                    "ry" to fmt(r),
                    "class" to if (ext) "kuml-system-external" else "kuml-system",
                ),
        )
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to if (ext) "kuml-on-external-tag" else "kuml-stereotype",
                    "x" to fmt(w / 2f),
                    "y" to "18",
                    "text-anchor" to "middle",
                ),
        ) { text(c4TypeTag(element)) }
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to if (ext) "kuml-on-external" else "kuml-title",
                    "x" to fmt(w / 2f),
                    "y" to "36",
                    "text-anchor" to "middle",
                ),
        ) { text(element.name) }
        element.description?.let { desc ->
            renderWrappedDescription(
                builder = this,
                desc = desc,
                boxWidth = w,
                cssClass = if (ext) "kuml-on-external-small" else "kuml-small",
            )
        }
    }
}

private fun fmt(v: Float): String = fmt2(v)
