package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlState

/**
 * Rendert einen [UmlState] als gerundetes Rechteck (rx=12, ry=12).
 *
 * Zwei Varianten:
 * - **Einfacher State** ([UmlState.substates] leer): Name zentriert in der Box (bisheriges Verhalten).
 * - **Composite State** ([UmlState.substates] nicht leer): Name oben linksbündig bei y=18,
 *   darunter eine horizontale Trennlinie (`kuml-divider`) bei y=28, die die Namenszeile
 *   vom Substate-Bereich (der durch ELK-Layout befüllt wird) abgrenzt.
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
        if (element.substates.isNotEmpty()) {
            // Composite state: name at top, horizontal divider below
            tag(
                "text",
                mapOf(
                    "class" to "kuml-body",
                    "x" to fmt(w / 2f),
                    "y" to "18",
                    "text-anchor" to "middle",
                ),
            ) { text(element.name) }
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to "28",
                    "x2" to fmt(w),
                    "y2" to "28",
                    "class" to "kuml-divider",
                ),
            )
        } else {
            // Simple state: name centered
            tag(
                "text",
                mapOf(
                    "class" to "kuml-body",
                    "x" to fmt(w / 2f),
                    "y" to fmt(h / 2f + 4f),
                    "text-anchor" to "middle",
                ),
            ) { text(element.name) }
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
