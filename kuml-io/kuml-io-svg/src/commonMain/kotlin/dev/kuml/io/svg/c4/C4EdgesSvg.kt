package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Relationship
import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.layout.EdgeRoute
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
 * V2.0.46 — Label-Position via [EdgeLabelGeometry] auf die echte Polyline-Mitte
 * gehoben (statt `(source + target) / 2`, was bei orthogonalen Routes im
 * leeren L-Bogen-Raum landete) plus:
 *
 * - Horizontale Segmente → Label oberhalb der Linie (text-anchor=middle).
 * - Vertikale Segmente → Label rechts der Linie (text-anchor=start), damit
 *   bei parallelen Person→System-Kanten im C4-Landscape die Labels seitlich
 *   und nicht zentriert auf den Box-Beschreibungstexten landen.
 *
 * Beide Varianten nutzen die `kuml-edge-label`-CSS-Klasse mit weißem Halo
 * (paint-order: stroke), damit das Label auch dann lesbar bleibt, wenn es
 * teilweise über einer Linie oder einem Beschreibungstext liegt.
 *
 * @param labelYOffset Optionaler zusätzlicher y-Versatz für das Label (positiv = nach unten).
 *   Wird von [dev.kuml.io.svg.KumlSvgRenderer] gesetzt, wenn die Überlappungserkennung
 *   zwei Labels als zu nah beieinander einstuft (z.B. Customer→System + System→External
 *   durch denselben vertikalen Korridor).
 */
internal fun renderC4Relationship(
    rel: C4Relationship,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
    labelYOffset: Float = 0f,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(name = tag, attrs = attrs + mapOf("class" to "kuml-edge"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(from = arrowFrom, tip = arrowTip, style = ArrowStyle.OPEN, theme = theme, builder = builder)

    val label = c4RelationshipLabel(rel)
    if (label.isEmpty()) return

    val anchor = EdgeLabelGeometry.midAnchor(route)
    val (x, y, textAnchor) =
        when (anchor.direction) {
            EdgeLabelGeometry.SegmentDirection.Horizontal ->
                // Label sits a few px above the horizontal segment, centred.
                Triple(anchor.x, anchor.y - 4f + labelYOffset, "middle")
            EdgeLabelGeometry.SegmentDirection.Vertical ->
                // Label sits to the right of the vertical segment, left-aligned.
                // V11.x — von `+6f` auf `+10f` erhöht: gibt dem Label etwas mehr
                // Luft zur Linie, so dass selbst bei einer Halo-Stroke-Breite
                // von 4 px der Glyphenrand sauber von der Linie absteht.
                // `y + 4f` puts the baseline just below the mid-point so the text
                // bounding box straddles it evenly rather than running above.
                Triple(anchor.x + 10f, anchor.y + 4f + labelYOffset, "start")
        }
    builder.renderEdgeLabelWithHalo(label = label, x = x, y = y, textAnchor = textAnchor)
}
