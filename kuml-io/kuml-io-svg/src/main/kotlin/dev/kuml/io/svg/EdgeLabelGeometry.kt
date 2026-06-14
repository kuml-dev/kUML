package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders an edge label with a "halo" — first a stroked background pass, then
 * the actual coloured text. Two `<text>` elements share x/y/text-anchor.
 *
 * The halo (`kuml-edge-label-halo`) is drawn in the canvas background colour
 * with a thick stroke so the label remains readable when it overlaps stroke
 * lines or node description text (e.g. C4 landscape Person → System edges
 * landing on the Person's description row).
 *
 * Batik's `paint-order: stroke` attribute is unreliable in 1.x; this two-pass
 * trick is the portable equivalent and works under Batik, Inkscape, and every
 * mainstream browser. Same technique Graphviz uses for `xlabel` rendering.
 */
internal fun SvgBuilder.renderEdgeLabelWithHalo(
    label: String,
    x: Float,
    y: Float,
    textAnchor: String,
) {
    val attrs =
        mapOf(
            "x" to fmtCoord(x),
            "y" to fmtCoord(y),
            "text-anchor" to textAnchor,
        )
    tag("text", mapOf("class" to "kuml-edge-label-halo") + attrs) { text(label) }
    tag("text", mapOf("class" to "kuml-edge-label") + attrs) { text(label) }
}

private fun fmtCoord(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}

/**
 * Geometric helper for placing edge labels on the *actual* polyline path,
 * not on the straight line between source and target.
 *
 * Bug-fix V2.0.46 (C4 Landscape edge labels): the previous implementations in
 * [dev.kuml.io.svg.c4.renderC4Relationship] and [dev.kuml.io.svg.uml.renderUmlAssociation]
 * placed the label at `(source + target) / 2`. For an `EdgeRoute.OrthogonalRounded`
 * with waypoints — which is what ELK delivers by default for C4 landscape
 * diagrams — that midpoint is inside the L-shaped bend and frequently lands
 * in completely empty whitespace (or worse: on top of an unrelated node's
 * description text). The visible symptom in the C4 landscape sample PNG was
 * "Applies for loans" / "Manages credit cards" / "Administers" stacking up on
 * the Customer / Administrator description row.
 *
 * This helper walks the full polyline (`source + waypoints + target`) and
 * picks the point at 50 % of the cumulative arc length. It also returns the
 * direction of the *segment* that contains that midpoint, so callers can
 * offset the label perpendicular to the segment (above for horizontal, to the
 * side for vertical) instead of always offsetting straight up — which would
 * push the label into the source/target box on a vertical edge.
 *
 * For [EdgeRoute.Bezier] we fall back to the straight `source ↔ target`
 * midpoint — building a proper Bézier arc-length parameterisation here is not
 * worth it for the rare case where ELK emits bezier C4 edges.
 */
internal object EdgeLabelGeometry {
    /**
     * Anchor for placing a single edge label, computed from the polyline path.
     *
     * - [x], [y]: SVG coordinates of the geometric midpoint of the path.
     * - [direction]: orientation of the segment containing the midpoint, used
     *   by callers to choose label offset and `text-anchor`.
     */
    data class LabelAnchor(
        val x: Float,
        val y: Float,
        val direction: SegmentDirection,
    )

    /** Predominant direction of the polyline segment that contains the midpoint. */
    enum class SegmentDirection {
        Horizontal,
        Vertical,
    }

    /**
     * Unit tangent of the **first** polyline segment (`source → first kink`).
     * Used by callers that place a label at the *source end* of an edge — for
     * orthogonal routes the source segment can be perpendicular to the
     * straight-line source→target direction, and labels offset against the
     * straight direction get pushed into the wrong half-plane.
     *
     * Returns `(tx, ty)` as a unit vector. Falls back to `(1, 0)` for fully
     * degenerate (zero-length) routes.
     */
    fun sourceSegmentTangent(route: EdgeRoute): Pair<Float, Float> = tangentOfSegment(firstSegment(route))

    /**
     * Unit tangent of the **last** polyline segment (`last kink → target`).
     * Used by callers that place a label at the *target end* of an edge.
     *
     * Fix V3.0.11 — `renderUmlAssociation` used the straight `source→target`
     * tangent for target-end multiplicity labels, which for orthogonal routes
     * with a vertical final segment landed the label below/above the
     * arrowhead and inside the target node. Walking the polyline and using
     * the *last segment's* tangent puts the label where the user expects:
     * along the actual edge tail.
     *
     * Returns `(tx, ty)` as a unit vector. Falls back to `(1, 0)` for fully
     * degenerate (zero-length) routes.
     */
    fun targetSegmentTangent(route: EdgeRoute): Pair<Float, Float> = tangentOfSegment(lastSegment(route))

    private fun firstSegment(route: EdgeRoute): Pair<Point, Point> {
        val poly = polylineOf(route)
        return if (poly.size < 2) poly.first() to poly.first() else poly[0] to poly[1]
    }

    private fun lastSegment(route: EdgeRoute): Pair<Point, Point> {
        val poly = polylineOf(route)
        return if (poly.size < 2) poly.first() to poly.first() else poly[poly.size - 2] to poly[poly.size - 1]
    }

    private fun tangentOfSegment(segment: Pair<Point, Point>): Pair<Float, Float> {
        val (a, b) = segment
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        return if (len < 0.01f) 1f to 0f else (dx / len) to (dy / len)
    }

    /** Returns the midpoint anchor of [route], walking the actual polyline. */
    fun midAnchor(route: EdgeRoute): LabelAnchor {
        val polyline = polylineOf(route)
        if (polyline.size < 2) {
            // Degenerate route: single point. Just return it horizontally oriented.
            val p = polyline.firstOrNull() ?: Point(0f, 0f)
            return LabelAnchor(p.x, p.y, SegmentDirection.Horizontal)
        }

        val totalLength = polylineLength(polyline)
        if (totalLength < 0.01f) {
            // Source and target coincide (extreme degenerate case).
            return LabelAnchor(polyline.first().x, polyline.first().y, SegmentDirection.Horizontal)
        }

        val targetDist = totalLength / 2f
        var walked = 0f
        for (i in 1 until polyline.size) {
            val a = polyline[i - 1]
            val b = polyline[i]
            val segLen = segmentLength(a, b)
            if (walked + segLen >= targetDist || i == polyline.size - 1) {
                val remaining = (targetDist - walked).coerceAtLeast(0f)
                val t = if (segLen > 0.01f) remaining / segLen else 0.5f
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                val direction =
                    if (abs(b.x - a.x) >= abs(b.y - a.y)) {
                        SegmentDirection.Horizontal
                    } else {
                        SegmentDirection.Vertical
                    }
                return LabelAnchor(x, y, direction)
            }
            walked += segLen
        }

        // Should not reach here, but fall back to last point.
        val last = polyline.last()
        return LabelAnchor(last.x, last.y, SegmentDirection.Horizontal)
    }

    /** Convert an [EdgeRoute] to an ordered list of polyline points. */
    private fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded ->
                listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded ->
                listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> {
                // Build a cheap polyline approximation by interpolating the cubic Bézier
                // (or quadratic) with 16 samples. Good enough for label placement; no
                // need for exact arc-length parameterisation.
                approximateBezier(route)
            }
        }

    private fun approximateBezier(route: EdgeRoute.Bezier): List<Point> {
        val src = route.source
        val tgt = route.target
        return when (route.controlPoints.size) {
            0 -> listOf(src, tgt)
            1 -> {
                // Quadratic Bézier
                val c = route.controlPoints[0]
                (0..16).map { i ->
                    val t = i / 16f
                    val one = 1f - t
                    val x = one * one * src.x + 2f * one * t * c.x + t * t * tgt.x
                    val y = one * one * src.y + 2f * one * t * c.y + t * t * tgt.y
                    Point(x, y)
                }
            }
            else -> {
                // Cubic Bézier (first two control points)
                val c1 = route.controlPoints[0]
                val c2 = route.controlPoints[1]
                (0..16).map { i ->
                    val t = i / 16f
                    val one = 1f - t
                    val x =
                        one * one * one * src.x +
                            3f * one * one * t * c1.x +
                            3f * one * t * t * c2.x +
                            t * t * t * tgt.x
                    val y =
                        one * one * one * src.y +
                            3f * one * one * t * c1.y +
                            3f * one * t * t * c2.y +
                            t * t * t * tgt.y
                    Point(x, y)
                }
            }
        }
    }

    private fun polylineLength(points: List<Point>): Float {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += segmentLength(points[i - 1], points[i])
        }
        return sum
    }

    private fun segmentLength(
        a: Point,
        b: Point,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
