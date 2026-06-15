package dev.kuml.io.svg.uml

import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink

// ── UML Edge Renderer ─────────────────────────────────────────────────────────

/**
 * UML Association — durchgezogene Linie mit offenem Pfeilkopf.
 *
 * In V1.1: Wenn [UmlAssociation.appliedStereotypes] gesetzt sind, wird ein
 * `«stereotype»`-Label am Mittelpunkt der Kante gerendert.
 */
internal fun renderUmlAssociation(
    rel: UmlAssociation,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN, theme, builder)
    // Stereotype label takes precedence over association name; name is appended below it
    val (mx, my) = routeLabelMid(route)
    val hadStereo = StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
    val labelY = if (hadStereo) my + (theme.stereotypes.headerFontSize + 3f) else my
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder, overrideY = if (hadStereo) labelY else null)
    }

    // V2.0.44 / V3.0.11 — Multiplicity labels on source and target ends (UML 2.x).
    // Only rendered when the multiplicity is non-trivial (i.e. not exactly "1").
    //
    // V3.0.11 fix: previously both labels used the straight `source→target`
    // tangent, which for orthogonal-rounded routes with a vertical final
    // segment placed the target-end label *below* the arrowhead and inside the
    // target node (Order → OrderItem composition, `1..*` overlapping the
    // OrderItem border). We now use the **first segment tangent** for the
    // source label and the **last segment tangent** for the target label, so
    // labels always offset along their actual edge tail.
    //
    // 16 px along-edge margin keeps the label clear of the arrowhead / node
    // border; 10 px perpendicular offset keeps it off the line itself.
    if (rel.ends.size >= 2) {
        val sourceEnd = rel.ends[0]
        val targetEnd = rel.ends[1]
        val srcLabel = sourceEnd.multiplicity.toLabel()
        val tgtLabel = targetEnd.multiplicity.toLabel()
        val margin = 16f
        val perpOff = 10f
        if (srcLabel != null) {
            val (tx, ty) = EdgeLabelGeometry.sourceSegmentTangent(route)
            val lx = route.source.x + tx * margin - ty * perpOff
            val ly = route.source.y + ty * margin + tx * perpOff
            builder.tag(
                "text",
                mapOf("class" to "kuml-small", "x" to fmt(lx), "y" to fmt(ly), "text-anchor" to "middle"),
            ) { text(srcLabel) }
        }
        if (tgtLabel != null) {
            val (tx, ty) = EdgeLabelGeometry.targetSegmentTangent(route)
            val lx = route.target.x - tx * margin - ty * perpOff
            val ly = route.target.y - ty * margin + tx * perpOff
            builder.tag(
                "text",
                mapOf("class" to "kuml-small", "x" to fmt(lx), "y" to fmt(ly), "text-anchor" to "middle"),
            ) { text(tgtLabel) }
        }
    }
}

/** Returns a multiplicity label string, or null if the multiplicity is the trivial "1". */
private fun Multiplicity.toLabel(): String? {
    val upper = if (this.upper == null) "*" else this.upper.toString()
    val label = if (this.lower == this.upper) upper else "${this.lower}..$upper"
    return if (label == "1") null else label
}

/**
 * UML Generalization — durchgezogene Linie mit hohlem Dreieck-Pfeilkopf.
 *
 * In V1.1: Wenn [UmlGeneralization.appliedStereotypes] gesetzt sind, wird ein
 * `«stereotype»`-Label am Mittelpunkt gerendert.
 */
internal fun renderUmlGeneralization(
    rel: UmlGeneralization,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.TRIANGLE, theme, builder)
    val (mx, my) = routeLabelMid(route)
    StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
}

/**
 * UML InterfaceRealization — gestrichelte Linie mit hohlem Dreieck-Pfeilkopf.
 *
 * In V1.1: Wenn [UmlInterfaceRealization.appliedStereotypes] gesetzt sind, wird ein
 * `«stereotype»`-Label am Mittelpunkt gerendert.
 */
internal fun renderUmlInterfaceRealization(
    rel: UmlInterfaceRealization,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge-dashed"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.TRIANGLE_MUTED, theme, builder)
    val (mx, my) = routeLabelMid(route)
    StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
}

/**
 * UML Dependency — gestrichelte Linie mit offenem Pfeilkopf.
 *
 * In V1.1: Wenn [UmlDependency.appliedStereotypes] gesetzt sind, wird ein
 * `«stereotype»`-Label am Mittelpunkt gerendert (über dem Namen, falls vorhanden).
 */
internal fun renderUmlDependency(
    rel: UmlDependency,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge-dashed"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN_MUTED, theme, builder)
    val (mx, my) = routeLabelMid(route)
    val hadStereo = StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
    val labelY = if (hadStereo) my + (theme.stereotypes.headerFontSize + 3f) else null
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder, overrideY = labelY)
    }
}

/**
 * UML Connector — durchgezogene Linie ohne Pfeilkopf.
 *
 * In V1.1: Wenn [UmlConnector.appliedStereotypes] gesetzt sind, wird ein
 * `«stereotype»`-Label am Mittelpunkt gerendert.
 */
internal fun renderUmlConnector(
    rel: UmlConnector,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
    val (mx, my) = routeLabelMid(route)
    val hadStereo = StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
    val labelY = if (hadStereo) my + (theme.stereotypes.headerFontSize + 3f) else null
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder, overrideY = labelY)
    }
}

/**
 * UML Include — gestrichelte Linie mit offenem Pfeilkopf + festes `«include»`-Label.
 */
internal fun renderUmlInclude(
    rel: UmlInclude,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge-dashed"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN_MUTED, theme, builder)
    renderEdgeLabel("«include»", route, theme, builder)
}

/**
 * UML Extend — gestrichelte Linie mit offenem Pfeilkopf + festes `«extend»`-Label.
 */
internal fun renderUmlExtend(
    rel: UmlExtend,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge-dashed"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN_MUTED, theme, builder)
    renderEdgeLabel("«extend»", route, theme, builder)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Returns the polyline midpoint of [route] — `x` and `y - 4 px` (the standard
 * "above the line" offset used for UML edge labels).
 *
 * V2.0.46 — replaces the previous `(source + target) / 2` shortcut, which
 * ignored waypoints on `OrthogonalRounded` / `TreeRounded` routes and dropped
 * labels in empty L-bend whitespace.
 */
internal fun routeLabelMid(route: EdgeRoute): Pair<Float, Float> {
    val a = EdgeLabelGeometry.midAnchor(route)
    return a.x to (a.y - 4f)
}

/**
 * Rendert ein Edge-Label am Mittelpunkt der Kante.
 *
 * [overrideY] erlaubt es dem Aufrufer, die Y-Position zu überschreiben — z.B.
 * wenn ein Stereotyp-Label bereits direkt über dem Namen gerendert wurde.
 *
 * V2.0.46 — Label nutzt jetzt die `kuml-edge-label`-Klasse (mit Halo) und
 * sitzt auf der echten Polyline-Mitte.
 */
private fun renderEdgeLabel(
    label: String,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
    overrideY: Float? = null,
) {
    val (mx, defaultMy) = routeLabelMid(route)
    val my = overrideY ?: defaultMy
    builder.tag(
        "text",
        mapOf(
            "class" to "kuml-edge-label",
            "x" to fmt(mx),
            "y" to fmt(my),
            "text-anchor" to "middle",
        ),
    ) {
        text(xmlEscapeText(label))
    }
}

/**
 * UML Link — solid line, no arrowhead. Object-diagram instances of an
 * association. Optional `sourceRole` / `targetRole` labels appear near the
 * respective endpoints. If neither role is set, the link is unlabelled.
 */
internal fun renderUmlLink(
    rel: UmlLink,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))

    // Role-Labels werden an die jeweilige Endpoint angesetzt — dieselbe Konvention
    // wie für Multiplizitäts-Labels in [renderUmlAssociation] (V3.0.11): 16 px
    // entlang des ersten/letzten Segments, 10 px perpendikulär. Das hält das
    // Label nah am Klassifikator, dessen Rolle es benennt — die alte 25%/75%-
    // Linearinterpolation legte beide Labels in die Mitte der Edge, sodass z.B.
    // `targetRole = "purchase"` auf einem 60-px-Link erkennbar weit von der
    // Zielinstanz weg saß.
    val margin = 16f
    val perpOff = 10f

    rel.sourceRoleName?.let { label ->
        val (tx, ty) = EdgeLabelGeometry.sourceSegmentTangent(route)
        val lx = route.source.x + tx * margin - ty * perpOff
        val ly = route.source.y + ty * margin + tx * perpOff
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to fmt(lx),
                "y" to fmt(ly),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(label)) }
    }

    rel.targetRoleName?.let { label ->
        val (tx, ty) = EdgeLabelGeometry.targetSegmentTangent(route)
        val lx = route.target.x - tx * margin - ty * perpOff
        val ly = route.target.y - ty * margin + tx * perpOff
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to fmt(lx),
                "y" to fmt(ly),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(label)) }
    }
}

/**
 * UML Activity edge — solid line with optional `[guard]` label (V1.1).
 * Dashed for object flow.
 */
internal fun renderUmlActivityEdge(
    rel: UmlActivityEdge,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    val cls = if (rel.isObjectFlow) "kuml-edge kuml-edge-dashed" else "kuml-edge"
    builder.tag(tag, attrs + mapOf("class" to cls))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN, theme, builder)
    rel.guard?.let { guard ->
        renderEdgeLabel("[$guard]", route, theme, builder)
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
