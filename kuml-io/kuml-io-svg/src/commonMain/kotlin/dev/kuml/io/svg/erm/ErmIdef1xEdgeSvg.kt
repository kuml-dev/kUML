package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Renders an [ErmRelationship] as an IDEF1X-notation edge (V3.4.5):
 *
 * - **Line style**: solid for [RelationshipKind.IDENTIFYING], dashed for
 *   [RelationshipKind.NON_IDENTIFYING] — identical convention to Martin/
 *   Bachman.
 * - **Child-end marker** (`route.target`, the dependent/child entity by this
 *   wave's `targetEntityId` = dependent-entity convention): a small filled
 *   dot touching the entity border.
 * - **Parent-end marker** (`route.source`): a small open diamond when the
 *   parent end is optional (`sourceCardinality.optional`) — the classic
 *   IDEF1X "the child may or may not have this parent row" glyph. No marker
 *   when the parent end is mandatory.
 * - **Cardinality annotation**: a short text label (`P`, `Z`, or `1`) placed
 *   just past the child dot, following the IDEF1X convention: blank = "zero,
 *   one, or many" (the default, no label needed), `P` = "one or more"
 *   (`(1,N)`), `Z` = "zero or one" (`(0,1)`), `1` = "exactly one" (`(1,1)`).
 * - **Label**: the relationship's verb phrase (`rel.name`), centered on the
 *   longest polyline segment, offset downward by [labelStackIndex] steps —
 *   mirrors [dev.kuml.io.svg.erm.renderErmMartinRelationship]'s stacking
 *   convention.
 * - **Role labels**: `sourceRole` / `targetRole`, drawn small near the
 *   respective end, when present.
 */
internal fun renderErmIdef1xRelationship(
    rel: ErmRelationship,
    route: EdgeRoute,
    theme: KumlTheme,
    b: SvgBuilder,
    labelStackIndex: Int = 0,
) {
    val (tagName, attrs) = EdgePathBuilder.build(route)
    val dashed = rel.kind == RelationshipKind.NON_IDENTIFYING
    val cssClass = if (dashed) "kuml-edge-dashed" else "kuml-edge"
    b.tag(tagName, attrs + mapOf("class" to cssClass))

    // Tangent pointing FROM the source node INTO the edge (away from the box).
    val sourceDir = EdgeLabelGeometry.sourceSegmentTangent(route)
    // Tangent pointing FROM the second-to-last waypoint TOWARD the target —
    // i.e. INTO the target node. Negate to get the direction pointing AWAY
    // from the target, back along the edge (what the glyph needs).
    val targetIntoNode = EdgeLabelGeometry.targetSegmentTangent(route)
    val targetDir = -targetIntoNode.first to -targetIntoNode.second

    if (rel.sourceCardinality.optional) {
        renderParentDiamond(route.source, sourceDir, b)
    }
    renderChildDot(route.target, targetDir, b)
    idef1xCardinalityLabel(rel.targetCardinality)?.let { label ->
        renderCardinalityLabel(route.target, targetDir, label, b)
    }

    val selfLoop = rel.sourceEntityId == rel.targetEntityId
    val relName = rel.name
    if (!relName.isNullOrBlank()) {
        b.renderErmRelationshipNameLabel(relName, route, labelStackIndex, selfLoop, sourceDir, targetDir)
    }

    rel.sourceRole?.let { role ->
        b.renderErmRoleLabel(route.source, sourceDir, role)
    }
    rel.targetRole?.let { role ->
        b.renderErmRoleLabel(route.target, targetDir, role, perpBias = if (selfLoop) 1f else -1f)
    }
}

/** Radius of the filled child-end dot. */
private const val DOT_RADIUS_PX: Float = 4f

/** Half-diagonal of the open parent-end diamond. */
private const val DIAMOND_HALF_PX: Float = 6f

/** Gap between the child dot and the cardinality annotation text. */
private const val CARDINALITY_GAP_PX: Float = 10f

/** Draws the filled child-end dot, touching the entity border at [anchor], extending along [dir]. */
private fun renderChildDot(
    anchor: Point,
    dir: Pair<Float, Float>,
    b: SvgBuilder,
) {
    val (dx, dy) = dir
    val cx = anchor.x + dx * DOT_RADIUS_PX
    val cy = anchor.y + dy * DOT_RADIUS_PX
    b.tag(
        "circle",
        mapOf("cx" to fmt(cx), "cy" to fmt(cy), "r" to fmt(DOT_RADIUS_PX), "class" to "kuml-erm-idef1x-dot"),
    )
}

/** Draws the open parent-end diamond, apex touching the entity border at [anchor], extending along [dir]. */
private fun renderParentDiamond(
    anchor: Point,
    dir: Pair<Float, Float>,
    b: SvgBuilder,
) {
    val (dx, dy) = dir
    val px = -dy
    val py = dx

    val apexX = anchor.x
    val apexY = anchor.y
    val farX = anchor.x + dx * (DIAMOND_HALF_PX * 2f)
    val farY = anchor.y + dy * (DIAMOND_HALF_PX * 2f)
    val midX = anchor.x + dx * DIAMOND_HALF_PX
    val midY = anchor.y + dy * DIAMOND_HALF_PX
    val leftX = midX + px * DIAMOND_HALF_PX
    val leftY = midY + py * DIAMOND_HALF_PX
    val rightX = midX - px * DIAMOND_HALF_PX
    val rightY = midY - py * DIAMOND_HALF_PX

    b.tag(
        "polygon",
        mapOf(
            "points" to
                "${fmt(apexX)},${fmt(apexY)} ${fmt(leftX)},${fmt(leftY)} " +
                "${fmt(farX)},${fmt(farY)} ${fmt(rightX)},${fmt(rightY)}",
            "class" to "kuml-erm-idef1x-diamond",
        ),
    )
}

private fun renderCardinalityLabel(
    anchor: Point,
    dir: Pair<Float, Float>,
    label: String,
    b: SvgBuilder,
) {
    val (dx, dy) = dir
    val offset = DOT_RADIUS_PX * 2f + CARDINALITY_GAP_PX
    val x = anchor.x + dx * offset
    val y = anchor.y + dy * offset
    b.tag(
        "text",
        mapOf("class" to "kuml-erm-idef1x-card", "x" to fmt(x), "y" to fmt(y), "text-anchor" to "middle"),
    ) { text(label) }
}

/**
 * Maps a [Cardinality] to the IDEF1X child-end annotation vocabulary. Blank
 * (`null`) is the default "zero, one, or many" — no label needed.
 */
internal fun idef1xCardinalityLabel(cardinality: Cardinality): String? =
    when {
        cardinality.min <= 0 && cardinality.max == 1 -> "Z"
        cardinality.min >= 1 && !cardinality.many -> "1"
        cardinality.min >= 1 && cardinality.many -> "P"
        else -> null
    }

private fun fmt(v: Float): String = fmt2(v)
