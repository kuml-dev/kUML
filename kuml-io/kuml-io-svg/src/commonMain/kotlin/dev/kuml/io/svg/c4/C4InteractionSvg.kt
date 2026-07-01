package dev.kuml.io.svg.c4

import dev.kuml.c4.model.C4Interaction
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
 * Rendert eine [C4Interaction] eines [dev.kuml.c4.model.DynamicDiagram]:
 *
 * - **Request** (`response = false`) → durchgezogene Linie mit offenem Pfeilkopf.
 * - **Response** (`response = true`) → gestrichelte Linie mit gedämpftem offenen
 *   Pfeilkopf (`OPEN_MUTED`), analog zur «include»/«extend»-Konvention im UML
 *   Use-Case-Renderer.
 *
 * Das Label wird immer mit der Sequenznummer geprefixt (`"1. Submit order"`)
 * — die zeitliche Ordnung des Dynamic-Flusses muss lesbar bleiben, auch wenn
 * der Layout-Engine die Knoten beliebig anordnet. Falls eine `technology`
 * gesetzt ist, wird sie wie bei [renderC4Relationship] als `[Tech]`-Suffix
 * angehängt.
 *
 * ## Label-Platzierung — paar-bewusst (Side-Swap)
 *
 * Im Gegensatz zu einer statischen C4-Relationship treten Interactions im
 * Dynamic-Diagramm fast immer **paarweise** auf: ein Request hin (z.B. von
 * WebApp nach API) und seine Response zurück (von API nach WebApp). ELK
 * platziert die beiden Pfeile dicht nebeneinander auf parallelen Bahnen,
 * sodass das naive `midAnchor + (side)`-Placement die beiden Labels exakt
 * übereinander schiebt — "2. POST /orders" überdeckt "5. 201 Created".
 *
 * Lösung: **Side-Swap** entlang der Querachse des Hauptsegments —
 *
 *  - Vertikales Hauptsegment: Request-Label rechts der Linie
 *    (text-anchor=start), Response-Label **links** (text-anchor=end).
 *  - Horizontales Hauptsegment: Request-Label oberhalb (Baseline −4 px),
 *    Response-Label **unterhalb** (Baseline +14 px).
 *
 * Ein zusätzlicher kleiner Längs-Versatz (Request bei 45 %, Response bei
 * 55 % der Polyline-Arc-Länge) verhindert, dass exakt-mittige Routings die
 * Glyphen-Boxen seitlich überlappen, falls die Halo-Stroke zu schmal ist.
 *
 * Die Methode arbeitet rein pro-Edge — sie braucht keine Kenntnis über
 * Nachbar-Edges, bleibt also robust gegen neue Diagrammvarianten.
 */
internal fun renderC4Interaction(
    interaction: C4Interaction,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    val edgeClass = if (interaction.response) "kuml-edge kuml-edge-dashed" else "kuml-edge"
    builder.tag(tag, attrs + mapOf("class" to edgeClass))

    val (arrowFrom, arrowTip) = route.arrowDirection()
    val style = if (interaction.response) ArrowStyle.OPEN_MUTED else ArrowStyle.OPEN
    renderInlineArrow(arrowFrom, arrowTip, style, theme, builder)

    val label =
        buildString {
            append("${interaction.sequence}. ")
            append(interaction.description)
            interaction.technology?.let { tech ->
                append(" [")
                append(tech)
                append("]")
            }
        }

    // Sanfter Längs-Versatz: Request bei 45 %, Response bei 55 % der Arc-
    // Länge. Mehr Spreizung wäre kontraproduktiv — bei L-/Z-Bends würde die
    // Position in einem anderen Segment landen und die Wahl
    // horizontal-vs-vertikal flippen, was die Quer-Offsets durcheinander
    // bringt.
    val fraction = if (interaction.response) 0.55f else 0.45f
    val anchor = EdgeLabelGeometry.anchorAt(route, fraction)

    // Quer-Versatz: Side-Swap nach Response-Flag.
    val (x, y, textAnchor) =
        when (anchor.direction) {
            EdgeLabelGeometry.SegmentDirection.Horizontal ->
                if (interaction.response) {
                    // Response unter der Linie — Baseline knapp unterhalb des
                    // Glyph-Korridors. 14 px = ascent (11) + 3 px Lücke zur
                    // Linie damit Pfeilstrich und Label sich nicht berühren.
                    Triple(anchor.x, anchor.y + 14f, "middle")
                } else {
                    Triple(anchor.x, anchor.y - 4f, "middle")
                }
            EdgeLabelGeometry.SegmentDirection.Vertical ->
                if (interaction.response) {
                    // Response links der Linie, rechtsbündig — Glyphenrand
                    // sitzt damit 10 px vom Strich entfernt, analog zur
                    // Halo-Stroke-Breite.
                    Triple(anchor.x - 10f, anchor.y + 4f, "end")
                } else {
                    Triple(anchor.x + 10f, anchor.y + 4f, "start")
                }
        }
    builder.renderEdgeLabelWithHalo(label, x, y, textAnchor)
}
