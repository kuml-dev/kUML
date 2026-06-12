package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Relationship
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme

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
    if (label.isEmpty()) return

    val anchor = EdgeLabelGeometry.midAnchor(route)
    val (x, y, textAnchor) =
        when (anchor.direction) {
            EdgeLabelGeometry.SegmentDirection.Horizontal ->
                // Label sits a few px above the horizontal segment, centred.
                Triple(anchor.x, anchor.y - 4f, "middle")
            EdgeLabelGeometry.SegmentDirection.Vertical ->
                // Label sits to the right of the vertical segment, left-aligned.
                // `y + 4f` puts the baseline just below the mid-point so the text
                // bounding box straddles it evenly rather than running above.
                Triple(anchor.x + 6f, anchor.y + 4f, "start")
        }
    builder.renderEdgeLabelWithHalo(label, x, y, textAnchor)
}
