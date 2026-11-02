package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.c4TypeTag
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
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
    val ext = element.external
    val tagShift = if (ext) C4_EXTERNAL_TAG_H else 0f

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        val cx = w / 2f
        // Stick figure
        tag(name = "circle", attrs = mapOf("cx" to fmt(cx), "cy" to "10", "r" to "8", "class" to "kuml-actor"))
        tag(
            name = "path",
            attrs =
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
                TextWrap.wrapToWidth(text = it, maxWidthPx = descMaxWidth, charPx = C4DescriptionLayout.DESC_CHAR_PX)
            } ?: emptyList()
        val boxH =
            if (descLines.isEmpty()) {
                28f + tagShift
            } else {
                // name baseline at boxY+18, gap of 16 to first desc line baseline,
                // 13 px per additional desc line, 10 px bottom pad.
                tagShift + 18f + 16f + (descLines.size - 1) * C4DescriptionLayout.DESC_LINE_H + 10f
            }
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "x" to "4",
                    "y" to fmt(boxY),
                    "width" to fmt(w - 8f),
                    "height" to fmt(boxH),
                    "class" to if (ext) "kuml-person-external" else "kuml-class",
                ),
        )
        if (ext) {
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-on-external-tag",
                        "x" to fmt(cx),
                        "y" to fmt(boxY + 14f),
                        "text-anchor" to "middle",
                    ),
            ) { text(c4TypeTag(element)) }
        }
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to if (ext) "kuml-on-external" else "kuml-title",
                    "x" to fmt(cx),
                    "y" to fmt(boxY + tagShift + 18f),
                    "text-anchor" to "middle",
                ),
        ) { text(element.name) }

        if (descLines.isNotEmpty()) {
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to if (ext) "kuml-on-external-small" else "kuml-stereotype",
                        "x" to fmt(cx),
                        "y" to fmt(boxY + tagShift + 34f),
                        "text-anchor" to "middle",
                    ),
            ) {
                descLines.forEachIndexed { idx, line ->
                    tag(
                        name = "tspan",
                        attrs =
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

/** Extra height of the label box of an external person for the `[External Person]` line (mirrored by the size provider). */
internal const val C4_EXTERNAL_TAG_H: Float = 14f

private fun fmt(v: Float): String = fmt2(v)
