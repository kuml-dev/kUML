package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlFinalState

/**
 * Renders a [UmlFinalState] as a UML final state symbol:
 * outer circle (hollow) with inner filled circle (bull's-eye / donut).
 */
internal fun renderUmlFinalState(
    element: UmlFinalState,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = x + w / 2f
    val cy = y + h / 2f
    val outerR = minOf(w, h) / 2f * 0.9f
    val innerR = outerR * 0.5f

    builder.tag("g", mapOf("id" to xmlEscapeAttr(element.id))) {
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "r" to fmt(outerR),
                "class" to "kuml-class",
                "fill" to "white",
            ),
        )
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "r" to fmt(innerR),
                "class" to "kuml-class",
                "fill" to "currentColor",
            ),
        )
    }
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        "%.2f".format(java.util.Locale.ROOT, v)
    }
