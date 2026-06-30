package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlActor

/**
 * Rendert einen [UmlActor] als Strichmännchen mit Name darunter.
 */
internal fun renderUmlActor(
    element: UmlActor,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        val cx = w / 2f
        // Head
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to "10",
                "r" to "8",
                "class" to "kuml-actor",
            ),
        )
        // Body + arms + legs
        tag(
            "path",
            mapOf(
                "d" to "M ${fmt(cx)} 18 L ${fmt(cx)} 38 M ${fmt(cx - 12f)} 26 L ${fmt(cx + 12f)} 26 " +
                    "M ${fmt(cx)} 38 L ${fmt(cx - 10f)} 52 M ${fmt(cx)} 38 L ${fmt(cx + 10f)} 52",
                "class" to "kuml-actor",
            ),
        )
        // Name
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(cx),
                "y" to "65",
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
