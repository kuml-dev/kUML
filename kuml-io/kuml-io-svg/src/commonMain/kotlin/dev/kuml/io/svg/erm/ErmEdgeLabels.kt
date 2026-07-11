package dev.kuml.io.svg.erm

import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.abs
import kotlin.math.sqrt

/** Baseline gap above a horizontal segment for the relationship-name label. */
internal const val ERM_LABEL_BASE_OFFSET_PX: Float = 6f

/** Per-stack-index downward step for parallel (same-pair) relationship-name labels. */
internal const val ERM_LABEL_STACK_OFFSET_PX: Float = 16f

/** Sideways push for a label beside a vertical segment (matches C4's +10f). */
internal const val ERM_LABEL_SIDE_OFFSET_PX: Float = 10f

/** Along-edge distance of a role label from its entity border. */
internal const val ERM_ROLE_LABEL_OFFSET_PX: Float = 20f

/** Perpendicular lift of a role label off the edge line. */
internal const val ERM_ROLE_LABEL_PERP_PX: Float = 9f

/**
 * Places the relationship-NAME label. Direction-aware (mirrors `C4EdgesSvg`):
 *  - horizontal longest segment → centered above the line ("middle"),
 *    stacked upward by [stackIndex] for same-pair parallel edges.
 *  - vertical longest segment → pushed to the free side, "start", so the
 *    glyph run grows AWAY from the line instead of straddling it.
 *  - self-loops ([selfLoop]) → pushed outward past the loop bulge (outward =
 *    normalized sum of the two into-edge tangents, which for a self-loop both
 *    point away from the box), so the label never overflows into the owning
 *    entity.
 *
 * Bug-fix (ERM/Martin edge-label collision, feature/fix-erm-martin-edge-label-collision):
 * previously every ERM notation (Martin/Bachman/IDEF1X) called
 * `EdgeLabelGeometry.midAnchor(route)` for its `.x`/`.y` but ignored the
 * returned `.direction`, always drawing with `text-anchor="middle"`. For a
 * self-referential relationship's tight loop route hugging the entity's own
 * border, "middle" straddles the line and paints half the glyph run *inside*
 * the box (e.g. `Category -> Category "subcategory of"` in the E-Commerce
 * ERM/Martin sample). This helper is shared by all three ERM edge renderers
 * so the fix lands uniformly.
 */
internal fun SvgBuilder.renderErmRelationshipNameLabel(
    label: String,
    route: EdgeRoute,
    stackIndex: Int,
    selfLoop: Boolean,
    sourceDir: Pair<Float, Float>,
    targetDir: Pair<Float, Float>,
) {
    val a = EdgeLabelGeometry.midAnchor(route)
    val stack = stackIndex * ERM_LABEL_STACK_OFFSET_PX
    if (selfLoop) {
        val outward = normalize(sourceDir.first + targetDir.first, sourceDir.second + targetDir.second)
        val x = a.x + outward.first * ERM_LABEL_SIDE_OFFSET_PX
        val y = a.y + 4f - stack
        val anchor = if (outward.first < 0f) "end" else "start"
        renderEdgeLabelWithHalo(label, x, y, anchor)
        return
    }
    when (a.direction) {
        EdgeLabelGeometry.SegmentDirection.Horizontal ->
            renderEdgeLabelWithHalo(label, a.x, a.y - ERM_LABEL_BASE_OFFSET_PX - stack, "middle")
        EdgeLabelGeometry.SegmentDirection.Vertical ->
            renderEdgeLabelWithHalo(label, a.x + ERM_LABEL_SIDE_OFFSET_PX, a.y + 4f - stack, "start")
    }
}

/**
 * Places a role label near an entity border. Offsets [ERM_ROLE_LABEL_OFFSET_PX]
 * along the edge, lifts [ERM_ROLE_LABEL_PERP_PX] perpendicular to it, and picks
 * "start"/"end"/"middle" so the text grows away from the box:
 *  - edge leaves vertically → text to the side (anchor by horizontal sign,
 *    which already differs naturally between the source and target ends).
 *  - edge leaves horizontally → text above the line, "middle", biased by
 *    [perpBias] (`-1f` = above/up, `+1f` = below/down).
 *
 * [perpBias] matters for self-loops: both ends of a self-loop's tight route
 * share the *same* horizontal tangent (there is only one "line" direction),
 * so without a caller-supplied bias both the source-end and target-end role
 * labels would be pushed the same way (e.g. both "up") and converge toward
 * the relationship-name label sitting between them, shrinking the vertical
 * gap between name and role label as the loop tightens. Callers push the
 * source-end role away on one side (default `-1f`) and the target-end role
 * away on the other (`+1f`) for self-loops, so the two role labels diverge
 * from the shared name label instead of crowding it. Non-self-loop callers
 * keep the historical `-1f` ("above the line") for both ends.
 */
internal fun SvgBuilder.renderErmRoleLabel(
    anchor: Point,
    dir: Pair<Float, Float>,
    role: String,
    perpBias: Float = -1f,
) {
    val (dx, dy) = dir
    val px = -dy // perpendicular unit
    if (abs(dy) > abs(dx)) {
        // vertical edge -> push sideways, grow away from box
        val side = if (px >= 0f) 1f else -1f
        val x = anchor.x + dx * ERM_ROLE_LABEL_OFFSET_PX + side * ERM_ROLE_LABEL_PERP_PX
        val y = anchor.y + dy * ERM_ROLE_LABEL_OFFSET_PX + 4f
        renderEdgeLabelWithHalo(role, x, y, if (side < 0f) "end" else "start")
    } else {
        val x = anchor.x + dx * ERM_ROLE_LABEL_OFFSET_PX
        val y = anchor.y + dy * ERM_ROLE_LABEL_OFFSET_PX + perpBias * ERM_ROLE_LABEL_PERP_PX
        renderEdgeLabelWithHalo(role, x, y, "middle")
    }
}

/**
 * Places an ERM/Chen-notation cardinality label (`1`/`N`) near an entity
 * border. Structurally identical to [renderErmRoleLabel]'s perpendicular/
 * anchor logic — vertical-leaving edges push the label sideways ("start"/
 * "end", growing away from the box), horizontal-leaving edges lift it above
 * the line ("middle") — but takes the along-edge offset as a parameter
 * instead of a fixed constant, since callers fold a per-hub stacking step
 * (`ErmChenSizing.CARDINALITY_LABEL_STACK_PX`) into it.
 *
 * Bug-fix (ERM/Chen cardinality-label collision, fix/erm-chen-label-
 * collisions, V3.4.7): `renderChenConnector` used to place this label with
 * its own ad-hoc, purely along-edge math and `text-anchor="middle"` — the
 * only ERM label path that didn't go through a shared perpendicular-offset
 * helper. That left two independent defects: the SOURCE-side offset
 * direction was inverted (label landed *inside* the entity box, on top of
 * its title) and there was no perpendicular component at all (the glyph
 * straddled the connector line). Routing through this helper fixes both and
 * gets the [renderEdgeLabelWithHalo] two-pass halo for free — Chen
 * cardinality text previously had no halo, so it was unreadable wherever it
 * crossed a line.
 *
 * [anchor] is the entity-side border point of the connector (`route.source`
 * or `route.target`), [outward] the unit vector pointing away from the
 * entity, along the edge, toward the relationship diamond.
 */
internal fun SvgBuilder.renderErmCardinalityLabel(
    anchor: Point,
    outward: Pair<Float, Float>,
    label: String,
    alongOffsetPx: Float,
) {
    val (dx, dy) = outward
    val px = -dy // perpendicular unit
    if (abs(dy) > abs(dx)) {
        // vertical edge -> push sideways, grow away from the line
        val side = if (px >= 0f) 1f else -1f
        val x = anchor.x + dx * alongOffsetPx + side * ErmChenSizing.CARDINALITY_LABEL_PERP_PX
        val y = anchor.y + dy * alongOffsetPx + 4f
        renderEdgeLabelWithHalo(label, x, y, if (side < 0f) "end" else "start")
    } else {
        // horizontal edge -> lift above the line
        val x = anchor.x + dx * alongOffsetPx
        val y = anchor.y + dy * alongOffsetPx - ErmChenSizing.CARDINALITY_LABEL_PERP_PX
        renderEdgeLabelWithHalo(label, x, y, "middle")
    }
}

private fun normalize(
    x: Float,
    y: Float,
): Pair<Float, Float> {
    val len = sqrt(x * x + y * y)
    return if (len < 0.01f) 1f to 0f else (x / len) to (y / len)
}
