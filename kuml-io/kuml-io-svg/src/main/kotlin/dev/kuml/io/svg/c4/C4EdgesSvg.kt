package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Relationship
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert eine [C4Relationship] — durchgezogene Linie mit offenem Pfeilkopf und optionalem Label.
 */
internal fun renderC4Relationship(
    rel: C4Relationship,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge", "marker-end" to "url(#arrow-open)"))

    val label =
        buildString {
            if (rel.label.isNotEmpty()) append(rel.label)
            rel.technology?.let { tech ->
                if (isNotEmpty()) append(" ")
                append("[$tech]")
            }
        }
    if (label.isNotEmpty()) {
        val mx = (route.source.x + route.target.x) / 2f
        val my = (route.source.y + route.target.y) / 2f - 4f
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to fmt(mx),
                "y" to fmt(my),
                "text-anchor" to "middle",
            ),
        ) {
            text(xmlEscapeText(label))
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(v)
}
