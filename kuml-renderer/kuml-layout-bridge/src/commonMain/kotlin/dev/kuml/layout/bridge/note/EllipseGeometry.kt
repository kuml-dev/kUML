package dev.kuml.layout.bridge.note

import dev.kuml.layout.Point
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Points on the outline of an [AnchorGeometry.Ellipse] (a use case). Pure geometry: every function returns a point
 * that satisfies the ellipse equation up to float rounding, so a line that ends there ends ON the drawn outline.
 */
internal object EllipseGeometry {
    /** A projection is refused this close to the top/bottom (left/right) extreme: the line would graze the outline. */
    private const val GRAZE_FACTOR = 0.98f

    /** The outline point on the ray from the centre toward [toward] (the centre itself falls back to the right extreme). */
    fun onRay(
        e: AnchorGeometry.Ellipse,
        toward: Point,
    ): Point {
        val dx = toward.x - e.cx
        val dy = toward.y - e.cy
        val k = sqrt((dx / e.rx) * (dx / e.rx) + (dy / e.ry) * (dy / e.ry))
        if (k < 1e-6f) return Point(x = e.cx + e.rx, y = e.cy)
        return Point(x = e.cx + dx / k, y = e.cy + dy / k)
    }

    /** The outline point at height [y] on the left ([fromLeft]) or right half; `null` when [y] is (nearly) outside the ellipse. */
    fun horizontalHit(
        e: AnchorGeometry.Ellipse,
        y: Float,
        fromLeft: Boolean,
    ): Point? {
        val t = (y - e.cy) / e.ry
        if (abs(t) >= GRAZE_FACTOR) return null
        val half = e.rx * sqrt(1f - t * t)
        return Point(x = if (fromLeft) e.cx - half else e.cx + half, y = y)
    }

    /** The outline point at abscissa [x] on the top ([fromTop]) or bottom half; `null` when [x] is (nearly) outside the ellipse. */
    fun verticalHit(
        e: AnchorGeometry.Ellipse,
        x: Float,
        fromTop: Boolean,
    ): Point? {
        val t = (x - e.cx) / e.rx
        if (abs(t) >= GRAZE_FACTOR) return null
        val half = e.ry * sqrt(1f - t * t)
        return Point(x = x, y = if (fromTop) e.cy - half else e.cy + half)
    }

    /** Distance estimate of [p] to the outline in px: `|(x/rx)^2 + (y/ry)^2 - 1| * min(rx, ry)`. */
    fun residual(
        e: AnchorGeometry.Ellipse,
        p: Point,
    ): Float = abs(residualSigned(e = e, p = p))

    /** Like [residual] but negative inside the ellipse, positive outside. */
    fun residualSigned(
        e: AnchorGeometry.Ellipse,
        p: Point,
    ): Float {
        val nx = (p.x - e.cx) / e.rx
        val ny = (p.y - e.cy) / e.ry
        return (nx * nx + ny * ny - 1f) * minOf(e.rx, e.ry)
    }

    /**
     * True if the segment from [q] (on the outline) toward [prev] runs outside the ellipse. `f(t) = |(P(t)-c)/r|^2` is a
     * convex quadratic with `f(0) = 1`, so the segment stays outside exactly when `f` does not fall at `t = 0`.
     */
    fun leavesOutward(
        e: AnchorGeometry.Ellipse,
        q: Point,
        prev: Point,
    ): Boolean {
        val slope = ((q.x - e.cx) / (e.rx * e.rx)) * (prev.x - q.x) + ((q.y - e.cy) / (e.ry * e.ry)) * (prev.y - q.y)
        return slope >= -1e-3f
    }
}
