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
 * Renders an [ErmRelationship] as a Bachman-notation edge (V3.4.3):
 *
 * - **Line style**: solid for [RelationshipKind.IDENTIFYING], dashed for
 *   [RelationshipKind.NON_IDENTIFYING] — identical to the Martin renderer.
 * - **Cardinality glyphs** at both ends, derived from [Cardinality]:
 *   - `many` (`max == -1 || max > 1`) → a filled arrowhead, apex touching
 *     the entity border, pointing *into* the entity.
 *   - `optional` (`min == 0`) → a small hollow circle, positioned a bit
 *     further along the line than the arrowhead.
 *   - not optional (`min >= 1`) → a small filled circle, at the same
 *     position the hollow circle would occupy.
 *   These four combinations reproduce the four standard Bachman pairs:
 *   `(0,1)` = hollow circle, no arrow · `(1,1)` = filled circle, no arrow ·
 *   `(0,N)` = hollow circle + arrow · `(1,N)` = filled circle + arrow.
 * - **Label**: the relationship's verb phrase (`rel.name`), centered on the
 *   longest polyline segment, offset downward by [labelStackIndex] steps to
 *   avoid collisions when two relationships connect the same pair of
 *   entities (mirrors [renderErmMartinRelationship]'s stacking convention).
 * - **Role labels**: `sourceRole` / `targetRole`, drawn small near the
 *   respective end, when present.
 */
internal fun renderErmBachmanRelationship(
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

    renderBachmanCardinalityGlyph(route.source, sourceDir, rel.sourceCardinality, b)
    renderBachmanCardinalityGlyph(route.target, targetDir, rel.targetCardinality, b)

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

/** Distance from the entity border to the arrowhead apex — the arrow's own length. */
private const val ARROW_LEN_PX: Float = 12f

/** Half-spread of the arrowhead base, perpendicular to the line. */
private const val ARROW_HALF_PX: Float = 5f

/** Gap between the arrowhead base (or the entity border, if no arrow) and the circle marker. */
private const val MARKER_GAP_PX: Float = 6f

/** Radius of the circle marker (hollow = optional, filled = mandatory). */
private const val CIRCLE_RADIUS_PX: Float = 4f

/**
 * Draws the arrowhead / circle combination for one end of a relationship,
 * anchored at [anchor] (a node border point, e.g. `route.source` or
 * `route.target`) and extending along [dir] — the unit vector pointing
 * *away* from the entity, into the edge.
 *
 * The arrowhead's apex sits at [anchor] (touching the entity border) and its
 * base extends outward along [dir] — visually the arrow points *into* the
 * entity, signalling the "many" side of the relationship.
 */
private fun renderBachmanCardinalityGlyph(
    anchor: Point,
    dir: Pair<Float, Float>,
    cardinality: Cardinality,
    b: SvgBuilder,
) {
    val (dx, dy) = dir
    // Perpendicular unit vector (rotate 90°).
    val px = -dy
    val py = dx

    var innerOffset = 0f
    if (cardinality.many) {
        val baseX = anchor.x + dx * ARROW_LEN_PX
        val baseY = anchor.y + dy * ARROW_LEN_PX
        val leftX = baseX + px * ARROW_HALF_PX
        val leftY = baseY + py * ARROW_HALF_PX
        val rightX = baseX - px * ARROW_HALF_PX
        val rightY = baseY - py * ARROW_HALF_PX
        b.tag(
            "path",
            mapOf(
                "class" to "kuml-erm-bachman-arrow",
                "d" to
                    "M ${fmt(anchor.x)} ${fmt(anchor.y)} L ${fmt(leftX)} ${fmt(leftY)} " +
                    "L ${fmt(rightX)} ${fmt(rightY)} Z",
            ),
        )
        innerOffset = ARROW_LEN_PX
    }

    val markerOffset = innerOffset + MARKER_GAP_PX + CIRCLE_RADIUS_PX
    val circleCx = anchor.x + dx * markerOffset
    val circleCy = anchor.y + dy * markerOffset

    if (cardinality.optional) {
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
        b.tag(
            "circle",
            mapOf(
                "cx" to fmt(circleCx),
                "cy" to fmt(circleCy),
                "r" to fmt(CIRCLE_RADIUS_PX),
                "class" to "kuml-erm-bachman-mandatory",
            ),
        )
    }
}

private fun fmt(v: Float): String = fmt2(v)
