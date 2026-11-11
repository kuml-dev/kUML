package dev.kuml.layout.bridge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Side choice for the optional name of a decision / merge / fork / join node (Nacharbeit 69). The name goes to the
 * first side whose text rectangle crosses no edge; if every side is crossed, the first preference is kept. Pure and
 * deterministic: the text width is estimated with a fixed character width, never glyph metrics.
 */
public object ActControlNamePlacement {
    /** The sides a name can sit on, relative to the node. */
    public enum class Side { Right, Left, Top, Bottom }

    /** Where the name is drawn: baseline origin ([x], [y]), SVG text [anchor], the occupied [rect] and its [side]. */
    public data class Placed(
        val side: Side,
        val x: Float,
        val y: Float,
        val anchor: String,
        val rect: Rect,
    )

    /** Approximate width (px) of one character of the 13 px name text. */
    private const val CHAR_PX: Float = 7.0f

    private const val TEXT_HEIGHT: Float = 14f
    private const val GAP: Float = 6f

    /** Preference for diamonds: right first (the historic position). */
    public val DIAMOND_ORDER: List<Side> = listOf(Side.Right, Side.Left, Side.Top, Side.Bottom)

    /** Preference for bars: left first (the historic position). */
    public val BAR_ORDER: List<Side> = listOf(Side.Left, Side.Right, Side.Top, Side.Bottom)

    /**
     * Places a name of [charCount] characters next to [node]. [routes] are the polylines of all edges; the first side
     * in [order] whose text rectangle intersects none of them wins.
     */
    public fun place(
        node: Rect,
        charCount: Int,
        routes: List<List<Point>>,
        order: List<Side>,
    ): Placed {
        val w = charCount.coerceAtLeast(1) * CHAR_PX
        val candidates = order.map { candidate(node = node, w = w, side = it) }
        return candidates.firstOrNull { c -> routes.none { crosses(rect = c.rect, pts = it) } } ?: candidates.first()
    }

    private fun candidate(
        node: Rect,
        w: Float,
        side: Side,
    ): Placed {
        val left = node.origin.x
        val top = node.origin.y
        val right = left + node.size.width
        val bottom = top + node.size.height
        val cx = left + node.size.width / 2f
        val cy = top + node.size.height / 2f
        return when (side) {
            Side.Right ->
                Placed(side = side, x = right + GAP, y = cy + 4f, anchor = "start", rect = rectOf(x = right + GAP, y = cy - 8f, w = w))
            Side.Left ->
                Placed(side = side, x = left - GAP, y = cy + 4f, anchor = "end", rect = rectOf(x = left - GAP - w, y = cy - 8f, w = w))
            Side.Top ->
                Placed(side = side, x = cx, y = top - GAP, anchor = "middle", rect = rectOf(x = cx - w / 2f, y = top - GAP - 12f, w = w))
            Side.Bottom ->
                Placed(
                    side = side,
                    x = cx,
                    y = bottom + GAP + 10f,
                    anchor = "middle",
                    rect = rectOf(x = cx - w / 2f, y = bottom + GAP, w = w),
                )
        }
    }

    private fun rectOf(
        x: Float,
        y: Float,
        w: Float,
    ): Rect = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = TEXT_HEIGHT))

    private fun crosses(
        rect: Rect,
        pts: List<Point>,
    ): Boolean {
        for (i in 0 until pts.size - 1) if (segmentHits(rect = rect, a = pts[i], b = pts[i + 1])) return true
        return false
    }

    /** Liang-Barsky clip of the segment a-b against [rect]. */
    private fun segmentHits(
        rect: Rect,
        a: Point,
        b: Point,
    ): Boolean {
        val xMin = rect.origin.x
        val xMax = xMin + rect.size.width
        val yMin = rect.origin.y
        val yMax = yMin + rect.size.height
        val dx = b.x - a.x
        val dy = b.y - a.y
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - xMin, xMax - a.x, a.y - yMin, yMax - a.y)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return true
    }
}
