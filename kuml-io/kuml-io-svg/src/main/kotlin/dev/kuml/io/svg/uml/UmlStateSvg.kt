package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlState

/**
 * Rendert einen [UmlState] als gerundetes Rechteck (rx=12, ry=12) mit dem Namen zentriert.
 *
 * Alle UmlState-Subtypen werden gleich behandelt (Spec-Entscheidung: Rounded Rectangle).
 */
internal fun renderUmlState(
    element: UmlState,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to "12",
                "ry" to "12",
                "class" to "kuml-state",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(w / 2f),
                "y" to fmt(h / 2f + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(v)
}
