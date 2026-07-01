package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Container
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert einen [C4Container] als gerundetes Rechteck mit dünnem Rahmen.
 *
 * Zeigt `[Container: <technology>]`-Header, Name (fett) und optionale,
 * mehrzeilig umbrechende Beschreibung.
 */
internal fun renderC4Container(
    element: C4Container,
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
                "class" to "kuml-container",
            ),
        )
        val tech = element.technology?.let { " $it" } ?: ""
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to "18",
                "text-anchor" to "middle",
            ),
        ) { text("[Container:$tech]") }
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to "36",
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
        element.description?.let { desc ->
            renderWrappedDescription(this, desc, w)
        }
    }
}

private fun fmt(v: Float): String = fmt2(v)
