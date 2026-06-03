package dev.kuml.io.svg.uml

import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
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
 */
internal fun renderUmlAssociation(
    rel: UmlAssociation,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge", "marker-end" to "url(#arrow-open)"))
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder)
    }
}

/**
 * UML Generalization — durchgezogene Linie mit hohlem Dreieck-Pfeilkopf.
 */
internal fun renderUmlGeneralization(
    rel: UmlGeneralization,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge", "marker-end" to "url(#arrow-triangle)"))
}

/**
 * UML InterfaceRealization — gestrichelte Linie mit hohlem Dreieck-Pfeilkopf.
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
}

/**
 * UML Dependency — gestrichelte Linie mit offenem Pfeilkopf.
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
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder)
    }
}

/**
 * UML Connector — durchgezogene Linie ohne Pfeilkopf.
 */
internal fun renderUmlConnector(
    rel: UmlConnector,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
    rel.name?.let { label ->
        renderEdgeLabel(label, route, theme, builder)
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

private fun renderEdgeLabel(
    label: String,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
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

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
