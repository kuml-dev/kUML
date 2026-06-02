package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlClass

/**
 * Rendert eine [UmlClass] als drei-Sektions-Rechteck:
 * - Header: optionaler Stereotyp + Klassenname (fett)
 * - Mitte: Attribute
 * - Unten: Operationen
 */
internal fun renderUmlClass(
    element: UmlClass,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val bo = theme.borders

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 18f
        if (element.isAbstract) {
            tag(
                "text",
                mapOf(
                    "class" to "kuml-stereotype",
                    "x" to fmt(w / 2f),
                    "y" to fmt(cy),
                    "text-anchor" to "middle",
                ),
            ) { text("«abstract»") }
            cy += 14f
        }

        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }
        cy += 6f

        if (element.attributes.isNotEmpty() || element.operations.isNotEmpty()) {
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 14f
        }

        for (attr in element.attributes) {
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
            ) { text(xmlEscapeText(attr.format())) }
            cy += 13f
        }

        if (element.attributes.isNotEmpty() && element.operations.isNotEmpty()) {
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 14f
        }

        for (op in element.operations) {
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(bo.thinPx + 4f), "y" to fmt(cy)),
            ) { text(xmlEscapeText(op.format())) }
            cy += 13f
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
