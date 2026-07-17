package dev.kuml.io.svg.uml

import dev.kuml.io.svg.ArrowStyle
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.arrowDirection
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.io.svg.renderInlineArrow
import dev.kuml.io.svg.sourceArrowDirection
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.AggregationKind
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
import kotlin.math.abs

// ── UML Edge Renderer ─────────────────────────────────────────────────────────

/**
 * Which end of a [UmlAssociation] / [UmlLink] a role/multiplicity label
 * belongs to. Used only for the converging-label fan-out grouping computed
 * once per diagram in `KumlSvgRenderer` — rendering itself still resolves
 * source/target directly from the [EdgeRoute].
 */
internal enum class UmlEndpointSide { SOURCE, TARGET }

/**
 * Per-sibling along-edge step (px) added on top of the fixed multiplicity/
 * role margins when several association/link ends converge on the same
 * node face (`stackIndex > 0`). Mirrors
 * `ErmChenSizing.CARDINALITY_LABEL_STACK_PX` — the identical fix already
 * applied to ERM/Chen cardinality labels (fix/erm-chen-label-collisions).
 */
internal const val UML_ENDPOINT_LABEL_STACK_PX: Float = 14f

/**
 * Caps the along-edge stacking so a very dense hub doesn't fling labels far
 * from their node. One index higher than ERM/Chen's
 * `CHEN_CARDINALITY_MAX_STACK_INDEX` (`2`) because the real-world repro
 * case (four associations converging on one class, e.g. `Mitglied` in a
 * PdV-statutes diagram) needs four distinct positions (`0..3`); beyond
 * that, extra siblings share the last slot — bounded degradation rather
 * than unbounded drift.
 */
internal const val UML_ENDPOINT_LABEL_MAX_STACK_INDEX: Int = 3

/**
 * Quantizes a segment tangent to one coarse border face (N/S/E/W) so labels
 * docking on genuinely different sides of a large node are grouped into
 * separate fan-out sequences instead of being stacked together as if they
 * converged on the same spot.
 */
internal fun umlEndpointFaceBucket(tangent: Pair<Float, Float>): String {
    val (tx, ty) = tangent
    return if (abs(tx) >= abs(ty)) {
        if (tx >= 0f) "E" else "W"
    } else {
        if (ty >= 0f) "S" else "N"
    }
}

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
    sourceStackIndex: Int = 0,
    targetStackIndex: Int = 0,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
    val (arrowFrom, arrowTip) = route.arrowDirection()
    renderInlineArrow(arrowFrom, arrowTip, ArrowStyle.OPEN, theme, builder)

    // Aggregation/composition diamond at the SOURCE end (the "whole"). UML places
    // the rhombus on the aggregating classifier: SHARED → hollow, COMPOSITE →
    // filled. NONE leaves the source end undecorated.
    val diamondStyle =
        when (rel.aggregation) {
            AggregationKind.SHARED -> ArrowStyle.DIAMOND
            AggregationKind.COMPOSITE -> ArrowStyle.DIAMOND_FILLED
            AggregationKind.NONE -> null
        }
    diamondStyle?.let { style ->
        val (diaFrom, diaTip) = route.sourceArrowDirection()
        renderInlineArrow(diaFrom, diaTip, style, theme, builder)
    }

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
    //
    // V0.17 — role names are rendered alongside the multiplicity at each end.
    // Two-axis separation so the pair never collides regardless of edge
    // orientation: the multiplicity sits on the `+perp` side close to the node
    // (mulMargin); the role name on the `−perp` side a bit further along the
    // edge (roleMargin). For a vertical edge they end up diagonally apart, for a
    // horizontal edge they straddle the line at different along-edge offsets.
    //
    // Bug-fix (fix/uml-association-label-overlap): when several associations
    // from different source classes converge on the same target class close
    // together on its border, their role-name / multiplicity labels used to
    // pile on top of each other (identical bug class as the ERM/Chen
    // cardinality-label hub collision, already fixed there). [sourceStackIndex]
    // / [targetStackIndex] are 0-based sibling indices — assigned once per
    // diagram in `KumlSvgRenderer` by grouping every label-bearing end by
    // `(nodeId, borderFace)` — that add `UML_ENDPOINT_LABEL_STACK_PX` per
    // step to that end's along-edge margins, so converging siblings fan apart
    // instead of stacking. `stackIndex == 0` (the default) reproduces the
    // pre-fix geometry byte-for-byte.
    if (rel.ends.size >= 2) {
        val sourceEnd = rel.ends[0]
        val targetEnd = rel.ends[1]
        val mulMargin = 14f
        val roleMargin = 30f
        val perpOff = 10f
        val srcStep = sourceStackIndex.coerceIn(0, UML_ENDPOINT_LABEL_MAX_STACK_INDEX) * UML_ENDPOINT_LABEL_STACK_PX
        val tgtStep = targetStackIndex.coerceIn(0, UML_ENDPOINT_LABEL_MAX_STACK_INDEX) * UML_ENDPOINT_LABEL_STACK_PX

        val (stx, sty) = EdgeLabelGeometry.sourceSegmentTangent(route)
        sourceEnd.multiplicity.toLabel()?.let { label ->
            val m = mulMargin + srcStep
            endpointLabel(
                builder,
                route.source.x + stx * m - sty * perpOff,
                route.source.y + sty * m + stx * perpOff,
                label,
            )
        }
        sourceEnd.role?.let { label ->
            val m = roleMargin + srcStep
            endpointLabel(
                builder,
                route.source.x + stx * m + sty * perpOff,
                route.source.y + sty * m - stx * perpOff,
                label,
            )
        }

        val (ttx, tty) = EdgeLabelGeometry.targetSegmentTangent(route)
        targetEnd.multiplicity.toLabel()?.let { label ->
            val m = mulMargin + tgtStep
            endpointLabel(
                builder,
                route.target.x - ttx * m - tty * perpOff,
                route.target.y - tty * m + ttx * perpOff,
                label,
            )
        }
        targetEnd.role?.let { label ->
            val m = roleMargin + tgtStep
            endpointLabel(
                builder,
                route.target.x - ttx * m + tty * perpOff,
                route.target.y - tty * m - ttx * perpOff,
                label,
            )
        }
    }
}

/**
 * Renders a small endpoint label (multiplicity or role) centred at [x], [y].
 *
 * V0.23.2 — two-pass halo rendering: a `kuml-small-halo` element (background-
 * coloured stroke) is drawn first, then the visible `kuml-small` fill text.
 * This keeps role names ("orders", "items") and multiplicity labels ("0..*",
 * "1..*") readable when the association/link polyline passes directly through
 * or near the label position.
 */
private fun endpointLabel(
    builder: SvgBuilder,
    x: Float,
    y: Float,
    label: String,
) {
    val attrs = mapOf("x" to fmt(x), "y" to fmt(y), "text-anchor" to "middle")
    builder.tag("text", mapOf("class" to "kuml-small-halo") + attrs) { text(label) }
    builder.tag("text", mapOf("class" to "kuml-small") + attrs) { text(label) }
}

/**
 * Returns a multiplicity label string, or null if the multiplicity is the
 * trivial "1". Internal (not private) so `KumlSvgRenderer`'s converging-
 * label grouping can reuse the exact same "does this end draw a label?"
 * rule as the renderer, keeping stack indices dense (no gaps from ends that
 * never draw anything).
 */
internal fun Multiplicity.toLabel(): String? {
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
 * UML Comment anchor link — plain dashed line, no arrowhead, no label.
 *
 * Per the UML spec, the attachment between a [dev.kuml.uml.UmlComment] (note)
 * and the element it annotates carries no semantics beyond "this note
 * describes that element" — so unlike [renderUmlDependency] there is no
 * arrowhead and no room for a name/stereotype label.
 */
internal fun renderUmlCommentLink(
    route: EdgeRoute,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    builder.tag(tag, attrs + mapOf("class" to "kuml-edge-dashed"))
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
 * V2.0.46 — Label sitzt auf der echten Polyline-Mitte.
 * V0.23.2 — nutzt [renderEdgeLabelWithHalo] (zwei `<text>`-Elemente) statt
 * eines einzelnen `<text class="kuml-edge-label">`. Damit werden Assoziations-
 * namen ("notifies"), Abhängigkeitsnamen und UC-Stereotypen ("«include»",
 * "«extend»") lesbar, wenn der Kantenstrich genau durch die Label-Mitte läuft.
 */
private fun renderEdgeLabel(
    label: String,
    route: EdgeRoute,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
    overrideY: Float? = null,
) {
    val (mx, defaultMy) = routeLabelMid(route)
    val my = overrideY ?: defaultMy
    builder.renderEdgeLabelWithHalo(label, mx, my, "middle")
}

/**
 * UML Link — solid line, no arrowhead. Object-diagram instances of an
 * association. Optional `sourceRole` / `targetRole` labels appear near the
 * respective endpoints. If neither role is set, the link is unlabelled.
 *
 * Bug-fix (fix/uml-association-label-overlap): shares the exact single-edge
 * fixed-margin math and converging-hub overlap as [renderUmlAssociation] —
 * several links can converge on one instance box (e.g. one order instance
 * linked from multiple line-item instances). [sourceStackIndex] /
 * [targetStackIndex] apply the identical along-edge fan-out; see
 * [renderUmlAssociation]'s KDoc for the full rationale.
 */
internal fun renderUmlLink(
    rel: UmlLink,
    route: EdgeRoute,
    theme: KumlTheme,
    builder: SvgBuilder,
    sourceStackIndex: Int = 0,
    targetStackIndex: Int = 0,
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
    val srcStep = sourceStackIndex.coerceIn(0, UML_ENDPOINT_LABEL_MAX_STACK_INDEX) * UML_ENDPOINT_LABEL_STACK_PX
    val tgtStep = targetStackIndex.coerceIn(0, UML_ENDPOINT_LABEL_MAX_STACK_INDEX) * UML_ENDPOINT_LABEL_STACK_PX

    rel.sourceRoleName?.let { label ->
        val (tx, ty) = EdgeLabelGeometry.sourceSegmentTangent(route)
        val m = margin + srcStep
        endpointLabel(
            builder,
            route.source.x + tx * m - ty * perpOff,
            route.source.y + ty * m + tx * perpOff,
            label,
        )
    }

    rel.targetRoleName?.let { label ->
        val (tx, ty) = EdgeLabelGeometry.targetSegmentTangent(route)
        val m = margin + tgtStep
        endpointLabel(
            builder,
            route.target.x - tx * m - ty * perpOff,
            route.target.y - ty * m + tx * perpOff,
            label,
        )
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

private fun fmt(v: Float): String = fmt2(v)
