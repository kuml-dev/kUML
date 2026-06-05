package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.abs
import kotlin.math.min

/**
 * Wandelt einen [EdgeRoute] in SVG-Path-Data (d-Attribut) oder eine Line-Darstellung um.
 *
 * Gibt einen Pair aus (svgTag, attributes) zurück:
 * - `Direct` → `<line>` mit x1/y1/x2/y2
 * - `OrthogonalRounded`, `TreeRounded` → `<path d="...">` mit Bögen
 * - `Bezier` → `<path d="M ... C ...">` mit kubischen Bézierkurven
 *
 * Beispiel:
 * ```kotlin
 * val (tag, attrs) = EdgePathBuilder.build(route)
 * builder.tag(tag, attrs)
 * ```
 */
internal object EdgePathBuilder {
    /**
     * Gibt `(tagName, attrMap)` zurück. Der Aufrufer fügt `class`, `marker-end` etc.
     * separat hinzu.
     */
    fun build(route: EdgeRoute): Pair<String, Map<String, String>> =
        when (route) {
            is EdgeRoute.Direct -> buildDirect(route.source, route.target)
            is EdgeRoute.OrthogonalRounded ->
                buildOrthogonal(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                )
            is EdgeRoute.TreeRounded ->
                buildOrthogonal(
                    route.source,
                    route.target,
                    route.waypoints,
                    route.cornerRadiusPx,
                )
            is EdgeRoute.Bezier ->
                buildBezier(
                    route.source,
                    route.target,
                    route.controlPoints,
                )
        }

    /** Direkte Linie: Gibt path-data zurück (für einheitliche Behandlung in Tests). */
    fun buildPathData(route: EdgeRoute): String =
        when (route) {
            is EdgeRoute.Direct ->
                "M ${fmt(route.source.x)} ${fmt(route.source.y)} L ${fmt(route.target.x)} ${fmt(route.target.y)}"
            is EdgeRoute.OrthogonalRounded ->
                orthogonalPathData(route.source, route.target, route.waypoints, route.cornerRadiusPx)
            is EdgeRoute.TreeRounded ->
                orthogonalPathData(route.source, route.target, route.waypoints, route.cornerRadiusPx)
            is EdgeRoute.Bezier ->
                bezierPathData(route.source, route.target, route.controlPoints)
        }

    // ── Private builders ──────────────────────────────────────────────────────

    private fun buildDirect(
        src: Point,
        tgt: Point,
    ): Pair<String, Map<String, String>> =
        "line" to
            mapOf(
                "x1" to fmt(src.x),
                "y1" to fmt(src.y),
                "x2" to fmt(tgt.x),
                "y2" to fmt(tgt.y),
            )

    private fun buildOrthogonal(
        src: Point,
        tgt: Point,
        waypoints: List<Point>,
        radius: Float,
    ): Pair<String, Map<String, String>> = "path" to mapOf("d" to orthogonalPathData(src, tgt, waypoints, radius))

    private fun buildBezier(
        src: Point,
        tgt: Point,
        controlPoints: List<Point>,
    ): Pair<String, Map<String, String>> = "path" to mapOf("d" to bezierPathData(src, tgt, controlPoints))

    private fun orthogonalPathData(
        src: Point,
        tgt: Point,
        waypoints: List<Point>,
        radius: Float,
    ): String {
        val all = listOf(src) + waypoints + listOf(tgt)
        if (all.size < 2) return "M ${fmt(src.x)} ${fmt(src.y)} L ${fmt(tgt.x)} ${fmt(tgt.y)}"

        val sb = StringBuilder()
        sb.append("M ${fmt(all[0].x)} ${fmt(all[0].y)}")

        for (i in 1 until all.size) {
            val prev = all[i - 1]
            val curr = all[i]
            val next = if (i + 1 < all.size) all[i + 1] else null

            if (radius > 0f && next != null) {
                // Compute actual radius clamped to segment length
                val segLen = segmentLength(prev, curr)
                val nextSegLen = segmentLength(curr, next)
                val r = min(radius, min(segLen / 2f, nextSegLen / 2f))

                // Point before the corner
                val beforeX = curr.x - r * normalize(curr.x - prev.x, segLen)
                val beforeY = curr.y - r * normalize(curr.y - prev.y, segLen)
                // Point after the corner
                val afterX = curr.x + r * normalize(next.x - curr.x, nextSegLen)
                val afterY = curr.y + r * normalize(next.y - curr.y, nextSegLen)

                sb.append(" L ${fmt(beforeX)} ${fmt(beforeY)}")
                // sweep-flag: 0 or 1 depending on turn direction
                val sweep = if (crossProduct(prev, curr, next) > 0) 1 else 0
                sb.append(" A ${fmt(r)} ${fmt(r)} 0 0 $sweep ${fmt(afterX)} ${fmt(afterY)}")
            } else {
                sb.append(" L ${fmt(curr.x)} ${fmt(curr.y)}")
            }
        }
        return sb.toString()
    }

    private fun bezierPathData(
        src: Point,
        tgt: Point,
        controlPoints: List<Point>,
    ): String {
        val sb = StringBuilder()
        sb.append("M ${fmt(src.x)} ${fmt(src.y)}")
        when {
            controlPoints.size >= 2 -> {
                // Cubic Bezier: use first two control points
                val c1 = controlPoints[0]
                val c2 = controlPoints[1]
                sb.append(
                    " C ${fmt(c1.x)} ${fmt(c1.y)} ${fmt(c2.x)} ${fmt(c2.y)} ${fmt(tgt.x)} ${fmt(tgt.y)}",
                )
            }
            controlPoints.size == 1 -> {
                // Quadratic (fallback for single control point)
                val c = controlPoints[0]
                sb.append(" Q ${fmt(c.x)} ${fmt(c.y)} ${fmt(tgt.x)} ${fmt(tgt.y)}")
            }
            else -> {
                sb.append(" L ${fmt(tgt.x)} ${fmt(tgt.y)}")
            }
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(v)
    }

    private fun segmentLength(
        a: Point,
        b: Point,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun normalize(
        delta: Float,
        length: Float,
    ): Float = if (abs(length) < 0.001f) 0f else delta / length

    /** Z-component of cross product (p1→p2 × p2→p3) to determine turn direction. */
    private fun crossProduct(
        p1: Point,
        p2: Point,
        p3: Point,
    ): Float = (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x)
}
