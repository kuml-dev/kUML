package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlUseCase

/**
 * Rendert einen [UmlUseCase] als Ellipse mit dem Namen zentriert darin.
 */
internal fun renderUmlUseCase(
    element: UmlUseCase,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val rx = w / 2f
    val ry = h / 2f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "ellipse",
            mapOf(
                "cx" to fmt(rx),
                "cy" to fmt(ry),
                "rx" to fmt(rx),
                "ry" to fmt(ry),
                "class" to "kuml-usecase",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(rx),
                "y" to fmt(ry + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
