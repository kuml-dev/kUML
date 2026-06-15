package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Person
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.TextWrap
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert eine [C4Person] als Strichmännchen über einer beschrifteten Box.
 *
 * Die Box zeigt Name (fett) und optionale Beschreibung (klein, kursiv).
 * Lange Beschreibungen werden mehrzeilig umgebrochen — die Label-Box wächst
 * entsprechend mit, in Synchronität mit dem `C4ContentSizeProvider`.
 */
internal fun renderC4Person(
    element: C4Person,
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
        // Stick figure
        tag("circle", mapOf("cx" to fmt(cx), "cy" to "10", "r" to "8", "class" to "kuml-actor"))
        tag(
            "path",
            mapOf(
                "d" to "M ${fmt(cx)} 18 L ${fmt(cx)} 38 M ${fmt(cx - 12f)} 26 L ${fmt(cx + 12f)} 26 " +
                    "M ${fmt(cx)} 38 L ${fmt(cx - 10f)} 52 M ${fmt(cx)} 38 L ${fmt(cx + 10f)} 52",
                "class" to "kuml-actor",
            ),
        )

        // Label box — wraps the description so long lines no longer overflow.
        val boxY = 58f
        val descMaxWidth = w - 8f - 2f * C4DescriptionLayout.H_PAD // rect inset (x=4, w-8) minus internal padding
        val descLines =
            element.description?.let {
                TextWrap.wrapToWidth(it, descMaxWidth, C4DescriptionLayout.DESC_CHAR_PX)
            } ?: emptyList()
        val boxH =
            if (descLines.isEmpty()) {
                28f
            } else {
                // name baseline at boxY+18, gap of 16 to first desc line baseline,
                // 13 px per additional desc line, 10 px bottom pad.
                18f + 16f + (descLines.size - 1) * C4DescriptionLayout.DESC_LINE_H + 10f
            }
        tag(
            "rect",
            mapOf(
                "x" to "4",
                "y" to fmt(boxY),
                "width" to fmt(w - 8f),
                "height" to fmt(boxH),
                "class" to "kuml-class",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(boxY + 18f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }

        if (descLines.isNotEmpty()) {
            tag(
                "text",
                mapOf(
                    "class" to "kuml-stereotype",
                    "x" to fmt(cx),
                    "y" to fmt(boxY + 34f),
                    "text-anchor" to "middle",
                ),
            ) {
                descLines.forEachIndexed { idx, line ->
                    tag(
                        "tspan",
                        mapOf(
                            "x" to fmt(cx),
                            "dy" to if (idx == 0) "0" else fmt(C4DescriptionLayout.DESC_LINE_H),
                        ),
                    ) { text(line) }
                }
            }
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
