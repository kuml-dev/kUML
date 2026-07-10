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
 * Renders an [ErmRelationship] as a Martin-notation (crow's-foot) edge:
 *
 * - **Line style**: solid for [RelationshipKind.IDENTIFYING], dashed for
 *   [RelationshipKind.NON_IDENTIFYING].
 * - **Cardinality glyphs** at both ends, derived from [Cardinality]:
 *   - `many` (`max == -1 || max > 1`) → a crow's-foot fan touching the
 *     entity border.
 *   - `optional` (`min == 0`) → a small open circle, positioned a bit
 *     further along the line than the crow's foot.
 *   - not optional (`min >= 1`) → a single perpendicular tick ("bar"),
 *     at the same position the circle would occupy.
 *   These four combinations reproduce the four standard crow's-foot pairs:
 *   `(0,1)` = circle + bar, `(1,1)` = bar + bar, `(0,N)` = circle + crow's
 *   foot, `(1,N)` = bar + crow's foot.
 * - **Label**: the relationship's verb phrase (`rel.name`), centered on the
 *   longest polyline segment, offset downward by [labelStackIndex] steps to
 *   avoid collisions when two relationships connect the same pair of
 *   entities (mirrors `Sysml2EdgeRenderer`'s stacking convention).
 * - **Role labels**: `sourceRole` / `targetRole`, drawn small near the
 *   respective end, when present.
 */
internal fun renderErmMartinRelationship(
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

    renderCardinalityGlyph(route.source, sourceDir, rel.sourceCardinality, theme, b)
    renderCardinalityGlyph(route.target, targetDir, rel.targetCardinality, theme, b)

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

/** Distance from the entity border to the crow's-foot apex. */
private const val CROWFOOT_LEN_PX: Float = 14f

/** Half-spread of the two outer crow's-foot prongs, perpendicular to the line. */
private const val CROWFOOT_SPREAD_PX: Float = 6f

/** Gap between the crow's-foot apex (or the entity border, if no crow's foot) and the one/zero marker. */
private const val MARKER_GAP_PX: Float = 6f

/** Half-length of the perpendicular "mandatory" tick mark. */
private const val BAR_HALF_PX: Float = 5f

/** Radius of the "optional" open-circle marker. */
private const val CIRCLE_RADIUS_PX: Float = 4f

/**
 * Draws the crow's-foot / bar / circle combination for one end of a
 * relationship, anchored at [anchor] (a node border point, e.g.
 * `route.source` or `route.target`) and extending along [dir] — the unit
 * vector pointing *away* from the entity, into the edge.
 */
private fun renderCardinalityGlyph(
    anchor: Point,
    dir: Pair<Float, Float>,
    cardinality: Cardinality,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    b: SvgBuilder,
) {
    val (dx, dy) = dir
    // Perpendicular unit vector (rotate 90°).
    val px = -dy
    val py = dx

    var innerOffset = 0f
    if (cardinality.many) {
        val apexX = anchor.x + dx * CROWFOOT_LEN_PX
        val apexY = anchor.y + dy * CROWFOOT_LEN_PX
        val leftX = anchor.x + px * CROWFOOT_SPREAD_PX
        val leftY = anchor.y + py * CROWFOOT_SPREAD_PX
        val rightX = anchor.x - px * CROWFOOT_SPREAD_PX
        val rightY = anchor.y - py * CROWFOOT_SPREAD_PX
        b.tag(
            "path",
            mapOf(
                "class" to "kuml-erm-crowfoot",
                "d" to
                    "M ${fmt(apexX)} ${fmt(apexY)} L ${fmt(anchor.x)} ${fmt(anchor.y)} " +
                    "M ${fmt(apexX)} ${fmt(apexY)} L ${fmt(leftX)} ${fmt(leftY)} " +
                    "M ${fmt(apexX)} ${fmt(apexY)} L ${fmt(rightX)} ${fmt(rightY)}",
            ),
        )
        innerOffset = CROWFOOT_LEN_PX
    }

    val markerOffset = innerOffset + MARKER_GAP_PX
    val markerX = anchor.x + dx * markerOffset
    val markerY = anchor.y + dy * markerOffset

    if (cardinality.optional) {
        val circleCx = markerX + dx * CIRCLE_RADIUS_PX
        val circleCy = markerY + dy * CIRCLE_RADIUS_PX
        b.tag(
            "circle",
            mapOf(
                "cx" to fmt(circleCx),
                "cy" to fmt(circleCy),
                "r" to fmt(CIRCLE_RADIUS_PX),
                "class" to "kuml-erm-optional-marker",
            ),
        )
    } else {
        val barX1 = markerX + px * BAR_HALF_PX
        val barY1 = markerY + py * BAR_HALF_PX
        val barX2 = markerX - px * BAR_HALF_PX
        val barY2 = markerY - py * BAR_HALF_PX
        b.tag(
            "line",
            mapOf(
                "x1" to fmt(barX1),
                "y1" to fmt(barY1),
                "x2" to fmt(barX2),
                "y2" to fmt(barY2),
                "class" to "kuml-erm-mandatory-marker",
            ),
        )
    }
}

private fun fmt(v: Float): String = fmt2(v)
