package dev.kuml.io.svg.uml

import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
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
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge", "marker-end" to "url(#arrow-open)"))
    // Stereotype label takes precedence over association name; name is appended below it
    val mx = (route.source.x + route.target.x) / 2f
    val my = (route.source.y + route.target.y) / 2f - 4f
    val hadStereo = StereotypeHelper.renderEdgeStereotype(rel, theme, builder, mx, my)
    val labelY = if (hadStereo) my + (theme.stereotypes.headerFontSize + 3f) else my
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder, overrideY = if (hadStereo) labelY else null)
    }
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
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge", "marker-end" to "url(#arrow-triangle)"))
    val mx = (route.source.x + route.target.x) / 2f
    val my = (route.source.y + route.target.y) / 2f - 4f
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
    builder.tag(
        tag,
        attrs + mapOf("class" to "kuml-edge-dashed", "marker-end" to "url(#arrow-triangle-muted)"),
    )
    val mx = (route.source.x + route.target.x) / 2f
    val my = (route.source.y + route.target.y) / 2f - 4f
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
    builder.tag(
        tag,
        attrs + mapOf("class" to "kuml-edge-dashed", "marker-end" to "url(#arrow-open-muted)"),
    )
    val mx = (route.source.x + route.target.x) / 2f
    val my = (route.source.y + route.target.y) / 2f - 4f
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
    val mx = (route.source.x + route.target.x) / 2f
    val my = (route.source.y + route.target.y) / 2f - 4f
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
    builder.tag(
        tag,
        attrs + mapOf("class" to "kuml-edge-dashed", "marker-end" to "url(#arrow-open-muted)"),
    )
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
    builder.tag(
        tag,
        attrs + mapOf("class" to "kuml-edge-dashed", "marker-end" to "url(#arrow-open-muted)"),
    )
    renderEdgeLabel("«extend»", route, theme, builder)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Rendert ein Edge-Label am Mittelpunkt der Kante.
 *
 * [overrideY] erlaubt es dem Aufrufer, die Y-Position zu überschreiben — z.B.
 * wenn ein Stereotyp-Label bereits direkt über dem Namen gerendert wurde.
 */
private fun renderEdgeLabel(
    label: String,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
    overrideY: Float? = null,
) {
    val mx = (route.source.x + route.target.x) / 2f
    val my = overrideY ?: ((route.source.y + route.target.y) / 2f - 4f)
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

    rel.sourceRoleName?.let { label ->
        // Place the source-role label one-quarter of the way from source to target.
        val qx = route.source.x + (route.target.x - route.source.x) * 0.25f
        val qy = route.source.y + (route.target.y - route.source.y) * 0.25f - 4f
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to fmt(qx),
                "y" to fmt(qy),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(label)) }
    }

    rel.targetRoleName?.let { label ->
        // Three-quarters of the way from source to target.
        val qx = route.source.x + (route.target.x - route.source.x) * 0.75f
        val qy = route.source.y + (route.target.y - route.source.y) * 0.75f - 4f
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to fmt(qx),
                "y" to fmt(qy),
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
    builder.tag(tag, attrs + mapOf("class" to cls, "marker-end" to "url(#arrow-open)"))
    rel.guard?.let { guard ->
        renderEdgeLabel("[$guard]", route, theme, builder)
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
