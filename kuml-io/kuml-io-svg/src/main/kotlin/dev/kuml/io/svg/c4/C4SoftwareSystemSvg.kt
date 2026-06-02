package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert ein [C4SoftwareSystem] als gerundetes Rechteck mit dickem Rahmen.
 *
 * Zeigt `[Software System]`-Header, Name (fett) und optionale Beschreibung.
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

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to fmt(r),
                "ry" to fmt(r),
                "class" to "kuml-system",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to "18",
                "text-anchor" to "middle",
            ),
        ) { text("[Software System]") }
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to "36",
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }
        element.description?.let { desc ->
            tag(
                "text",
                mapOf(
                    "class" to "kuml-small",
                    "x" to fmt(w / 2f),
                    "y" to "52",
                    "text-anchor" to "middle",
                ),
            ) { text(xmlEscapeText(desc)) }
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
