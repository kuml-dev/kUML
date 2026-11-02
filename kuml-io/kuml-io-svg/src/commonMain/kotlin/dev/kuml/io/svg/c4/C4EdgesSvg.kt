package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Relationship
import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Rect
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Baut den Label-String für eine [C4Relationship] aus [C4Relationship.label]
 * und [C4Relationship.technology]. Leere Labels ergeben einen Leerstring.
 *
 * Eigenständige Hilfsfunktion, damit [dev.kuml.io.svg.KumlSvgRenderer] den
 * Label-String vorab lesen kann — z.B. für die Label-Überlappungserkennung
 * in `computeC4LabelStaggerOffsets` — ohne die Beziehung vollständig zu rendern.
 */
internal fun c4RelationshipLabel(rel: C4Relationship): String =
    buildString {
        if (rel.label.isNotEmpty()) append(rel.label)
        rel.technology?.let { tech ->
            if (isNotEmpty()) append(" ")
            append("[$tech]")
        }
    }

/**
 * Rendert eine [C4Relationship] — durchgezogene Linie mit offenem Pfeilkopf und optionalem Label.
 *
 * With a label plan (PAL wave 2G-b, [C4EdgeLabelPlan]) the label is drawn as a group: a translucent background
 * rectangle in the canvas colour (opacity 0.9) and one `<tspan>` per wrapped line, centred in the planned
 * rectangle. Without a plan (nothing planned for this edge) the older single-line label next to the middle of
 * the line is drawn as a fallback.
 *
 * @param plannedLines wrapped label lines; `null` selects the fallback
 * @param plannedRect planned label rectangle in drawn coordinates
 */
internal fun renderC4Relationship(
    rel: C4Relationship,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
    plannedLines: List<String>? = null,
    plannedRect: Rect? = null,
    lineHeightPx: Float = 0f,
    fontSizePx: Float = 0f,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(name = tag, attrs = attrs + mapOf("class" to "kuml-edge", "data-edge-id" to xmlEscapeAttr(rel.id)))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(from = arrowFrom, tip = arrowTip, style = ArrowStyle.OPEN, theme = theme, builder = builder)

    val label = c4RelationshipLabel(rel)
    if (label.isEmpty()) return

    if (plannedLines != null && plannedRect != null && plannedLines.isNotEmpty()) {
        val r = plannedRect
        val cx = r.origin.x + r.size.width / 2f
        builder.tag(
            name = "g",
            attrs =
                mapOf(
                    "class" to "kuml-label kuml-c4-label",
                    "data-label-id" to xmlEscapeAttr(rel.id),
                    "data-label-rect" to
                        "${fmt2(r.origin.x)} ${fmt2(r.origin.y)} ${fmt2(r.size.width)} ${fmt2(r.size.height)}",
                ),
        ) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "class" to "kuml-c4-label-bg",
                        "x" to fmt2(r.origin.x),
                        "y" to fmt2(r.origin.y),
                        "width" to fmt2(r.size.width),
                        "height" to fmt2(r.size.height),
                        "rx" to "3",
                        "ry" to "3",
                        "fill" to theme.colors.background.toHex(),
                        "fill-opacity" to "0.9",
                    ),
            )
            val firstBaseline = r.origin.y + 3f + (lineHeightPx - fontSizePx) / 2f + fontSizePx * 0.8f
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-edge-label",
                        "x" to fmt2(cx),
                        "y" to fmt2(firstBaseline),
                        "text-anchor" to "middle",
                    ),
            ) {
                plannedLines.forEachIndexed { idx, line ->
                    tag(
                        name = "tspan",
                        attrs = mapOf("x" to fmt2(cx), "dy" to if (idx == 0) "0" else fmt2(lineHeightPx)),
                    ) { text(line) }
                }
            }
        }
        return
    }

    val anchor = EdgeLabelGeometry.midAnchor(route)
    val (x, y, textAnchor) =
        when (anchor.direction) {
            EdgeLabelGeometry.SegmentDirection.Horizontal -> Triple(anchor.x, anchor.y - 4f, "middle")
            EdgeLabelGeometry.SegmentDirection.Vertical -> Triple(anchor.x + 10f, anchor.y + 4f, "start")
        }
    builder.renderEdgeLabelWithHalo(label = label, x = x, y = y, textAnchor = textAnchor)
}
